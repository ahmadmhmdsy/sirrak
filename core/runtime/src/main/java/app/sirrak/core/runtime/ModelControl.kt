package app.sirrak.core.runtime

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M5 model control: the typed half of "per-model generation settings".
 *
 * Everything here is pure Kotlin (no Android, no native library) so the precedence rules, range
 * validation and content-file parsers are cheap to pin with JVM tests. The engine maps
 * [GenerationOptions] onto llama.cpp; the app resolves the layers; the UI edits the result.
 */

/** How the next token is chosen. */
enum class SamplingMode {
    DEFAULT,
    GREEDY,
    MIROSTAT_V2;

    /** The token stored in app.json / settings JSON. */
    val configValue: String
        get() = when (this) {
            DEFAULT -> "default"
            GREEDY -> "greedy"
            MIROSTAT_V2 -> "mirostat_v2"
        }

    companion object {
        fun fromConfig(value: String?): SamplingMode? = when (value?.trim()?.lowercase()) {
            "default" -> DEFAULT
            "greedy" -> GREEDY
            "mirostat_v2" -> MIROSTAT_V2
            else -> null
        }
    }
}

/** Where the chat template used for this request comes from. */
enum class TemplateSource {
    /** The model ships its own template in GGUF metadata. */
    MODEL,

    /** The user forced a built-in llama.cpp template. */
    BUILTIN,

    /** No model template and no forced template: llama.cpp's chatml fallback. */
    FALLBACK,

    /** No template information at all (e.g. a model without chat metadata, before resolution). */
    NONE,
}

/** A user choice of chat template. Stored as a nullable built-in name; never localised. */
sealed interface TemplateChoice {
    data object ModelDefault : TemplateChoice

    data class Builtin(val name: String) : TemplateChoice

    /** The value persisted in [ModelSettings.template]; null means "model default". */
    fun storedValue(): String? = (this as? Builtin)?.name

    companion object {
        fun fromStored(raw: String?): TemplateChoice =
            if (raw.isNullOrBlank()) ModelDefault else Builtin(raw.trim())
    }
}

/** The strongest layer that supplied at least one generation value, for honest "defaults from" UI. */
enum class DefaultSource { CODE, APP_DEFAULT, PRESET, MODEL, USER, CONVERSATION }

/** Where the effective system prompt came from. */
enum class PromptSource { RESOURCE, APP_DEFAULT, GLOBAL, MODEL, CONVERSATION }

data class ResolvedPrompt(val text: String, val source: PromptSource)

data class ResolvedTemplate(
    val choice: TemplateChoice,
    val source: TemplateSource,
    /** Built-in template name to pass to the engine, or null to use the model's own template. */
    val templateName: String?,
)

/** Validated ranges for every control, in one place: UI sliders, JSON clamps and tests share them. */
object GenerationRanges {
    val temperature = 0f..2f
    // 0.01, not 0: a zero-nucleus request is a degenerate sampler call; the clamp keeps any
    // corrupted GGUF metadata or preset from asking llama.cpp for it.
    val topP = 0.01f..1f
    val topK = 0..200
    val maxTokens = 16..2048
    val repeatPenalty = 0f..2f
    val repeatLastN = -1..2048
    val minP = 0f..1f
    val typicalP = 0f..1f
    val presencePenalty = 0f..2f
    val frequencyPenalty = 0f..2f
    val mirostatTau = 0f..20f
    val mirostatEta = 0f..1f
    val xtcProbability = 0f..1f
    val xtcThreshold = 0f..1f
    val dryMultiplier = 0f..10f
    val topNSigma = -1f..10f
    const val MAX_STOP_SEQUENCES = 4
    const val MAX_STOP_LENGTH = 64
}

/**
 * The complete generation parameter set. Defaults are the shipped code defaults; a
 * [GenerationOverrides] layer only carries the fields it wants to change.
 */
@Serializable
data class GenerationParams(
    // Simple
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    // Advanced
    val repeatPenalty: Float = 1.0f,
    val repeatLastN: Int = 64,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val minP: Float = 0.05f,
    val typicalP: Float = 1.0f,
    val seed: Int = -1,
    val stop: List<String> = emptyList(),
    val samplingMode: SamplingMode = SamplingMode.DEFAULT,
    val mirostatTau: Float = 5.0f,
    val mirostatEta: Float = 0.1f,
    // Experimental: kept for v1.1, hidden in the v1.0 UI.
    val xtcProbability: Float = 0.0f,
    val xtcThreshold: Float = 0.1f,
    val dryMultiplier: Float = 0.0f,
    val topNSigma: Float = -1.0f,
) {
    /** Human-readable validation problems; empty means every value is in range. */
    fun problems(): List<String> = buildList {
        fun check(name: String, ok: Boolean) { if (!ok) add("$name out of range") }
        check("temperature", temperature in GenerationRanges.temperature)
        check("topP", topP in GenerationRanges.topP)
        check("topK", topK in GenerationRanges.topK)
        check("maxTokens", maxTokens in GenerationRanges.maxTokens)
        check("repeatPenalty", repeatPenalty in GenerationRanges.repeatPenalty)
        check("repeatLastN", repeatLastN in GenerationRanges.repeatLastN)
        check("presencePenalty", presencePenalty in GenerationRanges.presencePenalty)
        check("frequencyPenalty", frequencyPenalty in GenerationRanges.frequencyPenalty)
        check("minP", minP in GenerationRanges.minP)
        check("typicalP", typicalP in GenerationRanges.typicalP)
        check("seed", seed >= -1)
        check("mirostatTau", mirostatTau in GenerationRanges.mirostatTau)
        check("mirostatEta", mirostatEta in GenerationRanges.mirostatEta)
        check("xtcProbability", xtcProbability in GenerationRanges.xtcProbability)
        check("xtcThreshold", xtcThreshold in GenerationRanges.xtcThreshold)
        check("dryMultiplier", dryMultiplier in GenerationRanges.dryMultiplier)
        check("topNSigma", topNSigma in GenerationRanges.topNSigma)
        if (stop.size > GenerationRanges.MAX_STOP_SEQUENCES) add("too many stop sequences")
        stop.forEachIndexed { index, value ->
            if (value.isBlank() || value.length > GenerationRanges.MAX_STOP_LENGTH) {
                add("stop[$index] out of range")
            }
        }
    }

    /** Coerces every value into its range. Loaded/bad JSON must never crash or reach native out of bounds. */
    fun clamped(): GenerationParams = copy(
        temperature = temperature.coerceIn(GenerationRanges.temperature),
        topP = topP.coerceIn(GenerationRanges.topP),
        topK = topK.coerceIn(GenerationRanges.topK),
        maxTokens = maxTokens.coerceIn(GenerationRanges.maxTokens),
        repeatPenalty = repeatPenalty.coerceIn(GenerationRanges.repeatPenalty),
        repeatLastN = repeatLastN.coerceIn(GenerationRanges.repeatLastN),
        presencePenalty = presencePenalty.coerceIn(GenerationRanges.presencePenalty),
        frequencyPenalty = frequencyPenalty.coerceIn(GenerationRanges.frequencyPenalty),
        minP = minP.coerceIn(GenerationRanges.minP),
        typicalP = typicalP.coerceIn(GenerationRanges.typicalP),
        seed = seed.coerceAtLeast(-1),
        stop = stop.filter { it.isNotBlank() && it.length <= GenerationRanges.MAX_STOP_LENGTH }
            .take(GenerationRanges.MAX_STOP_SEQUENCES),
        mirostatTau = mirostatTau.coerceIn(GenerationRanges.mirostatTau),
        mirostatEta = mirostatEta.coerceIn(GenerationRanges.mirostatEta),
        xtcProbability = xtcProbability.coerceIn(GenerationRanges.xtcProbability),
        xtcThreshold = xtcThreshold.coerceIn(GenerationRanges.xtcThreshold),
        dryMultiplier = dryMultiplier.coerceIn(GenerationRanges.dryMultiplier),
        topNSigma = topNSigma.coerceIn(GenerationRanges.topNSigma),
    )

    companion object {
        val DEFAULT = GenerationParams()
    }
}

/** A sparse parameter layer: only the fields a source actually provides. */
@Serializable
data class GenerationOverrides(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val repeatPenalty: Float? = null,
    val repeatLastN: Int? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val minP: Float? = null,
    val typicalP: Float? = null,
    val seed: Int? = null,
    val stop: List<String>? = null,
    val samplingMode: SamplingMode? = null,
    val mirostatTau: Float? = null,
    val mirostatEta: Float? = null,
    val xtcProbability: Float? = null,
    val xtcThreshold: Float? = null,
    val dryMultiplier: Float? = null,
    val topNSigma: Float? = null,
) {
    fun isEmpty(): Boolean =
        temperature == null && topP == null && topK == null && maxTokens == null &&
            repeatPenalty == null && repeatLastN == null && presencePenalty == null &&
            frequencyPenalty == null && minP == null && typicalP == null && seed == null &&
            stop == null && samplingMode == null && mirostatTau == null && mirostatEta == null &&
            xtcProbability == null && xtcThreshold == null && dryMultiplier == null && topNSigma == null

    /** Ids of the fields this layer actually declares; the persisted touched-params vocabulary. */
    fun touchedFields(): Set<String> = buildSet {
        if (temperature != null) add("temperature")
        if (topP != null) add("topP")
        if (topK != null) add("topK")
        if (maxTokens != null) add("maxTokens")
        if (repeatPenalty != null) add("repeatPenalty")
        if (repeatLastN != null) add("repeatLastN")
        if (presencePenalty != null) add("presencePenalty")
        if (frequencyPenalty != null) add("frequencyPenalty")
        if (minP != null) add("minP")
        if (typicalP != null) add("typicalP")
        if (seed != null) add("seed")
        if (stop != null) add("stop")
        if (samplingMode != null) add("samplingMode")
        if (mirostatTau != null) add("mirostatTau")
        if (mirostatEta != null) add("mirostatEta")
        if (xtcProbability != null) add("xtcProbability")
        if (xtcThreshold != null) add("xtcThreshold")
        if (dryMultiplier != null) add("dryMultiplier")
        if (topNSigma != null) add("topNSigma")
    }

    /** Field-wise overlay: every non-null value replaces the base, everything else is inherited. */
    fun applyTo(base: GenerationParams): GenerationParams = base.copy(
        temperature = temperature ?: base.temperature,
        topP = topP ?: base.topP,
        topK = topK ?: base.topK,
        maxTokens = maxTokens ?: base.maxTokens,
        repeatPenalty = repeatPenalty ?: base.repeatPenalty,
        repeatLastN = repeatLastN ?: base.repeatLastN,
        presencePenalty = presencePenalty ?: base.presencePenalty,
        frequencyPenalty = frequencyPenalty ?: base.frequencyPenalty,
        minP = minP ?: base.minP,
        typicalP = typicalP ?: base.typicalP,
        seed = seed ?: base.seed,
        stop = stop ?: base.stop,
        samplingMode = samplingMode ?: base.samplingMode,
        mirostatTau = mirostatTau ?: base.mirostatTau,
        mirostatEta = mirostatEta ?: base.mirostatEta,
        xtcProbability = xtcProbability ?: base.xtcProbability,
        xtcThreshold = xtcThreshold ?: base.xtcThreshold,
        dryMultiplier = dryMultiplier ?: base.dryMultiplier,
        topNSigma = topNSigma ?: base.topNSigma,
    )
}

/**
 * Maps the GGUF `general.sampling.*` metadata onto a parameter layer. Only keys the file actually
 * carries are filled; malformed or out-of-range values are ignored rather than trusted, because a
 * corrupted file must never break generation. Mapping is exactly the council's table (doc 17 §7.2.3).
 */
object GgufSampling {
    fun fromMetadata(metadata: Map<String, String>): GenerationOverrides {
        fun float(key: String, range: ClosedFloatingPointRange<Float>): Float? =
            metadata["general.sampling.$key"]?.trim()?.toFloatOrNull()?.takeIf { it in range }

        fun int(key: String, range: IntRange): Int? =
            metadata["general.sampling.$key"]?.trim()?.toIntOrNull()?.takeIf { it in range }

        return GenerationOverrides(
            temperature = float("temp", GenerationRanges.temperature),
            topP = float("top_p", GenerationRanges.topP),
            topK = int("top_k", GenerationRanges.topK),
            minP = float("min_p", GenerationRanges.minP),
            repeatPenalty = float("penalty_repeat", GenerationRanges.repeatPenalty),
            repeatLastN = int("penalty_last_n", GenerationRanges.repeatLastN),
            mirostatTau = float("mirostat_tau", GenerationRanges.mirostatTau),
            mirostatEta = float("mirostat_eta", GenerationRanges.mirostatEta),
            samplingMode = when (int("mirostat", 0..2)) {
                2 -> SamplingMode.MIROSTAT_V2
                else -> null
            },
        )
    }
}

/** The effective prompt/template/params for the next message, resolved across all layers. */
object ModelControlResolver {

    fun resolveParams(
        code: GenerationParams = GenerationParams.DEFAULT,
        appConfig: GenerationOverrides = GenerationOverrides(),
        familyPreset: GenerationOverrides? = null,
        modelMetadata: GenerationOverrides = GenerationOverrides(),
        userParams: GenerationParams? = null,
        userTouched: Set<String> = emptySet(),
        conversation: GenerationOverrides? = null,
    ): GenerationParams {
        var effective = code
        effective = appConfig.applyTo(effective)
        familyPreset?.let { effective = it.applyTo(effective) }
        effective = modelMetadata.applyTo(effective)
        if (userParams != null && userTouched.isNotEmpty()) {
            effective = touchedOverrides(userParams, userTouched).applyTo(effective)
        }
        conversation?.let { effective = it.applyTo(effective) }
        return effective.clamped()
    }

    /** The strongest layer that supplied at least one base value; user/conversation edits win. */
    fun paramsSource(
        appConfig: GenerationOverrides = GenerationOverrides(),
        familyPreset: GenerationOverrides? = null,
        modelMetadata: GenerationOverrides = GenerationOverrides(),
        userParams: GenerationParams? = null,
        userTouched: Set<String> = emptySet(),
        conversation: GenerationOverrides? = null,
    ): DefaultSource = when {
        conversation != null && !conversation.isEmpty() -> DefaultSource.CONVERSATION
        // Touched-ness, not snapshot equality: editing a field to the code default is still a user
        // edit, and the UI must keep saying so until the user resets it.
        userParams != null && userTouched.isNotEmpty() -> DefaultSource.USER
        !modelMetadata.isEmpty() -> DefaultSource.MODEL
        familyPreset != null && !familyPreset.isEmpty() -> DefaultSource.PRESET
        !appConfig.isEmpty() -> DefaultSource.APP_DEFAULT
        else -> DefaultSource.CODE
    }

    fun resolveSystemPrompt(
        conversation: String?,
        perModel: String?,
        global: String?,
        appDefault: String,
        resourceFallback: String,
    ): ResolvedPrompt = when {
        !conversation.isNullOrBlank() -> ResolvedPrompt(conversation.trim(), PromptSource.CONVERSATION)
        !perModel.isNullOrBlank() -> ResolvedPrompt(perModel.trim(), PromptSource.MODEL)
        !global.isNullOrBlank() -> ResolvedPrompt(global.trim(), PromptSource.GLOBAL)
        appDefault.isNotBlank() -> ResolvedPrompt(appDefault.trim(), PromptSource.APP_DEFAULT)
        else -> ResolvedPrompt(resourceFallback, PromptSource.RESOURCE)
    }

    fun resolveTemplate(choice: TemplateChoice, hasModelTemplate: Boolean): ResolvedTemplate = when (choice) {
        is TemplateChoice.Builtin ->
            if (choice.name.isBlank()) resolveTemplate(TemplateChoice.ModelDefault, hasModelTemplate)
            else ResolvedTemplate(choice, TemplateSource.BUILTIN, choice.name.trim())
        TemplateChoice.ModelDefault ->
            if (hasModelTemplate) {
                ResolvedTemplate(choice, TemplateSource.MODEL, null)
            } else {
                ResolvedTemplate(choice, TemplateSource.FALLBACK, null)
            }
    }

    /** The fields declared touched become a layer; untouched fields stay inherited from below. */
    private fun touchedOverrides(params: GenerationParams, touched: Set<String>): GenerationOverrides =
        GenerationOverrides(
            temperature = params.temperature.takeIf { "temperature" in touched },
            topP = params.topP.takeIf { "topP" in touched },
            topK = params.topK.takeIf { "topK" in touched },
            maxTokens = params.maxTokens.takeIf { "maxTokens" in touched },
            repeatPenalty = params.repeatPenalty.takeIf { "repeatPenalty" in touched },
            repeatLastN = params.repeatLastN.takeIf { "repeatLastN" in touched },
            presencePenalty = params.presencePenalty.takeIf { "presencePenalty" in touched },
            frequencyPenalty = params.frequencyPenalty.takeIf { "frequencyPenalty" in touched },
            minP = params.minP.takeIf { "minP" in touched },
            typicalP = params.typicalP.takeIf { "typicalP" in touched },
            seed = params.seed.takeIf { "seed" in touched },
            stop = params.stop.takeIf { "stop" in touched },
            samplingMode = params.samplingMode.takeIf { "samplingMode" in touched },
            mirostatTau = params.mirostatTau.takeIf { "mirostatTau" in touched },
            mirostatEta = params.mirostatEta.takeIf { "mirostatEta" in touched },
            xtcProbability = params.xtcProbability.takeIf { "xtcProbability" in touched },
            xtcThreshold = params.xtcThreshold.takeIf { "xtcThreshold" in touched },
            dryMultiplier = params.dryMultiplier.takeIf { "dryMultiplier" in touched },
            topNSigma = params.topNSigma.takeIf { "topNSigma" in touched },
        )
}

/** Facts read from a loaded GGUF model. Parsed from the native `key=value` lines in pure Kotlin. */
data class ModelFacts(
    val architecture: String? = null,
    val nCtxTrain: Int = 0,
    val nParams: Long = 0,
    val nVocab: Int = 0,
    val templateSource: TemplateSource = TemplateSource.NONE,
    val templateFirstLine: String? = null,
    val templateHash: String? = null,
    val samplingMetadata: Map<String, String> = emptyMap(),
    val bosTokenId: Int? = null,
    val eosTokenId: Int? = null,
) {
    val hasModelTemplate: Boolean get() = templateSource == TemplateSource.MODEL

    companion object {
        private const val SAMPLING_PREFIX = "general.sampling."

        /**
         * Parses newline-separated `key=value` lines from the native model-info call. [chatTemplate] is
         * the model's raw template text when the caller also fetched it (needed for the hash/first line);
         * facts without it still know whether a template exists.
         */
        fun parse(raw: String, chatTemplate: String? = null): ModelFacts? {
            if (raw.isBlank()) return null
            val values = LinkedHashMap<String, String>()
            raw.lineSequence().forEach { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                val key = line.substring(0, eq).trim()
                if (key.isEmpty()) return@forEach
                values[key] = line.substring(eq + 1).trim()
            }
            if (values.isEmpty()) return null

            val sampling = values.filterKeys { it.startsWith(SAMPLING_PREFIX) }
            val templatePresent = values["chat_template_present"] in setOf("1", "true")
            val templateSource = if (chatTemplate != null || templatePresent) TemplateSource.MODEL else TemplateSource.NONE
            val firstLine = chatTemplate
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?: values["chat_template_first_line"]

            return ModelFacts(
                architecture = values["architecture"]?.takeIf { it.isNotBlank() },
                nCtxTrain = values["n_ctx_train"]?.toIntOrNull() ?: 0,
                nParams = values["n_params"]?.toLongOrNull() ?: 0L,
                nVocab = values["n_vocab"]?.toIntOrNull() ?: 0,
                templateSource = if (chatTemplate != null) TemplateSource.MODEL else templateSource,
                templateFirstLine = firstLine,
                templateHash = chatTemplate?.let { ModelFingerprint.sha256Hex(it) },
                samplingMetadata = sampling,
                bosTokenId = values["bos_token_id"]?.toIntOrNull(),
                eosTokenId = values["eos_token_id"]?.toIntOrNull(),
            )
        }
    }
}

/**
 * Model identity: a content fingerprint over the file size plus the first and last 64 KiB. Renaming or
 * moving the file keeps the key; replacing its body (or its ends) changes it, so settings cannot leak
 * across models. Scanned ends only, so a 2 GB import costs two small reads.
 */
object ModelFingerprint {
    private const val SAMPLE_BYTES = 64 * 1024

    fun sha256Hex(text: String): String = "sha256:" + digestHex(text.toByteArray(Charsets.UTF_8))

    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val length = file.length()
        val lengthBytes = ByteArray(8)
        var remaining = length
        for (i in 0 until 8) {
            lengthBytes[i] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        digest.update(lengthBytes)
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(SAMPLE_BYTES.toLong(), length).toInt())
            raf.readFully(head)
            digest.update(head)
            val tailLength = minOf(SAMPLE_BYTES.toLong(), length).toInt()
            raf.seek(length - tailLength)
            val tail = ByteArray(tailLength)
            raf.readFully(tail)
            digest.update(tail)
        }
        return "sha256:" + hex(digest.digest())
    }

    private fun digestHex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return hex(digest.digest(bytes))
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}

/** Persisted per-model settings, keyed by [ModelFingerprint]. */
@Serializable
data class ModelSettings(
    val modelKey: String,
    /** The full parameter snapshot the user edited; only [touchedParams] fields are applied. */
    val params: GenerationParams? = null,
    /** Field ids (e.g. "temperature") the user changed; the rest stay model/preset derived. */
    val touchedParams: List<String> = emptyList(),
    /** Per-model system prompt; null/blank means "use the global default". */
    val systemPrompt: String? = null,
    /** Forced built-in template name; null means "use the model's own template". */
    val template: String? = null,
    val userModifiedAt: Long? = null,
) {
    val templateChoice: TemplateChoice get() = TemplateChoice.fromStored(template)
}

/** Durable home of [ModelSettings]. Implementation lives in :app (atomic JSON file). */
interface ModelSettingsStore {
    val settings: StateFlow<Map<String, ModelSettings>>

    fun get(modelKey: String): ModelSettings?

    fun put(settings: ModelSettings)

    /** Drops every override for [modelKey], returning it to model/preset/app defaults. */
    fun reset(modelKey: String)
}

/** Localised content used by presets: both languages are always shipped together. */
@Serializable
data class LocalisedText(val ar: String = "", val en: String = "") {
    fun inLanguage(language: AppLanguage): String = when (language) {
        AppLanguage.ARABIC -> ar.ifBlank { en }
        AppLanguage.ENGLISH -> en.ifBlank { ar }
    }
}

/** A persona-lite prompt preset: content, not code (asset `content/prompt_presets.json`). */
@Serializable
data class PromptPreset(
    val id: String,
    val name: LocalisedText,
    val description: LocalisedText,
    val systemPrompt: LocalisedText,
    val params: GenerationOverrides? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class PromptPresetsFile(val schemaVersion: Int = 1, val presets: List<PromptPreset> = emptyList())

/** A family preset: default parameters for a model family (asset `content/model_presets.json`). */
@Serializable
data class ModelPreset(
    val id: String,
    val families: List<String> = emptyList(),
    val params: GenerationOverrides = GenerationOverrides(),
)

@Serializable
data class ModelPresetsFile(val schemaVersion: Int = 1, val presets: List<ModelPreset> = emptyList())

object PromptPresets {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Result<PromptPresetsFile> =
        runCatching { json.decodeFromString(PromptPresetsFile.serializer(), raw) }

    fun problems(file: PromptPresetsFile): List<String> = buildList {
        if (file.schemaVersion != 1) add("unsupported schemaVersion " + file.schemaVersion)
        val duplicates = file.presets.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (duplicates.isNotEmpty()) add("duplicate preset ids: " + duplicates.keys)
        file.presets.forEach { preset ->
            val label = "preset '" + preset.id + "'"
            if (preset.id.isBlank()) add("blank preset id")
            if (preset.name.ar.isBlank() || preset.name.en.isBlank()) add(label + " name missing a language")
            if (preset.description.ar.isBlank() || preset.description.en.isBlank()) {
                add(label + " description missing a language")
            }
            if (preset.systemPrompt.ar.isBlank() || preset.systemPrompt.en.isBlank()) {
                add(label + " systemPrompt missing a language")
            }
        }
    }
}

object ModelPresets {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Result<ModelPresetsFile> =
        runCatching { json.decodeFromString(ModelPresetsFile.serializer(), raw) }

    fun problems(file: ModelPresetsFile): List<String> = buildList {
        if (file.schemaVersion != 1) add("unsupported schemaVersion " + file.schemaVersion)
        val duplicates = file.presets.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (duplicates.isNotEmpty()) add("duplicate preset ids: " + duplicates.keys)
        file.presets.forEach { preset ->
            if (preset.id.isBlank()) add("blank preset id")
            if (preset.families.isEmpty()) add("preset '" + preset.id + "' has no families")
        }
    }

    /** First preset whose family token appears in [architecture], or null when none matches. */
    fun forArchitecture(file: ModelPresetsFile, architecture: String?): ModelPreset? {
        val arch = architecture?.lowercase() ?: return null
        return file.presets.firstOrNull { preset ->
            preset.families.any { family -> family.isNotBlank() && arch.contains(family.lowercase()) }
        }
    }
}

/**
 * Stable key for a model's settings. A content fingerprint, not a display name or path alone, so
 * renaming/moving keeps the settings and replacing the file cannot leak them onto another model.
 */
interface ModelIdentity {
    fun keyFor(model: InstalledModel): String
}

/** Presets that ship with the app; both are content assets, not code. */
interface PresetRepository {
    val modelPresets: ModelPresetsFile
    val promptPresets: PromptPresetsFile
}

/** What the chat feature needs from model control for one request: no wide dependency on the UI. */
fun interface GenerationSettingsProvider {
    fun options(): GenerationOptions

    /**
     * Layers the conversation on top of the base resolution (M6). The default implementation folds
     * the conversation overrides onto [options], so every engine/test keeps working; the app's
     * implementation overrides it to feed the same layer into ModelControlResolver, where the
     * "defaults from" reporting and clamping already live.
     */
    fun options(
        conversationOverrides: GenerationOverrides?,
        conversationPrompt: String?,
    ): GenerationOptions {
        val base = options()
        val params = conversationOverrides?.applyTo(base.params) ?: base.params
        return base.copy(
            params = params.clamped(),
            systemPrompt = conversationPrompt?.takeIf { it.isNotBlank() } ?: base.systemPrompt,
        )
    }

    companion object {
        val Default = GenerationSettingsProvider { GenerationOptions() }
    }
}

/** Everything the engine needs for one generation request. */
data class GenerationOptions(
    val params: GenerationParams = GenerationParams.DEFAULT,
    val template: TemplateChoice = TemplateChoice.ModelDefault,
    /** Resolved system prompt; null lets the engine use its constructor default. */
    val systemPrompt: String? = null,
)
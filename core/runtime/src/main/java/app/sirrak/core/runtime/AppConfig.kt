package app.sirrak.core.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The declarative configuration of the app. One source of truth for safe runtime overrides.
 *
 * Membership of feature plugins is declared in Kotlin (see `AppComposition`), never here; this file
 * only carries data that is safe to change without a rebuild: feature order/enablement, default
 * theme/language, engine provider id, generation defaults, content paths and UI limits. Unknown JSON
 * keys are rejected by the strict Json parser so a typo cannot silently disable something.
 */
@Serializable
data class AppConfig(
    val schemaVersion: Int = 1,
    val features: FeaturesConfig = FeaturesConfig(),
    val theme: ThemeConfig = ThemeConfig(),
    val language: LanguageConfig = LanguageConfig(),
    val engine: EngineConfig = EngineConfig(),
    val content: ContentConfig = ContentConfig(),
    val ui: UiConfig = UiConfig(),
    /**
     * P3 privacy surfaces (PRV-2/PRV-5/PRV-6): the declared network endpoints, the offline-only
     * default, and the policy/report channels. Data, not code: adding a network user is one entry.
     */
    val privacy: PrivacyConfig = PrivacyConfig(),
    /**
     * P3.2 licence/SBOM registry: the asset that drives the About screen and the NOTICE file. A new
     * component is one JSON entry; the app never hardcodes a licence row.
     */
    val licences: LicencesConfig = LicencesConfig(),
)

@Serializable
data class FeaturesConfig(
    val order: List<String> = emptyList(),
    val enabled: List<String> = emptyList(),
)

@Serializable
data class ThemeConfig(
    val defaultMode: String = "system",
    /**
     * Theme-pack id shown until the user picks one. Validated structurally here (non-blank); the app
     * resolves it against :core:design's registry and falls back to the default pack for unknown ids,
     * because :core:runtime must not depend on :core:design.
     */
    val defaultPack: String = "girih_ink",
)

@Serializable
data class LanguageConfig(val defaultLanguage: String = "ar")

/**
 * Engine defaults. The original four values are non-null because they predate M5 and are always
 * meaningful; the M5 layer is deliberately sparse (null = inherit from code), so "defaults from:
 * app config" can only be shown when app.json really declared a value.
 */
@Serializable
data class EngineConfig(
    val provider: String = "llamacpp",
    val contextTokens: Int = 2048,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    /** Empty means "use the localised default prompt from resources". */
    val systemPrompt: String = "",
    // M5 sparse layer: absent/null means "inherit from code defaults".
    val repeatPenalty: Float? = null,
    val repeatLastN: Int? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val minP: Float? = null,
    val typicalP: Float? = null,
    val seed: Int? = null,
    /** One of "default", "greedy", "mirostat_v2" (see [SamplingMode]). */
    val samplingMode: String? = null,
    val mirostatTau: Float? = null,
    val mirostatEta: Float? = null,
    val xtcProbability: Float? = null,
    val xtcThreshold: Float? = null,
    val dryMultiplier: Float? = null,
    val topNSigma: Float? = null,
    val stop: List<String> = emptyList(),
)

/**
 * The app.json generation layer, converted to the sparse overlay the resolver understands. Values equal
 * to the code defaults are treated as "not declared": app.json only overrides what it changes, so the
 * defaults-source row can stay honest and content/family/model layers keep working.
 */
fun EngineConfig.generationOverrides(): GenerationOverrides {
    val code = GenerationParams.DEFAULT
    return GenerationOverrides(
        temperature = temperature.takeIf { it != code.temperature },
        topP = topP.takeIf { it != code.topP },
        topK = topK.takeIf { it != code.topK },
        maxTokens = maxTokens.takeIf { it != code.maxTokens },
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        minP = minP,
        typicalP = typicalP,
        seed = seed,
        stop = stop.takeIf { it.isNotEmpty() },
        samplingMode = samplingMode?.let { SamplingMode.fromConfig(it) },
        mirostatTau = mirostatTau,
        mirostatEta = mirostatEta,
        xtcProbability = xtcProbability,
        xtcThreshold = xtcThreshold,
        dryMultiplier = dryMultiplier,
        topNSigma = topNSigma,
    )
}

@Serializable
data class ContentConfig(
    val demoReplies: String = "content/demo_chat.json",
    val models: String = "content/models.json",
    /** Bilingual system-prompt presets (content, editable without a rebuild). */
    val promptPresets: String = "content/prompt_presets.json",
    /** Model-family generation defaults. */
    val modelPresets: String = "content/model_presets.json",
    /**
     * M11: the curated model catalogue (size, licence, quant, RAM, SHA-256, download repo/file). Data,
     * not code: adding or removing a recommended model is one JSON entry, no rebuild of logic.
     */
    val catalog: String = "catalog/models_catalogue.json",
)

@Serializable
data class UiConfig(
    val maxContentWidthDp: Int = 680,
    val showTimestamps: Boolean = false,
)

@Serializable
data class PrivacyConfig(
    /** PRV-2: start with the offline-only switch on. Off by default so model download works. */
    val offlineByDefault: Boolean = false,
    /** PRV-5: published privacy policy. Blank = not published yet (the UI says so honestly). */
    val policyUrl: String = "",
    /** PRV-5: security disclosure policy. */
    val securityUrl: String = "",
    /** PRV-6: where a shared AI-output report should go (mailto). Blank = local-only reports. */
    val reportEmail: String = "",
    /** PRV-6: issue-tracker fallback for sharing a report. */
    val reportIssueUrl: String = "",
    /**
     * Every network endpoint the app may contact, declared as data. The privacy screen renders this
     * registry next to the on-device audit log; the downloader's host must appear here.
     */
    val endpoints: List<NetworkEndpoint> = emptyList(),
)

@Serializable
data class NetworkEndpoint(
    val id: String,
    /** Host only (no scheme, no path), e.g. "huggingface.co". */
    val host: String,
    /** English technical purpose; shown when no localised [purposeKey] is known. */
    val purpose: String = "",
    /** English technical "when is it used" line. */
    val whenUsed: String = "",
    val userInitiated: Boolean = true,
    /**
     * Stable key the UI resolves to a localised string (e.g. "model_download"). A new endpoint can
     * ship without a key: the raw purpose lines are shown instead.
     */
    val purposeKey: String = "",
)

@Serializable
data class LicencesConfig(
    /** Asset path of the licence registry, relative to assets/. */
    val assetPath: String = "config/licences.json",
)

object AppConfigLoader {

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(raw: String): AppConfig = json.decodeFromString(AppConfig.serializer(), raw)

    /** Structural checks that need no Android context. Returns human-readable problems. */
    fun validate(config: AppConfig): List<String> = buildList {
        if (config.schemaVersion != 1) add("unsupported schemaVersion ${config.schemaVersion}")
        val engine = config.engine
        if (engine.provider.isBlank()) add("engine.provider is blank")
        if (engine.contextTokens !in 512..8192) add("engine.contextTokens out of range")
        if (engine.maxTokens !in 16..2048) add("engine.maxTokens out of range")
        if (engine.temperature !in 0f..2f) add("engine.temperature out of range")
        if (engine.topP !in 0.05f..1f) add("engine.topP out of range")
        if (engine.topK !in 1..200) add("engine.topK out of range")
        engine.repeatPenalty?.let { if (it !in 0f..2f) add("engine.repeatPenalty out of range") }
        engine.repeatLastN?.let { if (it !in -1..2048) add("engine.repeatLastN out of range") }
        engine.presencePenalty?.let { if (it !in 0f..2f) add("engine.presencePenalty out of range") }
        engine.frequencyPenalty?.let { if (it !in 0f..2f) add("engine.frequencyPenalty out of range") }
        engine.minP?.let { if (it !in 0f..1f) add("engine.minP out of range") }
        engine.typicalP?.let { if (it !in 0f..1f) add("engine.typicalP out of range") }
        engine.seed?.let { if (it < -1) add("engine.seed out of range") }
        engine.samplingMode?.let { if (SamplingMode.fromConfig(it) == null) add("engine.samplingMode is not default|greedy|mirostat_v2") }
        engine.mirostatTau?.let { if (it !in 0f..20f) add("engine.mirostatTau out of range") }
        engine.mirostatEta?.let { if (it !in 0f..1f) add("engine.mirostatEta out of range") }
        engine.xtcProbability?.let { if (it !in 0f..1f) add("engine.xtcProbability out of range") }
        engine.xtcThreshold?.let { if (it !in 0f..1f) add("engine.xtcThreshold out of range") }
        engine.dryMultiplier?.let { if (it !in 0f..10f) add("engine.dryMultiplier out of range") }
        engine.topNSigma?.let { if (it !in -1f..10f) add("engine.topNSigma out of range") }
        if (engine.stop.size > GenerationRanges.MAX_STOP_SEQUENCES) add("engine.stop has too many sequences")
        engine.stop.forEachIndexed { index, value ->
            if (value.isBlank() || value.length > GenerationRanges.MAX_STOP_LENGTH) {
                add("engine.stop[$index] out of range")
            }
        }
        if (config.theme.defaultMode !in setOf("light", "dark", "system")) {
            add("theme.defaultMode '${config.theme.defaultMode}' is not light|dark|system")
        }
        if (config.theme.defaultPack.isBlank()) add("theme.defaultPack is blank")
        if (config.language.defaultLanguage !in setOf("ar", "en")) {
            add("language.defaultLanguage '${config.language.defaultLanguage}' is not ar|en")
        }
        if (config.content.demoReplies.isBlank()) add("content.demoReplies is blank")
        if (config.content.models.isBlank()) add("content.models is blank")
        if (config.content.promptPresets.isBlank()) add("content.promptPresets is blank")
        if (config.content.modelPresets.isBlank()) add("content.modelPresets is blank")
        val duplicated = config.features.order.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (duplicated.isNotEmpty()) add("features.order duplicates: ${duplicated.keys}")
        val unknownEnabled = config.features.enabled - config.features.order.toSet()
        if (unknownEnabled.isNotEmpty()) add("features.enabled not in order: $unknownEnabled")
        if (config.ui.maxContentWidthDp !in 320..1200) add("ui.maxContentWidthDp out of range")
        validatePrivacy(config.privacy, ::add)
        validateLicences(config.licences, ::add)
    }

    private fun validatePrivacy(privacy: PrivacyConfig, add: (String) -> Unit) {
        fun validateUrl(field: String, value: String) {
            if (value.isBlank()) return
            if (!value.startsWith("https://") || value.length > 300) {
                add("privacy.\$field must be an https URL")
            }
        }
        validateUrl("policyUrl", privacy.policyUrl)
        validateUrl("securityUrl", privacy.securityUrl)
        validateUrl("reportIssueUrl", privacy.reportIssueUrl)
        if (privacy.reportEmail.isNotBlank()) {
            val email = privacy.reportEmail
            if (!email.contains('@') || email.any { it.isWhitespace() } || email.length > 200) {
                add("privacy.reportEmail is not an email address")
            }
        }
        val duplicated = privacy.endpoints.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (duplicated.isNotEmpty()) add("privacy.endpoints duplicate ids: \${duplicated.keys}")
        privacy.endpoints.forEachIndexed { index, endpoint ->
            if (endpoint.id.isBlank()) add("privacy.endpoints[\$index].id is blank")
            if (endpoint.host.isBlank() || endpoint.host.contains("://") || endpoint.host.contains('/')) {
                add("privacy.endpoints[\$index].host must be a host name")
            }
            if (endpoint.purpose.length > 200 || endpoint.whenUsed.length > 200) {
                add("privacy.endpoints[\$index] text too long")
            }
        }
    }

    private fun validateLicences(licences: LicencesConfig, add: (String) -> Unit) {
        val path = licences.assetPath
        if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.length > 200) {
            add("licences.assetPath must be a relative asset path")
        }
    }

    fun requireValid(config: AppConfig) {
        val problems = validate(config)
        check(problems.isEmpty()) { "Invalid app config: ${problems.joinToString("; ")}" }
    }
}

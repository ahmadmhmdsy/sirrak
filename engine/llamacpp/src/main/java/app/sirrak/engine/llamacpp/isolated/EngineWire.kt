package app.sirrak.engine.llamacpp.isolated

import android.os.Bundle
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.Role
import app.sirrak.core.runtime.SamplingMode
import app.sirrak.core.runtime.TemplateChoice

/**
 * ADR-4 process-isolation spike, wire layer. The UI process binds [IsolatedEngineService] running in
 * `<applicationId>:engine` and the two sides exchange Bundles through a [android.os.Messenger].
 *
 * Why a separate process: llama.cpp aborts the whole process on a failed GGML assertion (see the M8
 * chunked-prefill fix). If the engine lives in the UI process, that abort kills the app. With the
 * engine in its own process the UI survives, reports [app.sirrak.core.runtime.EngineReasons.PROCESS_LOST]
 * and a retry loads the model in a fresh engine process.
 *
 * This file keeps every message id, key and codec in one place so the client and the service cannot
 * drift; the device test EngineIsolationDeviceTest is the integration evidence.
 */
object EngineWire {

    /** The manifest process suffix: the service runs as `<applicationId>:engine`. */
    const val PROCESS_SUFFIX = ":engine"

    // UI process -> engine process
    const val MSG_LOAD = 1
    const val MSG_GENERATE = 2
    const val MSG_STOP = 3
    const val MSG_UNLOAD = 4

    /** Debug-only simulation of a native abort: kills the engine process from inside (test hook). */
    const val MSG_SIMULATE_CRASH = 5

    // engine process -> UI process
    const val MSG_LOADED = 101
    const val MSG_TOKEN = 102
    const val MSG_DONE = 103
    const val MSG_ERROR = 104

    const val KEY_PID = "pid"
    const val KEY_PROCESS = "process"
    const val KEY_MODEL_PATH = "modelPath"
    const val KEY_MODEL_NAME = "modelName"
    const val KEY_PROMPT = "prompt"
    const val KEY_ROLES = "roles"
    const val KEY_TEXTS = "texts"
    const val KEY_OPTIONS = "options"
    const val KEY_ERROR = "error"
    const val KEY_DELTA = "delta"

    private const val KEY_SYSTEM_PROMPT = "systemPrompt"
    private const val KEY_TEMPLATE = "template"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_TOP_P = "topP"
    private const val KEY_TOP_K = "topK"
    private const val KEY_MAX_TOKENS = "maxTokens"
    private const val KEY_REPEAT_PENALTY = "repeatPenalty"
    private const val KEY_REPEAT_LAST_N = "repeatLastN"
    private const val KEY_PRESENCE_PENALTY = "presencePenalty"
    private const val KEY_FREQUENCY_PENALTY = "frequencyPenalty"
    private const val KEY_MIN_P = "minP"
    private const val KEY_TYPICAL_P = "typicalP"
    private const val KEY_SEED = "seed"
    private const val KEY_STOPS = "stops"
    private const val KEY_SAMPLING = "sampling"
    private const val KEY_MIROSTAT_TAU = "mirostatTau"
    private const val KEY_MIROSTAT_ETA = "mirostatEta"
    private const val KEY_XTC_PROBABILITY = "xtcProbability"
    private const val KEY_XTC_THRESHOLD = "xtcThreshold"
    private const val KEY_DRY_MULTIPLIER = "dryMultiplier"
    private const val KEY_TOP_N_SIGMA = "topNSigma"

    /** A load request: only the path is authoritative; the name is for logs/status labels. */
    fun loadRequest(path: String, displayName: String): Bundle = Bundle().apply {
        putString(KEY_MODEL_PATH, path)
        putString(KEY_MODEL_NAME, displayName)
    }

    /** A generation request: prompt, chat history (roles + rendered texts) and the effective options. */
    fun generationRequest(
        prompt: String,
        history: List<ChatTurn>,
        options: GenerationOptions,
    ): Bundle = Bundle().apply {
        putString(KEY_PROMPT, prompt)
        putStringArray(KEY_ROLES, history.map { it.role.name }.toTypedArray())
        putStringArray(KEY_TEXTS, history.map { it.text }.toTypedArray())
        putBundle(KEY_OPTIONS, encodeOptions(options))
    }

    /** History round-trip; attachments are intentionally not carried over the spike wire. */
    fun decodeHistory(bundle: Bundle): List<ChatTurn> {
        val roles = bundle.getStringArray(KEY_ROLES).orEmpty()
        val texts = bundle.getStringArray(KEY_TEXTS).orEmpty()
        return roles.indices.mapNotNull { index ->
            val role = roles[index].let { runCatching { Role.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            ChatTurn(role, texts.getOrElse(index) { "" })
        }
    }

    /** Encodes every effective generation field so the engine process runs the exact request. */
    fun encodeOptions(options: GenerationOptions): Bundle {
        val params = options.params
        return Bundle().apply {
            putString(KEY_SYSTEM_PROMPT, options.systemPrompt)
            putString(KEY_TEMPLATE, (options.template as? TemplateChoice.Builtin)?.name)
            putFloat(KEY_TEMPERATURE, params.temperature)
            putFloat(KEY_TOP_P, params.topP)
            putInt(KEY_TOP_K, params.topK)
            putInt(KEY_MAX_TOKENS, params.maxTokens)
            putFloat(KEY_REPEAT_PENALTY, params.repeatPenalty)
            putInt(KEY_REPEAT_LAST_N, params.repeatLastN)
            putFloat(KEY_PRESENCE_PENALTY, params.presencePenalty)
            putFloat(KEY_FREQUENCY_PENALTY, params.frequencyPenalty)
            putFloat(KEY_MIN_P, params.minP)
            putFloat(KEY_TYPICAL_P, params.typicalP)
            putInt(KEY_SEED, params.seed)
            putStringArray(KEY_STOPS, params.stop.toTypedArray())
            putString(KEY_SAMPLING, params.samplingMode.name)
            putFloat(KEY_MIROSTAT_TAU, params.mirostatTau)
            putFloat(KEY_MIROSTAT_ETA, params.mirostatEta)
            putFloat(KEY_XTC_PROBABILITY, params.xtcProbability)
            putFloat(KEY_XTC_THRESHOLD, params.xtcThreshold)
            putFloat(KEY_DRY_MULTIPLIER, params.dryMultiplier)
            putFloat(KEY_TOP_N_SIGMA, params.topNSigma)
        }
    }

    /** Decodes a request; missing keys fall back to the shipped code defaults, never to zero. */
    fun decodeOptions(bundle: Bundle?): GenerationOptions {
        if (bundle == null) return GenerationOptions()
        val defaults = GenerationParams.DEFAULT
        val params = GenerationParams(
            temperature = bundle.getFloat(KEY_TEMPERATURE, defaults.temperature),
            topP = bundle.getFloat(KEY_TOP_P, defaults.topP),
            topK = bundle.getInt(KEY_TOP_K, defaults.topK),
            maxTokens = bundle.getInt(KEY_MAX_TOKENS, defaults.maxTokens),
            repeatPenalty = bundle.getFloat(KEY_REPEAT_PENALTY, defaults.repeatPenalty),
            repeatLastN = bundle.getInt(KEY_REPEAT_LAST_N, defaults.repeatLastN),
            presencePenalty = bundle.getFloat(KEY_PRESENCE_PENALTY, defaults.presencePenalty),
            frequencyPenalty = bundle.getFloat(KEY_FREQUENCY_PENALTY, defaults.frequencyPenalty),
            minP = bundle.getFloat(KEY_MIN_P, defaults.minP),
            typicalP = bundle.getFloat(KEY_TYPICAL_P, defaults.typicalP),
            seed = bundle.getInt(KEY_SEED, defaults.seed),
            stop = bundle.getStringArray(KEY_STOPS)?.toList().orEmpty(),
            samplingMode = SamplingMode.entries.firstOrNull { it.name == bundle.getString(KEY_SAMPLING) }
                ?: defaults.samplingMode,
            mirostatTau = bundle.getFloat(KEY_MIROSTAT_TAU, defaults.mirostatTau),
            mirostatEta = bundle.getFloat(KEY_MIROSTAT_ETA, defaults.mirostatEta),
            xtcProbability = bundle.getFloat(KEY_XTC_PROBABILITY, defaults.xtcProbability),
            xtcThreshold = bundle.getFloat(KEY_XTC_THRESHOLD, defaults.xtcThreshold),
            dryMultiplier = bundle.getFloat(KEY_DRY_MULTIPLIER, defaults.dryMultiplier),
            topNSigma = bundle.getFloat(KEY_TOP_N_SIGMA, defaults.topNSigma),
        )
        val template = bundle.getString(KEY_TEMPLATE)?.takeIf { it.isNotBlank() }
            ?.let { TemplateChoice.Builtin(it) }
            ?: TemplateChoice.ModelDefault
        return GenerationOptions(
            params = params,
            template = template,
            systemPrompt = bundle.getString(KEY_SYSTEM_PROMPT),
        )
    }
}

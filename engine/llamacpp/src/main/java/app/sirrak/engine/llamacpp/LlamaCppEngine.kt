package app.sirrak.engine.llamacpp

import android.util.Log
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.EngineCapabilities
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineReasons
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.ImageInput
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelFacts
import app.sirrak.core.runtime.Role
import app.sirrak.core.runtime.SamplingMode
import app.sirrak.core.runtime.TemplateChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device inference through llama.cpp. One model at a time; a loaded model stays resident until it
 * is unloaded or replaced, because reloading a 1-2 GB file per message would be unusable.
 *
 * The conversation is rebuilt on every turn from the supplied history, and native code drops the
 * oldest turns if the prompt would overflow the context window. M5 adds: GGUF facts read once at
 * load, per-request generation parameters/template/stop sequences, and a prompt preview used by
 * Settings > Model.
 *
 * Constructor parameters are the fallback defaults for callers that do not pass [GenerationOptions];
 * the model-control service supplies the effective values on the real chat path.
 */
class LlamaCppEngine(
    engineDisplayName: String,
    private val defaultSystemPrompt: String,
    private val defaultContextTokens: Int = 2048,
    private val defaultMaxTokens: Int = 512,
    private val defaultTemperature: Float = 0.7f,
    private val defaultTopP: Float = 0.95f,
    private val defaultTopK: Int = 40,
) : InferenceEngine {

    override val id: String = ID
    override val displayName: String = engineDisplayName

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Unloaded)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    @Volatile
    private var facts: ModelFacts? = null
    override val modelFacts: ModelFacts? get() = facts

    private val loadMutex = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var handle: Long = 0L

    /** M9: true only when the loaded projector actually reports vision support. */
    @Volatile
    private var visionReady: Boolean = false

    /** M9: libmtmd's media marker, fetched from native code after a vision-capable load. */
    @Volatile
    private var visionMarker: String = ""

    /** True when this engine can answer questions about images right now. */
    val visionEnabled: Boolean get() = visionReady

    /**
     * What the composer may attach: documents stay off because grounding is the RAG pipeline's job
     * (M8); images turn on only when a projector with real vision support is loaded (M9).
     */
    override val capabilities: EngineCapabilities
        get() = EngineCapabilities(
            text = true,
            images = visionReady,
            documents = false,
            audioTranscripts = false,
        )

    /** Loads [model] and its optional vision projector, replacing any resident model. */
    suspend fun load(model: InstalledModel) {
        loadMutex.withLock {
            val previous = handle
            handle = 0L
            facts = null
            visionReady = false
            visionMarker = ""
            if (previous != 0L) {
                withContext(Dispatchers.Default) {
                    runCatching { LlamaNative.nativeUnloadModel(previous) }
                }
            }
            _status.value = EngineStatus.Loading(model.displayName)
            val loaded = withContext(Dispatchers.Default) {
                runCatching {
                    LlamaNative.nativeLoadModel(model.path, defaultContextTokens, model.visionProjectorPath)
                }.onFailure { Log.e(TAG, "model load failed", it) }.getOrDefault(0L)
            }
            when {
                loaded == PROJECTOR_FAILED ->
                    _status.value = EngineStatus.Failed(EngineReasons.VISION_PROJECTOR_FAILED)
                loaded == PROJECTOR_NO_VISION ->
                    _status.value = EngineStatus.Failed(EngineReasons.VISION_PROJECTOR_NO_VISION)
                loaded > 0L -> {
                    handle = loaded
                    facts = withContext(Dispatchers.Default) { readFacts(loaded) }
                    visionReady = withContext(Dispatchers.Default) {
                        runCatching {
                            if (!LlamaNative.nativeVisionSupported(loaded)) return@runCatching false
                            visionMarker = LlamaNative.nativeVisionMarker()
                            true
                        }.onFailure { Log.w(TAG, "could not query vision support", it) }
                            .getOrDefault(false)
                    }
                    _status.value = EngineStatus.Ready(model.displayName)
                }
                else -> _status.value = EngineStatus.Failed(EngineReasons.LOAD_FAILED)
            }
        }
    }

    fun unload() {
        val previous = handle
        handle = 0L
        facts = null
        visionReady = false
        visionMarker = ""
        _status.value = EngineStatus.Unloaded
        if (previous != 0L) {
            engineScope.launch { runCatching { LlamaNative.nativeUnloadModel(previous) } }
        }
    }

    /** Frees the model and stops accepting work. The composition calls this on shutdown. */
    fun close() {
        unload()
        engineScope.cancel()
    }

    override fun stop() {
        val active = handle
        if (active != 0L) LlamaNative.nativeRequestStop(active)
    }

    /** Built-in templates llama.cpp can apply; shown in Settings > Model. */
    fun builtinTemplates(): List<String> =
        runCatching { LlamaNative.nativeBuiltinTemplates().toList() }.getOrDefault(emptyList())

    /**
     * Renders the prompt a request would produce, without generating. Used by the template preview.
     * Returns null when no model is loaded or the template cannot be applied.
     */
    fun previewPrompt(
        systemPrompt: String,
        prompt: String,
        history: List<ChatTurn> = emptyList(),
        choice: TemplateChoice = TemplateChoice.ModelDefault,
    ): String? {
        val session = handle
        if (session == 0L) return null
        val rendered = runCatching {
            LlamaNative.nativeFormatPrompt(session, systemPrompt, buildMessages(prompt, history), templateName(choice))
        }.getOrNull() ?: return null
        return String(rendered, Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> =
        stream(prompt, history, defaultOptions())

    override fun stream(
        prompt: String,
        history: List<ChatTurn>,
        options: GenerationOptions,
    ): Flow<EngineChunk> = generation(prompt, history, image = null, options = options)

    /**
     * M9: one image plus the question. Only the first image is sent today (multi-image is v1.1); the
     * media marker goes into the last user message and libmtmd replaces it with the encoded image.
     */
    override fun streamWithImages(
        prompt: String,
        history: List<ChatTurn>,
        images: List<ImageInput>,
        options: GenerationOptions,
    ): Flow<EngineChunk> {
        val image = images.firstOrNull() ?: return stream(prompt, history, options)
        return generation(prompt, history, image, options)
    }

    /** One generation pipeline for text and image turns; [image] is null for plain text. */
    private fun generation(
        prompt: String,
        history: List<ChatTurn>,
        image: ImageInput?,
        options: GenerationOptions,
    ): Flow<EngineChunk> = channelFlow {
        val session = handle
        if (session == 0L) {
            trySend(EngineChunk.Failed(EngineReasons.NO_MODEL))
            close()
            return@channelFlow
        }
        if (image != null && !visionReady) {
            trySend(EngineChunk.Failed(EngineReasons.VISION_UNAVAILABLE))
            close()
            return@channelFlow
        }
        val params = options.params
        val messages = if (image == null) buildMessages(prompt, history) else mediaMessages(prompt, history)
        val sink = object : TokenSink {
            override fun onToken(piece: String) {
                trySend(EngineChunk.Delta(piece))
            }
        }
        val systemPrompt = options.systemPrompt ?: defaultSystemPrompt
        val floats = parameterFloats(params)
        val ints = parameterInts(params)
        val stops = params.stop.joinToString(STOP_SEPARATOR)
        val template = templateName(options.template)
        launch(Dispatchers.Default) {
            try {
                if (image == null) {
                    LlamaNative.nativeGenerate(
                        handle = session,
                        systemPrompt = systemPrompt,
                        messages = messages,
                        maxTokens = params.maxTokens,
                        floats = floats,
                        ints = ints,
                        stopJoined = stops,
                        templateName = template,
                        sink = sink,
                    )
                } else {
                    LlamaNative.nativeGenerateVision(
                        handle = session,
                        systemPrompt = systemPrompt,
                        messages = messages,
                        image = image.bytes,
                        maxTokens = params.maxTokens,
                        floats = floats,
                        ints = ints,
                        stopJoined = stops,
                        templateName = template,
                        sink = sink,
                    )
                }
                trySend(EngineChunk.Completed(emptyList()))
            } catch (t: Throwable) {
                Log.e(TAG, "generation failed", t)
                trySend(EngineChunk.Failed(EngineReasons.GENERATION_FAILED))
            } finally {
                close()
            }
        }
        awaitClose { stop() }
    }

    private fun defaultOptions(): GenerationOptions = GenerationOptions(
        params = GenerationParams(
            maxTokens = defaultMaxTokens,
            temperature = defaultTemperature,
            topP = defaultTopP,
            topK = defaultTopK,
        ),
        systemPrompt = defaultSystemPrompt,
    )

    /**
     * The image marker must be inside the user content before the chat template is applied; libmtmd
     * then replaces it with the image chunk. The marker comes from native code, never a literal.
     */
    private fun mediaMessages(prompt: String, history: List<ChatTurn>): Array<String> {
        val messages = buildMessages(prompt, history)
        val marker = visionMarker.ifBlank { FALLBACK_MEDIA_MARKER }
        val last = messages.lastIndex
        messages[last] = marker + "\n" + messages[last]
        return messages
    }

    /** Positional float parameters, in the order LlamaNative.nativeGenerate documents. */
    private fun parameterFloats(params: GenerationParams): FloatArray = floatArrayOf(
        params.temperature,
        params.topP,
        params.repeatPenalty,
        params.presencePenalty,
        params.frequencyPenalty,
        params.minP,
        params.typicalP,
        params.mirostatTau,
        params.mirostatEta,
        params.xtcProbability,
        params.xtcThreshold,
        params.dryMultiplier,
        params.topNSigma,
    )

    /** Positional int parameters, in the order LlamaNative.nativeGenerate documents. */
    private fun parameterInts(params: GenerationParams): IntArray = intArrayOf(
        params.topK,
        params.repeatLastN,
        params.seed,
        when (params.samplingMode) {
            SamplingMode.DEFAULT -> 0
            SamplingMode.GREEDY -> 1
            SamplingMode.MIROSTAT_V2 -> 2
        },
    )

    /** Native expects flat role/content pairs: [user, text, assistant, text, ..., user, prompt]. */
    private fun buildMessages(prompt: String, history: List<ChatTurn>): Array<String> {
        val messages = ArrayList<String>((history.size + 1) * 2)
        history.forEach { turn ->
            messages += when (turn.role) {
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
            }
            messages += turn.text
        }
        messages += "user"
        messages += prompt
        return messages.toTypedArray()
    }

    private fun readFacts(activeHandle: Long): ModelFacts? = runCatching {
        val info = String(LlamaNative.nativeModelInfo(activeHandle), Charsets.UTF_8)
        val template = String(LlamaNative.nativeChatTemplate(activeHandle), Charsets.UTF_8)
            .takeIf { it.isNotBlank() }
        ModelFacts.parse(info, chatTemplate = template)
    }.onFailure { Log.w(TAG, "could not read model facts", it) }.getOrNull()

    /** Null template name = use the model's own template; a built-in name forces that template. */
    private fun templateName(choice: TemplateChoice): String? =
        (choice as? TemplateChoice.Builtin)?.name?.takeIf { it.isNotBlank() }

    companion object {
        const val ID = "llamacpp"

        /** Unit separator: stop sequences cannot contain it, so the flat JNI string is unambiguous. */
        private const val STOP_SEPARATOR = "\u001F"

        /** nativeLoadModel sentinel: the mmproj exists but could not be initialised. */
        private const val PROJECTOR_FAILED = -1L

        /** nativeLoadModel sentinel: the mmproj is valid but carries no vision capability. */
        private const val PROJECTOR_NO_VISION = -2L

        /** Only used if nativeVisionMarker() ever fails; matches libmtmd's default marker. */
        private const val FALLBACK_MEDIA_MARKER = "<__media__>"
        private const val TAG = "SirrakEngine"
    }
}

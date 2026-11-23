package app.sirrak.engine.llamacpp

/**
 * The JNI surface of the vendored llama.cpp build (see src/main/cpp). Kept in one object so there is
 * exactly one place where native code is declared; [LlamaCppEngine] is the only caller.
 *
 * Handles are model sessions: 0 means "nothing loaded". All calls are blocking and must run on a
 * background dispatcher, except [nativeRequestStop] which only flips an atomic flag.
 *
 * Parameter layouts are positional and documented here because they cross the JNI boundary:
 *  - floats: temperature, topP, repeatPenalty, presencePenalty, frequencyPenalty, minP, typicalP,
 *    mirostatTau, mirostatEta, xtcProbability, xtcThreshold, dryMultiplier, topNSigma
 *  - ints: topK, repeatLastN, seed, samplingMode (0 default, 1 greedy, 2 mirostat_v2)
 *  - stop sequences are joined with U+001F (unit separator); templateName null = model's own template.
 *
 * M9 vision: [nativeLoadModel] also takes an optional mmproj path. A load returns 0 when the text
 * model fails, -1 when the projector cannot be initialised, and -2 when it has no vision capability;
 * [nativeVisionSupported] then reports the actual capability of the loaded session.
 */
internal object LlamaNative {

    init {
        System.loadLibrary("sirrak_llm")
    }

    external fun nativeLoadModel(path: String, contextTokens: Int, mmprojPath: String?): Long

    /** True when the session's projector really supports image input (not just any mmproj). */
    external fun nativeVisionSupported(handle: Long): Boolean

    /** libmtmd's media marker (for example `<__media__>`); replaced by the image chunk at tokenize. */
    external fun nativeVisionMarker(): String

    /** Projector facts without loading a model: newline-separated `key=value` (`vision=0|1`). */
    external fun nativeProjectorInfo(path: String): ByteArray

    /** Newline-separated `key=value` GGUF facts; empty for an invalid handle. */
    external fun nativeModelInfo(handle: Long): ByteArray

    /** The model's own chat template text; empty when the file ships none. */
    external fun nativeChatTemplate(handle: Long): ByteArray

    /** llama.cpp's supported built-in template names (chatml, llama2, mistral-v1, ...). */
    external fun nativeBuiltinTemplates(): Array<String>

    /** Renders the prompt exactly as generation would; empty array on failure. */
    external fun nativeFormatPrompt(
        handle: Long,
        systemPrompt: String,
        messages: Array<String>,
        templateName: String?,
    ): ByteArray

    external fun nativeGenerate(
        handle: Long,
        systemPrompt: String,
        messages: Array<String>,
        maxTokens: Int,
        floats: FloatArray,
        ints: IntArray,
        stopJoined: String,
        templateName: String?,
        sink: TokenSink,
    )

    /**
     * M9: generation with one image. The caller has already inserted [nativeVisionMarker] into the
     * last user message; native code formats the chat, replaces the marker with the encoded image and
     * prefills both, then streams tokens exactly like [nativeGenerate].
     */
    external fun nativeGenerateVision(
        handle: Long,
        systemPrompt: String,
        messages: Array<String>,
        image: ByteArray,
        maxTokens: Int,
        floats: FloatArray,
        ints: IntArray,
        stopJoined: String,
        templateName: String?,
        sink: TokenSink,
    )

    external fun nativeRequestStop(handle: Long)

    external fun nativeUnloadModel(handle: Long)
}

/** Receives streamed pieces from native code (called on the generation thread). */
interface TokenSink {
    fun onToken(piece: String)
}

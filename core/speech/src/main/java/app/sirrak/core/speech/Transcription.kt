package app.sirrak.core.speech

import kotlinx.coroutines.flow.StateFlow

enum class TranscriptionStatus { UNLOADED, LOADING, READY, FAILED }

/**
 * What an engine can transcribe. Kept tiny and honest: a model that only handles one language reports
 * it, and the UI can disable a language row instead of promising a transcript it cannot produce.
 */
data class TranscriptionCapabilities(
    val onDevice: Boolean = true,
    val languages: List<DictationLanguage> = DictationLanguage.entries,
)

sealed interface TranscriptionResult {
    data class Success(
        val text: String,
        val language: DictationLanguage?,
        val audioDurationMs: Long,
        val latencyMs: Long,
    ) : TranscriptionResult

    data class Failure(val reason: String) : TranscriptionResult
}

/**
 * The one speech seam every recognizer implements. The controller depends on this interface only, so a
 * different engine (a future whisper.cpp or a fine-tuned Arabic model) is a composition line, and the
 * state machine, UI and tests never name the sherpa-onnx implementation.
 */
interface TranscriptionEngine {
    val id: String
    val displayName: String

    /** Observable lifecycle for the settings card; the controller also re-checks it at use time. */
    val status: StateFlow<TranscriptionStatus>

    /** The model id currently loaded, or null when nothing is loaded. */
    val loadedModelId: StateFlow<String?>

    val capabilities: TranscriptionCapabilities get() = TranscriptionCapabilities()

    suspend fun load(model: SpeechModel)

    /**
     * Loads with a language hint so an engine can build its recognizer once (Whisper bakes the
     * language into the decoder's initial tokens). Engines that ignore the hint inherit the simple
     * overload, so this addition never breaks an implementation or a fake.
     */
    suspend fun load(model: SpeechModel, language: DictationLanguage) = load(model)

    fun unload()

    /**
     * Transcribes one WAV (any PCM16 rate; the engine resamples to 16 kHz). Implementations return a
     * typed [TranscriptionResult.Failure] for anything they cannot do — never throw at the caller.
     */
    suspend fun transcribe(wav: ByteArray, language: DictationLanguage): TranscriptionResult
}

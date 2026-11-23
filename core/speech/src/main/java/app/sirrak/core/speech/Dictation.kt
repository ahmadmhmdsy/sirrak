package app.sirrak.core.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Dictation language. The id is the persisted value (data, not UI); the whisper code is the engine's
 * argument. AUTO maps to an empty code, which is Whisper's own language detection.
 */
enum class DictationLanguage(val id: String, val whisperCode: String) {
    AUTO("auto", ""),
    ARABIC("ar", "ar"),
    ENGLISH("en", "en");

    companion object {
        fun fromId(id: String?): DictationLanguage = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** How the mic behaves: tap to start/stop, or press and hold. Persisted by id like the language. */
enum class DictationMode(val id: String) {
    TOGGLE("toggle"),
    HOLD("hold");

    companion object {
        fun fromId(id: String?): DictationMode = entries.firstOrNull { it.id == id } ?: TOGGLE
    }
}

/** Persisted dictation preferences; the app owns the SharedPreferences implementation. */
interface DictationSettings {
    val language: StateFlow<DictationLanguage>
    val mode: StateFlow<DictationMode>
    fun setLanguage(language: DictationLanguage)
    fun setMode(mode: DictationMode)
}

/** One captured turn as WAV bytes plus its measured duration. */
data class RecordedAudio(val wav: ByteArray, val durationMs: Long)

/**
 * The recorder seam. The Android implementation wraps AudioRecord; tests inject a fake, so the
 * controller's behaviour is provable without a microphone.
 */
interface AudioRecorder {
    val isRecording: Boolean
    fun start(): Boolean
    fun stop(): RecordedAudio?
    fun cancel()
}

sealed interface DictationState {
    data object Idle : DictationState
    data class Recording(val startedAtMs: Long) : DictationState
    data object Transcribing : DictationState
    data class Failed(val reason: String) : DictationState
}

/**
 * The dictation state machine: start/stop/cancel, lazy model load, typed failures and one transcript
 * stream. It is deliberately free of Android and Compose types, so every branch below is a plain JVM
 * test; the UI only renders [state] and appends [transcript] to the composer.
 */
class DictationController(
    private val recorder: AudioRecorder,
    private val engine: TranscriptionEngine,
    private val models: SpeechModelStore,
    private val settings: DictationSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _transcript = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val transcript: SharedFlow<String> = _transcript.asSharedFlow()

    /** Starts recording. Returns false (with a typed state) when there is no model or no recorder. */
    fun start(): Boolean {
        if (_state.value is DictationState.Recording) return false
        if (models.selected.value == null) {
            fail(SpeechReasons.NO_MODEL)
            return false
        }
        if (!recorder.start()) {
            fail(SpeechReasons.RECORDER_UNAVAILABLE)
            return false
        }
        _state.value = DictationState.Recording(startedAtMs = clock())
        return true
    }

    /** UI entry point: stop and transcribe on the controller's own scope. */
    fun stop() {
        if (_state.value !is DictationState.Recording) return
        scope.launch { stopAndTranscribe() }
    }

    /** Suspending core, public so tests can await the whole path without a scheduler. */
    suspend fun stopAndTranscribe() {
        if (_state.value !is DictationState.Recording) return
        val recorded = recorder.stop()
        if (recorded == null) {
            fail(SpeechReasons.RECORDING_FAILED)
            return
        }
        if (recorded.durationMs < SpeechLimits.MIN_RECORDING_MS) {
            fail(SpeechReasons.RECORDING_TOO_SHORT)
            return
        }
        val model = models.selected.value
        if (model == null) {
            fail(SpeechReasons.NO_MODEL)
            return
        }
        _state.value = DictationState.Transcribing
        if (engine.loadedModelId.value != model.id) {
            val loaded = runCatching { engine.load(model, settings.language.value) }.isSuccess
            if (!loaded) {
                fail(SpeechReasons.ENGINE_LOAD_FAILED)
                return
            }
        }
        val result = runCatching { engine.transcribe(recorded.wav, settings.language.value) }
            .getOrElse { TranscriptionResult.Failure(SpeechReasons.TRANSCRIPTION_FAILED) }
        when (result) {
            is TranscriptionResult.Success -> {
                val text = result.text.trim()
                if (SpeechSilence.isSilence(text)) {
                    fail(SpeechReasons.NO_SPEECH)
                } else {
                    _transcript.tryEmit(text)
                    _state.value = DictationState.Idle
                }
            }
            is TranscriptionResult.Failure -> fail(result.reason)
        }
    }

    fun toggle() {
        if (_state.value is DictationState.Recording) stop() else start()
    }

    fun cancel() {
        if (_state.value is DictationState.Recording) recorder.cancel()
        _state.value = DictationState.Idle
    }

    fun onPermissionDenied() {
        fail(SpeechReasons.NO_PERMISSION)
    }

    fun clearFailure() {
        if (_state.value is DictationState.Failed) _state.value = DictationState.Idle
    }

    private fun fail(reason: String) {
        _state.value = DictationState.Failed(reason)
    }
}

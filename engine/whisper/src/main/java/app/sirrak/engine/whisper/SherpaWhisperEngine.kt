package app.sirrak.engine.whisper

import android.os.SystemClock
import android.util.Log
import app.sirrak.core.speech.AudioResampler
import app.sirrak.core.speech.DictationLanguage
import app.sirrak.core.speech.SpeechLimits
import app.sirrak.core.speech.SpeechModel
import app.sirrak.core.speech.SpeechReasons
import app.sirrak.core.speech.TranscriptionEngine
import app.sirrak.core.speech.TranscriptionResult
import app.sirrak.core.speech.TranscriptionStatus
import app.sirrak.core.speech.WavCodec
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The M10 speech engine: the same sherpa-onnx v2.0.1 release AAR and Whisper int8 models the H3b
 * spike measured (doc 10 §3.7), behind the pure-JVM [TranscriptionEngine] seam.
 *
 * One recognizer lives at a time. Whisper bakes the language into the decoder's initial tokens, so a
 * language change rebuilds the recognizer (a few seconds) instead of decoding with the wrong tokens;
 * AUTO passes an empty language and lets Whisper detect it, which is the native decoder's documented
 * branch. Nothing here is a feature flag: the engine reports what it can do and the controller decides.
 */
class SherpaWhisperEngine(
    private val threads: Int = DEFAULT_THREADS,
) : TranscriptionEngine {

    override val id: String = ID
    override val displayName: String = "sherpa-onnx Whisper"

    private val _status = MutableStateFlow(TranscriptionStatus.UNLOADED)
    override val status: StateFlow<TranscriptionStatus> = _status.asStateFlow()

    private val _loadedModelId = MutableStateFlow<String?>(null)
    override val loadedModelId: StateFlow<String?> = _loadedModelId.asStateFlow()

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerLanguage: DictationLanguage? = null
    private var loadedModel: SpeechModel? = null

    override suspend fun load(model: SpeechModel) = load(model, DictationLanguage.AUTO)

    override suspend fun load(model: SpeechModel, language: DictationLanguage) = mutex.withLock {
        withContext(Dispatchers.IO) {
            releaseRecognizerLocked()
            _status.value = TranscriptionStatus.LOADING
            val started = SystemClock.uptimeMillis()
            try {
                validate(model)
                recognizer = buildRecognizer(model, language)
                recognizerLanguage = language
                loadedModel = model
                _loadedModelId.value = model.id
                _status.value = TranscriptionStatus.READY
                Log.i(
                    TAG,
                    "SPEECH_MODEL_LOADED model=" + model.id + " language=" + language.id +
                        " threads=" + threads + " load_ms=" + (SystemClock.uptimeMillis() - started),
                )
                Unit
            } catch (t: Throwable) {
                releaseRecognizerLocked()
                _status.value = TranscriptionStatus.FAILED
                _loadedModelId.value = null
                Log.e(TAG, "SPEECH_MODEL_LOAD_FAILED model=" + model.id, t)
                throw t
            }
        }
    }

    override fun unload() {
        releaseRecognizerLocked()
        _status.value = TranscriptionStatus.UNLOADED
        _loadedModelId.value = null
        Log.i(TAG, "SPEECH_MODEL_RELEASED")
    }

    override suspend fun transcribe(wav: ByteArray, language: DictationLanguage): TranscriptionResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                transcribeLocked(wav, language)
            }
        }

    private fun transcribeLocked(wav: ByteArray, language: DictationLanguage): TranscriptionResult {
        val audio = WavCodec.read(wav)
            ?: return TranscriptionResult.Failure(SpeechReasons.TRANSCRIPTION_FAILED)
        val samples = AudioResampler.resample(audio.samples, audio.sampleRate)
        val durationMs = samples.size.toLong() * 1000L / SpeechLimits.TARGET_SAMPLE_RATE
        if (samples.isEmpty()) return TranscriptionResult.Failure(SpeechReasons.TRANSCRIPTION_FAILED)
        val model = loadedModel
            ?: return TranscriptionResult.Failure(SpeechReasons.NO_MODEL)
        // Silence is the one input whisper cannot handle gracefully: it always emits a label such as
        // "(static)". Reject it before decoding, which is both correct and saves a wasted decode.
        val level = rms(samples)
        if (level < SpeechLimits.SILENCE_RMS) {
            Log.i(TAG, "SPEECH_SILENCE audio_ms=" + durationMs + " rms=" + String.format(java.util.Locale.US, "%.1f", level))
            return TranscriptionResult.Failure(SpeechReasons.NO_SPEECH)
        }

        return try {
            val active = ensureRecognizer(model, language)
            val started = SystemClock.uptimeMillis()
            val stream = active.createStream()
            try {
                val floats = FloatArray(samples.size) { samples[it] / 32768.0f }
                stream.acceptWaveform(floats, SpeechLimits.TARGET_SAMPLE_RATE)
                active.decode(stream)
                val result = active.getResult(stream)
                val text = result.text?.trim().orEmpty()
                val latency = SystemClock.uptimeMillis() - started
                val detected = result.lang
                    ?.takeIf { it.isNotBlank() }
                    ?.let { code -> DictationLanguage.entries.firstOrNull { it.whisperCode == code } }
                Log.i(
                    TAG,
                    "SPEECH_TRANSCRIBE language=" + language.id + " detected=" + (detected?.id ?: "-") +
                        " audio_ms=" + durationMs + " latency_ms=" + latency +
                        " rtf=" + String.format(java.util.Locale.US, "%.3f", latency.toDouble() / durationMs.coerceAtLeast(1)) +
                        " chars=" + text.length,
                )
                TranscriptionResult.Success(
                    text = text,
                    language = detected ?: language,
                    audioDurationMs = durationMs,
                    latencyMs = latency,
                )
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SPEECH_TRANSCRIBE_FAILED", t)
            TranscriptionResult.Failure(SpeechReasons.TRANSCRIPTION_FAILED)
        }
    }

    /** Rebuilds the recognizer only when the requested language differs from the loaded one. */
    private fun ensureRecognizer(model: SpeechModel, language: DictationLanguage): OfflineRecognizer {
        val current = recognizer
        if (current != null && recognizerLanguage == language && loadedModel?.id == model.id) return current
        releaseRecognizerLocked()
        val started = SystemClock.uptimeMillis()
        val rebuilt = buildRecognizer(model, language)
        recognizer = rebuilt
        recognizerLanguage = language
        loadedModel = model
        _status.value = TranscriptionStatus.READY
        Log.i(
            TAG,
            "SPEECH_LANGUAGE_RELOAD language=" + language.id +
                " load_ms=" + (SystemClock.uptimeMillis() - started),
        )
        return rebuilt
    }

    private fun buildRecognizer(model: SpeechModel, language: DictationLanguage): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig(
            encoder = model.encoderPath,
            decoder = model.decoderPath,
            // Empty string is Whisper's auto-detect branch in the native decoder; a named language
            // must exist in the model's lang2id map or the native code exits, so only the two
            // shipped ids plus AUTO are ever passed.
            language = language.whisperCode,
            task = "transcribe",
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SpeechLimits.TARGET_SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = whisper,
                tokens = model.tokensPath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(config = config)
    }

    /** Root-mean-square of the 16-bit samples; a cheap, allocation-free energy gate for silence. */
    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        return kotlin.math.sqrt(sum / samples.size)
    }

    private fun validate(model: SpeechModel) {
        listOf(model.encoderPath, model.decoderPath, model.tokensPath).forEach { path ->
            val file = File(path)
            if (!file.isFile || file.length() == 0L) {
                throw IllegalStateException("speech model file missing or empty: " + path)
            }
        }
    }

    private fun releaseRecognizerLocked() {
        recognizer?.release()
        recognizer = null
        recognizerLanguage = null
        loadedModel = null
    }

    companion object {
        const val ID = "sherpa-whisper"

        /** Four threads is what the H3b spike measured for whisper-small on the emulator. */
        const val DEFAULT_THREADS = 4

        private const val TAG = "SirrakSpeech"
    }
}

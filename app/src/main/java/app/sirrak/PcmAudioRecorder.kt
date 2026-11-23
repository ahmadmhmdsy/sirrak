package app.sirrak

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import app.sirrak.core.speech.AudioRecorder
import app.sirrak.core.speech.RecordedAudio
import app.sirrak.core.speech.SpeechLimits
import app.sirrak.core.speech.WavCodec
import java.io.ByteArrayOutputStream

/**
 * The microphone behind the pure-JVM [AudioRecorder] seam. The capture loop runs on one daemon thread
 * and appends PCM16 into memory: a dictation turn is capped at two minutes (≈3.8 MB), so a temporary
 * file would add failure modes without buying anything. The stop path converts the buffer with
 * [WavCodec] so the engine always receives a real 16 kHz mono WAV.
 *
 * The [PcmSource] factory is the test seam: instrumentation feeds a WAV-backed fake source through the
 * exact same loop, so the recorder→controller→engine pipeline is proven without depending on the
 * emulator's (usually absent) audio input device.
 */
class PcmAudioRecorder(
    private val sourceFactory: () -> PcmSource?,
) : AudioRecorder {

    private val lock = Any()
    private var source: PcmSource? = null
    private var thread: Thread? = null

    @Volatile
    private var recording = false

    @Volatile
    private var captureFailed = false

    private var pcm = ByteArrayOutputStream()

    override val isRecording: Boolean
        get() = recording

    override fun start(): Boolean = synchronized(lock) {
        if (recording) return false
        val opened = sourceFactory() ?: return false
        pcm = ByteArrayOutputStream()
        captureFailed = false
        source = opened
        recording = true
        val localPcm = pcm
        val worker = Thread({
            val buffer = ShortArray(CHUNK_SAMPLES)
            var totalSamples = 0L
            while (recording) {
                val read = try {
                    opened.read(buffer)
                } catch (t: Throwable) {
                    Log.e(TAG, "SPEECH_RECORDER_READ_FAILED", t)
                    -1
                }
                if (read < 0) {
                    captureFailed = true
                    break
                }
                if (read == 0) continue
                val bytes = ByteArray(read * 2)
                for (index in 0 until read) {
                    val value = buffer[index].toInt()
                    bytes[index * 2] = value.toByte()
                    bytes[index * 2 + 1] = (value shr 8).toByte()
                }
                synchronized(lock) { localPcm.write(bytes) }
                totalSamples += read
                if (totalSamples * 1000L / SpeechLimits.TARGET_SAMPLE_RATE >= SpeechLimits.MAX_RECORDING_MS) {
                    recording = false
                }
            }
            // A read error or the duration cap ends capture on the worker thread; stop() still owns
            // the conversion and the release, so the UI sees one consistent isRecording transition.
            recording = false
        }, THREAD_NAME)
        worker.isDaemon = true
        thread = worker
        worker.start()
        Log.i(TAG, "SPEECH_RECORD_STARTED")
        true
    }

    override fun stop(): RecordedAudio? {
        val worker: Thread?
        val opened: PcmSource?
        synchronized(lock) {
            if (!recording && thread == null) return null
            recording = false
            worker = thread
            thread = null
            opened = source
            source = null
        }
        worker?.join(JOIN_TIMEOUT_MS)
        opened?.release()
        val captured: ByteArray
        synchronized(lock) {
            captured = pcm.toByteArray()
            pcm = ByteArrayOutputStream()
        }
        val frames = captured.size / 2
        if (frames == 0 || captureFailed) {
            Log.w(TAG, "SPEECH_RECORD_EMPTY frames=" + frames + " failed=" + captureFailed)
            return null
        }
        val samples = ShortArray(frames) { index ->
            ((captured[index * 2].toInt() and 0xFF) or (captured[index * 2 + 1].toInt() shl 8)).toShort()
        }
        val wav = WavCodec.writePcm16(samples, SpeechLimits.TARGET_SAMPLE_RATE)
        val durationMs = frames * 1000L / SpeechLimits.TARGET_SAMPLE_RATE
        Log.i(TAG, "SPEECH_RECORD_STOPPED frames=" + frames + " duration_ms=" + durationMs)
        return RecordedAudio(wav = wav, durationMs = durationMs)
    }

    override fun cancel() {
        val worker: Thread?
        val opened: PcmSource?
        synchronized(lock) {
            recording = false
            worker = thread
            thread = null
            opened = source
            source = null
            pcm = ByteArrayOutputStream()
        }
        worker?.join(JOIN_TIMEOUT_MS)
        opened?.release()
        Log.i(TAG, "SPEECH_RECORD_CANCELLED")
    }

    companion object {
        private const val TAG = "SirrakSpeech"
        private const val THREAD_NAME = "sirrak-dictation"
        private const val JOIN_TIMEOUT_MS = 2_000L
        private const val CHUNK_SAMPLES = 1_600 // 100 ms at 16 kHz
    }
}

/** One capture device, abstracted so the loop above is testable with a WAV-backed source. */
interface PcmSource {
    /** Fills [buffer] with mono PCM16 samples; returns the count, 0 to retry, or a negative error. */
    fun read(buffer: ShortArray): Int

    fun release()
}

/**
 * The Android implementation: VOICE_RECOGNITION source (the noise-suppressed path dictation wants),
 * 16 kHz mono PCM16, which is exactly what Whisper's feature extractor expects. If the permission was
 * revoked or the device has no usable input, the factory returns null and the UI reports
 * speech-recorder-unavailable instead of a crash.
 */
class AndroidPcmSource(context: Context) : PcmSource {

    private val appContext = context.applicationContext
    private val record: AudioRecord?

    init {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        record = if (!granted) {
            null
        } else {
            val minBytes = AudioRecord.getMinBufferSize(
                SpeechLimits.TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = if (minBytes > 0) maxOf(minBytes, MIN_BUFFER_BYTES) else MIN_BUFFER_BYTES
            val created = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SpeechLimits.TARGET_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            }.getOrNull()
            if (created == null || created.state != AudioRecord.STATE_INITIALIZED) {
                created?.release()
                null
            } else {
                created.startRecording()
                created
            }
        }
        if (record == null) Log.w(TAG, "SPEECH_RECORDER_UNAVAILABLE")
    }

    override fun read(buffer: ShortArray): Int =
        record?.read(buffer, 0, buffer.size) ?: -1

    override fun release() {
        record?.let { audioRecord ->
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }

    companion object {
        private const val TAG = "SirrakSpeech"
        private const val MIN_BUFFER_BYTES = 16_000
    }
}

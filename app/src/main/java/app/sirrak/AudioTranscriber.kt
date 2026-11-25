package app.sirrak

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import app.sirrak.core.speech.AudioResampler
import app.sirrak.core.speech.DictationLanguage
import app.sirrak.core.speech.SpeechModelStore
import app.sirrak.core.speech.SpeechReasons
import app.sirrak.core.speech.TranscriptionEngine
import app.sirrak.core.speech.TranscriptionResult
import app.sirrak.core.speech.WavCodec
import app.sirrak.media.MediaLimits
import app.sirrak.media.MediaPcm
import app.sirrak.media.MediaPhase
import app.sirrak.media.MediaReasons
import app.sirrak.media.MediaTranscriptionAvailability
import app.sirrak.media.MediaTranscriptionPort
import app.sirrak.media.MediaTranscriptionResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P5 audio: the on-device path from a picked audio file to a stored transcript.
 *
 * The shape of the pipeline is fixed by the founder decision (docs/31 §6.9, decision 4): an audio file
 * is decoded locally, transcribed by the *existing* Whisper engine, and the transcript is the durable
 * artefact. There is no cloud seam, no network call, and no second recogniser: this class only moves
 * bytes between MediaCodec, [WavCodec] and [TranscriptionEngine].
 *
 * Typed failures, never exceptions at the caller: every branch below reports a [MediaReasons] code so
 * the shelf can say what went wrong and offer a retry.
 */
class AndroidAudioTranscriber(
    private val engine: () -> TranscriptionEngine?,
    private val models: () -> SpeechModelStore?,
) : MediaTranscriptionPort {

    override fun availability(): MediaTranscriptionAvailability {
        if (engine() == null) return MediaTranscriptionAvailability.Missing(MediaReasons.NO_TRANSCRIBER)
        val selected = models()?.selected?.value
        if (selected == null) {
            return MediaTranscriptionAvailability.Missing(MediaReasons.NO_SPEECH_MODEL)
        }
        return MediaTranscriptionAvailability.Available
    }

    override suspend fun transcribe(
        file: File,
        onPhase: (MediaPhase) -> Unit,
    ): MediaTranscriptionResult {
        val engine = engine()
            ?: return MediaTranscriptionResult.Failure(MediaReasons.NO_TRANSCRIBER)
        val model = models()?.selected?.value
            ?: return MediaTranscriptionResult.Failure(MediaReasons.NO_SPEECH_MODEL)

        onPhase(MediaPhase.DECODING)
        val decoded = withContext(Dispatchers.IO) { AudioFileDecoder.decodeToWav(file) }
        val audio = when (decoded) {
            is AudioDecodeResult.Failed -> return MediaTranscriptionResult.Failure(decoded.reason)
            is AudioDecodeResult.Decoded -> decoded
        }
        Log.i(
            TAG,
            "MEDIA_AUDIO_DECODED bytes=" + audio.wav.size + " duration_ms=" + audio.durationMs +
                " name=" + file.name,
        )

        onPhase(MediaPhase.TRANSCRIBING)
        // The recogniser is built per language; AUTO lets Whisper detect it, exactly as dictation does.
        if (engine.loadedModelId.value != model.id) {
            val loaded = runCatching { engine.load(model, DictationLanguage.AUTO) }.isSuccess
            if (!loaded) {
                Log.e(TAG, "MEDIA_AUDIO_MODEL_LOAD_FAILED model=" + model.id)
                return MediaTranscriptionResult.Failure(MediaReasons.TRANSCRIPTION_FAILED)
            }
        }
        val result = runCatching { engine.transcribe(audio.wav, DictationLanguage.AUTO) }
            .getOrElse { TranscriptionResult.Failure(SpeechReasons.TRANSCRIPTION_FAILED) }
        return when (result) {
            is TranscriptionResult.Success -> {
                val text = result.text.trim()
                if (text.isEmpty()) {
                    MediaTranscriptionResult.Failure(MediaReasons.TRANSCRIPT_EMPTY)
                } else {
                    Log.i(TAG, "MEDIA_AUDIO_TRANSCRIBED chars=" + text.length)
                    MediaTranscriptionResult.Success(text = text, languageId = result.language?.id)
                }
            }
            is TranscriptionResult.Failure ->
                MediaTranscriptionResult.Failure(mapSpeechReason(result.reason))
        }
    }

    /** Speech codes are the engine's language; the shelf speaks the media codes the UI localises. */
    private fun mapSpeechReason(reason: String): String = when (reason) {
        SpeechReasons.NO_MODEL -> MediaReasons.NO_SPEECH_MODEL
        SpeechReasons.NO_SPEECH -> MediaReasons.NO_SPEECH
        else -> MediaReasons.TRANSCRIPTION_FAILED
    }

    private companion object {
        const val TAG = "SirrakMedia"
    }
}

/** One decoded audio file: a real 16 kHz mono PCM16 WAV, ready for the Whisper engine. */
sealed interface AudioDecodeResult {
    data class Decoded(val wav: ByteArray, val durationMs: Long) : AudioDecodeResult
    data class Failed(val reason: String) : AudioDecodeResult
}

/**
 * MediaExtractor + MediaCodec decode of any audio file the platform can read, finished with a WAV
 * header so [TranscriptionEngine.transcribe] receives exactly the shape it documents (PCM16 mono,
 * resampled to 16 kHz by [AudioResampler]). A file with no audio track, an unsupported codec, a
 * decoder that dies, or more than [MediaLimits.MAX_AUDIO_MS] of audio is a typed failure; the original
 * file is never modified.
 */
object AudioFileDecoder {

    private const val TIMEOUT_US = 10_000L
    private const val TAG = "SirrakMedia"

    fun decodeToWav(file: File): AudioDecodeResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    trackFormat = candidate
                    break
                }
            }
            val format = trackFormat
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "MEDIA_AUDIO_NO_TRACK name=" + file.name)
                return AudioDecodeResult.Failed(MediaReasons.DECODE_FAILED)
            }
            if (declaredDurationMs(format) > MediaLimits.MAX_AUDIO_MS) {
                return AudioDecodeResult.Failed(MediaReasons.AUDIO_TOO_LONG)
            }
            extractor.selectTrack(trackIndex)

            var sampleRate = intOption(format, MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = intOption(format, MediaFormat.KEY_CHANNEL_COUNT, 0)
            var floatSamples = false

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return AudioDecodeResult.Failed(MediaReasons.DECODE_FAILED)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val sink = ShortSink()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val input = decoder.getInputBuffer(inIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        val outputRate = intOption(output, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val outputChannels = intOption(output, MediaFormat.KEY_CHANNEL_COUNT, channels)
                        if (outputRate > 0) sampleRate = outputRate
                        if (outputChannels > 0) channels = outputChannels
                        floatSamples = pcmEncodingIsFloat(output)
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)
                            if (buffer != null && channels > 0) {
                                val chunk = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(chunk)
                                sink.append(
                                    MediaPcm.mono16FromInterleaved(chunk, channels, floatSamples),
                                )
                                if (MediaPcm.durationMs(
                                        sink.size.toLong(),
                                        sampleRate.coerceAtLeast(1),
                                    ) > MediaLimits.MAX_AUDIO_MS
                                ) {
                                    return AudioDecodeResult.Failed(MediaReasons.AUDIO_TOO_LONG)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (sampleRate <= 0 || sink.size == 0) {
                Log.w(TAG, "MEDIA_AUDIO_EMPTY rate=" + sampleRate + " samples=" + sink.size)
                return AudioDecodeResult.Failed(MediaReasons.DECODE_FAILED)
            }
            val mono = sink.toShortArray()
            val resampled = AudioResampler.resample(
                samples = mono,
                fromRate = sampleRate,
                toRate = MediaLimits.TARGET_SAMPLE_RATE,
            )
            if (resampled.isEmpty()) return AudioDecodeResult.Failed(MediaReasons.DECODE_FAILED)
            val wav = WavCodec.writePcm16(resampled, MediaLimits.TARGET_SAMPLE_RATE)
            val durationMs = MediaPcm.durationMs(resampled.size.toLong(), MediaLimits.TARGET_SAMPLE_RATE)
            return AudioDecodeResult.Decoded(wav = wav, durationMs = durationMs)
        } catch (error: Throwable) {
            Log.e(TAG, "MEDIA_AUDIO_DECODE_FAILED name=" + file.name, error)
            return AudioDecodeResult.Failed(MediaReasons.DECODE_FAILED)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun declaredDurationMs(format: MediaFormat): Long {
        if (!format.containsKey(MediaFormat.KEY_DURATION)) return 0L
        val microseconds = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        return if (microseconds <= 0L) 0L else microseconds / 1000L
    }

    private fun intOption(format: MediaFormat, key: String, fallback: Int): Int =
        if (!format.containsKey(key)) {
            fallback
        } else {
            runCatching { format.getInteger(key) }.getOrDefault(fallback)
        }

    /** Some OEM decoders emit float PCM; the flag comes from the output format, not the container. */
    private fun pcmEncodingIsFloat(format: MediaFormat): Boolean {
        if (!format.containsKey(MediaFormat.KEY_PCM_ENCODING)) return false
        val encoding = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrDefault(0)
        return encoding == AudioFormat.ENCODING_PCM_FLOAT
    }

    /**
     * A growable mono buffer. Audio is appended chunk by chunk; a list of chunks would double the peak
     * memory of a ten-minute recording, so the samples are copied into one doubling array instead.
     */
    private class ShortSink(initialCapacity: Int = 16_000) {
        private var data = ShortArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(values: ShortArray) {
            if (values.isEmpty()) return
            ensureCapacity(size + values.size)
            values.copyInto(data, size)
            size += values.size
        }

        fun toShortArray(): ShortArray = data.copyOf(size)

        private fun ensureCapacity(capacity: Int) {
            if (capacity <= data.size) return
            var next = data.size
            while (next < capacity) next *= 2
            data = data.copyOf(next)
        }
    }
}

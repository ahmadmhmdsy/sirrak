package app.sirrak.core.speech

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounds shared by the recorder, the store and the controller; one owner, no scattered magic numbers. */
object SpeechLimits {
    /**
     * A 16-bit RMS below this is quieter than -60 dBFS: no usable speech, so decoding it would only
     * invite a hallucination. Measured on the emulator's silent input, where whisper invented "(static)".
     */
    const val SILENCE_RMS = 32.0
    /** A tap shorter than this is not speech; it becomes an honest "too short" message, not noise. */
    const val MIN_RECORDING_MS = 400L
    /** One dictation turn is capped so a forgotten recording cannot grow without bound. */
    const val MAX_RECORDING_MS = 120_000L
    /** The largest file a Whisper import may contain (the f32 small decoder is 559 MB). */
    const val MAX_MODEL_FILE_BYTES = 600L * 1024L * 1024L
    const val TARGET_SAMPLE_RATE = 16_000
}

/** Decoded PCM: mono samples after the codec's downmix, plus the rate they were sampled at. */
data class WavAudio(val samples: ShortArray, val sampleRate: Int, val channels: Int)

/**
 * The WAV boundary for dictation. Recording writes PCM16; the engine reads it back, so the two sides
 * share one implementation and one set of header rules (little-endian RIFF, mono or stereo downmix,
 * 16-bit integer PCM). Anything else is rejected as null rather than silently mis-decoded.
 */
object WavCodec {
    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE = 16

    fun writePcm16(samples: ShortArray, sampleRate: Int): ByteArray {
        val rate = max(0, sampleRate)
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(HEADER_BYTES + dataBytes)
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        fun le32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(16)
        le16(PCM_FORMAT)
        le16(1)
        le32(rate)
        le32(rate * 2)
        le16(2)
        le16(BITS_PER_SAMPLE)
        out.write("data".toByteArray(Charsets.US_ASCII))
        le32(dataBytes)
        samples.forEach { le16(it.toInt()) }
        return out.toByteArray()
    }

    /** Parses a PCM16 mono/stereo WAV, downmixing stereo to mono. Returns null for anything else. */
    fun read(bytes: ByteArray): WavAudio? {
        if (bytes.size < 12) return null
        if (String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) != "WAVE") return null

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataStart = -1
        var dataBytes = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes.copyOfRange(offset, offset + 4), Charsets.US_ASCII)
            val size = le32(bytes, offset + 4)
            if (size < 0) return null
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    format = le16(bytes, body)
                    channels = le16(bytes, body + 2)
                    sampleRate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataStart = body
                    dataBytes = min(size, bytes.size - body)
                    break
                }
            }
            offset = body + size + (size % 2)
        }
        if (format != PCM_FORMAT || bits != BITS_PER_SAMPLE) return null
        if (channels !in 1..2 || sampleRate <= 0) return null
        if (dataStart < 0 || dataBytes < 2) return null

        val frames = dataBytes / (2 * channels)
        val samples = ShortArray(frames)
        for (frame in 0 until frames) {
            val base = dataStart + frame * 2 * channels
            samples[frame] = if (channels == 1) {
                le16(bytes, base).toShort()
            } else {
                val left = le16(bytes, base)
                val right = le16(bytes, base + 2)
                ((left + right) / 2).toShort()
            }
        }
        return WavAudio(samples = samples, sampleRate = sampleRate, channels = 1)
    }

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}

/** Linear resampling to the engine's 16 kHz; the spike proved linear is enough for Whisper here. */
object AudioResampler {
    fun resample(samples: ShortArray, fromRate: Int, toRate: Int = SpeechLimits.TARGET_SAMPLE_RATE): ShortArray {
        if (samples.isEmpty() || fromRate <= 0 || toRate <= 0 || fromRate == toRate) return samples.copyOf()
        val outSize = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            val position = i.toDouble() * fromRate / toRate
            val index = position.toInt().coerceAtMost(samples.size - 1)
            val next = (index + 1).coerceAtMost(samples.size - 1)
            val fraction = position - index
            val value = samples[index] + (samples[next] - samples[index]) * fraction
            out[i] = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}

/** Formatting helper shared by the settings rows and the device evidence lines. */
internal fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

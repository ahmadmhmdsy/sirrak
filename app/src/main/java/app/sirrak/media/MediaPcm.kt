package app.sirrak.media

import kotlin.math.roundToInt

/**
 * The pure arithmetic between a platform audio decoder and the Whisper engine.
 *
 * MediaCodec hands back interleaved PCM whose channel count and sample encoding depend on the file and
 * on the device's decoder (16-bit integer is normal, 32-bit float happens on some OEM decoders). This
 * object is deliberately Android-free so the conversions are unit-tested: the decoder itself needs a
 * device, but a wrong downmix would silently corrupt every transcript, so the math earns its tests.
 */
object MediaPcm {

    private const val BYTES_PER_SHORT = 2
    private const val BYTES_PER_FLOAT = 4
    private const val MAX_SHORT_F = 32767.0f

    /**
     * Converts one interleaved decoded buffer to mono 16-bit PCM by averaging all channels.
     *
     * Trailing bytes that do not complete a frame are ignored: a decoder may hand back a buffer whose
     * size is not frame-aligned, and refusing the whole file for it would be a lie about the audio.
     */
    fun mono16FromInterleaved(bytes: ByteArray, channels: Int, float: Boolean): ShortArray {
        if (bytes.isEmpty() || channels <= 0) return ShortArray(0)
        return if (float) monoFromFloat(bytes, channels) else monoFromInteger(bytes, channels)
    }

    private fun monoFromInteger(bytes: ByteArray, channels: Int): ShortArray {
        val frameBytes = channels * BYTES_PER_SHORT
        if (frameBytes <= 0) return ShortArray(0)
        val frames = bytes.size / frameBytes
        if (frames == 0) return ShortArray(0)
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            val base = frame * frameBytes
            for (channel in 0 until channels) {
                val at = base + channel * BYTES_PER_SHORT
                val lo = bytes[at].toInt() and 0xFF
                val hi = bytes[at + 1].toInt()
                sum += (lo or (hi shl 8)).toShort().toInt()
            }
            out[frame] = (sum / channels).toShort()
        }
        return out
    }

    private fun monoFromFloat(bytes: ByteArray, channels: Int): ShortArray {
        val frameBytes = channels * BYTES_PER_FLOAT
        if (frameBytes <= 0) return ShortArray(0)
        val frames = bytes.size / frameBytes
        if (frames == 0) return ShortArray(0)
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0.0f
            val base = frame * frameBytes
            for (channel in 0 until channels) {
                val at = base + channel * BYTES_PER_FLOAT
                val bits = (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24)
                sum += Float.fromBits(bits)
            }
            val averaged = sum / channels
            out[frame] = (averaged.coerceIn(-1f, 1f) * MAX_SHORT_F).roundToInt().toShort()
        }
        return out
    }

    /** Samples as a duration at [sampleRate]; used to enforce the audio cap while decoding. */
    fun durationMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else frames * 1000L / sampleRate
}

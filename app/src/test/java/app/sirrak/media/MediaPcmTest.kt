package app.sirrak.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The PCM math between MediaCodec and Whisper: a wrong downmix would silently corrupt transcripts. */
class MediaPcmTest {

    private fun shorts(vararg values: Short): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            bytes[index * 2] = (value.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun monoIntegerPcmPassesThrough() {
        val samples = MediaPcm.mono16FromInterleaved(shorts(100, -200, 32767), channels = 1, float = false)
        assertArrayEquals(shortArrayOf(100, -200, 32767), samples)
    }

    @Test
    fun stereoIsAveragedToMono() {
        val samples = MediaPcm.mono16FromInterleaved(
            shorts(100, 200, -300, -100),
            channels = 2,
            float = false,
        )
        assertArrayEquals(shortArrayOf(150, -200), samples)
    }

    @Test
    fun floatPcmIsScaledAndClamped() {
        val floats = floatArrayOf(0f, 1f, -1f, 2f)
        val bytes = ByteArray(floats.size * 4)
        floats.forEachIndexed { index, value ->
            val bits = java.lang.Float.floatToIntBits(value)
            for (byte in 0 until 4) {
                bytes[index * 4 + byte] = ((bits shr (byte * 8)) and 0xFF).toByte()
            }
        }
        val samples = MediaPcm.mono16FromInterleaved(bytes, channels = 1, float = true)
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 32767), samples)
    }

    @Test
    fun trailingBytesThatDoNotCompleteAFrameAreIgnored() {
        val bytes = shorts(10, 20) + byteArrayOf(1, 2, 3)
        val samples = MediaPcm.mono16FromInterleaved(bytes, channels = 2, float = false)
        assertEquals(1, samples.size)
        assertEquals(15.toShort(), samples[0])
    }

    @Test
    fun durationIsReportedInMilliseconds() {
        assertEquals(1000L, MediaPcm.durationMs(16_000L, 16_000))
        assertEquals(0L, MediaPcm.durationMs(16_000L, 0))
    }
}

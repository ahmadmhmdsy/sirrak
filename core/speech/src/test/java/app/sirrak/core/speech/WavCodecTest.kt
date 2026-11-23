package app.sirrak.core.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavCodecTest {

    @Test
    fun `writePcm16 then read round-trips samples and sample rate`() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234, -4321)
        val bytes = WavCodec.writePcm16(samples, 16_000)

        val audio = WavCodec.read(bytes)

        assertEquals(16_000, audio!!.sampleRate)
        assertEquals(1, audio.channels)
        assertArrayEquals(samples, audio.samples)
    }

    @Test
    fun `written header is a standard 44-byte RIFF PCM WAV`() {
        val bytes = WavCodec.writePcm16(ShortArray(10), 16_000)

        assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(bytes.copyOfRange(36, 40), Charsets.US_ASCII))
        assertEquals(44 + 20, bytes.size)
    }

    @Test
    fun `read rejects data that is not a RIFF WAVE file`() {
        assertNull(WavCodec.read(ByteArray(0)))
        assertNull(WavCodec.read("not a wav file at all".toByteArray()))
    }

    @Test
    fun `read rejects a non-16-bit encoding`() {
        val bytes = WavCodec.writePcm16(ShortArray(4), 16_000)
        // Flip the bits-per-sample field (offset 34) from 16 to 8.
        bytes[34] = 8
        bytes[35] = 0

        assertNull(WavCodec.read(bytes))
    }

    @Test
    fun `read rejects a non-PCM format tag`() {
        val bytes = WavCodec.writePcm16(ShortArray(4), 16_000)
        // Format tag 3 is IEEE float; the codec only accepts integer PCM.
        bytes[20] = 3

        assertNull(WavCodec.read(bytes))
    }

    @Test
    fun `read downmixes stereo PCM to mono by averaging the channels`() {
        val frames = 3
        val stereo = stereoWav(16_000, shortArrayOf(100, 300, -100, -300, 0, 0))

        val audio = WavCodec.read(stereo)!!

        assertEquals(1, audio.channels)
        assertEquals(frames, audio.samples.size)
        assertEquals(200, audio.samples[0].toInt())
        assertEquals(-200, audio.samples[1].toInt())
        assertEquals(0, audio.samples[2].toInt())
    }

    @Test
    fun `read accepts a WAV whose data chunk is preceded by an extra chunk`() {
        val pcm = WavCodec.writePcm16(shortArrayOf(7, -7), 16_000)
        val fmtEnd = 36
        val data = pcm.copyOfRange(fmtEnd, pcm.size)
        val extraChunk = "LIST".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0, 0) +
            "info".toByteArray(Charsets.US_ASCII)
        val body = pcm.copyOfRange(0, fmtEnd) + extraChunk + data
        val out = ByteArrayOutputStream()
        out.write(body)
        val bytes = out.toByteArray()
        // Fix the RIFF size to cover the inserted chunk.
        val riffSize = bytes.size - 8
        bytes[4] = (riffSize and 0xFF).toByte()
        bytes[5] = ((riffSize shr 8) and 0xFF).toByte()
        bytes[6] = ((riffSize shr 16) and 0xFF).toByte()
        bytes[7] = ((riffSize shr 24) and 0xFF).toByte()

        val audio = WavCodec.read(bytes)!!

        assertArrayEquals(shortArrayOf(7, -7), audio.samples)
    }

    @Test
    fun `write clamps the sample rate to a sane positive value`() {
        assertTrue(WavCodec.writePcm16(ShortArray(2), 0).isNotEmpty())
        val audio = WavCodec.read(WavCodec.writePcm16(ShortArray(2), 0))
        assertNull(audio)
    }

    private fun stereoWav(sampleRate: Int, interleaved: ShortArray): ByteArray {
        val dataBytes = interleaved.size * 2
        val out = ByteArrayOutputStream()
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(16); le16(1); le16(2)
        le32(sampleRate); le32(sampleRate * 4); le16(4); le16(16)
        out.write("data".toByteArray(Charsets.US_ASCII)); le32(dataBytes)
        interleaved.forEach { le16(it.toInt()) }
        return out.toByteArray()
    }
}

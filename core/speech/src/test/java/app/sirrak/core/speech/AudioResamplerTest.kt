package app.sirrak.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioResamplerTest {

    @Test
    fun `resampling 22050 to 16000 keeps the expected duration`() {
        val input = ShortArray(22_050) { 1000 }
        val output = AudioResampler.resample(input, 22_050)

        assertEquals(16_000, output.size)
        assertTrue(output.all { abs(it.toInt() - 1000) <= 1 })
    }

    @Test
    fun `resampling to the same rate is a copy`() {
        val input = shortArrayOf(1, 2, 3, 4)
        val output = AudioResampler.resample(input, 16_000, 16_000)

        assertEquals(input.toList(), output.toList())
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, AudioResampler.resample(ShortArray(0), 22_050).size)
    }

    @Test
    fun `invalid source rate falls back to a copy instead of dividing by zero`() {
        val input = shortArrayOf(5, 6, 7)
        assertEquals(input.toList(), AudioResampler.resample(input, 0).toList())
    }
}

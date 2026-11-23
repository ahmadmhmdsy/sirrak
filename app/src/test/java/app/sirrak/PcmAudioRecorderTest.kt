package app.sirrak

import app.sirrak.core.speech.SpeechLimits
import app.sirrak.core.speech.WavCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioRecorderTest {

    @Test
    fun recordsA16kMonoWavFromTheSource() {
        val source = FakePcmSource(sample = 1234, sleepMs = 2)
        val recorder = PcmAudioRecorder { source }

        assertTrue(recorder.start())
        waitFor { source.reads >= 3 }
        val audio = recorder.stop()

        assertTrue("no audio captured", audio != null)
        val wav = WavCodec.read(audio!!.wav)
        assertTrue("stop did not produce a readable WAV", wav != null)
        assertEquals(SpeechLimits.TARGET_SAMPLE_RATE, wav!!.sampleRate)
        assertTrue("empty capture", wav.samples.isNotEmpty())
        assertEquals(1234, wav.samples.first().toInt())
        assertEquals(wav.samples.size * 1000L / SpeechLimits.TARGET_SAMPLE_RATE, audio.durationMs)
        assertTrue("source was not released", source.released)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun startFailsWhenNoSourceCanBeOpened() {
        val recorder = PcmAudioRecorder { null }

        assertFalse(recorder.start())
        assertNull(recorder.stop())
    }

    @Test
    fun secondStartWhileRecordingIsRefused() {
        val source = FakePcmSource(sample = 7, sleepMs = 2)
        val recorder = PcmAudioRecorder { source }

        assertTrue(recorder.start())
        assertFalse("a second start must not open a second microphone", recorder.start())
        waitFor { source.reads >= 2 }
        assertTrue(recorder.stop() != null)
    }

    @Test
    fun cancelDiscardsTheRecording() {
        val source = FakePcmSource(sample = 9, sleepMs = 2)
        val recorder = PcmAudioRecorder { source }

        assertTrue(recorder.start())
        waitFor { source.reads >= 2 }
        recorder.cancel()

        assertNull("a cancelled recording must not be transcribable", recorder.stop())
        assertFalse(recorder.isRecording)
        assertTrue(source.released)
    }

    @Test
    fun captureStopsAtTheMaximumDuration() {
        val source = FakePcmSource(sample = 1, sleepMs = 0)
        val recorder = PcmAudioRecorder { source }

        assertTrue(recorder.start())
        waitFor(timeoutMs = 20_000) { !recorder.isRecording }
        val audio = recorder.stop()

        assertTrue("capped recording is missing", audio != null)
        assertTrue(
            "recording exceeded the cap: " + audio!!.durationMs,
            audio.durationMs >= SpeechLimits.MAX_RECORDING_MS,
        )
        assertTrue(
            "recording went far past the cap: " + audio.durationMs,
            audio.durationMs <= SpeechLimits.MAX_RECORDING_MS + 100,
        )
    }

    @Test
    fun aReadFailureMakesTheStopFailInsteadOfReturningGarbage() {
        val source = FakePcmSource(sample = 5, sleepMs = 0, failAfterReads = 0)
        val recorder = PcmAudioRecorder { source }

        assertTrue(recorder.start())
        waitFor { !recorder.isRecording }

        assertNull("a failed read must surface as recording-failed", recorder.stop())
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertTrue("condition not met within " + timeoutMs + " ms", condition())
    }

    /** A PCM device that yields the same sample forever, or fails on demand. */
    private class FakePcmSource(
        private val sample: Short,
        private val sleepMs: Long,
        private val failAfterReads: Int = Int.MAX_VALUE,
    ) : PcmSource {
        @Volatile
        var reads = 0

        @Volatile
        var released = false

        override fun read(buffer: ShortArray): Int {
            if (reads >= failAfterReads) return -1
            reads++
            buffer.fill(sample)
            if (sleepMs > 0) Thread.sleep(sleepMs)
            return buffer.size
        }

        override fun release() {
            released = true
        }
    }
}

package app.sirrak.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps whisper's sound labels out of the composer. The data lives in
 * [SpeechSilence] so the controller and any future surface share one list.
 */
class SpeechSilenceTest {

    @Test
    fun `bracketed and parenthesised labels count as silence`() {
        listOf(
            "(static)",
            "[static]",
            "(silence)",
            "[silence]",
            "(music)",
            "[music]",
            "(noise)",
            "[noise]",
            "(applause)",
            "[applause]",
            "(blank_audio)",
            "[blank]",
            "(inaudible)",
            "[background noise]",
        ).forEach { label ->
            assertTrue("expected silence for " + label, SpeechSilence.isSilence(label))
        }
    }

    @Test
    fun `labels are matched case-insensitively and after trimming`() {
        assertTrue(SpeechSilence.isSilence("  (Static)  "))
        assertTrue(SpeechSilence.isSilence("[MUSIC]"))
        assertTrue(SpeechSilence.isSilence("( background   noise )"))
    }

    @Test
    fun `a blank transcript is silence`() {
        assertTrue(SpeechSilence.isSilence(""))
        assertTrue(SpeechSilence.isSilence("   "))
    }

    @Test
    fun `real words that also name a sound are never filtered`() {
        // A user can legitimately dictate these; only the bracketed decoder labels are artefacts.
        assertFalse(SpeechSilence.isSilence("music"))
        assertFalse(SpeechSilence.isSilence("static"))
        assertFalse(SpeechSilence.isSilence("I love music"))
        assertFalse(SpeechSilence.isSilence("silence is golden"))
    }

    @Test
    fun `the same label with a different wrapping is still speech`() {
        // "(static) please stop" is a decoder label followed by real words; it must reach the user.
        assertFalse(SpeechSilence.isSilence("(static) please stop"))
        assertEquals(false, SpeechSilence.isSilence("[music] playing now"))
    }
}

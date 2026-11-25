package app.sirrak

import app.sirrak.core.models.HfCallKind
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metered-consent seam and the memory-only token: the two host behaviours that must never silently
 * skip a question or persist a credential. The dialog itself is composition; this drives the contract
 * the dialog answers through.
 */
class HfServicesTest {

    @Test
    fun tokenIsTrimmedAndBlankClearsIt() {
        val store = HfTokenStore()
        assertNull(store.token.value)
        store.set("  hf_abc  ")
        assertEquals("hf_abc", store.token.value)
        store.set("   ")
        assertNull(store.token.value)
    }

    @Test
    fun meteredQuestionSuspendsUntilTheAnswerAndClearsAfter() {
        runBlocking {
            val host = HfMeteredPromptHost()
            val answer = async { host.ask(HfCallKind.PROBE) }
            withTimeout(5_000) { host.pending.first { it != null } }
            assertEquals(HfCallKind.PROBE, host.pending.value)
            host.answer(true)
            assertTrue(withTimeout(5_000) { answer.await() })
            withTimeout(5_000) { host.pending.first { it == null } }
        }
    }

    @Test
    fun dismissedQuestionAnswersNo() {
        runBlocking {
            val host = HfMeteredPromptHost()
            val answer = async { host.ask(HfCallKind.DOWNLOAD) }
            withTimeout(5_000) { host.pending.first { it != null } }
            host.answer(false)
            assertFalse(withTimeout(5_000) { answer.await() })
            withTimeout(5_000) { host.pending.first { it == null } }
        }
    }

    @Test
    fun answerWithNothingPendingIsHarmless() {
        HfMeteredPromptHost().answer(true)
    }
}

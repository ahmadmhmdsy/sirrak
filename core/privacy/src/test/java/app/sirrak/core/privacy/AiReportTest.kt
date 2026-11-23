package app.sirrak.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReportTest {

    private class FakeStore(var saved: List<AiReport> = emptyList()) : AiReportStore {
        var saves = 0
        override fun reports(): List<AiReport> = saved
        override fun save(reports: List<AiReport>) {
            saved = reports
            saves++
        }
    }

    private fun queue(
        store: FakeStore = FakeStore(),
        capacity: Int = AiReportRules.MAX_REPORTS,
        clock: () -> Long = { 1_000L },
    ) = AiReportQueue(store, capacity = capacity, clock = clock)

    @Test
    fun excerptKeepsShortAnswers() {
        assertEquals("Hello there", AiReportRules.excerpt("  Hello there  "))
    }

    @Test
    fun excerptTruncatesLongAnswersAtTheLimit() {
        val answer = "ا".repeat(AiReportRules.MAX_EXCERPT_CHARS + 500)
        val excerpt = AiReportRules.excerpt(answer)
        assertEquals(AiReportRules.MAX_EXCERPT_CHARS, excerpt.length)
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun noteIsTrimmedAndCapped() {
        assertEquals("note", AiReportRules.note("  note  "))
        assertEquals(AiReportRules.MAX_NOTE_CHARS, AiReportRules.note("n".repeat(5_000)).length)
    }

    @Test
    fun submitStoresReportWithGeneratedIdTimestampAndFields() {
        var now = 7_777L
        val store = FakeStore()
        val queue = queue(store, clock = { now })
        val result = queue.submit(
            reason = AiReportReason.HARMFUL,
            answer = "some answer",
            note = "  it insulted  ",
            modelLabel = "Qwen2.5 0.5B",
        )
        val report = (result as AiReportSubmit.Accepted).report
        assertEquals(7_777L, report.createdAtMs)
        assertEquals(AiReportReason.HARMFUL, report.reason)
        assertEquals("some answer", report.answerExcerpt)
        assertEquals("it insulted", report.note)
        assertEquals("Qwen2.5 0.5B", report.modelLabel)
        assertTrue(report.id.isNotBlank())
        assertEquals(1, store.saves)
        assertEquals(listOf(report), queue.reports())
        now = 8_888L
        val second = queue.submit(AiReportReason.OTHER, "another answer")
        assertTrue(second is AiReportSubmit.Accepted)
        assertNotEquals(report.id, (second as AiReportSubmit.Accepted).report.id)
        assertEquals(2, queue.reports().size)
    }

    @Test
    fun submitRejectsABlankAnswerWithoutTouchingTheStore() {
        val store = FakeStore()
        val queue = queue(store)
        val result = queue.submit(AiReportReason.HARMFUL, "   ")
        assertEquals(AiReportSubmit.Rejected(AiReportSubmit.Rejection.EMPTY_ANSWER), result)
        assertEquals(0, store.saves)
        assertTrue(queue.reports().isEmpty())
    }

    @Test
    fun queueFullRejectsNewReports() {
        val store = FakeStore()
        val queue = queue(store, capacity = 2)
        assertTrue(queue.submit(AiReportReason.OTHER, "one") is AiReportSubmit.Accepted)
        assertTrue(queue.submit(AiReportReason.OTHER, "two") is AiReportSubmit.Accepted)
        val third = queue.submit(AiReportReason.OTHER, "three")
        assertEquals(AiReportSubmit.Rejected(AiReportSubmit.Rejection.QUEUE_FULL), third)
        assertEquals(2, queue.reports().size)
    }

    @Test
    fun removeAndClearPersist() {
        val store = FakeStore()
        val queue = queue(store)
        val first = (queue.submit(AiReportReason.HATE, "first") as AiReportSubmit.Accepted).report
        queue.submit(AiReportReason.SEXUAL, "second")
        assertTrue(queue.remove(first.id))
        assertEquals(1, queue.reports().size)
        assertFalse(queue.remove("missing"))
        queue.clear()
        assertTrue(queue.reports().isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun loadsExistingReportsFromStore() {
        val existing = listOf(AiReport("r1", 1L, AiReportReason.OTHER, "x"))
        val queue = queue(FakeStore(existing))
        assertEquals(existing, queue.reports())
    }

    @Test
    fun reasonIdsRoundTripAndUnknownIsNull() {
        AiReportReason.entries.forEach { reason ->
            assertEquals(reason, AiReportReason.fromId(reason.id))
        }
        assertEquals(null, AiReportReason.fromId("nope"))
    }

    @Test
    fun shareTextIsASelfContainedLocalReport() {
        val report = AiReport(
            id = "r-1",
            createdAtMs = 1_000L,
            reason = AiReportReason.VIOLENCE,
            answerExcerpt = "violent answer",
            note = "please review",
            modelLabel = "Qwen2.5 0.5B",
        )
        val text = AiReportRules.shareText(report, appLabel = "Sirrak")
        assertTrue(text.contains("Sirrak"))
        assertTrue(text.contains(AiReportReason.VIOLENCE.id))
        assertTrue(text.contains("Qwen2.5 0.5B"))
        assertTrue(text.contains("violent answer"))
        assertTrue(text.contains("please review"))
        assertTrue(text.contains("r-1"))
    }
}

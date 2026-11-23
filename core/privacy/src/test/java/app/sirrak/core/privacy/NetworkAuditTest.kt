package app.sirrak.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAuditTest {

    private class FakeStore(var saved: List<NetworkCall> = emptyList()) : NetworkAuditStore {
        var saves = 0
        override fun events(): List<NetworkCall> = saved
        override fun save(events: List<NetworkCall>) {
            saved = events
            saves++
        }
    }

    private fun audit(
        store: FakeStore = FakeStore(),
        capacity: Int = 50,
        clock: () -> Long = { 1_000L },
    ) = NetworkAudit(store, capacity = capacity, clock = clock)

    @Test
    fun recordsCallsNewestFirst() {
        var now = 1_000L
        val audit = audit(clock = { now })
        audit.record("model_download", "huggingface.co")
        now = 2_000L
        audit.record("model_download", "huggingface.co")
        val events = audit.events()
        assertEquals(2, events.size)
        assertEquals(2_000L, events[0].startedAtMs)
        assertEquals(1_000L, events[1].startedAtMs)
    }

    @Test
    fun trimsToCapacityKeepingNewest() {
        var now = 0L
        val audit = audit(capacity = 3, clock = { now++ })
        repeat(5) { audit.record("model_download", "huggingface.co") }
        val events = audit.events()
        assertEquals(3, events.size)
        assertEquals(listOf(4L, 3L, 2L), events.map { it.startedAtMs })
    }

    @Test
    fun persistsEveryRecord() {
        val store = FakeStore()
        val audit = audit(store)
        audit.record("model_download", "huggingface.co")
        audit.record("model_download", "huggingface.co")
        assertEquals(2, store.saves)
        assertEquals(2, store.saved.size)
    }

    @Test
    fun loadsExistingEventsFromStore() {
        val existing = listOf(NetworkCall("model_download", "huggingface.co", 5L))
        val audit = audit(FakeStore(existing))
        assertEquals(existing, audit.events())
    }

    @Test
    fun recordsBytesAndOutcome() {
        val audit = audit()
        val call = audit.record(
            endpointId = "model_download",
            host = "huggingface.co",
            bytes = 491_400_032L,
            outcome = NetworkOutcome.COMPLETED,
        )
        assertEquals(491_400_032L, call.bytes)
        assertEquals(NetworkOutcome.COMPLETED, call.outcome)
        assertEquals(NetworkOutcome.COMPLETED, audit.events().single().outcome)
    }

    @Test
    fun clearRemovesEventsAndPersistsEmpty() {
        val store = FakeStore(listOf(NetworkCall("model_download", "huggingface.co", 1L)))
        val audit = audit(store)
        audit.clear()
        assertTrue(audit.events().isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankEndpointIdIsRejected() {
        audit().record("  ", "huggingface.co")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankHostIsRejected() {
        audit().record("model_download", "")
    }
}

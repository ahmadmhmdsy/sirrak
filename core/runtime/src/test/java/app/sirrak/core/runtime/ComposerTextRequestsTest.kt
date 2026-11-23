package app.sirrak.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerTextRequestsTest {

    @Test
    fun appendDeliversTrimmedTextToTheComposer() = runBlocking {
        val channel = ComposerTextRequests()
        val received = CompletableDeferred<String>()
        val job = launch(Dispatchers.Unconfined) { channel.requests.collect { received.complete(it) } }

        channel.append("  مرحبا  ")

        assertEquals("مرحبا", withTimeout(1_000) { received.await() })
        job.cancel()
    }

    @Test
    fun blankFragmentsNeverReachTheComposer() = runBlocking {
        val channel = ComposerTextRequests()
        val received = CompletableDeferred<String>()
        val job = launch(Dispatchers.Unconfined) { channel.requests.collect { received.complete(it) } }

        channel.append("   ")
        channel.append("")

        assertNull(withTimeoutOrNull(200) { received.await() })
        job.cancel()
    }

    @Test
    fun fragmentsArriveInEmissionOrder() = runBlocking {
        val channel = ComposerTextRequests()
        val received = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) { channel.requests.collect { received += it } }

        channel.append("first")
        channel.append("second")
        withTimeout(1_000) {
            while (received.size < 2) kotlinx.coroutines.yield()
        }

        assertEquals(listOf("first", "second"), received)
        job.cancel()
    }
}

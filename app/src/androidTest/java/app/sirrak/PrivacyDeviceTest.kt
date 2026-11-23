package app.sirrak

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sirrak.core.models.DownloadReasons
import app.sirrak.core.privacy.AiReportQueue
import app.sirrak.core.privacy.AiReportReason
import app.sirrak.core.privacy.AiReportRules
import app.sirrak.core.privacy.AiReportSubmit
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.NetworkAuditStore
import app.sirrak.core.privacy.NetworkCall
import app.sirrak.core.privacy.NetworkOutcome
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PRV device evidence on the emulator: the production HTTP transport records real calls in the
 * on-device audit log, the offline-only switch hard-blocks the download policy, and both privacy
 * stores survive a fresh instance (process-death behaviour). Network stays on the loopback interface,
 * so the test itself makes no external request.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transportRecordsACompletedCallInTheOnDeviceAudit() = runBlocking {
        val store = MemoryAuditStore()
        val audit = NetworkAudit(store)
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val server = LoopbackServer(payload)
        try {
            val transport = HttpDownloadTransport(audit = audit, endpointId = "model_download")
            val response = transport.open(server.url, offset = 0L, authorization = null)
            assertNotNull("loopback response should open", response)
            assertEquals(payload.size.toLong(), response!!.contentLength)
            response.body.close()
            val call = audit.events().single()
            assertEquals("model_download", call.endpointId)
            assertEquals("the host is parsed from the URL, never hardcoded", "127.0.0.1", call.host)
            assertEquals(NetworkOutcome.COMPLETED, call.outcome)
            assertEquals(payload.size.toLong(), call.bytes)
            assertEquals("the audit is persisted immediately", 1, store.events().size)
        } finally {
            server.close()
        }
        Unit
    }

    @Test
    fun transportRecordsAFailedCallForANonSuccessfulResponse() = runBlocking {
        val audit = NetworkAudit(MemoryAuditStore())
        val server = LoopbackServer(payload = null, status = 404)
        try {
            val transport = HttpDownloadTransport(audit = audit, endpointId = "model_download")
            assertNull("a 404 must not produce a response", transport.open(server.url, offset = 0L, authorization = null))
            val call = audit.events().single()
            assertEquals(NetworkOutcome.FAILED, call.outcome)
            assertEquals(0L, call.bytes)
        } finally {
            server.close()
        }
        Unit
    }

    @Test
    fun offlineOnlyBlocksTheDownloadPolicyAndSurvivesAFreshStoreInstance() {
        val store = SharedPrefsOfflineModeStore(context)
        store.setOfflineOnly(false)
        assertNull("online mode must not report a block", AndroidNetworkPolicy(context, store).blockedReason())
        store.setOfflineOnly(true)
        val freshStore = SharedPrefsOfflineModeStore(context)
        assertTrue("the switch must persist", freshStore.isOfflineOnly())
        assertEquals(
            DownloadReasons.OFFLINE_MODE,
            AndroidNetworkPolicy(context, freshStore).blockedReason(),
        )
        store.setOfflineOnly(false)
    }

    @Test
    fun auditAndReportsRoundTripThroughSharedPreferences() {
        val auditStore = SharedPrefsNetworkAuditStore(context)
        auditStore.save(emptyList())
        NetworkAudit(auditStore).record(
            endpointId = "model_download",
            host = "huggingface.co",
            bytes = 42L,
            outcome = NetworkOutcome.COMPLETED,
        )
        val reloaded = NetworkAudit(SharedPrefsNetworkAuditStore(context)).events().single()
        assertEquals("huggingface.co", reloaded.host)
        assertEquals(NetworkOutcome.COMPLETED, reloaded.outcome)
        assertEquals(42L, reloaded.bytes)
        NetworkAudit(SharedPrefsNetworkAuditStore(context)).clear()

        val reportStore = SharedPrefsAiReportStore(context)
        reportStore.save(emptyList())
        val submitted = AiReportQueue(reportStore).submit(
            reason = AiReportReason.PRIVACY,
            answer = "2 plus 2 equals 4.",
            note = "device evidence",
            modelLabel = "qwen2.5 0.5b instruct",
        )
        val report = (submitted as AiReportSubmit.Accepted).report
        val reloadedReport = AiReportQueue(SharedPrefsAiReportStore(context)).reports().single()
        assertEquals(report.id, reloadedReport.id)
        assertEquals(AiReportReason.PRIVACY, reloadedReport.reason)
        assertEquals("2 plus 2 equals 4.", reloadedReport.answerExcerpt)
        assertEquals("device evidence", reloadedReport.note)
        assertTrue(
            "share text must be self-contained",
            AiReportRules.shareText(reloadedReport, "Sirrak").contains("Reason: privacy"),
        )
        AiReportQueue(SharedPrefsAiReportStore(context)).clear()
    }

    private class MemoryAuditStore : NetworkAuditStore {
        private var saved: List<NetworkCall> = emptyList()
        override fun events(): List<NetworkCall> = saved
        override fun save(events: List<NetworkCall>) {
            saved = events
        }
    }

    /** Minimal HTTP/1.1 responder on loopback: one request, one response, nothing leaves the device. */
    private class LoopbackServer(private val payload: ByteArray?, private val status: Int = 200) : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url: String = "http://127.0.0.1:" + socket.localPort + "/synthetic.gguf"
        @Volatile
        private var closed = false
        private val thread = Thread {
            while (!closed) {
                runCatching {
                    socket.accept().use { connection -> respond(connection) }
                }
            }
        }.apply { isDaemon = true; start() }

        private fun respond(connection: Socket) {
            val reader = connection.getInputStream().bufferedReader()
            reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val body = if (status in 200..299) payload ?: ByteArray(0) else ByteArray(0)
            val output = connection.getOutputStream()
            val header = "HTTP/1.1 " + status + " " + (if (status in 200..299) "OK" else "Not Found") + "\r\n" +
                "Content-Length: " + body.size + "\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body)
            output.flush()
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }
}

package app.sirrak.core.models

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelDownloaderTest {

    private lateinit var staging: File
    private val scopes = mutableListOf<CoroutineScope>()
    private val payload = ggufPayload(64 * 1024)

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("sirrak-downloads").toFile()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        staging.deleteRecursively()
    }

    @Test
    fun downloadsPayloadVerifiesChecksumAndInstallsGguf() = runBlocking {
        val transport = FakeTransport(payload)
        val downloader = downloader(transport)
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val installed = await(downloader, DownloadPhase.INSTALLED)
        val file = assertNotNull(installed.file).let { installed.file!! }
        assertArrayEquals(payload, file.readBytes())
        assertTrue(file.name.endsWith(".gguf"))
        assertFalse(File(staging, file.name + ".part").exists())
        assertEquals(100, installed.percent)
    }

    @Test
    fun resumesFromThePartFileWhenTheServerSupportsRanges() = runBlocking {
        val part = File(staging, "test-model.gguf.part")
        part.writeBytes(payload.copyOfRange(0, 20_000))
        val transport = FakeTransport(payload)
        val downloader = downloader(transport)
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val installed = await(downloader, DownloadPhase.INSTALLED)
        assertArrayEquals(payload, installed.file!!.readBytes())
        assertEquals(20_000L, transport.offsets.first())
    }

    @Test
    fun restartsCleanlyWhenTheServerIgnoresRange() = runBlocking {
        val part = File(staging, "test-model.gguf.part")
        part.writeBytes(payload.copyOfRange(0, 20_000))
        val transport = FakeTransport(payload, ignoreRange = true)
        val downloader = downloader(transport)
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val installed = await(downloader, DownloadPhase.INSTALLED)
        assertArrayEquals(payload, installed.file!!.readBytes())
        assertEquals(20_000L, transport.offsets.first())
        assertTrue(transport.offsets.size >= 1)
    }

    @Test
    fun rejectsAPayloadWhoseChecksumDoesNotMatch() = runBlocking {
        val downloader = downloader(FakeTransport(payload))
        downloader.start(entry(size = payload.size.toLong(), sha = "a".repeat(64)))
        val failed = await(downloader, DownloadPhase.FAILED)
        assertEquals(DownloadReasons.CHECKSUM, failed.reason)
        assertNull(failed.file)
        assertFalse(File(staging, "test-model.gguf.part").exists())
    }

    @Test
    fun rejectsAPayloadThatIsNotAGguf() = runBlocking {
        val junk = ByteArray(1024) { (it % 97).toByte() }
        val downloader = downloader(FakeTransport(junk))
        downloader.start(entry(size = junk.size.toLong(), sha = shaOf(junk)))
        val failed = await(downloader, DownloadPhase.FAILED)
        assertEquals(DownloadReasons.INVALID_MODEL, failed.reason)
    }

    @Test
    fun blocksOnAMeteredConnectionBeforeOpeningTheTransport() = runBlocking {
        val transport = FakeTransport(payload)
        val downloader = downloader(transport, policy = FakeNetworkPolicy(isUnmetered = false))
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val failed = await(downloader, DownloadPhase.FAILED)
        assertEquals(DownloadReasons.NOT_UNMETERED, failed.reason)
        assertTrue(transport.offsets.isEmpty())
    }

    @Test
    fun allowsMeteredDownloadsWhenWifiOnlyIsDisabled() = runBlocking {
        val downloader = downloader(
            FakeTransport(payload),
            policy = FakeNetworkPolicy(isUnmetered = false),
            wifiOnly = false,
        )
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        await(downloader, DownloadPhase.INSTALLED)
        Unit
    }

    @Test
    fun blocksBeforeTheTransportWhenTheUserChoseOfflineOnly() = runBlocking {
        val transport = FakeTransport(payload)
        val downloader = downloader(
            transport,
            policy = FakeNetworkPolicy(
                isUnmetered = true,
                blockedReason = DownloadReasons.OFFLINE_MODE,
            ),
        )
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val failed = await(downloader, DownloadPhase.FAILED)
        assertEquals(DownloadReasons.OFFLINE_MODE, failed.reason)
        assertTrue(transport.offsets.isEmpty())
    }

    @Test
    fun blocksWhenFreeStorageIsBelowTheStoragePreCheck() = runBlocking {
        val transport = FakeTransport(payload)
        val downloader = downloader(transport, storage = FakeStorage(freeBytes = 1024L))
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val failed = await(downloader, DownloadPhase.FAILED)
        assertEquals(DownloadReasons.STORAGE_LOW, failed.reason)
        assertTrue(transport.offsets.isEmpty())
    }

    @Test
    fun pausesAndResumesFromTheBytesAlreadyOnDisk() = runBlocking {
        val transport = SlowTransport(payload, chunk = 2048, delayMs = 15)
        val downloader = downloader(transport)
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        withTimeout(15_000) {
            downloader.progress.filterNotNull().first { it.phase == DownloadPhase.DOWNLOADING && it.bytesDownloaded > 0 }
        }
        downloader.pause()
        val paused = await(downloader, DownloadPhase.PAUSED)
        assertTrue(paused.bytesDownloaded < payload.size)
        downloader.resume()
        val terminal = withTimeout(15_000) {
            downloader.progress.filterNotNull().first {
                it.phase == DownloadPhase.INSTALLED || it.phase == DownloadPhase.FAILED
            }
        }
        assertEquals("terminal phase=${terminal.phase} reason=${terminal.reason}", DownloadPhase.INSTALLED, terminal.phase)
        val installed = terminal
        assertArrayEquals(payload, installed.file!!.readBytes())
        assertTrue(transport.offsets.size >= 2)
        assertTrue(transport.offsets[1] > 0L)
    }

    @Test
    fun passesTheAccessTokenToTheTransportForGatedRepositories() = runBlocking {
        val transport = FakeTransport(payload)
        val downloader = downloader(transport)
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload), gated = true), authorization = "hf_test_token")
        await(downloader, DownloadPhase.INSTALLED)
        assertEquals(listOf("hf_test_token"), transport.authorizations)
    }

    @Test
    fun downloadsAVisionProjectorIntoItsOwnFile() = runBlocking {
        val transport = FakeTransport(payload)
        val downloader = downloader(transport)
        val entry = entry(size = payload.size.toLong(), sha = shaOf(payload))
        val projector = CatalogueProjector(
            repo = "org/vision-GGUF",
            fileName = "mmproj-vision.gguf",
            sizeBytes = payload.size.toLong(),
            sha256 = shaOf(payload),
        )
        downloader.startProjector(entry, projector)
        val installed = await(downloader, DownloadPhase.INSTALLED)
        assertArrayEquals(payload, installed.file!!.readBytes())
        assertTrue(installed.file!!.name.endsWith(".mmproj.gguf"))
        assertEquals("https://huggingface.co/org/vision-GGUF/resolve/main/mmproj-vision.gguf", transport.urls.last())
    }

    @Test
    fun retriesTransientNetworkFailures() = runBlocking {
        val transport = FlakyTransport(payload, failures = 2)
        val downloader = downloader(transport)
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val installed = await(downloader, DownloadPhase.INSTALLED)
        assertArrayEquals(payload, installed.file!!.readBytes())
        assertEquals(3, transport.attempts)
    }

    // ---------------------------------------------- background keep-alive seam (P3.4)
    // The Android host turns these transitions into a foreground service; the pure downloader only
    // promises that "active" starts when a run is enqueued and stops on every terminal state.

    @Test
    fun reportsActiveWhenARunStartsAndIdleOnceItInstalls() = runBlocking {
        val phases = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val downloader = downloader(FakeTransport(payload), onActiveChanged = { phases += it })
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        await(downloader, DownloadPhase.INSTALLED)
        assertTrue("expected an active=true report, got $phases", phases.contains(true))
        assertEquals("expected the first report to be active, got $phases", true, phases.first())
        assertEquals("the last report must be idle, got $phases", false, phases.last())
    }

    @Test
    fun reportsIdleWhenPausedAndActiveAgainOnResume() = runBlocking {
        val phases = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val transport = SlowTransport(payload, chunk = 2048, delayMs = 15)
        val downloader = downloader(transport, onActiveChanged = { phases += it })
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        withTimeout(15_000) {
            downloader.progress.filterNotNull().first { it.phase == DownloadPhase.DOWNLOADING && it.bytesDownloaded > 0 }
        }
        downloader.pause()
        await(downloader, DownloadPhase.PAUSED)
        val afterPause = phases.toList()
        assertTrue("pause must report idle, got $afterPause", afterPause.last() == false)
        downloader.resume()
        await(downloader, DownloadPhase.INSTALLED)
        assertTrue("resume must report active again, got $phases", phases.count { it } >= 2)
        assertEquals("the last report must be idle, got $phases", false, phases.last())
    }

    @Test
    fun reportsIdleWhenTheUserCancels() = runBlocking {
        val phases = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val transport = SlowTransport(payload, chunk = 2048, delayMs = 15)
        val downloader = downloader(transport, onActiveChanged = { phases += it })
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        withTimeout(15_000) {
            downloader.progress.filterNotNull().first { it.phase == DownloadPhase.DOWNLOADING }
        }
        downloader.cancel()
        withTimeout(5_000) { while (phases.isEmpty() || phases.last()) kotlinx.coroutines.delay(20) }
        assertNull(downloader.progress.value)
        assertTrue("cancel must report idle, got $phases", phases.last() == false)
    }

    @Test
    fun reportsIdleWhenABlockedRunNeverOpensTheTransport() = runBlocking {
        val phases = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val transport = FakeTransport(payload)
        val downloader = downloader(
            transport,
            policy = FakeNetworkPolicy(isUnmetered = true, blockedReason = DownloadReasons.OFFLINE_MODE),
            onActiveChanged = { phases += it },
        )
        downloader.start(entry(size = payload.size.toLong(), sha = shaOf(payload)))
        val failed = await(downloader, DownloadPhase.FAILED)
        assertEquals(DownloadReasons.OFFLINE_MODE, failed.reason)
        assertTrue("expected an active=true report, got $phases", phases.contains(true))
        assertEquals("the last report must be idle, got $phases", false, phases.last())
    }

    // ---------------------------------------------------------------- helpers

    private fun downloader(
        transport: DownloadTransport,
        policy: NetworkPolicy = FakeNetworkPolicy(isUnmetered = true),
        storage: StorageProbe = FakeStorage(freeBytes = 1024L * 1024 * 1024),
        wifiOnly: Boolean = true,
        onActiveChanged: (Boolean) -> Unit = {},
    ): ModelDownloader {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return ModelDownloader(
            transport = transport,
            networkPolicy = policy,
            storage = storage,
            scope = scope,
            stagingDir = staging,
            wifiOnly = { wifiOnly },
            onActiveChanged = onActiveChanged,
        )
    }

    private suspend fun await(
        downloader: ModelDownloader,
        phase: DownloadPhase,
        timeoutMs: Long = 15_000,
    ): DownloadProgress = withTimeout(timeoutMs) {
        downloader.progress.filterNotNull().first { it.phase == phase }
    }

    private fun entry(size: Long, sha: String?, gated: Boolean = false) = CatalogueEntry(
        id = "test-model",
        displayName = "Test Model",
        repo = "org/test-GGUF",
        fileName = "test-model.gguf",
        sizeBytes = size,
        sha256 = sha,
        ramMb = 512,
        minRamMb = 2048,
        gated = gated,
        licence = "Apache-2.0",
    )

    private fun shaOf(bytes: ByteArray): String {
        val file = File(staging, "payload-for-sha.bin")
        file.writeBytes(bytes)
        return ModelDownloader.sha256(file)
    }

    private fun ggufPayload(size: Int): ByteArray {
        val bytes = ByteArray(size) { index -> (index % 251).toByte() }
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 3
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 0
        return bytes
    }

    private class FakeNetworkPolicy(
        private val isUnmetered: Boolean,
        private val blockedReason: String? = null,
    ) : NetworkPolicy {
        override fun isUnmetered(): Boolean = isUnmetered
        override fun blockedReason(): String? = blockedReason
    }

    private class FakeStorage(private val freeBytes: Long) : StorageProbe {
        override fun freeBytes(): Long = freeBytes
    }

    private open class FakeTransport(
        private val payload: ByteArray,
        private val ignoreRange: Boolean = false,
    ) : DownloadTransport {
        val offsets = mutableListOf<Long>()
        val authorizations = mutableListOf<String?>()
        val urls = mutableListOf<String>()

        override suspend fun open(url: String, offset: Long, authorization: String?): TransportResponse? {
            urls += url
            offsets += offset
            authorizations += authorization
            val start = if (ignoreRange) 0 else offset.toInt().coerceIn(0, payload.size)
            val slice = payload.copyOfRange(start, payload.size)
            return TransportResponse(
                body = streamOf(slice),
                contentLength = slice.size.toLong(),
                supportsRange = !ignoreRange,
            )
        }
    }

    private class FlakyTransport(private val payload: ByteArray, private var failures: Int) : DownloadTransport {
        var attempts = 0
            private set

        override suspend fun open(url: String, offset: Long, authorization: String?): TransportResponse? {
            attempts++
            if (failures > 0) {
                failures--
                throw IOException("transient network failure")
            }
            val start = offset.toInt().coerceIn(0, payload.size)
            val slice = payload.copyOfRange(start, payload.size)
            return TransportResponse(streamOf(slice), slice.size.toLong(), true)
        }
    }

    private class SlowTransport(
        private val payload: ByteArray,
        private val chunk: Int,
        private val delayMs: Long,
    ) : DownloadTransport {
        val offsets = mutableListOf<Long>()

        override suspend fun open(url: String, offset: Long, authorization: String?): TransportResponse? {
            offsets += offset
            val start = offset.toInt().coerceIn(0, payload.size)
            val slice = payload.copyOfRange(start, payload.size)
            var index = 0
            val body = object : TransportStream {
                override fun read(buffer: ByteArray): Int {
                    if (index >= slice.size) return -1
                    val count = minOf(buffer.size, chunk, slice.size - index)
                    System.arraycopy(slice, index, buffer, 0, count)
                    index += count
                    Thread.sleep(delayMs)
                    return count
                }

                override fun close() = Unit
            }
            return TransportResponse(body, slice.size.toLong(), true)
        }
    }

    private companion object {
        fun streamOf(bytes: ByteArray): TransportStream = object : TransportStream {
            private var index = 0

            override fun read(buffer: ByteArray): Int {
                if (index >= bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - index)
                System.arraycopy(bytes, index, buffer, 0, count)
                index += count
                return count
            }

            override fun close() = Unit
        }
    }
}

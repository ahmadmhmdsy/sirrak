package app.sirrak

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.CatalogueJson
import app.sirrak.core.models.DeviceProfile
import app.sirrak.core.models.DownloadPhase
import app.sirrak.core.models.DownloadProgress
import app.sirrak.core.models.DownloadReasons
import app.sirrak.core.models.DownloadTransport
import app.sirrak.core.models.LicenceConsentStore
import app.sirrak.core.models.ModelCatalogue
import app.sirrak.core.models.ModelDownloader
import app.sirrak.core.models.ModelRecommender
import app.sirrak.core.models.NetworkPolicy
import app.sirrak.core.models.OnboardingStore
import app.sirrak.core.models.StorageProbe
import app.sirrak.core.runtime.GgufFile
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M11 device evidence: the real catalogue asset, the real HTTP transport, the resumable downloader and
 * the model store, exercised on the emulator. Network calls never leave the device: a loopback
 * HTTP/1.1 server in the test serves synthetic payloads (and the real Qwen2.5-0.5B fixture staged from
 * /sdcard/Download) so the download path is the production one while the run stays offline and
 * deterministic.
 *
 * Honest limits: the real model reaches the test through a local mirror, not huggingface.co (the build
 * is offline by policy; doc 17 F7 hosts the public resolver). The checksum, range/resume, GGUF and
 * storage gates are the production code paths.
 */
@RunWith(AndroidJUnit4::class)
class ModelManagerDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scopes = mutableListOf<CoroutineScope>()
    private val servers = mutableListOf<LoopbackServer>()

    @Test
    fun catalogueAssetIsValidAndRecommendsAModel() {
        val catalogue = AssetModelCatalogue(context.assets, "catalog/models_catalogue.json").catalogue()
        assertEquals(emptyList<String>(), CatalogueJson.validationErrors(catalogue))
        assertTrue("catalogue should ship at least four models", catalogue.models.size >= 4)
        val qwen = catalogue.models.first { it.id == "qwen2.5-0.5b-instruct-q4_k_m" }
        assertEquals(491_400_032L, qwen.sizeBytes)
        assertEquals("74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", qwen.sha256)
        assertTrue(qwen.downloadUrl.startsWith("https://huggingface.co/"))
        val profile = AndroidDeviceProfiler(context).profile()
        assertTrue("emulator RAM should be reported", profile.totalRamMb > 0)
        assertTrue("emulator storage should be reported", profile.availableStorageBytes > 0)
        val recommendation = ModelRecommender.recommend(catalogue, profile)
        assertNotNull("a model should be recommended on the emulator", recommendation.entry)
        Log.i(TAG, "CATALOGUE models=" + catalogue.models.size + " recommended=" + recommendation.entry?.id + " ram=" + profile.totalRamMb + " free=" + profile.availableStorageBytes)
    }

    @Test
    fun downloaderDownloadsVerifiesAndInstallsOverLoopback() = runBlocking {
        val payload = ggufPayload(512 * 1024)
        val server = serve("model.gguf", payload)
        val downloader = downloader()
        try {
            downloader.start(entry(payload.size.toLong(), shaOf(payload)), url = server.url)
            val installed = await(downloader, DownloadPhase.INSTALLED)
            assertArrayEquals(payload, installed.file!!.readBytes())
            assertTrue(installed.file!!.name.endsWith(".gguf"))
            assertTrue(server.requests.any { it.startsWith("GET ") })
            Log.i(TAG, "DOWNLOAD ok bytes=" + installed.file!!.length() + " requests=" + server.requests.size)
        } finally {
            downloader.cancel()
        }
        Unit
    }

    @Test
    fun downloaderPausesAndResumesWithARangeRequest() = runBlocking {
        val payload = ggufPayload(1024 * 1024)
        val server = serve("model.gguf", payload, chunkDelayMs = 6L)
        val downloader = downloader()
        try {
            downloader.start(entry(payload.size.toLong(), shaOf(payload)), url = server.url)
            withTimeout(20_000) {
                downloader.progress.filterNotNull().first { it.phase == DownloadPhase.DOWNLOADING && it.bytesDownloaded > 0 }
            }
            downloader.pause()
            await(downloader, DownloadPhase.PAUSED)
            downloader.resume()
            val installed = await(downloader, DownloadPhase.INSTALLED)
            assertArrayEquals(payload, installed.file!!.readBytes())
            val ranges = server.requests.filter { it.startsWith("Range:") }
            assertTrue("resume must send a Range header, saw " + server.requests, ranges.isNotEmpty())
            assertTrue("resume must start past zero, saw " + ranges, ranges.any { range -> range.substringAfter("bytes=").substringBefore("-").toLongOrNull()?.let { it > 0 } == true })
            Log.i(TAG, "RESUME requests=" + server.requests.size + " ranges=" + ranges)
        } finally {
            downloader.cancel()
        }
        Unit
    }

    @Test
    fun downloaderBlocksMeteredConnectionsAndLowStorage() = runBlocking {
        val metered = downloader(policy = FakeNetworkPolicy(false), wifiOnly = true)
        try {
            metered.start(entry(1024L, null), url = "http://127.0.0.1:1/never.gguf")
            val failed = await(metered, DownloadPhase.FAILED)
            assertEquals(DownloadReasons.NOT_UNMETERED, failed.reason)
        } finally {
            metered.cancel()
        }
        val tight = downloader(storage = FakeStorage(1024L))
        try {
            tight.start(entry(1024L * 1024L, null), url = "http://127.0.0.1:1/never.gguf")
            val failed = await(tight, DownloadPhase.FAILED)
            assertEquals(DownloadReasons.STORAGE_LOW, failed.reason)
        } finally {
            tight.cancel()
        }
    }

    @Test
    fun downloaderRejectsABadChecksumAndANonGgufPayload() = runBlocking {
        val payload = ggufPayload(256 * 1024)
        val badChecksumServer = serve("bad.gguf", payload)
        val badChecksum = downloader()
        try {
            badChecksum.start(entry(payload.size.toLong(), "a".repeat(64)), url = badChecksumServer.url)
            assertEquals(DownloadReasons.CHECKSUM, await(badChecksum, DownloadPhase.FAILED).reason)
        } finally {
            badChecksum.cancel()
        }
        val junk = ByteArray(64 * 1024) { (it % 89).toByte() }
        val junkServer = serve("junk.gguf", junk)
        val junkDownloader = downloader()
        try {
            junkDownloader.start(entry(junk.size.toLong(), shaOf(junk)), url = junkServer.url)
            assertEquals(DownloadReasons.INVALID_MODEL, await(junkDownloader, DownloadPhase.FAILED).reason)
        } finally {
            junkDownloader.cancel()
        }
    }

    @Test
    fun licenceConsentAndOnboardingStoresPersist() {
        val consent: LicenceConsentStore = SharedPrefsLicenceConsentStore(context)
        consent.clear("llama-3.2-1b-instruct-q4_k_m")
        assertFalse(consent.isAccepted("llama-3.2-1b-instruct-q4_k_m"))
        consent.accept("llama-3.2-1b-instruct-q4_k_m", "Llama 3.2")
        assertTrue(consent.isAccepted("llama-3.2-1b-instruct-q4_k_m"))
        assertEquals("Llama 3.2", consent.accepted()["llama-3.2-1b-instruct-q4_k_m"]?.licence)

        val onboarding: OnboardingStore = SharedPrefsOnboardingStore(context)
        onboarding.reset()
        assertFalse(onboarding.state.value.completed)
        onboarding.complete(skipped = true)
        assertTrue(onboarding.state.value.completed)
        assertTrue(onboarding.state.value.skipped)
        onboarding.reset()
        assertFalse(onboarding.state.value.completed)
    }

    @Test
    fun importsADownloadedModelAndSelectsIt() = runBlocking {
        val payload = ggufPayload(256 * 1024)
        val server = serve("synthetic.gguf", payload)
        val downloader = downloader()
        val store = FileModelStore(context)
        try {
            downloader.start(entry(payload.size.toLong(), shaOf(payload)), url = server.url)
            val installed = await(downloader, DownloadPhase.INSTALLED)
            val imported = store.importFile("m11-synthetic.gguf", installed.file!!, shaOf(payload)).getOrThrow()
            assertTrue(store.models.value.any { it.id == imported.id })
            assertEquals(imported.id, store.selected.value?.id)
            assertTrue(File(imported.path).isFile)
            store.delete(imported)
            assertFalse(store.models.value.any { it.id == imported.id })
        } finally {
            downloader.cancel()
        }
    }

    @Test
    fun installsTheRealModelFromALocalMirror() = runBlocking {
        val fixture = stageRealModel()
        assumeTrue("Push qwen2.5-0.5b-instruct-q4_k_m.gguf to /sdcard/Download/ first", fixture != null)
        val file = fixture!!
        val catalogue = AssetModelCatalogue(context.assets, "catalog/models_catalogue.json").catalogue()
        val entry = catalogue.models.first { it.id == "qwen2.5-0.5b-instruct-q4_k_m" }
        assertEquals(file.length(), entry.sizeBytes)
        val server = serveFile(file)
        val downloader = downloader()
        val store = FileModelStore(context)
        val startedAt = System.currentTimeMillis()
        try {
            downloader.start(entry, url = server.url)
            val installed = await(downloader, DownloadPhase.INSTALLED, timeoutMs = 300_000)
            val downloadMs = System.currentTimeMillis() - startedAt
            val imported = store.importFile(entry.fileName, installed.file!!, entry.sha256).getOrThrow()
            assertEquals(entry.sizeBytes, imported.sizeBytes)
            assertEquals(imported.id, store.selected.value?.id)
            SharedPrefsOnboardingStore(context).complete(skipped = false)
            Log.i(TAG, "REAL_MODEL installed bytes=" + imported.sizeBytes + " downloadMs=" + downloadMs + " path=" + imported.path)
        } finally {
            downloader.cancel()
        }
        Unit
    }

    // ------------------------------------------------------------------ fixtures and test doubles

    private fun stageRealModel(): File? {
        val dir = File(context.getExternalFilesDir(null), "model-fixtures").apply { mkdirs() }
        val target = File(dir, "qwen2.5-0.5b-instruct-q4_k_m.gguf")
        if (!target.isFile) shell("cp /sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf " + target.absolutePath)
        shell("chmod 666 " + target.absolutePath)
        return target.takeIf { it.isFile && it.length() > 0L }
    }

    private fun shell(command: String) {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).use { stream -> stream.readBytes() }
        }
    }

    private fun stagingDir(): File = File(context.cacheDir, "m11-test-downloads").apply { mkdirs() }

    private fun downloader(
        policy: NetworkPolicy = FakeNetworkPolicy(true),
        storage: StorageProbe = FakeStorage(8L * 1024 * 1024 * 1024),
        wifiOnly: Boolean = false,
    ): ModelDownloader {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return ModelDownloader(
            transport = HttpDownloadTransport(),
            networkPolicy = policy,
            storage = storage,
            scope = scope,
            stagingDir = stagingDir(),
            wifiOnly = { wifiOnly },
        )
    }

    private fun entry(size: Long, sha: String?) = CatalogueEntry(
        id = "device-model",
        displayName = "Device Model",
        repo = "org/device-GGUF",
        fileName = "device-model.gguf",
        sizeBytes = size,
        sha256 = sha,
        ramMb = 512,
        minRamMb = 2048,
    )

    private fun serve(name: String, payload: ByteArray, chunkDelayMs: Long = 0L): LoopbackServer {
        val file = File(stagingDir(), name)
        file.writeBytes(payload)
        return serveFile(file, chunkDelayMs)
    }

    private fun serveFile(file: File, chunkDelayMs: Long = 0L): LoopbackServer {
        val server = LoopbackServer(file, chunkDelayMs)
        servers += server
        return server
    }

    private fun shaOf(bytes: ByteArray): String {
        val file = File(stagingDir(), "payload-" + bytes.size + ".bin")
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
        return bytes
    }

    private suspend fun await(
        downloader: ModelDownloader,
        phase: DownloadPhase,
        timeoutMs: Long = 60_000,
    ): DownloadProgress = withTimeout(timeoutMs) {
        downloader.progress.filterNotNull()
            .first { it.phase == phase || (phase != DownloadPhase.FAILED && it.phase == DownloadPhase.FAILED) }
            .also { progress ->
                if (progress.phase == DownloadPhase.FAILED && phase != DownloadPhase.FAILED) {
                    throw AssertionError("download failed while waiting for " + phase + ": " + progress.reason)
                }
            }
    }

    private class FakeNetworkPolicy(private val isUnmetered: Boolean) : NetworkPolicy {
        override fun isUnmetered(): Boolean = isUnmetered
    }

    private class FakeStorage(private val freeBytes: Long) : StorageProbe {
        override fun freeBytes(): Long = freeBytes
    }

    /** Minimal HTTP/1.1 file server with Range support, bound to loopback so nothing leaves the device. */
    private class LoopbackServer(private val file: File, private val chunkDelayMs: Long) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url: String = "http://127.0.0.1:" + socket.localPort + "/" + file.name
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile
        private var closed = false
        private val thread = Thread {
            while (!closed) {
                runCatching {
                    socket.accept().use { connection -> handle(connection) }
                }
            }
        }.apply { isDaemon = true; start() }

        private fun handle(connection: Socket) {
            val reader = connection.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            requests += requestLine
            var rangeStart = 0L
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    requests += line
                    rangeStart = line.substringAfter("bytes=").substringBefore("-").toLongOrNull() ?: 0L
                }
            }
            val total = file.length()
            val start = rangeStart.coerceIn(0L, total)
            val length = total - start
            val output = connection.getOutputStream()
            val header = buildString {
                append(if (start > 0L) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (start > 0L) {
                    append("Content-Range: bytes ").append(start).append("-").append(total - 1)
                        .append("/").append(total).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray())
            RandomAccessFile(file, "r").use { random ->
                random.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val read = random.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                    if (chunkDelayMs > 0L) Thread.sleep(chunkDelayMs)
                }
            }
            output.flush()
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TAG = "SirrakModelManager"
    }
}

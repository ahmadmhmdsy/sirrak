package app.sirrak.downloads

import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.DownloadPhase
import app.sirrak.core.models.DownloadProgress
import app.sirrak.core.models.DownloadQueue
import app.sirrak.core.models.DownloadQueueCodes
import app.sirrak.core.models.DownloadQueueSnapshot
import app.sirrak.core.models.DownloadReasons
import app.sirrak.core.models.DownloadTask
import app.sirrak.core.models.DownloadTaskPhase
import app.sirrak.core.models.EnqueueResult
import app.sirrak.core.models.ModelCatalogue
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.QueueStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2: the host bridge between the durable queue and the one running transfer.
 *
 * Plain JVM tests: the store is in memory, the downloader is a fake [DownloadPort] owning the
 * [MutableStateFlow] the test emits on, and the collector runs on [Dispatchers.Unconfined] so an
 * emission is handled before the assertion that follows it. That mirrors the production host's
 * serialization without Robolectric, a network or an Android device.
 */
class DownloadBridgeTest {

    private val now = 1_000L
    private val store = MemoryStore()
    private val port = FakeDownloadPort()
    private val first = catalogueEntry("model-a")
    private val second = catalogueEntry("model-b")
    private lateinit var scope: CoroutineScope
    private lateinit var bridge: DownloadBridge

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        bridge = DownloadBridge(store, port, FakeCatalogue(first, second), { now }, scope)
        bridge.load { }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `enqueue adds one queued row and a second request returns it instead of a second transfer`() {
        val added = bridge.enqueue(first)
        assertTrue(added is EnqueueResult.Added)
        assertEquals(listOf("model-a"), bridge.tasks().value.map { it.id })
        assertEquals(DownloadTaskPhase.QUEUED, bridge.tasks().value.single().phase)
        assertEquals(1, store.writes)

        val duplicate = bridge.enqueue(first)
        assertTrue(duplicate is EnqueueResult.Duplicate)
        assertEquals(1, bridge.tasks().value.size)
    }

    @Test
    fun `an unmetered pump starts the head and refuses a second concurrent transfer`() {
        bridge.enqueue(first)
        bridge.enqueue(second)
        assertTrue(port.starts.isEmpty())

        port.unmetered = true
        bridge.pump()

        assertEquals(listOf("model-a"), port.starts.map { it.entry.id })
        assertEquals(DownloadTaskPhase.ACTIVE, task("model-a").phase)
        assertEquals(1, task("model-a").attempts)
        assertEquals(DownloadTaskPhase.QUEUED, task("model-b").phase)

        bridge.pump()
        assertEquals(1, port.starts.size)
        assertFalse(bridge.start("model-b", allowMetered = true))
        assertEquals(DownloadTaskPhase.QUEUED, task("model-b").phase)
    }

    @Test
    fun `a metered connection leaves a new request queued until the user consents`() {
        bridge.enqueue(first)
        bridge.pump()
        assertTrue(port.starts.isEmpty())
        assertEquals(DownloadTaskPhase.QUEUED, task("model-a").phase)

        assertFalse(bridge.start("model-a", allowMetered = false))
        assertEquals(DownloadTaskPhase.QUEUED, task("model-a").phase)

        assertTrue(bridge.start("model-a", allowMetered = true))
        assertEquals(listOf(true), port.starts.map { it.allowMetered })
        assertEquals(DownloadTaskPhase.ACTIVE, task("model-a").phase)
        assertEquals(1, task("model-a").attempts)
    }

    @Test
    fun `enqueue on an unmetered connection starts without another tap`() {
        port.unmetered = true
        assertTrue(bridge.enqueue(first) is EnqueueResult.Added)
        assertEquals(listOf("model-a"), port.starts.map { it.entry.id })
        assertEquals(DownloadTaskPhase.ACTIVE, task("model-a").phase)
    }

    @Test
    fun `progress updates the running row and clamps it inside the file`() {
        port.unmetered = true
        bridge.enqueue(first)

        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 400))
        assertEquals(400L, task("model-a").downloadedBytes)
        assertEquals(0.4f, task("model-a").fraction, 0.0001f)

        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 5_000))
        assertEquals(1_000L, task("model-a").downloadedBytes)

        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 100))
        assertEquals(1_000L, task("model-a").downloadedBytes)
    }

    @Test
    fun `a verifying transfer moves the row without touching its bytes`() {
        port.unmetered = true
        bridge.enqueue(first)
        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 900))
        port.emit(downloadProgress("model-a", DownloadPhase.VERIFYING, bytes = 900))

        assertEquals(DownloadTaskPhase.VERIFYING, task("model-a").phase)
        assertEquals(900L, task("model-a").downloadedBytes)
    }

    @Test
    fun `a failed transfer records its stable reason and keeps the bytes`() {
        port.unmetered = true
        bridge.enqueue(first)
        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 250))
        port.emit(
            downloadProgress(
                "model-a",
                DownloadPhase.FAILED,
                bytes = 250,
                reason = DownloadReasons.NETWORK,
            ),
        )

        val row = task("model-a")
        assertEquals(DownloadTaskPhase.FAILED, row.phase)
        assertEquals(DownloadReasons.NETWORK, row.errorCode)
        assertEquals(250L, row.downloadedBytes)
    }

    @Test
    fun `the adoption coordinator can mark a task installed or failed`() {
        port.unmetered = true
        bridge.enqueue(first)
        bridge.enqueue(second)
        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 800))
        port.emit(downloadProgress("model-a", DownloadPhase.VERIFYING, bytes = 800))
        // The real downloader publishes INSTALLED when the file is verified; the bridge ignores that
        // snapshot, and the coordinator imports the file and only then reports back through markInstalled.
        port.emit(downloadProgress("model-a", DownloadPhase.INSTALLED, bytes = 1_000))

        assertTrue(bridge.markInstalled("model-a"))
        assertEquals(DownloadTaskPhase.INSTALLED, task("model-a").phase)
        assertEquals(1_000L, task("model-a").downloadedBytes)
        // The freed slot continues the queue on Wi-Fi without another tap.
        assertEquals(listOf("model-a", "model-b"), port.starts.map { it.entry.id })

        port.emit(downloadProgress("model-b", DownloadPhase.VERIFYING, bytes = 0))
        assertTrue(bridge.markFailed("model-b", "import_failed"))
        assertEquals(DownloadTaskPhase.FAILED, task("model-b").phase)
        assertEquals("import_failed", task("model-b").errorCode)

        val reloaded = DownloadBridge(store, FakeDownloadPort(), FakeCatalogue(first, second), { now }, scope)
        reloaded.load { }
        val rows = reloaded.tasks().value.associateBy { it.id }
        assertEquals(DownloadTaskPhase.INSTALLED, rows.getValue("model-a").phase)
        assertEquals("import_failed", rows.getValue("model-b").errorCode)
    }

    @Test
    fun `pausing stops the transfer and resuming on Wi-Fi starts it again`() {
        port.unmetered = true
        bridge.enqueue(first)
        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 600))

        assertTrue(bridge.pause("model-a"))
        assertEquals(1, port.pauses)
        assertEquals(DownloadTaskPhase.PAUSED, task("model-a").phase)
        assertEquals(600L, task("model-a").downloadedBytes)

        // The downloader's own PAUSED snapshot must not move an already parked row.
        port.emit(downloadProgress("model-a", DownloadPhase.PAUSED))
        assertEquals(600L, task("model-a").downloadedBytes)

        assertTrue(bridge.resume("model-a"))
        assertEquals(DownloadTaskPhase.ACTIVE, task("model-a").phase)
        assertEquals(2, port.starts.size)
    }

    @Test
    fun `cancelling the running task clears the slot and starts the next queued row`() {
        port.unmetered = true
        bridge.enqueue(first)
        bridge.enqueue(second)
        port.emit(downloadProgress("model-a", DownloadPhase.DOWNLOADING, bytes = 500))

        assertTrue(bridge.cancel("model-a"))

        assertEquals(1, port.cancels)
        assertEquals(listOf("model-a", "model-b"), port.starts.map { it.entry.id })
        assertEquals(DownloadTaskPhase.CANCELLED, task("model-a").phase)
        assertEquals(0L, task("model-a").downloadedBytes)
        assertEquals(DownloadTaskPhase.ACTIVE, task("model-b").phase)
    }

    @Test
    fun `the tasks flow reflects every mutation`() {
        assertEquals(emptyList<DownloadTask>(), bridge.tasks().value)
        bridge.enqueue(first)
        assertEquals(listOf("model-a"), bridge.tasks().value.map { it.id })

        port.unmetered = true
        bridge.pump()
        assertEquals(DownloadTaskPhase.ACTIVE, bridge.tasks().value.single().phase)

        assertTrue(bridge.pause("model-a"))
        assertEquals(DownloadTaskPhase.PAUSED, bridge.tasks().value.single().phase)

        assertTrue(bridge.resume("model-a"))
        assertEquals(DownloadTaskPhase.ACTIVE, bridge.tasks().value.single().phase)

        assertTrue(bridge.cancel("model-a"))
        assertEquals(DownloadTaskPhase.CANCELLED, bridge.tasks().value.single().phase)

        assertEquals(1, bridge.clearFinished())
        assertTrue(bridge.tasks().value.isEmpty())
    }

    @Test
    fun `load parks a persisted active task instead of silently resuming it`() {
        val queue = DownloadQueue(store, { now })
        queue.load()
        assertTrue(queue.enqueue(persistedTask("model-a")) is EnqueueResult.Added)
        assertTrue(queue.start("model-a"))
        assertTrue(queue.updateProgress("model-a", 300))

        val problems = mutableListOf<List<String>>()
        bridge.load { problems += it }

        assertEquals(listOf(emptyList<String>()), problems)
        assertEquals(DownloadTaskPhase.PAUSED, task("model-a").phase)
        assertEquals(300L, task("model-a").downloadedBytes)
        assertTrue(port.starts.isEmpty())
        assertNull(bridge.active())
    }

    @Test
    fun `load reports a broken snapshot and starts empty`() {
        store.text = "not json"
        val problems = mutableListOf<List<String>>()
        bridge.load { problems += it }

        assertEquals(listOf(listOf(DownloadQueueCodes.CORRUPT_QUEUE)), problems)
        assertTrue(bridge.tasks().value.isEmpty())
    }

    @Test
    fun `load repairs a snapshot with two active rows to no running row`() {
        store.text = Json.encodeToString(
            DownloadQueueSnapshot(tasks = listOf(activeTask("model-a"), activeTask("model-b"))),
        )
        val problems = mutableListOf<List<String>>()
        bridge.load { problems += it }

        assertEquals(listOf(listOf(DownloadQueueCodes.DUPLICATE_ACTIVE)), problems)
        assertEquals(2, bridge.tasks().value.size)
        assertTrue(bridge.tasks().value.all { it.phase == DownloadTaskPhase.PAUSED })
        assertNull(bridge.active())
    }

    @Test
    fun `a store write failure surfaces and leaves a coherent in memory list`() {
        store.failWrites = true
        val thrown = assertThrows(IllegalStateException::class.java) { bridge.enqueue(first) }
        assertEquals("disk full", thrown.message)

        // The failed save neither swallowed the error nor corrupted what the UI already shows.
        assertEquals(listOf("model-a"), bridge.tasks().value.map { it.id })
        assertEquals(DownloadTaskPhase.QUEUED, bridge.tasks().value.single().phase)

        // The next successful write persists the whole snapshot, including the unsaved row.
        store.failWrites = false
        assertTrue(bridge.cancel("model-a"))
        val reloaded = DownloadBridge(store, FakeDownloadPort(), FakeCatalogue(first), { now }, scope)
        reloaded.load { }
        assertEquals(DownloadTaskPhase.CANCELLED, reloaded.tasks().value.single().phase)
    }

    @Test
    fun `progress for an entry the queue does not hold is ignored`() {
        port.unmetered = true
        bridge.enqueue(first)
        port.emit(downloadProgress("ghost", DownloadPhase.DOWNLOADING, bytes = 100))

        assertEquals(DownloadTaskPhase.ACTIVE, task("model-a").phase)
        assertEquals(0L, task("model-a").downloadedBytes)
    }

    @Test
    fun `a port that refuses to start leaves a failed row instead of a stuck running row`() {
        port.unmetered = true
        port.failStarts = true

        bridge.enqueue(first)

        assertEquals(DownloadTaskPhase.FAILED, task("model-a").phase)
        assertEquals(DownloadReasons.IO, task("model-a").errorCode)
        assertNull(bridge.active())
    }

    @Test
    fun `a synthetic projector row starts even when the catalogue does not list it`() {
        port.unmetered = true
        val projector = CatalogueEntry(
            id = "model-a:mmproj",
            displayName = "Model A vision",
            repo = "org/model-a-GGUF",
            fileName = "mmproj-model-a.gguf",
            sizeBytes = 200L,
        )

        assertTrue(bridge.enqueue(projector) is EnqueueResult.Added)

        val started = port.starts.single().entry
        assertEquals("model-a:mmproj", started.id)
        assertEquals("mmproj-model-a.gguf", started.fileName)
        assertEquals(200L, started.sizeBytes)
        assertEquals(DownloadTaskPhase.ACTIVE, task("model-a:mmproj").phase)
    }

    @Test
    fun `resuming a parked row on a metered connection waits for consent`() {
        port.unmetered = true
        bridge.enqueue(first)
        assertTrue(bridge.pause("model-a"))
        port.unmetered = false

        assertTrue(bridge.resume("model-a"))
        assertEquals(DownloadTaskPhase.QUEUED, task("model-a").phase)
        assertEquals(1, port.starts.size)

        assertTrue(bridge.start("model-a", allowMetered = true))
        assertEquals(2, port.starts.size)
        assertEquals(DownloadTaskPhase.ACTIVE, task("model-a").phase)
    }

    @Test
    fun `the file store round trips a queue through a real file`() {
        val directory = Files.createTempDirectory("sirrak-download-queue").toFile()
        try {
            val file = File(directory, "download_queue.json")
            val store = QueueFileStore(file)
            assertNull(store.read())

            val queue = DownloadQueue(store, { now })
            queue.load()
            assertTrue(queue.enqueue(persistedTask("model-a")) is EnqueueResult.Added)
            assertTrue(file.exists())
            assertFalse(File(directory, "download_queue.json.tmp").exists())

            val reloaded = DownloadQueue(QueueFileStore(file), { now })
            assertEquals(emptyList<String>(), reloaded.load())
            assertEquals(listOf("model-a"), reloaded.tasks().map { it.id })
            assertEquals(DownloadTaskPhase.QUEUED, reloaded.tasks().single().phase)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the file store replaces an existing snapshot atomically`() {
        val directory = Files.createTempDirectory("sirrak-download-queue").toFile()
        try {
            val file = File(directory, "download_queue.json")
            val store = QueueFileStore(file)

            store.write("first")
            store.write("second")

            assertEquals("second", store.read())
            assertFalse(File(directory, "download_queue.json.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun task(id: String): DownloadTask = bridge.tasks().value.first { it.id == id }

    private fun catalogueEntry(id: String, sizeBytes: Long = 1_000L) = CatalogueEntry(
        id = id,
        displayName = "Model $id",
        repo = "org/$id-GGUF",
        fileName = "$id.gguf",
        sizeBytes = sizeBytes,
    )

    private fun persistedTask(id: String) = DownloadTask(
        id = id,
        entryId = id,
        displayName = "Model $id",
        repo = "org/$id-GGUF",
        fileName = "$id.gguf",
        sizeBytes = 1_000L,
        createdAt = now,
        updatedAt = now,
    )

    private fun activeTask(id: String) = persistedTask(id).copy(
        downloadedBytes = 100L,
        phase = DownloadTaskPhase.ACTIVE,
    )

    private fun downloadProgress(
        entryId: String,
        phase: DownloadPhase,
        bytes: Long = 0L,
        reason: String? = null,
    ) = DownloadProgress(
        entryId = entryId,
        phase = phase,
        bytesDownloaded = bytes,
        totalBytes = 1_000L,
        bytesPerSecond = 0L,
        reason = reason,
    )
}

/**
 * The production store writes a file; this one only needs the same contract, plus the failure switch
 * that proves a failed save is surfaced instead of swallowed.
 */
private class MemoryStore(var text: String? = null) : QueueStore {
    var writes = 0
        private set
    var failWrites = false

    override fun read(): String? = text

    override fun write(text: String) {
        if (failWrites) throw IllegalStateException("disk full")
        this.text = text
        writes++
    }
}

private class FakeCatalogue(vararg entries: CatalogueEntry) : ModelCatalogueProvider {
    private val models = entries.toList()

    override fun catalogue(): ModelCatalogue = ModelCatalogue(models = models)
}

/**
 * Stands in for the host's adapter over the single network downloader. It owns the same
 * [MutableStateFlow] contract, so the bridge's collector is exercised for real, and it records the
 * calls the bridge makes so a test can assert the queue's single-transfer invariant.
 */
private class FakeDownloadPort(
    override var unmetered: Boolean = false,
) : DownloadPort {
    override val progress = MutableStateFlow<DownloadProgress?>(null)
    val starts = mutableListOf<Start>()
    var pauses = 0
        private set
    var cancels = 0
        private set
    var failStarts = false

    override fun start(entry: CatalogueEntry, allowMetered: Boolean) {
        if (failStarts) throw IllegalStateException("no transport")
        starts += Start(entry, allowMetered)
    }

    override fun pause() {
        pauses++
        progress.value = progress.value?.copy(phase = DownloadPhase.PAUSED)
    }

    override fun resume() = Unit

    override fun cancel() {
        cancels++
        progress.value = null
    }

    override fun clear() {
        progress.value = null
    }

    fun emit(value: DownloadProgress) {
        progress.value = value
    }

    data class Start(val entry: CatalogueEntry, val allowMetered: Boolean)
}

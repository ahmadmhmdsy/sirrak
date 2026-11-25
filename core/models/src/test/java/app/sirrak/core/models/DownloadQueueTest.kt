package app.sirrak.core.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {

    private var now = 1_000L
    private val store = MemoryStore()
    private val queue = DownloadQueue(store, clock = { now })

    @Test
    fun `an enqueued task survives a new queue over the same store`() {
        val added = queue.enqueue(task("model-a")) as EnqueueResult.Added
        assertEquals(DownloadTaskPhase.QUEUED, added.task.phase)
        assertEquals(1_000L, added.task.updatedAt)
        assertEquals(1, store.writes)

        val restored = DownloadQueue(store, clock = { now })
        assertEquals(emptyList<String>(), restored.load())
        val persisted = restored.tasks().single()
        assertEquals("model-a", persisted.id)
        assertEquals("model-a", persisted.entryId)
        assertEquals(1_000L, persisted.createdAt)
        assertEquals(DownloadTaskPhase.QUEUED, persisted.phase)
    }

    @Test
    fun `a second enqueue for the same entry returns the existing row`() {
        val first = (queue.enqueue(task("model-a", entryId = "catalogue-a")) as EnqueueResult.Added).task
        val second = queue.enqueue(task("model-a-copy", entryId = "catalogue-a"))

        val duplicate = second as EnqueueResult.Duplicate
        assertEquals(first.id, duplicate.task.id)
        assertEquals(1, queue.tasks().size)
    }

    @Test
    fun `an installed entry is not queued again`() {
        queue.enqueue(task("model-a"))
        queue.start("model-a")
        queue.install("model-a")

        val result = queue.enqueue(task("model-a"))
        assertTrue(result is EnqueueResult.AlreadyInstalled)
        assertEquals(1, queue.tasks().size)
    }

    @Test
    fun `only one task can be active at a time`() {
        queue.enqueue(task("model-a"))
        queue.enqueue(task("model-b"))

        assertTrue(queue.start("model-a"))
        assertFalse(queue.start("model-b"))
        assertEquals("model-a", queue.active()?.id)

        assertTrue(queue.pause("model-a"))
        assertTrue(queue.start("model-b"))
        assertEquals("model-b", queue.active()?.id)
    }

    @Test
    fun `progress clamps to the file size and never rewinds`() {
        queue.enqueue(task("model-a", sizeBytes = 1_000))
        queue.start("model-a")

        assertTrue(queue.updateProgress("model-a", 400))
        assertEquals(400L, queue.tasks().single().downloadedBytes)
        assertEquals(0.4f, queue.tasks().single().fraction, 0.0001f)

        // A late callback from an older run must not move the bar backwards...
        assertTrue(queue.updateProgress("model-a", -50))
        assertEquals(400L, queue.tasks().single().downloadedBytes)

        // ...and an overreported byte count is clamped to the known total.
        assertTrue(queue.updateProgress("model-a", 2_000))
        assertEquals(1_000L, queue.tasks().single().downloadedBytes)
        assertEquals(1f, queue.tasks().single().fraction, 0.0001f)

        val restored = DownloadQueue(store, clock = { now })
        restored.load()
        assertEquals(1_000L, restored.tasks().single().downloadedBytes)
    }

    @Test
    fun `pause keeps the downloaded bytes`() {
        queue.enqueue(task("model-a"))
        queue.start("model-a")
        queue.updateProgress("model-a", 250)

        assertTrue(queue.pause("model-a"))
        val paused = queue.tasks().single()
        assertEquals(DownloadTaskPhase.PAUSED, paused.phase)
        assertEquals(250L, paused.downloadedBytes)
        assertTrue(paused.resumable)

        val restored = DownloadQueue(store, clock = { now })
        restored.load()
        assertEquals(250L, restored.tasks().single().downloadedBytes)
    }

    @Test
    fun `resume and retry keep the bytes and clear the error code`() {
        queue.enqueue(task("model-a"))
        queue.start("model-a")
        queue.updateProgress("model-a", 300)
        assertTrue(queue.fail("model-a", DownloadReasons.NETWORK))

        val failed = queue.tasks().single()
        assertEquals(DownloadTaskPhase.FAILED, failed.phase)
        assertEquals(300L, failed.downloadedBytes)
        assertEquals(DownloadReasons.NETWORK, failed.errorCode)

        assertTrue(queue.resume("model-a"))
        val resumed = queue.tasks().single()
        assertEquals(DownloadTaskPhase.QUEUED, resumed.phase)
        assertEquals(300L, resumed.downloadedBytes)
        assertNull(resumed.errorCode)

        queue.start("model-a")
        queue.updateProgress("model-a", 500)
        assertTrue(queue.fail("model-a", DownloadReasons.CHECKSUM))
        assertTrue(queue.retry("model-a"))
        val retried = queue.tasks().single()
        assertEquals(DownloadTaskPhase.QUEUED, retried.phase)
        assertEquals(500L, retried.downloadedBytes)
        assertNull(retried.errorCode)
    }

    @Test
    fun `install finishes the task at one hundred percent`() {
        queue.enqueue(task("model-a", sizeBytes = 1_000))
        queue.start("model-a")
        queue.updateProgress("model-a", 400)
        assertTrue(queue.beginVerify("model-a"))

        assertTrue(queue.install("model-a"))
        val installed = queue.tasks().single()
        assertEquals(DownloadTaskPhase.INSTALLED, installed.phase)
        assertEquals(1_000L, installed.downloadedBytes)
        assertEquals(1f, installed.fraction, 0.0001f)
        assertNull(installed.errorCode)
        assertFalse(installed.resumable)

        val restored = DownloadQueue(store, clock = { now })
        restored.load()
        assertEquals(DownloadTaskPhase.INSTALLED, restored.tasks().single().phase)
    }

    @Test
    fun `cancel zeroes the downloaded bytes`() {
        queue.enqueue(task("model-a"))
        queue.start("model-a")
        queue.updateProgress("model-a", 400)

        assertTrue(queue.cancel("model-a"))
        val cancelled = queue.tasks().single()
        assertEquals(DownloadTaskPhase.CANCELLED, cancelled.phase)
        assertEquals(0L, cancelled.downloadedBytes)
    }

    @Test
    fun `clearFinished drops installed and cancelled but keeps failed`() {
        queue.enqueue(task("installed"))
        queue.start("installed")
        queue.install("installed")

        queue.enqueue(task("cancelled"))
        queue.start("cancelled")
        queue.cancel("cancelled")

        queue.enqueue(task("failed"))
        queue.start("failed")
        queue.fail("failed", DownloadReasons.NETWORK)

        assertEquals(2, queue.clearFinished())
        val remaining = queue.tasks()
        assertEquals(1, remaining.size)
        assertEquals(DownloadTaskPhase.FAILED, remaining.single().phase)
        assertEquals(0, queue.clearFinished())
    }

    @Test
    fun `corrupt payloads and a missing store are tolerated`() {
        val empty = DownloadQueue(MemoryStore(null), clock = { now })
        assertEquals(emptyList<String>(), empty.load())
        assertTrue(empty.tasks().isEmpty())

        store.text = "{ not a snapshot"
        assertEquals(listOf(DownloadQueueCodes.CORRUPT_QUEUE), queue.load())
        assertTrue(queue.tasks().isEmpty())
        // load is idempotent: the same broken payload reports the same code again, without growing state.
        assertEquals(listOf(DownloadQueueCodes.CORRUPT_QUEUE), queue.load())

        store.text = "   "
        assertEquals(listOf(DownloadQueueCodes.CORRUPT_QUEUE), queue.load())

        store.text = """{"schemaVersion": 2, "tasks": []}"""
        assertEquals(listOf(DownloadQueueCodes.unsupportedSchema(2)), queue.load())
        assertTrue(queue.tasks().isEmpty())
    }

    @Test
    fun `an unreadable store yields an empty queue instead of a crash`() {
        val broken = DownloadQueue(
            object : QueueStore {
                override fun read(): String? = throw IllegalStateException("disk gone")
                override fun write(text: String) = Unit
            },
        )
        assertEquals(listOf(DownloadQueueCodes.UNREADABLE_STORE), broken.load())
        assertTrue(broken.tasks().isEmpty())
    }

    @Test
    fun `load demotes the extra active task to paused`() {
        store.text = snapshotOf(
            task("model-a", downloadedBytes = 100, phase = DownloadTaskPhase.ACTIVE),
            task("model-b", downloadedBytes = 200, phase = DownloadTaskPhase.ACTIVE, createdAt = 2_000),
        )

        assertEquals(listOf(DownloadQueueCodes.DUPLICATE_ACTIVE), queue.load())
        val tasks = queue.tasks()
        assertEquals(1, tasks.count { it.phase == DownloadTaskPhase.ACTIVE })
        assertEquals("model-a", queue.active()?.id)
        val demoted = tasks.single { it.id == "model-b" }
        assertEquals(DownloadTaskPhase.PAUSED, demoted.phase)
        assertEquals(200L, demoted.downloadedBytes)

        // The repair is persisted, so the next process starts clean instead of repairing again.
        val restored = DownloadQueue(store, clock = { now })
        assertEquals(emptyList<String>(), restored.load())
        assertEquals(1, restored.tasks().count { it.phase == DownloadTaskPhase.ACTIVE })
    }

    @Test
    fun `the active task sorts before the waiting ones and phases keep their order`() {
        queue.enqueue(task("queued", createdAt = 1_000))
        queue.enqueue(task("queuedSecond", createdAt = 1_500))
        queue.enqueue(task("paused", createdAt = 2_000))
        queue.enqueue(task("failed", createdAt = 3_000))
        queue.enqueue(task("active", createdAt = 4_000))

        queue.start("paused")
        queue.pause("paused")
        queue.start("failed")
        queue.fail("failed", DownloadReasons.NETWORK)
        queue.start("active")

        assertEquals(
            listOf("active", "queued", "queuedSecond", "paused", "failed"),
            queue.tasks().map { it.id },
        )
    }

    @Test
    fun `each start counts one attempt`() {
        queue.enqueue(task("model-a"))
        assertTrue(queue.start("model-a"))
        assertEquals(1, queue.tasks().single().attempts)

        // A start refused because the task is already running is not an attempt.
        assertFalse(queue.start("model-a"))
        assertEquals(1, queue.tasks().single().attempts)

        queue.pause("model-a")
        queue.resume("model-a")
        assertTrue(queue.start("model-a"))
        assertEquals(2, queue.tasks().single().attempts)

        val restored = DownloadQueue(store, clock = { now })
        restored.load()
        assertEquals(2, restored.tasks().single().attempts)
    }

    @Test
    fun `malformed tasks are rejected with stable codes`() {
        assertEquals(
            EnqueueResult.Invalid(DownloadQueueCodes.BLANK_ID),
            queue.enqueue(task("model-a").copy(id = " ")),
        )
        assertEquals(
            EnqueueResult.Invalid(DownloadQueueCodes.BLANK_ENTRY_ID),
            queue.enqueue(task("model-a", entryId = "")),
        )
        assertEquals(
            EnqueueResult.Invalid(DownloadQueueCodes.BLANK_FILE_NAME),
            queue.enqueue(task("model-a").copy(fileName = "")),
        )
        assertEquals(
            EnqueueResult.Invalid(DownloadQueueCodes.NEGATIVE_SIZE),
            queue.enqueue(task("model-a", sizeBytes = -1L)),
        )
        assertTrue(queue.tasks().isEmpty())
    }

    @Test
    fun `one task id cannot be shared by two entries`() {
        queue.enqueue(task("shared", entryId = "entry-a"))
        assertEquals(
            EnqueueResult.Invalid(DownloadQueueCodes.DUPLICATE_ID),
            queue.enqueue(task("shared", entryId = "entry-b")),
        )
    }

    @Test
    fun `transitions from the wrong phase are refused`() {
        queue.enqueue(task("model-a"))

        assertFalse(queue.updateProgress("model-a", 10))
        assertFalse(queue.install("model-a"))
        assertFalse(queue.fail("model-a", " "))
        assertFalse(queue.resume("missing"))
        assertTrue(queue.fail("model-a", DownloadReasons.NETWORK))
        assertEquals(DownloadTaskPhase.FAILED, queue.tasks().single().phase)
    }

    @Test
    fun `fraction and resumable reflect the task state`() {
        val task = task("model-a", sizeBytes = 0L)
        assertEquals(0f, task.fraction, 0.0001f)
        assertTrue(task.resumable)
        assertTrue(task.copy(phase = DownloadTaskPhase.PAUSED).resumable)
        assertFalse(task.copy(phase = DownloadTaskPhase.VERIFYING).resumable)
        assertFalse(task.copy(phase = DownloadTaskPhase.FAILED).resumable)
    }

    private fun task(
        id: String,
        entryId: String = id,
        sizeBytes: Long = 1_000L,
        downloadedBytes: Long = 0L,
        phase: DownloadTaskPhase = DownloadTaskPhase.QUEUED,
        errorCode: String? = null,
        attempts: Int = 0,
        createdAt: Long = now,
    ) = DownloadTask(
        id = id,
        entryId = entryId,
        displayName = "Model $id",
        repo = "org/models",
        fileName = "$id.gguf",
        sizeBytes = sizeBytes,
        downloadedBytes = downloadedBytes,
        phase = phase,
        errorCode = errorCode,
        attempts = attempts,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun snapshotOf(vararg tasks: DownloadTask): String =
        Json.encodeToString(DownloadQueueSnapshot(tasks = tasks.toList()))

    private class MemoryStore(var text: String? = null) : QueueStore {
        var writes = 0
            private set

        override fun read(): String? = text

        override fun write(text: String) {
            this.text = text
            writes++
        }
    }
}

package app.sirrak.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lifecycle of one file in the persisted download list.
 *
 * The old in-flight [DownloadPhase] could afford to forget, because it lived only in memory. This
 * enum is serialized, so it is the contract with the next process: a phase the list renders must be
 * a phase a restart can explain. [CANCELLED] and [INSTALLED] exist so the list can say what happened
 * to a task instead of deleting it behind the user's back.
 */
@Serializable
enum class DownloadTaskPhase {
    QUEUED,
    ACTIVE,
    PAUSED,
    VERIFYING,
    INSTALLED,
    FAILED,
    CANCELLED,
}

/**
 * One file the user asked for, with everything the queue and the host's single transfer slot need.
 *
 * The task is the durable half of a download: [phase] is what the list shows after a restart,
 * [downloadedBytes] is the resume offset the host continues from, and [errorCode] is a stable code
 * from [DownloadReasons] that the UI localizes. This type is serialized as JSON, so fields may be
 * added with defaults but a stored name never changes meaning.
 */
@Serializable
data class DownloadTask(
    /** Caller-supplied, stable across restarts: every queue mutation is keyed by it. */
    val id: String,
    /** The catalogue entry this file belongs to; dedupe and "already installed" work on it. */
    val entryId: String,
    val displayName: String,
    val repo: String,
    val fileName: String,
    /** Expected total bytes; 0 when the source does not know (a user-supplied Hugging Face file). */
    val sizeBytes: Long,
    /** Bytes already on disk from this or an earlier attempt; pause/fail must never reset it. */
    val downloadedBytes: Long = 0L,
    val phase: DownloadTaskPhase = DownloadTaskPhase.QUEUED,
    /** Stable failure code, null unless [phase] is [DownloadTaskPhase.FAILED]. */
    val errorCode: String? = null,
    /** How many times the host actually started a transfer; only [DownloadQueue.start] increments it. */
    val attempts: Int = 0,
    /** Caller-supplied creation time; the list sorts by it within a phase, so it survives restarts. */
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 0..1 for a determinate bar; 0 while the total is unknown, never a fake 100%. */
    val fraction: Float
        get() = if (sizeBytes <= 0L) {
            0f
        } else {
            (downloadedBytes.toDouble() / sizeBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    /**
     * Whether the bytes on disk can still be continued. VERIFYING is excluded because the host owns
     * that step, and FAILED is excluded because a checksum or invalid-file failure cannot be resumed.
     */
    val resumable: Boolean
        get() = phase == DownloadTaskPhase.QUEUED ||
            phase == DownloadTaskPhase.ACTIVE ||
            phase == DownloadTaskPhase.PAUSED
}

/**
 * The whole persisted download list. [schemaVersion] lets a future shape be migrated or refused
 * instead of decoded into the wrong meaning; [DownloadQueue] refuses an unknown version rather than
 * guessing, because a guessed queue could resume a transfer at the wrong offset.
 */
@Serializable
data class DownloadQueueSnapshot(
    val schemaVersion: Int = 1,
    val tasks: List<DownloadTask> = emptyList(),
)

/**
 * Persistence seam for [DownloadQueue]: the app host binds it to a file, tests to memory.
 *
 * Implementations MUST make [write] atomic — write a temporary file and rename it over the old one.
 * The queue's only recovery from a broken payload is an empty list, so a torn write would silently
 * discard every pending download; an atomic rename makes the previous list win instead. [read] returns
 * null when nothing was ever written (first launch) and may throw on I/O failure, which [DownloadQueue]
 * reports rather than crashing the download screen. A write that throws is not swallowed by the queue:
 * a list that silently fails to save is the same lie about state the queue exists to remove.
 */
interface QueueStore {
    fun read(): String?
    fun write(text: String)
}

/** Why [DownloadQueue.enqueue] did or did not add a task; the UI turns each case into an action. */
sealed interface EnqueueResult {
    /** The task entered the queue; [task] is the normalized row (always queued) to render. */
    data class Added(val task: DownloadTask) : EnqueueResult

    /** The same catalogue entry is already in flight; [task] is the existing row to show, not a new one. */
    data class Duplicate(val task: DownloadTask) : EnqueueResult

    /** The entry is already installed, so the user needs no second copy on disk. */
    data class AlreadyInstalled(val task: DownloadTask) : EnqueueResult

    /**
     * Rejected before it became a row the user cannot explain. [code] is a stable code from
     * [DownloadQueueCodes]; the UI localizes it.
     */
    data class Invalid(val code: String) : EnqueueResult
}

/**
 * Every code the queue returns instead of user-visible text (this module has no strings). A code is
 * part of the queue's API: the app's copy mapping and the retry policy are written against it, so
 * rename one only together with its mapping.
 */
object DownloadQueueCodes {
    /** The stored payload is not a [DownloadQueueSnapshot] any more; the list starts empty. */
    const val CORRUPT_QUEUE = "corrupt_queue"

    /** The store itself failed to read; the list starts empty rather than crashing the screen. */
    const val UNREADABLE_STORE = "unreadable_store"

    /** The stored list claimed two running transfers; the extra ones were demoted to [DownloadTaskPhase.PAUSED]. */
    const val DUPLICATE_ACTIVE = "duplicate_active"

    const val BLANK_ID = "blank_id"
    const val BLANK_ENTRY_ID = "blank_entry_id"
    const val BLANK_FILE_NAME = "blank_file_name"
    const val NEGATIVE_SIZE = "negative_size"

    /** Two different files tried to share one task id; ids are the key of every mutation. */
    const val DUPLICATE_ID = "duplicate_id"

    /** `unsupported_schema:<n>`: the payload is from another shape; refuse it rather than guess. */
    fun unsupportedSchema(version: Int): String = "unsupported_schema:$version"
}

/**
 * The app's download list as pure state: one JSON snapshot behind a [QueueStore], at most one
 * [DownloadTaskPhase.ACTIVE] task, and every mutation written through immediately so process death
 * can lose at most one progress tick, never the list.
 *
 * It is deliberately synchronous and not thread-safe: the host owns it on its own dispatcher (the
 * old downloader's races came from callers, not from the data), and the queue must be usable by the
 * future coordinator without a scope or a coroutine dependency. It downloads nothing and knows no
 * `.part` files — the host pushes bytes in with [updateProgress] and reports outcomes, which keeps
 * the network policy, storage probe and GGUF verification where they already live.
 *
 * Honest limit: a persisted [DownloadTaskPhase.ACTIVE] task is a transfer the process died under.
 * The queue repairs only impossible states (two active tasks); whether a lone active task resumes or
 * is reported as interrupted is the host's startup decision, because only the host knows the network.
 */
class DownloadQueue(
    private val store: QueueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {

    private val items = mutableListOf<DownloadTask>()

    /**
     * Reads the persisted list and returns diagnostics for it ([DownloadQueueCodes]). First launch
     * (no payload) is not a problem. Repairs the one impossible state a crash can leave behind: more
     * than one active task is demoted to paused, so the next start cannot race two transfers.
     *
     * Never throws: a download list that crashes startup is worse than one that starts empty, so a
     * corrupt payload becomes an empty queue plus a code for the diagnostics log.
     */
    fun load(): List<String> {
        val problems = mutableListOf<String>()
        items.clear()
        val text = try {
            store.read()
        } catch (error: Exception) {
            problems += DownloadQueueCodes.UNREADABLE_STORE
            return problems
        } ?: return problems
        if (text.isBlank()) {
            problems += DownloadQueueCodes.CORRUPT_QUEUE
            return problems
        }
        val snapshot = try {
            json.decodeFromString<DownloadQueueSnapshot>(text)
        } catch (error: Exception) {
            problems += DownloadQueueCodes.CORRUPT_QUEUE
            return problems
        }
        if (snapshot.schemaVersion != CURRENT_SCHEMA) {
            problems += DownloadQueueCodes.unsupportedSchema(snapshot.schemaVersion)
            return problems
        }
        items += snapshot.tasks
        if (repairDuplicateActive()) {
            problems += DownloadQueueCodes.DUPLICATE_ACTIVE
            // Load must stay non-throwing; a failed repair write only means the next load repairs again.
            runCatching { persist() }
        }
        return problems
    }

    /**
     * The list the UI renders: running first, then waiting, then paused/verifying, then the finished
     * rows. The order is a stable sort by [DownloadTask.createdAt] within a phase, so a bar or label
     * never jumps to a different row between two snapshots.
     */
    fun tasks(): List<DownloadTask> = items.sortedWith(
        compareBy({ phaseRank(it.phase) }, { it.createdAt }),
    )

    /** The single task the host may transfer now; null when the queue is idle. */
    fun active(): DownloadTask? = items.firstOrNull { it.phase == DownloadTaskPhase.ACTIVE }

    /**
     * Adds one acquisition. A second request for an entry already queued, running, paused or
     * verifying is not an error — the user tapped twice, so the existing row is returned instead of
     * a second row nobody can tell apart (the old downloader silently dropped it: defect G3). A
     * cancelled or failed row for the same entry is superseded, because the queue is what the user
     * started and has not finished, not a history. The task always enters as queued — the host decides
     * when a transfer starts, so a caller-supplied phase is not adopted.
     */
    fun enqueue(task: DownloadTask): EnqueueResult {
        validationError(task)?.let { return EnqueueResult.Invalid(it) }
        val forEntry = items.filter { it.entryId == task.entryId }
        forEntry.firstOrNull { it.phase in IN_FLIGHT }?.let { return EnqueueResult.Duplicate(it) }
        forEntry.firstOrNull { it.phase == DownloadTaskPhase.INSTALLED }?.let {
            return EnqueueResult.AlreadyInstalled(it)
        }
        if (items.any { it.id == task.id && it.entryId != task.entryId }) {
            return EnqueueResult.Invalid(DownloadQueueCodes.DUPLICATE_ID)
        }
        val now = clock()
        val queued = task.copy(
            downloadedBytes = task.downloadedBytes.coerceAtLeast(0L),
            phase = DownloadTaskPhase.QUEUED,
            errorCode = null,
            createdAt = if (task.createdAt > 0L) task.createdAt else now,
            updatedAt = now,
        )
        items.removeAll { it.entryId == task.entryId }
        items += queued
        persist()
        return EnqueueResult.Added(queued)
    }

    /**
     * Moves a queued task into the transfer slot and counts the attempt. False while any task is
     * active — one transfer at a time is the queue's invariant, not a UI preference — or when [id] is
     * not queued. A failed start never counts as an attempt.
     */
    fun start(id: String): Boolean {
        if (items.any { it.phase == DownloadTaskPhase.ACTIVE }) return false
        return transition(id, STARTABLE) {
            it.copy(phase = DownloadTaskPhase.ACTIVE, attempts = it.attempts + 1)
        }
    }

    /**
     * Records a transfer's progress. Only the active task may move, and the value is clamped to
     * 0..sizeBytes and never rewinds: a late byte callback from a cancelled run must not drag the bar
     * back over the bytes a resumed run already wrote.
     */
    fun updateProgress(id: String, downloadedBytes: Long): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val task = items[index]
        if (task.phase != DownloadTaskPhase.ACTIVE) return false
        val clamped = downloadedBytes.coerceIn(0L, task.sizeBytes.coerceAtLeast(0L))
        if (clamped <= task.downloadedBytes) return true
        items[index] = task.copy(downloadedBytes = clamped, updatedAt = clock())
        persist()
        return true
    }

    /** Pauses a running or waiting task without losing its bytes, so resume continues the .part file. */
    fun pause(id: String): Boolean = transition(id, PAUSABLE) {
        it.copy(phase = DownloadTaskPhase.PAUSED)
    }

    /**
     * Puts a paused or failed task back in line without starting it here; the host starts it when the
     * slot is free. resume() clears the failure code because a queued row is not failing any more,
     * and leaves the attempt count alone — only a real start is an attempt.
     */
    fun resume(id: String): Boolean = transition(id, RESUMABLE) {
        it.copy(phase = DownloadTaskPhase.QUEUED, errorCode = null)
    }

    /** Hands a complete download to verification; the bytes stay exactly as downloaded. */
    fun beginVerify(id: String): Boolean = transition(id, VERIFIABLE) {
        it.copy(phase = DownloadTaskPhase.VERIFYING)
    }

    /**
     * Marks a transfer or verification as failed with a stable [code]. The bytes are kept because the
     * host's resume and retry paths continue from them; a blank code is refused, since a failure the
     * UI cannot name is the same lying surface the queue exists to remove.
     */
    fun fail(id: String, code: String): Boolean {
        if (code.isBlank()) return false
        return transition(id, FAILABLE) {
            it.copy(phase = DownloadTaskPhase.FAILED, errorCode = code)
        }
    }

    /** Re-queues a failed or cancelled task, keeping its bytes and clearing the stale failure code. */
    fun retry(id: String): Boolean = transition(id, RETRYABLE) {
        it.copy(phase = DownloadTaskPhase.QUEUED, errorCode = null)
    }

    /**
     * Marks a verified task installed with a full bar. Accepts a direct ACTIVE→INSTALLED step for
     * sources that need no verification, so the queue does not force a fake VERIFYING phase.
     */
    fun install(id: String): Boolean = transition(id, INSTALLABLE) {
        it.copy(
            phase = DownloadTaskPhase.INSTALLED,
            downloadedBytes = it.sizeBytes,
            errorCode = null,
        )
    }

    /**
     * Cancels any unfinished task and zeroes its bytes: cancelling is the user saying "forget this
     * download", so keeping the .part offset would offer a resume they already refused.
     */
    fun cancel(id: String): Boolean = transition(id, CANCELLABLE) {
        it.copy(
            phase = DownloadTaskPhase.CANCELLED,
            downloadedBytes = 0L,
            errorCode = null,
        )
    }

    /** Deletes one row (and, on the host, its .part file). True when there was such a task. */
    fun remove(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    /**
     * Dismisses the finished rows and returns how many went. Installed/cancelled are done with;
     * FAILED is deliberately kept because it is the row that carries the retry affordance.
     */
    fun clearFinished(): Int {
        val finished = items.count {
            it.phase == DownloadTaskPhase.INSTALLED || it.phase == DownloadTaskPhase.CANCELLED
        }
        if (finished == 0) return 0
        items.removeAll {
            it.phase == DownloadTaskPhase.INSTALLED || it.phase == DownloadTaskPhase.CANCELLED
        }
        persist()
        return finished
    }

    private fun transition(
        id: String,
        allowed: Set<DownloadTaskPhase>,
        change: (DownloadTask) -> DownloadTask,
    ): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0 || items[index].phase !in allowed) return false
        items[index] = change(items[index]).copy(updatedAt = clock())
        persist()
        return true
    }

    /**
     * A stored list with more than one active task can only be a bug (the API refuses to create it),
     * so the first one is kept and the rest are demoted to paused with their bytes intact. Returns
     * true when the list changed and the repaired state must be written back.
     */
    private fun repairDuplicateActive(): Boolean {
        val activeIndices = items.indices.filter { items[it].phase == DownloadTaskPhase.ACTIVE }
        if (activeIndices.size < 2) return false
        val now = clock()
        activeIndices.drop(1).forEach { index ->
            items[index] = items[index].copy(phase = DownloadTaskPhase.PAUSED, updatedAt = now)
        }
        return true
    }

    private fun persist() {
        store.write(json.encodeToString(DownloadQueueSnapshot(CURRENT_SCHEMA, items.toList())))
    }

    private fun validationError(task: DownloadTask): String? = when {
        task.id.isBlank() -> DownloadQueueCodes.BLANK_ID
        task.entryId.isBlank() -> DownloadQueueCodes.BLANK_ENTRY_ID
        task.fileName.isBlank() -> DownloadQueueCodes.BLANK_FILE_NAME
        task.sizeBytes < 0L -> DownloadQueueCodes.NEGATIVE_SIZE
        else -> null
    }

    private companion object {
        const val CURRENT_SCHEMA = 1

        val STARTABLE = setOf(DownloadTaskPhase.QUEUED)
        val PAUSABLE = setOf(DownloadTaskPhase.QUEUED, DownloadTaskPhase.ACTIVE)
        val RESUMABLE = setOf(DownloadTaskPhase.PAUSED, DownloadTaskPhase.FAILED)
        val VERIFIABLE = setOf(DownloadTaskPhase.ACTIVE)
        val FAILABLE = setOf(
            DownloadTaskPhase.QUEUED,
            DownloadTaskPhase.ACTIVE,
            DownloadTaskPhase.VERIFYING,
        )
        val RETRYABLE = setOf(DownloadTaskPhase.FAILED, DownloadTaskPhase.CANCELLED)
        val INSTALLABLE = setOf(DownloadTaskPhase.ACTIVE, DownloadTaskPhase.VERIFYING)
        val CANCELLABLE = setOf(
            DownloadTaskPhase.QUEUED,
            DownloadTaskPhase.ACTIVE,
            DownloadTaskPhase.PAUSED,
            DownloadTaskPhase.VERIFYING,
            DownloadTaskPhase.FAILED,
        )
        val IN_FLIGHT = setOf(
            DownloadTaskPhase.QUEUED,
            DownloadTaskPhase.ACTIVE,
            DownloadTaskPhase.PAUSED,
            DownloadTaskPhase.VERIFYING,
        )

        fun phaseRank(phase: DownloadTaskPhase): Int = when (phase) {
            DownloadTaskPhase.ACTIVE -> 0
            DownloadTaskPhase.QUEUED -> 1
            DownloadTaskPhase.PAUSED, DownloadTaskPhase.VERIFYING -> 2
            DownloadTaskPhase.FAILED, DownloadTaskPhase.CANCELLED, DownloadTaskPhase.INSTALLED -> 3
        }
    }
}

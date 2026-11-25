package app.sirrak.downloads

import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.DownloadPhase
import app.sirrak.core.models.DownloadProgress
import app.sirrak.core.models.DownloadQueue
import app.sirrak.core.models.DownloadReasons
import app.sirrak.core.models.DownloadTask
import app.sirrak.core.models.DownloadTaskPhase
import app.sirrak.core.models.EnqueueResult
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one downloader the bridge is allowed to drive.
 *
 * It is a host-owned adapter over the network engine, not the engine itself, for two reasons: the
 * bridge stays pure Kotlin (so this file's logic is JVM-testable and does not import `android.*`),
 * and the engine can be replaced without teaching the queue about it.
 *
 * [unmetered] is read at every start decision because the bridge must never begin a transfer the user
 * did not consent to: a metered connection means "wait for the UI", not "guess".
 */
interface DownloadPort {
    val progress: StateFlow<DownloadProgress?>
    val unmetered: Boolean
    fun start(entry: CatalogueEntry, allowMetered: Boolean)
    fun pause()
    fun resume()
    fun cancel()
    fun clear()
}

/**
 * The host-side owner of the persisted [DownloadQueue], and the only code that drives a single
 * [DownloadPort]. The screen and the adoption coordinator talk to this bridge, never to both sides
 * directly.
 *
 * Why a bridge exists at all: the queue records durable intent (what the user asked for, what is
 * installed, what must be resumed later), while the downloader knows how to move bytes for exactly
 * one transfer. Before this, the screen held the only copy of that state, so a second request was
 * silently dropped (G3) and process death lost everything (G5). The bridge is the seam that turns
 * "move one transfer" into "one visible row per request, one running at a time, all of it saved".
 *
 * What it does not do: verification, import into the model store and cleanup belong to the adoption
 * coordinator. The downloader's INSTALLED snapshot is ignored here; the coordinator finishes the job
 * and calls [markInstalled] (or [markFailed]) so the queue row matches reality.
 *
 * Threading: [DownloadQueue] is synchronous and not thread-safe, while the progress collector and the
 * host both call in from different dispatchers, so every read-modify-write goes through one lock. The
 * lock only protects the queue; where possible the downloader is called outside it.
 *
 * Store failures are not swallowed. A failed write reaches the caller *after* the in-memory list and
 * [tasks] flow were refreshed, so the UI shows what the queue actually holds, and the next successful
 * write persists the full snapshot again. The one exception is progress ticks from the collector:
 * those are best-effort, because a transient disk error must not kill the only observer of a running
 * transfer.
 */
class DownloadBridge(
    private val store: QueueStore,
    private val port: DownloadPort,
    private val catalogue: ModelCatalogueProvider,
    private val clock: () -> Long,
    scope: CoroutineScope,
) {

    private val queue = DownloadQueue(store, clock)
    private val lock = Any()
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val tasksFlow = mutableTasks.asStateFlow()

    init {
        scope.launch {
            port.progress.collect { progress ->
                if (progress != null) {
                    runCatching { onProgress(progress) }
                }
            }
        }
    }

    /**
     * The rows to render, refreshed after every mutation: running first, then waiting, then
     * paused/verifying, then finished, stable by creation time within a phase. Same order as
     * [DownloadQueue.tasks], so the UI never re-implements it.
     */
    fun tasks(): StateFlow<List<DownloadTask>> = tasksFlow

    /** The running row, or null when the queue is idle. */
    fun active(): DownloadTask? = mutate { queue.active() }

    /**
     * Reads the saved queue and repairs what process death can leave behind.
     *
     * A persisted ACTIVE row means "was running when the process died"; there is no transfer behind
     * it any more, and docs/31 forbids silently resuming bytes the user did not re-authorise. So it
     * becomes PAUSED with its byte count intact: the next start continues from the partial file, but
     * only after a tap. A failed repair write is tolerated the same way the queue tolerates it — the
     * row is PAUSED in memory and the next start tries to save again — because a broken disk must not
     * crash app startup. [problems] carries the queue's stable codes (corrupt snapshot, unreadable
     * store, duplicate active rows) so the host can log them without this class owning a logger.
     */
    fun load(problems: (List<String>) -> Unit) {
        val codes = mutate {
            val codes = queue.load()
            queue.tasks()
                .filter { it.phase == DownloadTaskPhase.ACTIVE }
                .forEach { runCatching { queue.pause(it.id) } }
            codes
        }
        problems(codes)
    }

    /**
     * Adds [entry] as one visible row and persists it before returning. The task id is the catalogue
     * id, so one request per entry maps to one row; a second request returns the existing row instead
     * of a second transfer nobody can tell apart (defect G3).
     *
     * A projector is a second file of the same entry, so its caller passes a synthetic entry with a
     * distinct id (for example `"<entry.id>:mmproj"`), the projector's file name and size; the bridge
     * then uses that id as the task id and rebuilds the downloader entry from the row if the
     * catalogue does not list it.
     *
     * On a successful add the bridge pumps: on Wi-Fi the transfer starts without another tap, on a
     * metered connection the row stays QUEUED so the UI can ask for consent first.
     */
    fun enqueue(entry: CatalogueEntry): EnqueueResult {
        val result = mutate {
            val now = clock()
            queue.enqueue(
                DownloadTask(
                    id = entry.id,
                    entryId = entry.id,
                    displayName = entry.displayName,
                    repo = entry.repo,
                    fileName = entry.fileName,
                    sizeBytes = entry.sizeBytes,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        if (result is EnqueueResult.Added) pump()
        return result
    }

    /**
     * Starts one QUEUED task after an explicit user action. [allowMetered] is that consent: without
     * it a metered connection refuses the start and the row stays QUEUED.
     *
     * Refused (false) when another task is already running, when the connection is metered and
     * consent was not given, or when the id is not a QUEUED row. A port that refuses to start, or a
     * store that fails while the row is handed over, is recorded as an IO failure so the row never
     * sits ACTIVE with no transfer behind it.
     */
    fun start(id: String, allowMetered: Boolean): Boolean {
        val entry = mutate {
            if (!port.unmetered && !allowMetered) return false
            if (queue.active() != null) return false
            val task = queue.tasks().firstOrNull { it.id == id } ?: return false
            if (!startInQueue(id)) return false
            entryFor(task)
        }
        val started = runCatching { port.start(entry, allowMetered) }
        if (started.isFailure) {
            mutate { queue.fail(id, DownloadReasons.IO) }
            return false
        }
        return true
    }

    /**
     * Starts the next QUEUED row if, and only if, the queue is idle, the downloader is not busy with
     * a transfer the bridge has not acknowledged yet, and the connection is unmetered. This is the
     * only automatic start in the bridge; every other start needs [start]'s explicit consent.
     */
    fun pump() {
        val next = mutate {
            if (queue.active() != null) return
            if (!port.unmetered) return
            if (portRunning()) return
            queue.tasks().firstOrNull { it.phase == DownloadTaskPhase.QUEUED } ?: return
        }
        start(next.id, allowMetered = false)
    }

    /** Pauses a running task (or parks a queued one) and keeps its bytes for a later resume. */
    fun pause(id: String): Boolean = mutate {
        val wasActive = queue.active()?.id == id
        val paused = queue.pause(id)
        if (paused && wasActive) port.pause()
        paused
    }

    /** Re-queues a paused task; on Wi-Fi it may start again immediately, metered it waits. */
    fun resume(id: String): Boolean {
        val resumed = mutate { queue.resume(id) }
        if (resumed) pump()
        return resumed
    }

    /** Cancels a task, wipes its resume point and hands the slot to the next QUEUED row. */
    fun cancel(id: String): Boolean {
        val cancelled = mutate {
            val wasActive = queue.active()?.id == id
            val ok = queue.cancel(id)
            if (ok && wasActive) port.cancel()
            ok
        }
        if (cancelled) pump()
        return cancelled
    }

    /** Re-queues a failed or cancelled task; on Wi-Fi it may start again immediately, metered it waits. */
    fun retry(id: String): Boolean {
        val retried = mutate { queue.retry(id) }
        if (retried) pump()
        return retried
    }

    /** Removes a row; removing the running one also stops its transfer. */
    fun remove(id: String): Boolean {
        val removed = mutate {
            val wasActive = queue.active()?.id == id
            val ok = queue.remove(id)
            if (ok && wasActive) port.cancel()
            ok
        }
        if (removed) pump()
        return removed
    }

    /** Drops INSTALLED and CANCELLED rows; returns how many were dropped. */
    fun clearFinished(): Int = mutate { queue.clearFinished() }

    /**
     * Records that the adoption coordinator imported the file. This is the only place a row becomes
     * INSTALLED; the downloader's own INSTALLED snapshot is not enough, because the import can still
     * fail. The freed slot is pumped, so a queued download can continue on Wi-Fi.
     */
    fun markInstalled(id: String): Boolean {
        val installed = mutate { queue.install(id) }
        if (installed) pump()
        return installed
    }

    /**
     * Records that the adoption coordinator could not finish a verified download. [code] is a stable
     * lowercase reason code (for example `import_failed`), never user-facing text. The queue is not
     * pumped here: after a failure the user should see the row and decide whether to retry, instead
     * of the queue walking through every download failing.
     */
    fun markFailed(id: String, code: String): Boolean = mutate { queue.fail(id, code) }

    private fun onProgress(progress: DownloadProgress) {
        mutate { handleProgress(progress) }
    }

    private fun handleProgress(progress: DownloadProgress) {
        when (progress.phase) {
            DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING ->
                taskFor(progress.entryId, DownloadTaskPhase.ACTIVE)?.let {
                    queue.updateProgress(it.id, progress.bytesDownloaded)
                }

            DownloadPhase.VERIFYING -> taskFor(progress.entryId, DownloadTaskPhase.ACTIVE)?.let {
                queue.beginVerify(it.id)
            }

            DownloadPhase.PAUSED -> taskFor(
                progress.entryId,
                DownloadTaskPhase.ACTIVE,
                DownloadTaskPhase.QUEUED,
            )?.let { queue.pause(it.id) }

            DownloadPhase.FAILED -> taskFor(
                progress.entryId,
                DownloadTaskPhase.ACTIVE,
                DownloadTaskPhase.VERIFYING,
                DownloadTaskPhase.QUEUED,
            )?.let { queue.fail(it.id, progress.reason ?: DownloadReasons.IO) }

            // INSTALLED is the coordinator's signal; it imports the file and reports back through
            // markInstalled/markFailed. IDLE is also not a row: a user cancel is an action that calls
            // cancel(id), not a phase the downloader reports.
            DownloadPhase.INSTALLED, DownloadPhase.IDLE -> Unit
        }
    }

    private fun taskFor(entryId: String, vararg phases: DownloadTaskPhase): DownloadTask? =
        queue.tasks().firstOrNull { it.entryId == entryId && it.phase in phases }

    /** [DownloadQueue.start], but a failed save does not leave an ACTIVE row with nothing behind it. */
    private fun startInQueue(id: String): Boolean = try {
        queue.start(id)
    } catch (error: Exception) {
        // The row is ACTIVE in memory but the save failed; park it as a failure before rethrowing.
        runCatching { queue.fail(id, DownloadReasons.IO) }
        throw error
    }

    /**
     * Rebuilds the downloader's entry from the row. Curated entries are looked up so their checksum
     * survives to verification; a synthetic projector row is not in the catalogue, and the row
     * already carries everything the downloader needs (repo, file name, size), so it is rebuilt as
     * is. Honest limit: such a synthesized entry carries no checksum, so only its size is verified.
     */
    private fun entryFor(task: DownloadTask): CatalogueEntry {
        val curated = runCatching {
            catalogue.catalogue().models.firstOrNull { it.id == task.entryId }
        }.getOrNull()
        return curated ?: CatalogueEntry(
            id = task.entryId,
            displayName = task.displayName,
            repo = task.repo,
            fileName = task.fileName,
            sizeBytes = task.sizeBytes,
        )
    }

    private fun portRunning(): Boolean = when (port.progress.value?.phase) {
        DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING -> true
        else -> false
    }

    /**
     * One serialized read-modify-write of the queue. The refresh lives in `finally`: when a store
     * write throws, the caller still gets the exception, but the flow never shows a stale list, so a
     * failed save cannot turn into a UI that contradicts memory.
     */
    private inline fun <T> mutate(block: () -> T): T = synchronized(lock) {
        try {
            block()
        } finally {
            mutableTasks.value = queue.tasks()
        }
    }
}

package app.sirrak.feature.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.DeviceCapacity
import app.sirrak.core.models.FitVerdict
import app.sirrak.core.models.GgufMetadata
import app.sirrak.core.models.GgufProbe
import app.sirrak.core.models.GgufProbeResult
import app.sirrak.core.models.HfAccessPolicy
import app.sirrak.core.models.HfCallKind
import app.sirrak.core.models.HfClient
import app.sirrak.core.models.HfCodes
import app.sirrak.core.models.HfModelSummary
import app.sirrak.core.models.HfRepoFile
import app.sirrak.core.models.HfRepoInfo
import app.sirrak.core.models.HfResult
import app.sirrak.core.models.ModelFitEngine

/** What the search page is doing; each phase has one honest body instead of a spinner over stale rows. */
enum class HfSearchPhase { IDLE, LOADING, LOADED, LOADING_MORE, FAILED, BLOCKED }

/** What the repo page is doing: loading, listing, or one blocking state with its own fixing action. */
enum class HfRepoPhase { LOADING, LOADED, FAILED, BLOCKED, TOKEN_REQUIRED }

/** The capped header probe, a separate state because it can fail after the file list loaded. */
enum class HfProbePhase { IDLE, REQUESTING, DONE, FAILED, BLOCKED }

/** What happened when the download button was pressed; the page maps each to one localized sentence. */
enum class HfDownloadNotice {
    /** The row is in the persisted queue; on Wi-Fi it starts on its own, on mobile it waits. */
    QUEUED,

    /** Consent was given for a metered transfer and it started now. */
    STARTED,

    /** The same file was already in the list; nothing new was requested. */
    DUPLICATE,

    /** The file is already installed, so no second copy is wanted. */
    INSTALLED,

    /** The queue refused the row (its store failed); nothing will download. */
    REJECTED,

    /** The metered prompt was declined, so the queued row waits for an unmetered network. */
    DECLINED_METERED,

    /** Another transfer already owns the single download slot; this row waits its turn. */
    WAITING_FOR_OTHER,

    /** Offline-only mode is on; nothing was queued. */
    OFFLINE,

    /** The queue accepted the row but the engine refused to start it. */
    START_FAILED,
}

/**
 * The remembered search page, owned by the plugin rather than the composition. Typing, results and
 * the cursor survive opening a repo and coming back: a back gesture must not spend the anonymous API
 * budget on the same query again. The page debounces; this class only runs the one call.
 */
class HfSearchSession(private val client: HfClient) {

    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<HfModelSummary>>(emptyList())
        private set
    var nextCursor by mutableStateOf<String?>(null)
        private set
    var phase by mutableStateOf(HfSearchPhase.IDLE)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var blockedReason by mutableStateOf<String?>(null)
        private set
    var pagingFailure by mutableStateOf<String?>(null)
        private set

    private var lastQuery: String? = null

    fun onQueryChange(value: String) {
        query = value
    }

    /**
     * Called 400 ms after the last keystroke. A blank field clears the page without a call; a query
     * already attempted is not repeated, so returning to the page costs nothing.
     */
    suspend fun onDebouncedQuery() {
        val text = query.trim()
        if (text.isEmpty()) {
            clearResults()
            return
        }
        if (text == lastQuery) return
        runSearch(text)
    }

    /** Explicit retry after a failure; the only path that repeats the same query. */
    suspend fun retry() {
        val text = query.trim()
        if (text.isNotEmpty()) runSearch(text)
    }

    /** Fetches the next cursor page and appends it; the old page stays visible on failure. */
    suspend fun loadMore() {
        val cursor = nextCursor ?: return
        if (phase != HfSearchPhase.LOADED) return
        phase = HfSearchPhase.LOADING_MORE
        pagingFailure = null
        when (val result = client.page(cursor)) {
            is HfResult.Success -> {
                results = results + result.value.items
                nextCursor = result.value.nextCursorUrl
                phase = HfSearchPhase.LOADED
            }
            is HfResult.Blocked -> {
                pagingFailure = result.code
                phase = HfSearchPhase.LOADED
            }
            is HfResult.Failure -> {
                pagingFailure = result.code
                phase = HfSearchPhase.LOADED
            }
        }
    }

    /** Offline-only was just switched off by the page's fixing action; retry the same query. */
    suspend fun retryAfterOffline() {
        val text = query.trim()
        if (text.isEmpty()) {
            clearResults()
        } else {
            runSearch(text)
        }
    }

    private suspend fun runSearch(text: String) {
        lastQuery = text
        phase = HfSearchPhase.LOADING
        failure = null
        blockedReason = null
        pagingFailure = null
        when (val result = client.search(text)) {
            is HfResult.Success -> {
                results = result.value.items
                nextCursor = result.value.nextCursorUrl
                phase = HfSearchPhase.LOADED
            }
            is HfResult.Blocked -> {
                results = emptyList()
                nextCursor = null
                blockedReason = result.reason
                phase = HfSearchPhase.BLOCKED
            }
            is HfResult.Failure -> {
                results = emptyList()
                nextCursor = null
                failure = result.code
                phase = HfSearchPhase.FAILED
            }
        }
    }

    private fun clearResults() {
        results = emptyList()
        nextCursor = null
        lastQuery = null
        phase = HfSearchPhase.IDLE
        failure = null
        blockedReason = null
        pagingFailure = null
    }
}

/**
 * The repo page's remembered state: the card, the GGUF files, the selected file, one capped probe,
 * the arithmetic verdict and the download hand-off. It owns no Android type and no UI: the page can
 * be re-composed freely while the numbers stay put, and the plugin can build it from host lambdas.
 *
 * Every number shown as a decision comes from [ModelFitEngine]; this class never decides on its own
 * that a model runs. It also never downloads: it enqueues one synthetic [CatalogueEntry] through the
 * host lambda and asks the host to start it, so the persisted queue remains the one owner of bytes.
 */
class HfRepoSession(
    val repoId: String,
    private val client: HfClient,
    private val probe: GgufProbe,
    private val fitEngine: ModelFitEngine,
    private val policy: HfAccessPolicy,
    private val tokenProvider: () -> String?,
    private val capacity: () -> DeviceCapacity,
    private val enqueue: (CatalogueEntry) -> HfEnqueueOutcome,
    private val start: (String, Boolean) -> Boolean,
) {

    var phase by mutableStateOf(HfRepoPhase.LOADING)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var blockedReason by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<HfRepoInfo?>(null)
        private set
    var files by mutableStateOf<List<HfRepoFile>>(emptyList())
        private set

    /** Path of the file the user selected; null until the list loads (the first file is selected). */
    var selectedPath by mutableStateOf<String?>(null)
        private set
    var probePhase by mutableStateOf(HfProbePhase.IDLE)
        private set
    var probeFailure by mutableStateOf<String?>(null)
        private set
    var probeBlockedReason by mutableStateOf<String?>(null)
        private set

    /** The header the probe actually read; null when no probe ran for the selected file. */
    var metadata by mutableStateOf<GgufMetadata?>(null)
        private set
    var probeBytesRead by mutableStateOf(0)
        private set
    var probeRanged by mutableStateOf(false)
        private set

    /** The arithmetic for the selected file, refreshed on select and after a probe. */
    var verdict by mutableStateOf<FitVerdict?>(null)
        private set
    var notice by mutableStateOf<HfDownloadNotice?>(null)
        private set

    /** The path [metadata] belongs to, so switching files cannot borrow another file's header. */
    private var probedPath: String? = null

    fun selectedFile(): HfRepoFile? = files.firstOrNull { it.path == selectedPath }

    fun decision(): HfDownloadDecision? = verdict?.let(HfBrowseLogic::downloadDecision)

    fun select(file: HfRepoFile) {
        if (selectedPath == file.path) return
        selectedPath = file.path
        notice = null
        probePhase = HfProbePhase.IDLE
        probeFailure = null
        probeBlockedReason = null
        metadata = null
        probedPath = null
        probeBytesRead = 0
        probeRanged = false
        refreshVerdict()
    }

    /** Loads the card and the GGUF files; a gated repo with no token lands in TOKEN_REQUIRED. */
    suspend fun load() {
        phase = HfRepoPhase.LOADING
        failure = null
        blockedReason = null
        val blocked = policy.blockedReason()
        if (blocked != null) {
            blockedReason = blocked
            phase = HfRepoPhase.BLOCKED
            return
        }
        val token = tokenProvider()
        when (val result = client.files(repoId, token)) {
            is HfResult.Success -> files = HfBrowseLogic.ggufFiles(result.value)
            is HfResult.Blocked -> {
                blockedReason = result.reason
                phase = HfRepoPhase.BLOCKED
                return
            }
            is HfResult.Failure -> {
                if (result.code == HfCodes.GATED) {
                    phase = HfRepoPhase.TOKEN_REQUIRED
                } else {
                    failure = result.code
                    phase = HfRepoPhase.FAILED
                }
                return
            }
        }
        when (val card = client.detail(repoId, token)) {
            is HfResult.Success -> info = card.value
            else -> info = null
        }
        phase = HfRepoPhase.LOADED
        selectedPath = null
        files.firstOrNull()?.let(::select)
    }

    /** Retry after the token was saved or offline-only was switched off. */
    suspend fun reload() = load()

    /** One capped probe of the selected file, after the policy's probe gate (metered prompt included). */
    suspend fun probeSelected() {
        val file = selectedFile() ?: return
        probeFailure = null
        probeBlockedReason = null
        probePhase = HfProbePhase.REQUESTING
        when (val gate = client.gate(HfCallKind.PROBE)) {
            is HfResult.Success -> Unit
            is HfResult.Blocked -> {
                probeBlockedReason = gate.reason
                probePhase = HfProbePhase.BLOCKED
                return
            }
            is HfResult.Failure -> {
                probeFailure = gate.code
                probePhase = HfProbePhase.FAILED
                return
            }
        }
        when (val result = probe.probe(client.probeUrl(repoId, file.path), tokenProvider())) {
            is GgufProbeResult.Success -> {
                metadata = result.metadata
                probedPath = file.path
                probeBytesRead = result.bytesRead
                probeRanged = result.ranged
                probePhase = HfProbePhase.DONE
                refreshVerdict()
            }
            is GgufProbeResult.Failure -> {
                probeFailure = result.code
                probePhase = HfProbePhase.FAILED
            }
        }
    }

    /** Clears the download notice when the page shows a new action. */
    fun clearNotice() {
        notice = null
    }

    /**
     * The download button's action. It refuses a blocking verdict, asks the metered question only
     * when the network is metered, and hands exactly one synthetic entry to the persisted queue.
     * A declined metered prompt leaves the queued row waiting for Wi-Fi instead of dropping it.
     */
    suspend fun downloadNow() {
        val file = selectedFile() ?: return
        notice = null
        if (decision() == HfDownloadDecision.BLOCKED) return
        val offline = policy.blockedReason()
        if (offline != null) {
            blockedReason = offline
            notice = HfDownloadNotice.OFFLINE
            return
        }
        val entry = HfBrowseLogic.syntheticEntry(
            repoId = repoId,
            file = file,
            info = info,
            metadata = if (probedPath == file.path) metadata else null,
        )
        when (enqueue(entry)) {
            HfEnqueueOutcome.ADDED -> {
                if (policy.isUnmetered()) {
                    notice = HfDownloadNotice.QUEUED
                    return
                }
                when (val gate = client.gate(HfCallKind.DOWNLOAD)) {
                    is HfResult.Success -> {
                        notice = if (start(entry.id, true)) {
                            HfDownloadNotice.STARTED
                        } else {
                            HfDownloadNotice.WAITING_FOR_OTHER
                        }
                    }
                    is HfResult.Blocked -> {
                        notice = if (gate.reason == HfCodes.METERED_DECLINED) {
                            HfDownloadNotice.DECLINED_METERED
                        } else {
                            HfDownloadNotice.OFFLINE
                        }
                    }
                    is HfResult.Failure -> notice = HfDownloadNotice.START_FAILED
                }
            }
            HfEnqueueOutcome.DUPLICATE -> notice = HfDownloadNotice.DUPLICATE
            HfEnqueueOutcome.INSTALLED -> notice = HfDownloadNotice.INSTALLED
            HfEnqueueOutcome.REJECTED -> notice = HfDownloadNotice.REJECTED
        }
    }

    /** Re-runs the fit arithmetic for the selected file, using the capacity snapshot at this moment. */
    fun refreshVerdict() {
        val file = selectedFile()
        if (file == null) {
            verdict = null
            return
        }
        verdict = fitEngine.evaluate(
            fileBytes = file.sizeBytes,
            architecture = if (probedPath == file.path) metadata?.architecture else null,
            requestedContext = ModelFitEngine.DEFAULT_CONTEXT,
            capacity = capacity(),
        )
    }
}

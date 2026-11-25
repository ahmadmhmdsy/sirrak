package app.sirrak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.PillTone
import app.sirrak.core.design.ReadingColumn
import app.sirrak.core.design.ScreenHeader
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.design.StatusPill
import app.sirrak.core.models.AndroidDeviceProbe
import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.DeviceCapacity
import app.sirrak.core.models.DownloadReasons
import app.sirrak.core.models.DownloadTask
import app.sirrak.core.models.DownloadTaskPhase
import app.sirrak.core.models.EnqueueResult
import app.sirrak.core.models.FitReasons
import app.sirrak.core.models.FitStatus
import app.sirrak.core.models.FitVerdict
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelFitEngine
import app.sirrak.core.rag.RagService
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.PageIds
import app.sirrak.downloads.DownloadBridge
import app.sirrak.media.MediaLibrary
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * The Library is the app's device shelf, split into three segments the review asked for:
 * النماذج (models), التنزيلات (downloads) and الوسائط (media). The models segment is built from the
 * store and the catalogue, never from the fake reference cards; every card states an honest fit
 * verdict computed on this device right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    context: Context,
    store: ModelStore,
    engine: InferenceEngine,
    catalogue: ModelCatalogueProvider,
    bridge: DownloadBridge,
    adoption: ModelAdoptionCoordinator,
    fitEngine: ModelFitEngine,
    probe: AndroidDeviceProbe,
    rag: RagService? = null,
    navigator: AppNavigator,
    hasHuggingFaceSearch: Boolean = false,
    // P5: the media shelf. Nullable so a composition that cannot build it (or a preview) still shows
    // an honest fallback instead of a screen that claims a feature it does not have.
    media: MediaLibrary? = null,
) {
    val installed by store.models.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    // The persisted queue, not the downloader's transient snapshot, is what the shelf renders: a row
    // survives process death and a second request shows up as a row instead of being dropped.
    val tasks by bridge.tasks().collectAsStateWithLifecycle()
    val adoptionEvent by adoption.events.collectAsStateWithLifecycle()
    // Memory changes while the screen is open; re-measuring when the queue changes phase is cheap and
    // keeps the verdict honest instead of freezing the boot-time snapshot.
    val queueKey = tasks.map { task -> task.id + ":" + task.phase }
    val capacity: DeviceCapacity = remember(queueKey) { probe.capacity() }
    val entries = remember(catalogue) { catalogue.catalogue().models }

    var segment by rememberSaveable { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }
    var meteredTask by remember { mutableStateOf<DownloadTask?>(null) }
    var tightEntry by remember { mutableStateOf<CatalogueEntry?>(null) }
    var enqueueProblem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importFailed = false
        scope.launch {
            val name = queryDisplayName(context, uri) ?: "model.gguf"
            val result = store.import(name) {
                context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            }
            importing = false
            importFailed = result.isFailure
        }
    }

    fun start(entry: CatalogueEntry) {
        enqueueProblem = null
        // Metered is a question, never a silent decision (approved decision 3). The bridge only
        // auto-pumps an unmetered row, so queuing first keeps the request visible either way.
        if (isMetered(context)) {
            when (val result = bridge.enqueue(entry)) {
                is EnqueueResult.Added -> meteredTask = result.task
                is EnqueueResult.Duplicate -> meteredTask = result.task
                is EnqueueResult.AlreadyInstalled -> Unit
                is EnqueueResult.Invalid -> enqueueProblem = result.code
            }
            return
        }
        val verdict = verdictFor(entry, fitEngine, capacity)
        if (verdict.status == FitStatus.TIGHT) {
            tightEntry = entry
            return
        }
        when (val result = bridge.enqueue(entry)) {
            is EnqueueResult.Invalid -> enqueueProblem = result.code
            else -> Unit
        }
    }

    fun pauseTask() {
        bridge.active()?.let { task -> bridge.pause(task.id) }
    }

    fun resumeTask() {
        bridge.active()?.let { task -> bridge.resume(task.id) }
    }

    fun cancelTask() {
        bridge.active()?.let { task -> bridge.cancel(task.id) }
    }

    fun startQueued(task: DownloadTask) {
        enqueueProblem = null
        if (isMetered(context)) {
            meteredTask = task
            return
        }
        bridge.start(task.id, allowMetered = false)
    }

    ReadingColumn {
        ScreenHeader(title = stringResource(R.string.library_title))
        Column(Modifier.padding(horizontal = 20.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    R.string.library_segment_models,
                    R.string.library_segment_downloads,
                    R.string.library_segment_media,
                ).forEachIndexed { index, labelRes ->
                    SegmentedButton(
                        selected = segment == index,
                        onClick = { segment = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            adoptionEvent?.let { event ->
                AdoptionBanner(event = event, onDismiss = adoption::dismiss)
                Spacer(Modifier.height(12.dp))
            }
            enqueueProblem?.let { code ->
                SettingsNote(stringResource(R.string.library_queue_rejected, code))
                Spacer(Modifier.height(12.dp))
            }
            when (segment) {
                0 -> ModelsSegment(
                    installed = installed,
                    selected = selected,
                    status = status,
                    entries = entries,
                    tasks = tasks,
                    capacity = capacity,
                    fitEngine = fitEngine,
                    importing = importing,
                    importFailed = importFailed,
                    onImport = { picker.launch(arrayOf("*/*")) },
                    onOpenModel = { model -> navigator.push(PageIds.MODEL_DETAIL, model.id) },
                    onDelete = { model -> pendingDelete = model },
                    onStart = ::start,
                    onPauseTask = { id -> bridge.pause(id) },
                    onResumeTask = { id -> bridge.resume(id) },
                    onCancelTask = { id -> bridge.cancel(id) },
                    onRetryTask = { id -> bridge.retry(id) },
                    onRemoveTask = { id -> bridge.remove(id) },
                    hasHuggingFaceSearch = hasHuggingFaceSearch,
                    onOpenHuggingFace = { navigator.push(PageIds.HF_SEARCH) },
                )
                1 -> DownloadsSegment(
                    tasks = tasks,
                    installed = installed,
                    onStartTask = ::startQueued,
                    onPauseTask = { id -> bridge.pause(id) },
                    onResumeTask = { id -> bridge.resume(id) },
                    onCancelTask = { id -> bridge.cancel(id) },
                    onRetryTask = { id -> bridge.retry(id) },
                    onRemoveTask = { id -> bridge.remove(id) },
                    onDelete = { model -> pendingDelete = model },
                )
                else -> MediaSegment(media, rag)
            }
        }
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.library_delete_title, model.displayName)) },
            text = { Text(stringResource(R.string.library_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(model)
                    pendingDelete = null
                }) { Text(stringResource(R.string.library_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.library_delete_cancel))
                }
            },
        )
    }
    meteredTask?.let { task ->
        AlertDialog(
            onDismissRequest = { meteredTask = null },
            title = { Text(stringResource(R.string.library_metered_title)) },
            text = { Text(stringResource(R.string.library_metered_body, formatBytes(task.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    bridge.start(task.id, allowMetered = true)
                    meteredTask = null
                }) { Text(stringResource(R.string.library_metered_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { meteredTask = null }) {
                    Text(stringResource(R.string.library_metered_cancel))
                }
            },
        )
    }
    tightEntry?.let { entry ->
        val verdict = verdictFor(entry, fitEngine, capacity)
        AlertDialog(
            onDismissRequest = { tightEntry = null },
            title = { Text(stringResource(R.string.library_fit_tight_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.library_fit_tight_confirm_body,
                        verdict.footprint.totalBytes / ModelFitEngine.MB,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bridge.enqueue(entry)
                    tightEntry = null
                }) { Text(stringResource(R.string.library_fit_tight_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { tightEntry = null }) {
                    Text(stringResource(R.string.library_fit_cancel))
                }
            },
        )
    }
}

@Composable
private fun ModelsSegment(
    installed: List<InstalledModel>,
    selected: InstalledModel?,
    status: EngineStatus,
    entries: List<CatalogueEntry>,
    tasks: List<DownloadTask>,
    capacity: DeviceCapacity,
    fitEngine: ModelFitEngine,
    importing: Boolean,
    importFailed: Boolean,
    onImport: () -> Unit,
    onOpenModel: (InstalledModel) -> Unit,
    onDelete: (InstalledModel) -> Unit,
    onStart: (CatalogueEntry) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    hasHuggingFaceSearch: Boolean,
    onOpenHuggingFace: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.library_engine_title)) {
        SettingsRow(
            label = selected?.displayName ?: stringResource(R.string.engine_no_model),
            value = engineStatusLabel(status),
        )
        if (selected != null) {
            SettingsDivider()
            SettingsRow(label = stringResource(R.string.model_disk), value = selected.sizeLabel)
        }
        SettingsDivider()
        SettingsRow(label = stringResource(R.string.library_import_gguf), onClick = onImport)
        when {
            importing -> SettingsNote(stringResource(R.string.engine_importing))
            importFailed -> SettingsNote(stringResource(R.string.library_import_failed))
            selected == null -> SettingsNote(stringResource(R.string.library_engine_none))
        }
    }
    Spacer(Modifier.height(20.dp))

    SectionLabel(stringResource(R.string.library_on_device_title))
    if (installed.isEmpty()) {
        EmptyCard(stringResource(R.string.library_on_device_empty))
    } else {
        installed.forEachIndexed { index, model ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            InstalledModelCard(
                model = model,
                selected = selected?.path == model.path,
                onOpen = { onOpenModel(model) },
                onDelete = { onDelete(model) },
            )
        }
    }
    Spacer(Modifier.height(20.dp))

    SectionLabel(stringResource(R.string.library_available_title))
    val visible = entries.filter { entry ->
        installed.none { model -> model.displayName == entry.displayName || model.path.endsWith(entry.fileName) }
    }
    if (visible.isEmpty()) {
        EmptyCard(stringResource(R.string.library_available_empty))
    } else {
        visible.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            // A request is a row from the moment it is queued, so a second tap on the same model can
            // never silently replace a running transfer.
            val task = tasks.firstOrNull { it.entryId == entry.id }
            CatalogueCard(
                entry = entry,
                verdict = verdictFor(entry, fitEngine, capacity),
                task = task,
                onStart = { onStart(entry) },
                onPause = { task?.let { onPauseTask(it.id) } },
                onResume = { task?.let { onResumeTask(it.id) } },
                onCancel = { task?.let { onCancelTask(it.id) } },
                onRetry = { task?.let { onRetryTask(it.id) } },
                onRemove = { task?.let { onRemoveTask(it.id) } },
            )
        }
    }
    if (hasHuggingFaceSearch) {
        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.library_web_title))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.library_web_hf),
                    onClick = onOpenHuggingFace,
                )
                SettingsNote(stringResource(R.string.library_web_hf_note))
            }
        }
    }
}

@Composable
private fun DownloadsSegment(
    tasks: List<DownloadTask>,
    installed: List<InstalledModel>,
    onStartTask: (DownloadTask) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onDelete: (InstalledModel) -> Unit,
) {
    // FINISHED rows are already listed under "on device"; the queue segment shows only work that is
    // still the user's to do, newest request last.
    val pending = tasks.filter { it.phase != DownloadTaskPhase.INSTALLED }
    SectionLabel(stringResource(R.string.library_downloads_active_title))
    if (pending.isEmpty()) {
        EmptyCard(stringResource(R.string.library_downloads_empty))
    } else {
        pending.forEachIndexed { index, task ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            DownloadTaskCard(
                task = task,
                onStart = { onStartTask(task) },
                onPause = { onPauseTask(task.id) },
                onResume = { onResumeTask(task.id) },
                onCancel = { onCancelTask(task.id) },
                onRetry = { onRetryTask(task.id) },
                onRemove = { onRemoveTask(task.id) },
            )
        }
    }
    SettingsNote(stringResource(R.string.library_download_note))
    Spacer(Modifier.height(16.dp))

    SectionLabel(stringResource(R.string.library_on_device_title))
    if (installed.isEmpty()) {
        EmptyCard(stringResource(R.string.library_on_device_empty))
    } else {
        installed.forEachIndexed { index, model ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            InstalledModelCard(
                model = model,
                selected = false,
                onOpen = {},
                onDelete = { onDelete(model) },
            )
        }
    }
}

@Composable
private fun MediaSegment(media: MediaLibrary?, rag: RagService?) {
    if (media != null) {
        // The real P5 shelf: policy, the decision sheet, the three import rows and the shelves, all
        // backed by the app-private media store. Documents imported through chat stay listed below so
        // one RAG library does not appear as two disconnected ones.
        MediaLibrarySection(media = media)
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.library_media_documents))
        if (rag != null) {
            DocumentsLibrarySection(rag = rag)
        } else {
            EmptyCard(stringResource(R.string.library_media_empty))
        }
        SettingsNote(stringResource(R.string.media_library_documents_note))
        return
    }
    SectionLabel(stringResource(R.string.library_media_documents))
    if (rag != null) {
        DocumentsLibrarySection(rag = rag)
    } else {
        EmptyCard(stringResource(R.string.library_media_empty))
    }
    Spacer(Modifier.height(16.dp))
    // Without a media library the shelf still says what is true instead of showing empty controls.
    SettingsNote(stringResource(R.string.library_media_soon))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyCard(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun InstalledModelCard(
    model: InstalledModel,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            SettingsRow(
                label = model.displayName,
                value = if (selected) stringResource(R.string.engine_selected) + " · " + model.sizeLabel else model.sizeLabel,
                onClick = onOpen,
                trailing = {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                },
            )
        }
    }
}

@Composable
private fun CatalogueCard(
    entry: CatalogueEntry,
    verdict: FitVerdict,
    task: DownloadTask?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.library_model_meta,
                            entry.parameterLabel,
                            entry.quantization,
                            entry.sizeLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = fitLabel(verdict.status), tone = fitTone(verdict.status))
            }
            verdict.reasons.firstOrNull()?.let { reason ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = fitReasonText(reason) ?: stringResource(R.string.library_fit_unknown_reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (task == null) {
                Button(
                    onClick = onStart,
                    enabled = !verdict.blocked,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.library_download)) }
            } else {
                DownloadTaskBody(
                    task = task,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onRemove = onRemove,
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = taskPhaseLabel(task.phase), tone = taskTone(task.phase))
            }
            Spacer(Modifier.height(8.dp))
            DownloadTaskBody(
                task = task,
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
                onRemove = onRemove,
            )
        }
    }
}

/**
 * One queue row's phase-specific body, shared by the model card and the downloads list so the two
 * surfaces can never disagree about what a task is doing.
 */
@Composable
private fun DownloadTaskBody(
    task: DownloadTask,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    when (task.phase) {
        DownloadTaskPhase.QUEUED -> {
            ProgressLine(stringResource(R.string.library_download_waiting))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onStart) { Text(stringResource(R.string.library_queue_start)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.library_download_cancel)) }
            }
        }
        DownloadTaskPhase.ACTIVE, DownloadTaskPhase.PAUSED -> {
            if (task.sizeBytes > 0L) {
                LinearProgressIndicator(progress = { task.fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = stringResource(
                    R.string.library_task_progress,
                    (task.fraction * 100f).toInt(),
                    formatBytes(task.downloadedBytes),
                    formatBytes(task.sizeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.phase == DownloadTaskPhase.ACTIVE) {
                    TextButton(onClick = onPause) { Text(stringResource(R.string.library_download_pause)) }
                } else {
                    TextButton(onClick = onResume) { Text(stringResource(R.string.library_download_resume)) }
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.library_download_cancel)) }
            }
        }
        DownloadTaskPhase.VERIFYING -> ProgressLine(stringResource(R.string.library_download_verifying))
        DownloadTaskPhase.INSTALLED -> ProgressLine(stringResource(R.string.library_download_ready))
        DownloadTaskPhase.FAILED -> {
            Text(
                text = stringResource(
                    R.string.library_download_failed,
                    downloadReasonLabel(task.errorCode),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.library_queue_retry)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.library_queue_remove)) }
            }
        }
        DownloadTaskPhase.CANCELLED -> {
            ProgressLine(stringResource(R.string.library_task_cancelled))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.library_queue_retry)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.library_queue_remove)) }
            }
        }
    }
}

@Composable
private fun ProgressLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun taskPhaseLabel(phase: DownloadTaskPhase): String = stringResource(
    when (phase) {
        DownloadTaskPhase.QUEUED -> R.string.library_download_waiting
        DownloadTaskPhase.ACTIVE -> R.string.library_task_active
        DownloadTaskPhase.PAUSED -> R.string.library_task_paused
        DownloadTaskPhase.VERIFYING -> R.string.library_download_verifying
        DownloadTaskPhase.INSTALLED -> R.string.library_download_ready
        DownloadTaskPhase.FAILED -> R.string.library_task_failed
        DownloadTaskPhase.CANCELLED -> R.string.library_task_cancelled
    },
)

private fun taskTone(phase: DownloadTaskPhase): PillTone = when (phase) {
    DownloadTaskPhase.INSTALLED -> PillTone.OK
    DownloadTaskPhase.FAILED -> PillTone.BLOCK
    DownloadTaskPhase.VERIFYING -> PillTone.OK
    DownloadTaskPhase.QUEUED, DownloadTaskPhase.CANCELLED -> PillTone.NEUTRAL
    DownloadTaskPhase.ACTIVE, DownloadTaskPhase.PAUSED -> PillTone.WARN
}

@Composable
private fun AdoptionBanner(event: ModelAdoptionCoordinator.Event, onDismiss: () -> Unit) {
    val message = when (event) {
        is ModelAdoptionCoordinator.Event.Installed ->
            stringResource(R.string.library_adopted, event.model.displayName)
        is ModelAdoptionCoordinator.Event.Failed ->
            stringResource(R.string.library_adoption_failed, event.displayName)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

/**
 * Fit arithmetic is shared with the chip, the model page and the download gate. The card asks about
 * the default context, not the trained window: a 32k-trained model is not expected to run at 32k on a
 * 4 GB phone, and using the trained window here would print "won't run" for models that run fine.
 */
private fun verdictFor(
    entry: CatalogueEntry,
    fitEngine: ModelFitEngine,
    capacity: DeviceCapacity,
): FitVerdict = fitEngine.evaluate(
    fileBytes = entry.sizeBytes,
    architecture = entry.fitArchitecture(),
    requestedContext = ModelFitEngine.DEFAULT_CONTEXT,
    capacity = capacity,
)

@Composable
private fun fitLabel(status: FitStatus): String = stringResource(
    when (status) {
        FitStatus.RUNS -> R.string.library_fit_runs
        FitStatus.TIGHT -> R.string.library_fit_tight
        FitStatus.WONT_RUN -> R.string.library_fit_blocked
        FitStatus.UNKNOWN -> R.string.library_fit_unknown
    },
)

private fun fitTone(status: FitStatus): PillTone = when (status) {
    FitStatus.RUNS -> PillTone.OK
    FitStatus.TIGHT -> PillTone.WARN
    FitStatus.WONT_RUN -> PillTone.BLOCK
    FitStatus.UNKNOWN -> PillTone.NEUTRAL
}

@Composable
private fun fitReasonText(reason: String): String? = when (reason) {
    FitReasons.RAM_INSUFFICIENT -> stringResource(R.string.library_fit_ram_reason)
    FitReasons.DISK_INSUFFICIENT -> stringResource(R.string.library_fit_disk_reason)
    FitReasons.LOW_RAM_DEVICE -> stringResource(R.string.library_fit_low_ram_reason)
    FitReasons.ARCHITECTURE_UNSUPPORTED -> stringResource(R.string.library_fit_arch_reason)
    FitReasons.ARCHITECTURE_UNKNOWN -> stringResource(R.string.library_fit_unknown_reason)
    else -> null
}

@Composable
private fun downloadReasonLabel(reason: String?): String = stringResource(
    when (reason) {
        DownloadReasons.NOT_UNMETERED -> R.string.download_reason_unmetered
        DownloadReasons.STORAGE_LOW -> R.string.download_reason_storage
        DownloadReasons.NETWORK -> R.string.download_reason_network
        DownloadReasons.CHECKSUM -> R.string.download_reason_checksum
        DownloadReasons.OFFLINE_MODE -> R.string.download_reason_offline
        DownloadReasons.CANCELLED -> R.string.download_reason_cancelled
        else -> R.string.download_reason_generic
    },
)

private fun isMetered(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/** SAF does not promise a display name, but the document provider almost always supplies one. */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

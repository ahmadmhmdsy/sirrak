package app.sirrak.feature.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsKeyValueRow
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.design.SettingsVerticalSpacer
import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.ConsentRules
import app.sirrak.core.models.DeviceProfile
import app.sirrak.core.models.DeviceProfileProvider
import app.sirrak.core.models.DownloadPhase
import app.sirrak.core.models.DownloadProgress
import app.sirrak.core.models.DownloadReasons
import app.sirrak.core.models.LicenceConsentStore
import app.sirrak.core.models.ModelCatalogue
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelDownloader
import app.sirrak.core.models.ModelRecommender
import app.sirrak.core.models.Recommendation
import app.sirrak.core.models.RecommendationReason
import app.sirrak.core.models.StorageMath
import app.sirrak.core.runtime.GgufFile
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelStore
import java.util.Locale

/**
 * MOD-1..MOD-6 in one Settings group: the device report, the recommendation and why, the curated
 * catalogue with the single resumable download, licence consent for gated models, installed-model
 * management, the storage manager and a Hugging Face repo/file download for everything else.
 *
 * Everything is driven by the catalogue asset and the services passed in: adding a model, a language
 * or a licence is data, not code. The section owns no download state - it renders
 * [ModelDownloader.progress] and asks the downloader to start/pause/resume/cancel.
 */
@Composable
fun ModelManagerSection(
    catalogueProvider: ModelCatalogueProvider,
    profileProvider: DeviceProfileProvider,
    downloader: ModelDownloader,
    consentStore: LicenceConsentStore,
    modelStore: ModelStore,
) {
    val context = LocalContext.current
    val progress by downloader.progress.collectAsStateWithLifecycle()
    val installedModels by modelStore.models.collectAsStateWithLifecycle()
    val selectedModel by modelStore.selected.collectAsStateWithLifecycle()
    var refreshKey by remember { mutableIntStateOf(0) }
    val profile = remember(refreshKey) { profileProvider.profile() }
    val catalogue = remember(catalogueProvider) { catalogueProvider.catalogue() }
    val recommendation = remember(catalogue, profile) { ModelRecommender.recommend(catalogue, profile) }

    var detailEntry by remember { mutableStateOf<CatalogueEntry?>(null) }
    var consentEntry by remember { mutableStateOf<CatalogueEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<InstalledModel?>(null) }
    var pendingEntry by remember { mutableStateOf<CatalogueEntry?>(null) }
    var showOrphanDialog by remember { mutableStateOf(false) }
    var orphanCount by remember { mutableStateOf<Int?>(null) }
    var showHfDialog by remember { mutableStateOf(false) }
    var hfError by remember { mutableStateOf<String?>(null) }

    val phase = progress?.phase
    val busy = phase == DownloadPhase.QUEUED || phase == DownloadPhase.DOWNLOADING || phase == DownloadPhase.VERIFYING

    // A finished download is adopted by the model store here, once: the downloader never touches
    // installed models, and the store never touches the network.
    LaunchedEffect(progress?.phase, progress?.file) {
        val current = progress ?: return@LaunchedEffect
        if (current.phase != DownloadPhase.INSTALLED) return@LaunchedEffect
        val file = current.file ?: return@LaunchedEffect
        val source = pendingEntry
        val result = modelStore.importFile(
            displayName = source?.fileName ?: file.name,
            source = file,
            expectedSha256 = source?.sha256,
        )
        result.onSuccess { model ->
            modelStore.select(model)
            refreshKey++
        }
        downloader.clear()
        pendingEntry = null
    }

    val startDownload: (CatalogueEntry, String?) -> Unit = { entry, token ->
        // P3.4: the background-download notification needs a runtime grant on Android 13+; ask when
        // the user actually starts a download, never at launch.
        requestDownloadNotificationPermission(context)
        pendingEntry = entry
        downloader.start(entry = entry, authorization = token)
    }
    val requestDownload: (CatalogueEntry) -> Unit = { entry ->
        if (ConsentRules.requiresConsent(entry) && !consentStore.isAccepted(entry.id)) {
            consentEntry = entry
        } else {
            startDownload(entry, null)
        }
    }
    val retry: (CatalogueEntry) -> Unit = { entry ->
        val reason = progress?.reason
        if (reason == DownloadReasons.NETWORK || reason == DownloadReasons.HTTP) {
            downloader.resume()
        } else {
            downloader.cancel()
            startDownload(entry, null)
        }
    }

    Column {
        DeviceCard(profile = profile)
        SettingsVerticalSpacer()
        RecommendationCard(
            recommendation = recommendation,
            busy = busy,
            onOpen = { detailEntry = recommendation.entry },
            onDownload = { recommendation.entry?.let(requestDownload) },
        )
        SettingsVerticalSpacer()
        SettingsSection(title = stringResource(R.string.models_catalogue_title)) {
            if (catalogue.models.isEmpty()) {
                SettingsNote(stringResource(R.string.models_catalogue_empty))
            }
            catalogue.models.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                val installed = installedModels.firstOrNull { it.id == entry.fileName }
                CatalogueRow(
                    entry = entry,
                    isRecommended = recommendation.entry?.id == entry.id,
                    installed = installed,
                    isSelected = selectedModel?.id == entry.fileName,
                    progress = progress?.takeIf { it.entryId == entry.id },
                    busy = busy,
                    onOpen = { detailEntry = entry },
                    onDownload = { requestDownload(entry) },
                    onSelect = { installed?.let(modelStore::select) },
                    onPause = { downloader.pause() },
                    onResume = { downloader.resume() },
                    onCancel = { downloader.cancel() },
                    onRetry = { retry(entry) },
                )
            }
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.models_add_hf_title),
                onClick = { hfError = null; showHfDialog = true },
            )
        }
        SettingsNote(stringResource(R.string.models_network_note))
        SettingsVerticalSpacer()
        InstalledModelsCard(
            installed = installedModels,
            selected = selectedModel,
            onSelect = modelStore::select,
            onDelete = { deleteTarget = it },
        )
        SettingsVerticalSpacer()
        StorageManagerCard(
            profile = profile,
            installed = installedModels,
            orphanCount = orphanCount,
            onCleanOrphans = { showOrphanDialog = true },
        )
        SettingsNote(stringResource(R.string.models_storage_note))
        SettingsVerticalSpacer()
    }

    detailEntry?.let { entry ->
        ModelDetailSheet(
            entry = entry,
            profile = profile,
            installed = installedModels.firstOrNull { it.id == entry.fileName },
            isSelected = selectedModel?.id == entry.fileName,
            onDismiss = { detailEntry = null },
            onDownload = { detailEntry = null; requestDownload(entry) },
            onSelect = {
                installedModels.firstOrNull { it.id == entry.fileName }?.let(modelStore::select)
                detailEntry = null
            },
            onDelete = {
                installedModels.firstOrNull { it.id == entry.fileName }?.let { deleteTarget = it }
                detailEntry = null
            },
        )
    }

    consentEntry?.let { entry ->
        LicenceConsentDialog(
            entry = entry,
            onAccept = {
                consentStore.accept(entry.id, entry.licence)
                consentEntry = null
                startDownload(entry, null)
            },
            onDismiss = { consentEntry = null },
        )
    }

    deleteTarget?.let { model ->
        ConfirmDialog(
            title = stringResource(R.string.models_delete_dialog_title),
            body = stringResource(R.string.models_delete_dialog_body),
            confirmLabel = stringResource(R.string.models_action_delete),
            onConfirm = {
                modelStore.delete(model)
                refreshKey++
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (showOrphanDialog) {
        ConfirmDialog(
            title = stringResource(R.string.models_storage_orphans_dialog_title),
            body = stringResource(R.string.models_storage_orphans_dialog_body),
            confirmLabel = stringResource(R.string.models_storage_orphans),
            onConfirm = {
                orphanCount = modelStore.deleteOrphanFiles()
                refreshKey++
                showOrphanDialog = false
            },
            onDismiss = { showOrphanDialog = false },
        )
    }

    if (showHfDialog) {
        HuggingFaceDialog(
            error = hfError,
            onStart = { repo, file, token ->
                val safeId = "hf-" + file.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val entry = CatalogueEntry(
                    id = safeId,
                    displayName = file,
                    repo = repo,
                    fileName = file,
                    sizeBytes = 0L,
                    licence = "user-supplied",
                )
                hfError = null
                showHfDialog = false
                startDownload(entry, token.ifBlank { null })
            },
            onDismiss = { showHfDialog = false },
        )
    }
}

// ------------------------------------------------------------------------------------------- cards

@Composable
private fun DeviceCard(profile: DeviceProfile) {
    SettingsSection(title = stringResource(R.string.models_device_title)) {
        SettingsKeyValueRow(stringResource(R.string.models_device_ram), "${profile.totalRamMb} MB")
        SettingsKeyValueRow(stringResource(R.string.models_device_storage), GgufFile.formatSize(profile.availableStorageBytes))
        SettingsKeyValueRow(stringResource(R.string.models_device_android), "API ${profile.sdkInt}")
        if (profile.abis.isNotEmpty()) {
            SettingsKeyValueRow(stringResource(R.string.models_device_abi), profile.abis.joinToString(", "))
        }
        profile.socLabel?.let { SettingsKeyValueRow(stringResource(R.string.models_device_soc), it) }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: Recommendation,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val entry = recommendation.entry
    SettingsSection(title = stringResource(R.string.models_recommended_title)) {
        if (entry == null) {
            SettingsNote(stringResource(R.string.models_catalogue_empty))
            return@SettingsSection
        }
        SettingsRow(
            label = entry.displayName,
            value = entry.sizeLabel,
            onClick = onOpen,
        )
        SettingsNote(stringResource(whyTextKey(recommendation)))
        if (recommendation.storageTight) {
            SettingsNote(stringResource(R.string.models_storage_warning))
        }
        if (recommendation.ramTight) {
            SettingsNote(stringResource(R.string.models_ram_warning))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onDownload, enabled = !busy) {
                Text(stringResource(R.string.models_action_download))
            }
        }
    }
}

@Composable
private fun whyTextKey(recommendation: Recommendation): Int = when (recommendation.reason) {
    RecommendationReason.RECOMMENDED -> R.string.models_recommended_why_recommended_fits
    RecommendationReason.SMALLEST_THAT_FITS -> R.string.models_recommended_why_smallest_that_fits
    RecommendationReason.NOTHING_FITS -> R.string.models_recommended_why_nothing_fits
    RecommendationReason.EMPTY_CATALOGUE -> R.string.models_recommended_why_empty_catalogue
}

@Composable
private fun CatalogueRow(
    entry: CatalogueEntry,
    isRecommended: Boolean,
    installed: InstalledModel?,
    isSelected: Boolean,
    progress: DownloadProgress?,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (entry.parameterLabel.isNotBlank()) append(entry.parameterLabel).append(" · ")
                        if (entry.quantization.isNotBlank()) append(entry.quantization).append(" · ")
                        append(entry.sizeLabel)
                        if (entry.ramMb > 0) append(" · ").append(entry.ramMb).append(" MB")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                installed != null -> Badge(
                    text = stringResource(if (isSelected) R.string.models_action_selected else R.string.models_action_installed),
                )
                progress == null -> IconButton(onClick = onDownload, enabled = !busy) {
                    Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.models_action_download))
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRecommended) Badge(text = stringResource(R.string.models_badge_recommended))
            if (entry.gated) Badge(text = stringResource(R.string.models_badge_gated))
            if (entry.languages.isNotEmpty()) {
                Text(
                    text = entry.languages.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (progress != null && installed == null) {
            DownloadStatusRow(
                progress = progress,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
            )
        }
        if (installed != null && !isSelected) {
            TextButton(onClick = onSelect, modifier = Modifier.padding(top = 2.dp)) {
                Text(stringResource(R.string.models_action_select))
            }
        }
    }
}

@Composable
private fun DownloadStatusRow(
    progress: DownloadProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when (progress.phase) {
            DownloadPhase.DOWNLOADING, DownloadPhase.QUEUED -> {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.models_download_percent, progress.percent) +
                            " · " + GgufFile.formatSize(progress.bytesDownloaded) +
                            " / " + GgufFile.formatSize(progress.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (progress.bytesPerSecond > 0L) {
                        Text(
                            text = stringResource(
                                R.string.models_download_speed,
                                GgufFile.formatSize(progress.bytesPerSecond),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Rounded.Pause, contentDescription = stringResource(R.string.models_action_pause))
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.models_action_cancel))
                    }
                }
            }
            DownloadPhase.PAUSED -> {
                Text(
                    text = stringResource(R.string.models_action_pause),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.models_action_resume))
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.models_action_cancel))
                    }
                }
            }
            DownloadPhase.VERIFYING -> {
                Text(
                    text = stringResource(R.string.models_download_verifying),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DownloadPhase.FAILED -> {
                Text(
                    text = reasonText(progress.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.models_action_retry))
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.models_action_cancel))
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun InstalledModelsCard(
    installed: List<InstalledModel>,
    selected: InstalledModel?,
    onSelect: (InstalledModel) -> Unit,
    onDelete: (InstalledModel) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.models_installed_title)) {
        if (installed.isEmpty()) {
            SettingsNote(stringResource(R.string.models_installed_empty))
            return@SettingsSection
        }
        installed.forEachIndexed { index, model ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                label = model.displayName,
                value = model.sizeLabel,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected?.id == model.id) {
                            Badge(text = stringResource(R.string.models_action_selected))
                        } else {
                            TextButton(onClick = { onSelect(model) }) {
                                Text(stringResource(R.string.models_action_select))
                            }
                        }
                        IconButton(onClick = { onDelete(model) }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.models_action_delete))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun StorageManagerCard(
    profile: DeviceProfile,
    installed: List<InstalledModel>,
    orphanCount: Int?,
    onCleanOrphans: () -> Unit,
) {
    val modelsBytes = installed.sumOf { it.sizeBytes }
    SettingsSection(title = stringResource(R.string.models_storage_title)) {
        SettingsKeyValueRow(stringResource(R.string.models_storage_free), GgufFile.formatSize(profile.availableStorageBytes))
        SettingsKeyValueRow(stringResource(R.string.models_storage_models_used), GgufFile.formatSize(modelsBytes))
        SettingsRow(
            label = stringResource(R.string.models_storage_orphans),
            value = orphanCount?.let { stringResource(R.string.models_storage_orphans_done, it) },
            onClick = onCleanOrphans,
        )
    }
}

// ------------------------------------------------------------------------------------------ dialogs

@Composable
private fun LicenceConsentDialog(
    entry: CatalogueEntry,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_licence_accept_title)) },
        text = {
            Column {
                Text(stringResource(R.string.models_licence_accept_body, entry.displayName, entry.licence))
                entry.licenceUrl?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.models_licence_accept_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.models_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.models_cancel)) } },
    )
}

@Composable
private fun HuggingFaceDialog(
    error: String?,
    onStart: (repo: String, file: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var repo by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_add_hf_title)) },
        text = {
            Column {
                Text(stringResource(R.string.models_add_hf_body))
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it.trim() },
                    label = { Text(stringResource(R.string.models_add_hf_repo)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = file,
                    onValueChange = { file = it.trim() },
                    label = { Text(stringResource(R.string.models_add_hf_file)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text(stringResource(R.string.models_add_hf_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.models_add_hf_token_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(repo, file, token) },
                enabled = repo.isNotBlank() && file.isNotBlank() && file.endsWith(".gguf"),
            ) {
                Text(stringResource(R.string.models_add_hf_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.models_cancel)) }
        },
    )
}

// ------------------------------------------------------------------------------------------ helpers

@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun reasonText(reason: String?): String = stringResource(
    when (reason) {
        DownloadReasons.NOT_UNMETERED -> R.string.models_reason_not_unmetered
        DownloadReasons.STORAGE_LOW -> R.string.models_reason_storage_low
        DownloadReasons.NETWORK -> R.string.models_reason_network
        DownloadReasons.HTTP -> R.string.models_reason_http
        DownloadReasons.CHECKSUM -> R.string.models_reason_checksum
        DownloadReasons.IO -> R.string.models_reason_io
        DownloadReasons.CANCELLED -> R.string.models_reason_cancelled
        DownloadReasons.INVALID_MODEL -> R.string.models_reason_invalid_model
        DownloadReasons.RANGE_UNSUPPORTED -> R.string.models_reason_range_unsupported
        // PRV-2: the privacy screen's offline-only switch refused the download before any socket.
        DownloadReasons.OFFLINE_MODE -> R.string.models_reason_offline_mode
        else -> R.string.models_reason_network
    },
)

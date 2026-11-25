package app.sirrak.feature.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.PageScaffold
import app.sirrak.core.design.PillTone
import app.sirrak.core.design.StatusPill
import app.sirrak.core.models.FitReasons
import app.sirrak.core.models.FitStatus
import app.sirrak.core.models.FitVerdict
import app.sirrak.core.models.GgufMetadata
import app.sirrak.core.models.GgufProbeCodes
import app.sirrak.core.models.HfCallKind
import app.sirrak.core.models.HfCodes
import app.sirrak.core.models.HfModelSummary
import app.sirrak.core.models.HfRepoFile
import app.sirrak.core.models.ModelFitEngine
import app.sirrak.core.runtime.GgufFile
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Debounce for the search field: one keystroke must never become one Hub API call. */
private const val SEARCH_DEBOUNCE_MS = 400L

/**
 * The P3 search page: a debounced query, honest loading/empty/blocked/failure bodies, and rows that
 * open one repository. It renders exactly the [HfSearchSession] state, so going back to the page
 * shows the results it already had instead of spending the anonymous API budget again.
 */
@Composable
fun HfSearchPage(
    session: HfSearchSession,
    onOpenRepo: (String) -> Unit,
    onBack: () -> Unit,
    onDisableOffline: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val query = session.query
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        session.onDebouncedQuery()
    }
    val retryAfterOffline: () -> Unit = {
        onDisableOffline()
        scope.launch { session.retryAfterOffline() }
    }
    PageScaffold(
        title = stringResource(R.string.hf_search_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.hf_back),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = session.query,
                onValueChange = session::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.hf_search_field_label)) },
                placeholder = { Text(stringResource(R.string.hf_search_field_placeholder)) },
                trailingIcon = {
                    if (session.query.isNotEmpty()) {
                        IconButton(onClick = { session.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.hf_search_clear),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.hf_search_debounce_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(14.dp))

            when (session.phase) {
                HfSearchPhase.IDLE -> HfStateCard(
                    title = stringResource(R.string.hf_search_field_label),
                    body = stringResource(R.string.hf_search_initial),
                )
                HfSearchPhase.LOADING -> HfLoadingRow(stringResource(R.string.hf_search_loading))
                HfSearchPhase.BLOCKED -> {
                    if (session.blockedReason == HfCodes.METERED_DECLINED) {
                        HfFailureCard(
                            code = session.blockedReason,
                            genericRes = R.string.hf_search_failure_generic,
                        )
                    } else {
                        HfStateCard(
                            title = stringResource(R.string.hf_search_offline_title),
                            body = stringResource(R.string.hf_search_offline_body),
                            actionLabel = stringResource(R.string.hf_search_offline_action),
                            onAction = retryAfterOffline,
                        )
                    }
                }
                HfSearchPhase.FAILED -> HfFailureCard(
                    code = session.failure,
                    genericRes = R.string.hf_search_failure_generic,
                    onRetry = { scope.launch { session.retry() } },
                )
                HfSearchPhase.LOADING_MORE -> {
                    SearchResults(session, onOpenRepo)
                    Spacer(Modifier.height(12.dp))
                    HfLoadingRow(stringResource(R.string.hf_search_more_loading))
                }
                HfSearchPhase.LOADED -> {
                    if (session.results.isEmpty()) {
                        HfStateCard(
                            title = stringResource(R.string.hf_search_action),
                            body = stringResource(R.string.hf_search_empty),
                        )
                    } else {
                        SearchResults(session, onOpenRepo)
                        Spacer(Modifier.height(12.dp))
                        session.pagingFailure?.let {
                            Text(
                                text = stringResource(R.string.hf_search_more_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (session.nextCursor != null) {
                            TextButton(
                                onClick = { scope.launch { session.loadMore() } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.hf_search_more))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.hf_search_source_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SearchResults(session: HfSearchSession, onOpenRepo: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        session.results.forEach { model ->
            HfModelRow(model = model, onClick = { onOpenRepo(model.id) })
        }
    }
}

@Composable
private fun HfModelRow(model: HfModelSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.hf_search_row_meta,
                        latinNumber(model.downloads),
                        latinNumber(model.likes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model.gated) {
                    Spacer(Modifier.height(8.dp))
                    StatusPill(
                        text = stringResource(R.string.hf_search_gated_badge),
                        tone = PillTone.WARN,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.hf_search_open),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The repo page: the declared card, the GGUF files, one capped probe of the selected file, the fit
 * arithmetic, and the download hand-off. Nothing here downloads bytes itself: the button enqueues a
 * synthetic entry in the persisted queue and asks the host to start it.
 */
@Composable
fun HfRepoPage(
    session: HfRepoSession,
    token: StateFlow<String?>,
    meteredPending: StateFlow<HfCallKind?>,
    onMeteredAnswer: (Boolean) -> Unit,
    onSaveToken: (String?) -> Unit,
    onDisableOffline: () -> Unit,
    onBack: () -> Unit,
    downloadsPageAvailable: Boolean,
    onOpenDownloads: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val savedToken by token.collectAsState()
    var tokenDraft by remember { mutableStateOf(savedToken.orEmpty()) }
    var showTightConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(session.repoId) {
        if (session.phase == HfRepoPhase.LOADING && session.files.isEmpty()) session.load()
    }

    PageScaffold(
        title = session.repoId,
        onBack = onBack,
        backContentDescription = stringResource(R.string.hf_back),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            when (session.phase) {
                HfRepoPhase.LOADING -> HfLoadingRow(stringResource(R.string.hf_repo_loading))
                HfRepoPhase.BLOCKED -> HfStateCard(
                    title = stringResource(R.string.hf_repo_offline_title),
                    body = stringResource(R.string.hf_repo_offline_body),
                    actionLabel = stringResource(R.string.hf_search_offline_action),
                    onAction = {
                        onDisableOffline()
                        scope.launch { session.reload() }
                    },
                )
                HfRepoPhase.FAILED -> HfFailureCard(
                    code = session.failure,
                    genericRes = R.string.hf_repo_failure,
                    onRetry = { scope.launch { session.reload() } },
                )
                HfRepoPhase.TOKEN_REQUIRED -> {
                    HfTokenCard(
                        draft = tokenDraft,
                        onDraftChange = { tokenDraft = it },
                        saved = savedToken,
                        onSave = {
                            onSaveToken(tokenDraft.trim())
                            scope.launch { session.reload() }
                        },
                        onClear = {
                            tokenDraft = ""
                            onSaveToken(null)
                            scope.launch { session.reload() }
                        },
                        retry = {
                            onSaveToken(tokenDraft.trim())
                            scope.launch { session.reload() }
                        },
                    )
                }
                HfRepoPhase.LOADED -> HfLoadedRepo(
                    session = session,
                    scope = scope,
                    onDisableOffline = onDisableOffline,
                    downloadsPageAvailable = downloadsPageAvailable,
                    onOpenDownloads = onOpenDownloads,
                    onRequestTightConfirm = { showTightConfirm = true },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    HfMeteredDialog(pending = meteredPending, onAnswer = onMeteredAnswer)

    if (showTightConfirm) {
        AlertDialog(
            onDismissRequest = { showTightConfirm = false },
            title = { Text(stringResource(R.string.hf_fit_tight_title)) },
            text = { Text(stringResource(R.string.hf_fit_tight_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showTightConfirm = false
                    scope.launch { session.downloadNow() }
                }) { Text(stringResource(R.string.hf_fit_tight_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { showTightConfirm = false }) {
                    Text(stringResource(R.string.hf_fit_tight_cancel))
                }
            },
        )
    }
}

@Composable
private fun HfLoadedRepo(
    session: HfRepoSession,
    scope: kotlinx.coroutines.CoroutineScope,
    onDisableOffline: () -> Unit,
    downloadsPageAvailable: Boolean,
    onOpenDownloads: () -> Unit,
    onRequestTightConfirm: () -> Unit,
) {
    session.info?.let { info ->
        HfStateCard(
            title = info.id,
            body = buildString {
                append(stringResource(R.string.hf_repo_licence))
                append(": ")
                append(info.license?.takeIf { it.isNotBlank() } ?: stringResource(R.string.hf_repo_unknown))
                info.contextTokens?.takeIf { it > 0 }?.let {
                    append(" · ")
                    append(stringResource(R.string.hf_repo_hub_context))
                    append(": ")
                    append(it)
                }
                info.architecture?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(stringResource(R.string.hf_repo_hub_architecture))
                    append(": ")
                    append(it)
                }
            },
        )
        Spacer(Modifier.height(12.dp))
    }

    Text(
        text = stringResource(R.string.hf_repo_files_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    if (session.files.isEmpty()) {
        HfStateCard(
            title = stringResource(R.string.hf_repo_files_title),
            body = stringResource(R.string.hf_repo_files_empty),
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            session.files.forEach { file ->
                HfFileRow(
                    file = file,
                    selected = session.selectedPath == file.path,
                    onClick = { session.select(file) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.hf_repo_select_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }

    val selected = session.selectedFile()
    if (selected == null) return
    Spacer(Modifier.height(16.dp))
    ProbeSection(session = session, scope = scope, onDisableOffline = onDisableOffline)
    session.metadata?.let { metadata ->
        Spacer(Modifier.height(12.dp))
        HfMetadataCard(metadata = metadata, bytesRead = session.probeBytesRead, ranged = session.probeRanged)
    }
    session.verdict?.let { verdict ->
        Spacer(Modifier.height(12.dp))
        HfVerdictCard(verdict = verdict)
    }
    Spacer(Modifier.height(16.dp))
    DownloadSection(
        session = session,
        scope = scope,
        downloadsPageAvailable = downloadsPageAvailable,
        onOpenDownloads = onOpenDownloads,
        onRequestTightConfirm = onRequestTightConfirm,
    )
}

@Composable
private fun ProbeSection(
    session: HfRepoSession,
    scope: kotlinx.coroutines.CoroutineScope,
    onDisableOffline: () -> Unit,
) {
    Text(
        text = stringResource(R.string.hf_probe_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { scope.launch { session.probeSelected() } },
        enabled = session.probePhase != HfProbePhase.REQUESTING,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.hf_probe_action))
    }
    when (session.probePhase) {
        HfProbePhase.REQUESTING -> HfLoadingRow(stringResource(R.string.hf_probe_running))
        HfProbePhase.BLOCKED -> {
            if (session.probeBlockedReason == HfCodes.METERED_DECLINED) {
                Text(
                    text = stringResource(R.string.hf_probe_blocked_metered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.hf_repo_offline_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                TextButton(
                    onClick = {
                        onDisableOffline()
                        scope.launch { session.probeSelected() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.hf_search_offline_action)) }
            }
        }
        HfProbePhase.FAILED -> Column {
            Text(
                text = stringResource(probeFailureRes(session.probeFailure)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            TextButton(
                onClick = { scope.launch { session.probeSelected() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.hf_retry)) }
        }
        else -> Unit
    }
}

@Composable
private fun HfMetadataCard(metadata: GgufMetadata, bytesRead: Int, ranged: Boolean) {
    val architecture = metadata.architecture
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.hf_meta_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            HfKeyValue(stringResource(R.string.hf_meta_architecture), architecture?.name)
            HfKeyValue(stringResource(R.string.hf_meta_layers), architecture?.layerCount)
            HfKeyValue(stringResource(R.string.hf_meta_heads), architecture?.headCount)
            HfKeyValue(stringResource(R.string.hf_meta_kv_heads), architecture?.uniformKvHeadCount)
            HfKeyValue(stringResource(R.string.hf_meta_embedding), architecture?.embeddingLength)
            HfKeyValue(stringResource(R.string.hf_meta_context), architecture?.contextTokens)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (metadata.complete) {
                    stringResource(R.string.hf_probe_complete)
                } else {
                    stringResource(R.string.hf_probe_partial)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.hf_probe_bytes, sizeLabel(bytesRead.toLong())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (ranged) {
                    stringResource(R.string.hf_probe_ranged_note)
                } else {
                    stringResource(R.string.hf_probe_full_note)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.hf_meta_hub_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HfVerdictCard(verdict: FitVerdict) {
    val (statusText, tone) = when (verdict.status) {
        FitStatus.RUNS -> stringResource(R.string.hf_fit_status_runs) to PillTone.OK
        FitStatus.TIGHT -> stringResource(R.string.hf_fit_status_tight) to PillTone.WARN
        FitStatus.WONT_RUN -> stringResource(R.string.hf_fit_status_wont_run) to PillTone.BLOCK
        FitStatus.UNKNOWN -> stringResource(R.string.hf_fit_status_unknown) to PillTone.NEUTRAL
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.hf_fit_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = statusText, tone = tone)
            }
            Spacer(Modifier.height(10.dp))
            HfKeyValue(stringResource(R.string.hf_fit_weights), sizeLabel(verdict.footprint.fileBytes))
            HfKeyValue(
                label = stringResource(R.string.hf_fit_kv),
                value = optionalSize(verdict.footprint.kvBytes),
            )
            HfKeyValue(
                label = stringResource(R.string.hf_fit_overhead),
                value = sizeLabel(verdict.footprint.overheadBytes) + " · " +
                    stringResource(R.string.hf_fit_estimate),
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            HfKeyValue(
                label = stringResource(R.string.hf_fit_total),
                value = sizeLabel(verdict.footprint.totalBytes) + " · " +
                    stringResource(R.string.hf_fit_estimate),
            )
            HfKeyValue(
                label = stringResource(R.string.hf_fit_usable_ram),
                value = verdict.usableRamMb.toString() + " MB · " +
                    stringResource(R.string.hf_fit_estimate),
            )
            HfKeyValue(stringResource(R.string.hf_fit_free_disk), optionalSize(verdict.freeDiskBytes))
            verdict.suggestedContext?.let {
                HfKeyValue(stringResource(R.string.hf_fit_suggested_context), it.toString())
            }
            HfKeyValue(
                stringResource(R.string.hf_fit_requested_context),
                verdict.requestedContext.toString(),
            )
            verdict.reasons.forEach { reason -> FitReasonLine(reason) }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.hf_fit_math_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FitReasonLine(reason: String) {
    val res = when (reason) {
        FitReasons.LOW_RAM_DEVICE -> R.string.hf_fit_reason_low_ram
        FitReasons.RAM_INSUFFICIENT -> R.string.hf_fit_reason_ram
        FitReasons.RAM_TIGHT -> R.string.hf_fit_reason_tight
        FitReasons.DISK_INSUFFICIENT -> R.string.hf_fit_reason_disk
        FitReasons.ARCHITECTURE_UNSUPPORTED -> R.string.hf_fit_reason_arch_unsupported
        FitReasons.ARCHITECTURE_UNKNOWN -> R.string.hf_fit_reason_arch_unknown
        FitReasons.FILE_UNKNOWN -> R.string.hf_fit_reason_file_unknown
        else -> null
    }
    if (res != null) {
        Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun DownloadSection(
    session: HfRepoSession,
    scope: kotlinx.coroutines.CoroutineScope,
    downloadsPageAvailable: Boolean,
    onOpenDownloads: () -> Unit,
    onRequestTightConfirm: () -> Unit,
) {
    val decision = session.decision()
    Button(
        onClick = {
            when (decision) {
                HfDownloadDecision.CONFIRM_TIGHT -> onRequestTightConfirm()
                HfDownloadDecision.BLOCKED -> Unit
                else -> scope.launch { session.downloadNow() }
            }
        },
        enabled = decision != HfDownloadDecision.BLOCKED && decision != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.hf_download_action))
    }
    when (decision) {
        HfDownloadDecision.BLOCKED -> Text(
            text = stringResource(R.string.hf_fit_blocked_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        HfDownloadDecision.ALLOWED_UNVERIFIED, null -> Text(
            text = stringResource(R.string.hf_fit_unverified_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        else -> Unit
    }
    session.notice?.let { notice ->
        Text(
            text = stringResource(noticeRes(notice)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        if (downloadsPageAvailable &&
            (notice == HfDownloadNotice.QUEUED ||
                notice == HfDownloadNotice.STARTED ||
                notice == HfDownloadNotice.DECLINED_METERED ||
                notice == HfDownloadNotice.WAITING_FOR_OTHER)
        ) {
            TextButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.hf_download_open))
            }
        }
    }
}

@Composable
private fun HfTokenCard(
    draft: String,
    onDraftChange: (String) -> Unit,
    saved: String?,
    onSave: () -> Unit,
    onClear: () -> Unit,
    retry: () -> Unit,
) {
    HfStateCard(
        title = stringResource(R.string.hf_repo_token_title),
        body = stringResource(R.string.hf_repo_token_body),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.hf_repo_token_label)) },
        visualTransformation = PasswordVisualTransformation(),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.hf_repo_token_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.hf_repo_token_save))
    }
    if (saved != null) {
        TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hf_repo_token_clear))
        }
        TextButton(onClick = retry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hf_retry))
        }
    }
}

@Composable
private fun HfFileRow(file: HfRepoFile, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = file.path,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = sizeLabel(file.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One metered-consent dialog for both byte-moving calls; closing it answers "no". */
@Composable
fun HfMeteredDialog(pending: StateFlow<HfCallKind?>, onAnswer: (Boolean) -> Unit) {
    val kind by pending.collectAsState()
    val current = kind
    if (current != null) {
        AlertDialog(
            onDismissRequest = { onAnswer(false) },
            title = {
                Text(
                    stringResource(
                        if (current == HfCallKind.PROBE) {
                            R.string.hf_probe_metered_title
                        } else {
                            R.string.hf_download_metered_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (current == HfCallKind.PROBE) {
                            R.string.hf_probe_metered_body
                        } else {
                            R.string.hf_download_metered_body
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onAnswer(true) }) {
                    Text(stringResource(R.string.hf_metered_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAnswer(false) }) {
                    Text(stringResource(R.string.hf_metered_cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------- shared pieces

@Composable
private fun HfStateCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun HfFailureCard(
    code: String?,
    genericRes: Int,
    onRetry: (() -> Unit)? = null,
) {
    val text = when (code) {
        HfCodes.NETWORK -> stringResource(R.string.hf_search_failure_network)
        HfCodes.RATE_LIMITED -> stringResource(R.string.hf_search_failure_rate)
        HfCodes.GATED -> stringResource(R.string.hf_search_failure_gated)
        else -> stringResource(genericRes)
    }
    HfStateCard(
        title = stringResource(R.string.hf_search_failure_generic),
        body = text,
        actionLabel = if (onRetry != null) stringResource(R.string.hf_retry) else null,
        onAction = onRetry,
    )
}

@Composable
private fun HfLoadingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HfKeyValue(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: maybeMissing(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HfKeyValue(label: String, value: Int?) {
    HfKeyValue(label = label, value = value?.toString())
}

/** The em-dash placeholder, kept one string so "unknown" never reads as a measured zero. */
@Composable
private fun maybeMissing(): String = stringResource(R.string.hf_meta_missing)

@Composable
private fun sizeLabel(bytes: Long): String = if (bytes > 0L) {
    GgufFile.formatSize(bytes)
} else {
    stringResource(R.string.hf_repo_unknown)
}

@Composable
private fun optionalSize(bytes: Long): String = if (bytes > 0L) GgufFile.formatSize(bytes) else maybeMissing()

@Composable
private fun probeFailureRes(code: String?): Int = when {
    code == GgufProbeCodes.NETWORK -> R.string.hf_probe_failure_network
    code == GgufProbeCodes.RANGE_UNSUPPORTED -> R.string.hf_probe_failure_range
    code == GgufProbeCodes.MALFORMED -> R.string.hf_probe_failure_malformed
    code == HfCodes.GATED || code?.startsWith("http_40") == true -> R.string.hf_probe_failure_gated
    else -> R.string.hf_probe_failure_generic
}

private fun noticeRes(notice: HfDownloadNotice): Int = when (notice) {
    HfDownloadNotice.QUEUED -> R.string.hf_download_notice_queued
    HfDownloadNotice.STARTED -> R.string.hf_download_notice_started
    HfDownloadNotice.DUPLICATE -> R.string.hf_download_notice_duplicate
    HfDownloadNotice.INSTALLED -> R.string.hf_download_notice_installed
    HfDownloadNotice.REJECTED -> R.string.hf_download_notice_rejected
    HfDownloadNotice.DECLINED_METERED -> R.string.hf_download_notice_declined
    HfDownloadNotice.WAITING_FOR_OTHER -> R.string.hf_download_notice_waiting_other
    HfDownloadNotice.OFFLINE -> R.string.hf_download_notice_offline
    HfDownloadNotice.START_FAILED -> R.string.hf_download_notice_start_failed
}

/** Latin digits regardless of the UI locale, per the design rule for technical numbers. */
private fun latinNumber(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)

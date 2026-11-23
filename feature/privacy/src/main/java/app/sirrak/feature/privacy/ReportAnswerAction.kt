package app.sirrak.feature.privacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.LocalChatAnswerContext
import app.sirrak.core.privacy.AiReport
import app.sirrak.core.privacy.AiReportQueue
import app.sirrak.core.privacy.AiReportReason
import app.sirrak.core.privacy.AiReportRules
import app.sirrak.core.privacy.AiReportSubmit
import app.sirrak.core.runtime.PrivacyConfig

/**
 * PRV-6 per-answer report action. Rendered from [app.sirrak.core.runtime.Slots.ChatAnswerActions],
 * it reads the answer from [LocalChatAnswerContext] instead of receiving it as a slot parameter, so
 * the slot contract stays "no parameters". Saving is local and immediate; sharing is a second,
 * explicit step inside the dialog.
 */
@Composable
fun ReportAnswerAction(
    reportQueue: AiReportQueue,
    config: PrivacyConfig,
    appLabel: String,
    modelLabel: () -> String,
) {
    val answer = LocalChatAnswerContext.current ?: return
    var showDialog by remember { mutableStateOf(false) }
    IconButton(onClick = { showDialog = true }, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = Icons.Outlined.Flag,
            contentDescription = stringResource(R.string.privacy_report_action),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (showDialog) {
        ReportDialog(
            answerText = answer.answerText,
            reportQueue = reportQueue,
            config = config,
            appLabel = appLabel,
            modelLabel = modelLabel,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ReportDialog(
    answerText: String,
    reportQueue: AiReportQueue,
    config: PrivacyConfig,
    appLabel: String,
    modelLabel: () -> String,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf(AiReportReason.HARMFUL) }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<AiReport?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var outcome by remember { mutableStateOf<ShareOutcome?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (saved == null) R.string.privacy_report_dialog_title else R.string.privacy_report_saved_title,
                ),
            )
        },
        text = {
            if (saved == null) {
                Column {
                    Text(
                        text = stringResource(R.string.privacy_report_reason_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        AiReportReason.entries.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = reason == option,
                                        onClick = { reason = option },
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = reason == option,
                                    onClick = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(reasonLabelRes(option)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= AiReportRules.MAX_NOTE_CHARS) note = it },
                        label = { Text(stringResource(R.string.privacy_report_note_label)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        maxLines = 3,
                    )
                    errorRes?.let { message ->
                        Text(
                            text = stringResource(message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.privacy_report_saved_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    outcome?.let { result ->
                        val message = when (result) {
                            ShareOutcome.SHARED -> null
                            ShareOutcome.COPIED -> R.string.privacy_report_share_copied
                            ShareOutcome.FAILED -> R.string.privacy_report_share_failed
                        }
                        if (message != null) {
                            Text(
                                text = stringResource(message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (saved == null) {
                TextButton(onClick = {
                    when (val result = reportQueue.submit(reason, answerText, note, modelLabel())) {
                        is AiReportSubmit.Accepted -> {
                            saved = result.report
                            errorRes = null
                        }
                        is AiReportSubmit.Rejected -> {
                            errorRes = when (result.reason) {
                                AiReportSubmit.Rejection.EMPTY_ANSWER -> R.string.privacy_report_empty_answer
                                AiReportSubmit.Rejection.QUEUE_FULL -> R.string.privacy_report_queue_full
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.privacy_report_save))
                }
            } else {
                TextButton(onClick = {
                    val report = saved ?: return@TextButton
                    outcome = shareReport(context, report, config, appLabel)
                }) {
                    Text(stringResource(R.string.privacy_report_share_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (saved == null) R.string.privacy_report_cancel else R.string.privacy_report_done,
                    ),
                )
            }
        },
    )
}

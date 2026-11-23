package app.sirrak.feature.privacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.privacy.AiReport
import app.sirrak.core.privacy.AiReportQueue
import app.sirrak.core.runtime.PrivacyConfig

/**
 * PRV-6 local report list: what was saved on this device, with an explicit per-report share and
 * delete. Nothing here talks to the network; sharing is the user's action through an intent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReportsSheet(
    reportQueue: AiReportQueue,
    config: PrivacyConfig,
    appLabel: String,
    modelLabel: () -> String,
    onDismiss: () -> Unit,
) {
    var reports by remember { mutableStateOf(reportQueue.reports()) }
    var outcome by remember { mutableStateOf<ShareOutcome?>(null) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.privacy_reports_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(8.dp))
            SettingsNote(stringResource(R.string.privacy_reports_note))
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
                        color = if (result == ShareOutcome.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            if (reports.isEmpty()) {
                SettingsNote(stringResource(R.string.privacy_reports_empty))
            } else {
                reports.forEachIndexed { index, report ->
                    ReportRow(
                        report = report,
                        onShare = { outcome = shareReport(context, report, config, appLabel) },
                        onDelete = {
                            reportQueue.remove(report.id)
                            reports = reportQueue.reports()
                            outcome = null
                        },
                    )
                    if (index != reports.lastIndex) SettingsDivider()
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reports.isNotEmpty()) {
                    TextButton(onClick = {
                        reportQueue.clear()
                        reports = reportQueue.reports()
                        outcome = null
                    }) {
                        Text(stringResource(R.string.privacy_reports_clear))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.privacy_audit_close))
                }
            }
        }
    }
}

@Composable
private fun ReportRow(
    report: AiReport,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = stringResource(reasonLabelRes(report.reason)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = formatTimestamp(report.createdAtMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (report.modelLabel.isNotBlank()) {
            Text(
                text = report.modelLabel,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = report.answerExcerpt,
            // Content direction: an English answer stays readable inside the RTL screen instead of
            // having its full stop reordered to the front.
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (report.note.isNotBlank()) {
            Text(
                text = report.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.privacy_report_share))
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.privacy_report_delete))
            }
        }
    }
}

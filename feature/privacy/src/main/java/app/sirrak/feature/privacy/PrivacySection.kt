package app.sirrak.feature.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.design.SettingsSwitchRow
import app.sirrak.core.privacy.AiReportQueue
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.OfflineModeStore
import app.sirrak.core.runtime.PrivacyConfig

/**
 * PRV-2/PRV-5/PRV-6 Settings group. Everything shown here is data-driven: the endpoint registry and
 * the policy links come from app.json, the counts come from the live stores. No row is hardcoded to
 * a host or a URL, so publishing a policy or adding a network user is a config edit.
 */
@Composable
fun PrivacySection(
    offlineModeStore: OfflineModeStore,
    audit: NetworkAudit,
    reportQueue: AiReportQueue,
    config: PrivacyConfig,
    modelLabel: () -> String,
    appLabel: String,
    onOfflineEnabled: () -> Unit = {},
) {
    var offline by remember { mutableStateOf(offlineModeStore.isOfflineOnly()) }
    var auditCount by remember { mutableIntStateOf(audit.events().size) }
    var reportCount by remember { mutableIntStateOf(reportQueue.reports().size) }
    var showAudit by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    SettingsSection(title = stringResource(R.string.privacy_section_title)) {
        SettingsSwitchRow(
            label = stringResource(R.string.privacy_offline_label),
            checked = offline,
            onCheckedChange = { enabled ->
                offline = enabled
                offlineModeStore.setOfflineOnly(enabled)
                // A running download must stop, not finish the request the user just forbade.
                if (enabled) onOfflineEnabled()
            },
            description = stringResource(R.string.privacy_offline_description),
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.privacy_network_audit),
            value = stringResource(R.string.privacy_network_audit_value, auditCount),
            onClick = { showAudit = true },
        )
        SettingsRow(
            label = stringResource(R.string.privacy_reports),
            value = stringResource(R.string.privacy_reports_value, reportCount),
            onClick = { showReports = true },
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.privacy_policy),
            value = if (config.policyUrl.isBlank()) {
                stringResource(R.string.privacy_not_published)
            } else {
                stringResource(R.string.privacy_open)
            },
            onClick = config.policyUrl.takeIf { it.isNotBlank() }?.let { url ->
                { uriHandler.openUri(url) }
            },
        )
        SettingsRow(
            label = stringResource(R.string.privacy_security),
            value = if (config.securityUrl.isBlank()) {
                stringResource(R.string.privacy_not_published)
            } else {
                stringResource(R.string.privacy_open)
            },
            onClick = config.securityUrl.takeIf { it.isNotBlank() }?.let { url ->
                { uriHandler.openUri(url) }
            },
        )
        SettingsNote(stringResource(R.string.privacy_note))
    }

    if (showAudit) {
        NetworkAuditSheet(
            audit = audit,
            config = config,
            onDismiss = {
                showAudit = false
                auditCount = audit.events().size
            },
        )
    }
    if (showReports) {
        AiReportsSheet(
            reportQueue = reportQueue,
            config = config,
            appLabel = appLabel,
            modelLabel = modelLabel,
            onDismiss = {
                showReports = false
                reportCount = reportQueue.reports().size
            },
        )
    }
}

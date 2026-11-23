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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.NetworkCall
import app.sirrak.core.privacy.NetworkOutcome
import app.sirrak.core.runtime.NetworkEndpoint
import app.sirrak.core.runtime.PrivacyConfig

/**
 * PRV-2 network transparency: the endpoints the app declares it may contact, and the on-device log
 * of the calls it actually made. The log is local, bounded and clearable; it never leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkAuditSheet(
    audit: NetworkAudit,
    config: PrivacyConfig,
    onDismiss: () -> Unit,
) {
    var events by remember { mutableStateOf(audit.events()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.privacy_audit_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(16.dp))
            SettingsSection(title = stringResource(R.string.privacy_audit_declared)) {
                if (config.endpoints.isEmpty()) {
                    SettingsNote(stringResource(R.string.privacy_audit_no_endpoints))
                }
                config.endpoints.forEach { endpoint -> EndpointRow(endpoint) }
            }
            Spacer(Modifier.size(16.dp))
            SettingsSection(title = stringResource(R.string.privacy_audit_calls)) {
                if (events.isEmpty()) {
                    SettingsNote(stringResource(R.string.privacy_audit_empty))
                } else {
                    events.forEachIndexed { index, event ->
                        NetworkEventRow(event)
                        if (index != events.lastIndex) SettingsDivider()
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (events.isNotEmpty()) {
                    TextButton(onClick = {
                        audit.clear()
                        events = audit.events()
                    }) {
                        Text(stringResource(R.string.privacy_audit_clear))
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
private fun EndpointRow(endpoint: NetworkEndpoint) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = endpoint.host,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = endpointPurpose(endpoint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = endpointWhenUsed(endpoint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (endpoint.userInitiated) {
            Text(
                text = stringResource(R.string.privacy_audit_user_initiated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NetworkEventRow(event: NetworkCall) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = event.host,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatTimestamp(event.startedAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(outcomeLabelRes(event.outcome)),
                style = MaterialTheme.typography.labelMedium,
                color = if (event.outcome == NetworkOutcome.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            val bytes = formatBytes(event.bytes)
            if (bytes.isNotEmpty()) {
                Text(
                    text = bytes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun outcomeLabelRes(outcome: NetworkOutcome): Int = when (outcome) {
    NetworkOutcome.STARTED -> R.string.privacy_audit_started
    NetworkOutcome.COMPLETED -> R.string.privacy_audit_completed
    NetworkOutcome.FAILED -> R.string.privacy_audit_failed
}

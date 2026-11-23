package app.sirrak.feature.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsKeyValueRow
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.DeviceProfile
import app.sirrak.core.models.SpeedEstimator
import app.sirrak.core.runtime.InstalledModel
import java.util.Locale

/**
 * MOD-4 model detail: everything known about one catalogue entry before the user commits a gigabyte,
 * plus the one action that makes sense for its state (download / select / delete). Values are passed
 * in, so the same sheet serves the Settings list and the onboarding recommendation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailSheet(
    entry: CatalogueEntry,
    profile: DeviceProfile,
    installed: InstalledModel?,
    isSelected: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = entry.licence,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            SettingsSection(title = stringResource(R.string.models_detail_title)) {
                if (entry.parameterLabel.isNotBlank()) {
                    SettingsKeyValueRow(stringResource(R.string.models_detail_parameters), entry.parameterLabel)
                }
                if (entry.quantization.isNotBlank()) {
                    SettingsKeyValueRow(stringResource(R.string.models_detail_quant), entry.quantization)
                }
                SettingsKeyValueRow(stringResource(R.string.models_detail_size), entry.sizeLabel)
                if (entry.ramMb > 0) {
                    SettingsKeyValueRow(stringResource(R.string.models_detail_ram), "${entry.ramMb} MB")
                }
                if (entry.contextTokens > 0) {
                    SettingsKeyValueRow(stringResource(R.string.models_detail_context), "${entry.contextTokens}")
                }
                if (entry.languages.isNotEmpty()) {
                    SettingsKeyValueRow(stringResource(R.string.models_detail_languages), entry.languages.joinToString(", "))
                }
                SettingsKeyValueRow(stringResource(R.string.models_detail_repo), entry.repo)
                SettingsKeyValueRow(stringResource(R.string.models_detail_file), entry.fileName)
                entry.visionProjector?.let { projector ->
                    SettingsKeyValueRow(stringResource(R.string.models_detail_projector), projector.fileName)
                }
                val speed = SpeedEstimator.estimatedTokensPerSecond(entry, profile)
                if (speed != null) {
                    SettingsKeyValueRow(
                        stringResource(R.string.models_detail_speed),
                        stringResource(
                            R.string.models_detail_speed_value,
                            String.format(Locale.US, "%.1f", speed),
                        ),
                    )
                }
            }
            SettingsNote(stringResource(R.string.models_detail_estimate_note))
            val licenceUrl = entry.licenceUrl
            if (licenceUrl != null) {
                SettingsNote(licenceUrl)
            }
            if (installed != null) {
                SettingsDivider()
                SettingsNote(stringResource(R.string.models_detail_installed_note))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                if (installed == null) {
                    Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.models_action_download))
                    }
                } else {
                    if (!isSelected) {
                        Button(onClick = onSelect, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.models_action_select))
                        }
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.models_action_delete))
                    }
                }
            }
        }
    }
}

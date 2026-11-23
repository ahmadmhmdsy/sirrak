package app.sirrak

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.runtime.LicenceEntry
import app.sirrak.core.runtime.LicenceKind
import app.sirrak.core.runtime.LicenceRegistry

/**
 * P3.2 licence/SBOM sheet. One source of truth: [LicenceRegistry] from `config/licences.json`. The
 * list is grouped by component kind; tapping a component shows the full licence text bundled in the
 * APK when present (works offline) and otherwise offers the canonical https link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesSheet(registry: LicenceRegistry, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<LicenceEntry?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val entry = selected
        if (entry == null) {
            LicenceList(registry = registry, onSelect = { selected = it }, onDismiss = onDismiss)
        } else {
            LicenceText(entry = entry, onBack = { selected = null }, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun LicenceList(
    registry: LicenceRegistry,
    onSelect: (LicenceEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            text = stringResource(R.string.about_licenses),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        SettingsNote(stringResource(R.string.about_licences_sheet_note))
        if (registry.entries.isEmpty()) {
            SettingsNote(stringResource(R.string.licences_empty))
        } else {
            LicenceKind.entries.forEach { kind ->
                val entries = registry.entries.filter { it.kindOrLibrary == kind }
                if (entries.isEmpty()) return@forEach
                Spacer(Modifier.size(14.dp))
                Text(
                    text = kindLabel(kind),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                entries.forEachIndexed { index, entry ->
                    LicenceRow(entry = entry, onClick = { onSelect(entry) })
                    if (index != entries.lastIndex) SettingsDivider()
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.licences_close)) }
        }
    }
}

@Composable
private fun LicenceRow(entry: LicenceEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name + if (entry.version.isNotBlank()) " " + entry.version else "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = entry.licence,
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LicenceBadge(
                text = stringResource(if (entry.bundled) R.string.licences_bundled else R.string.licences_external),
                highlighted = entry.bundled,
            )
            if (entry.textAsset.isNotBlank()) {
                LicenceBadge(text = stringResource(R.string.licences_full_text), highlighted = false)
            }
        }
        if (entry.copyright.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = entry.copyright,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicenceBadge(text: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LicenceText(entry: LicenceEntry, onBack: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val text = remember(entry.id) {
        if (entry.textAsset.isBlank()) {
            null
        } else {
            runCatching {
                context.assets.open(entry.textAsset).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            text = entry.name + if (entry.version.isNotBlank()) " " + entry.version else "",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = entry.licence,
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.primary,
        )
        if (entry.copyright.isNotBlank()) SettingsNote(entry.copyright)
        if (text != null) {
            Spacer(Modifier.size(8.dp))
            SelectionContainer {
                Text(
                    text = text,
                    // Licence texts are English prose: keep their paragraphs LTR inside the RTL UI.
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = TextDirection.Ltr,
                        textAlign = TextAlign.Start,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            SettingsNote(stringResource(R.string.licences_text_missing))
            if (entry.licenceUrl.isNotBlank()) {
                TextButton(onClick = { runCatching { uriHandler.openUri(entry.licenceUrl) } }) {
                    Text(stringResource(R.string.licences_open_link))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.licences_back)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.licences_close)) }
        }
    }
}

@Composable
private fun kindLabel(kind: LicenceKind): String = stringResource(
    when (kind) {
        LicenceKind.APP -> R.string.licences_kind_app
        LicenceKind.FRAMEWORK -> R.string.licences_kind_framework
        LicenceKind.LIBRARY -> R.string.licences_kind_library
        LicenceKind.ENGINE -> R.string.licences_kind_engine
        LicenceKind.FONT -> R.string.licences_kind_font
        LicenceKind.MODEL -> R.string.licences_kind_model
        LicenceKind.TOOL -> R.string.licences_kind_tool
    },
)

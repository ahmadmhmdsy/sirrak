package app.sirrak.core.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

/**
 * A labelled slider with a live value, an optional "where this default came from" line, and an
 * optional reset action. Reusable across every numeric model setting: the caller owns the label, the
 * range and the value formatting, so adding a parameter never needs a new component.
 */
@Composable
fun SettingsSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    sourceText: String? = null,
    // Required on purpose: no hardcoded English control text inside a localised component.
    resetLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    onReset: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (sourceText != null) {
                    Text(
                        text = sourceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onReset != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(resetLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                // TalkBack reads the setting name and its current value, not just a percentage.
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                },
        )
    }
}

/** A labelled on/off row; one component for every boolean setting. */
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // The whole row handles the toggle; the visual switch must not stay a second focus node.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One selectable chip: [id] is stable data, [label] is already localised. */
data class SettingsChip(val id: String, val label: String, val selected: Boolean)

/**
 * A wrapping row of single-choice chips, used for prompt presets. Options are data, so a new preset
 * in the asset file appears here with no code change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsChipRow(
    label: String,
    chips: List<SettingsChip>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow {
            chips.forEach { chip ->
                FilterChip(
                    selected = chip.selected,
                    onClick = { onSelect(chip.id) },
                    label = { Text(chip.label) },
                    modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}

/**
 * A plain multi-line text editor dialog, reused for system prompts, stop sequences and any future
 * free-text model setting. Validation stays with the caller: [onConfirm] receives the raw text.
 */
@Composable
fun SettingsLongTextDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    singleLine: Boolean = false,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = title },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 6,
                maxLines = if (singleLine) 1 else 12,
                placeholder = placeholder?.let { text -> { Text(text) } },
                keyboardOptions = keyboardOptions,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/**
 * A label with its value stacked underneath, for long technical values (hashes, ids) that do not fit
 * on one settings line. Reusable: the caller owns label, value and direction.
 */
@Composable
fun SettingsStackedRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    direction: TextDirection = TextDirection.Ltr,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                textDirection = direction,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A read-only block of literal text (a rendered prompt, a file name). Technical values stay LTR by
 * default; prose passes [TextDirection.Content] so the block follows the surrounding language.
 */
@Composable
fun SettingsTextBlock(
    text: String,
    modifier: Modifier = Modifier,
    direction: TextDirection = TextDirection.Ltr,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (text.length > 600) 320.dp else 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                textDirection = direction,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
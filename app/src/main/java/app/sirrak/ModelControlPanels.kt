package app.sirrak

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.SettingsChoiceRow
import app.sirrak.core.design.SettingsChip
import app.sirrak.core.design.SettingsStackedRow
import app.sirrak.core.design.SettingsChipRow
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsLongTextDialog
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.design.SettingsSliderRow
import app.sirrak.core.design.SettingsSwitchRow
import app.sirrak.core.design.SettingsTextBlock
import app.sirrak.core.runtime.DefaultSource
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.ModelFacts
import app.sirrak.core.runtime.PromptSource
import app.sirrak.core.runtime.SamplingMode
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.TemplateChoice
import app.sirrak.core.runtime.TemplateSource
import app.sirrak.engine.llamacpp.LlamaCppEngine
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings > Model: the single UI for the M5 precedence layers.
 *
 * Every row reads and writes through [AppModelControlService]; the screen renders the *effective*
 * value plus where it came from, and a per-row reset drops exactly one override. Nothing here owns
 * persistence, so the same service already answers the chat send path.
 *
 * The parameter rows are generated from [ModelParameters.ALL], so adding a generation parameter is a
 * catalogue row + a string, not a new composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelControlSections(
    modelControl: AppModelControlService,
    engine: LlamaCppEngine,
    settings: SettingsService,
) {
    val selected by modelControl.selectedModel.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val overridesByModel by modelControl.modelSettingsState.collectAsStateWithLifecycle()
    val advanced by settings.modelAdvanced.collectAsStateWithLifecycle()
    val globalPrompt by settings.systemPrompt.collectAsStateWithLifecycle()
    val language by settings.language.collectAsStateWithLifecycle()

    val modelKey = selected?.let { modelControl.currentKey() }
    val overrides = modelKey?.let(overridesByModel::get)
    val touched = overrides?.touchedParams?.toSet().orEmpty()
    val facts = if (status is EngineStatus.Ready) modelControl.modelFacts() else null
    var params by remember(modelKey) { mutableStateOf(modelControl.effectiveParams()) }
    // Re-read after a load (facts appear), a selection change, or an edit persisted anywhere.
    LaunchedEffect(modelKey, overrides, status) { params = modelControl.effectiveParams() }

    var editingStop by remember { mutableStateOf(false) }
    var editingSeed by remember { mutableStateOf(false) }
    var editingGlobalPrompt by remember { mutableStateOf(false) }
    var editingModelPrompt by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var templatesOpen by remember { mutableStateOf(false) }

    val previewSample = stringResource(R.string.model_preview_sample)
    val resetLabel = stringResource(R.string.action_reset)
    val saveLabel = stringResource(R.string.action_save)
    val cancelLabel = stringResource(R.string.action_cancel)

    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        SettingsSection(title = stringResource(R.string.model_control_title)) {
            SettingsRow(
                label = stringResource(R.string.model_control_model),
                value = selected?.displayName ?: stringResource(R.string.engine_no_model),
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.model_defaults_source),
                value = defaultSourceLabel(modelControl.defaultsSource()),
            )
            if (overrides?.userModifiedAt != null) {
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.model_reset_all),
                    onClick = { modelControl.resetModelSettings() },
                )
            }
            SettingsDivider()
            SettingsSwitchRow(
                label = stringResource(R.string.model_advanced_mode),
                description = stringResource(R.string.model_advanced_mode_desc),
                checked = advanced,
                onCheckedChange = settings::setModelAdvanced,
            )
        }

        if (selected == null) {
            SettingsNote(stringResource(R.string.model_control_no_model))
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        SettingsSection(title = stringResource(R.string.model_parameters_title)) {
            val visible = if (advanced) ModelParameters.ALL else ModelParameters.simple
            visible.forEachIndexed { index, spec ->
                if (index > 0) SettingsDivider()
                val overridden = spec.id in touched
                val value = spec.get(params)
                SettingsSliderRow(
                    label = stringResource(spec.labelRes),
                    valueText = spec.format(value),
                    value = spec.snap(value),
                    valueRange = spec.min..spec.max,
                    // Continuous track: the value snaps to the spec step in onValueChange, so the
                    // slider stays quiet while still recording only representable values.
                    steps = 0,
                    sourceText = if (overridden) stringResource(R.string.default_source_user) else null,
                    resetLabel = resetLabel,
                    onValueChange = { raw -> params = spec.set(params, spec.snap(raw)) },
                    onValueChangeFinished = { modelControl.saveParams(params, setOf(spec.id)) },
                    onReset = if (overridden) ({ modelControl.resetParam(spec.id) }) else null,
                )
            }
            if (advanced) {
                SettingsDivider()
                val modes = SamplingMode.entries
                SettingsChoiceRow(
                    label = stringResource(R.string.model_sampling_title),
                    options = modes.map { samplingModeLabel(it) },
                    selectedIndex = modes.indexOf(params.samplingMode).coerceAtLeast(0),
                    onSelect = { index ->
                        val updated = params.copy(samplingMode = modes[index])
                        params = updated
                        modelControl.saveParams(updated, setOf("samplingMode"))
                    },
                )
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.model_seed),
                    value = if (params.seed < 0) stringResource(R.string.model_seed_random) else params.seed.toString(),
                    onClick = { editingSeed = true },
                    trailing = if ("seed" in touched) {
                        {
                            TextButton(onClick = { modelControl.resetParam("seed") }) { Text(resetLabel) }
                        }
                    } else {
                        null
                    },
                )
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.model_stop),
                    value = stringResource(
                        R.string.model_stop_count,
                        params.stop.size,
                        app.sirrak.core.runtime.GenerationRanges.MAX_STOP_SEQUENCES,
                    ),
                    onClick = { editingStop = true },
                    trailing = if ("stop" in touched) {
                        {
                            TextButton(onClick = { modelControl.resetParam("stop") }) { Text(resetLabel) }
                        }
                    } else {
                        null
                    },
                )
                val maxStopSequences = app.sirrak.core.runtime.GenerationRanges.MAX_STOP_SEQUENCES
                SettingsNote(
                    pluralStringResource(
                        R.plurals.model_stop_hint,
                        maxStopSequences,
                        maxStopSequences,
                        app.sirrak.core.runtime.GenerationRanges.MAX_STOP_LENGTH,
                    ),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        val resolvedTemplate = modelControl.resolvedTemplate()
        SettingsSection(title = stringResource(R.string.model_template_title)) {
            SettingsRow(
                label = stringResource(R.string.model_template_source),
                value = templateSourceLabel(resolvedTemplate.source),
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.model_template_choose),
                value = when (val choice = resolvedTemplate.choice) {
                    is TemplateChoice.Builtin -> choice.name
                    TemplateChoice.ModelDefault -> stringResource(R.string.model_template_model_default)
                },
                onClick = { templatesOpen = true },
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.model_template_preview),
                onClick = {
                    previewText = modelControl.previewPrompt(previewSample)
                    previewOpen = true
                },
            )
            if (overrides?.template != null) {
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.model_template_reset),
                    onClick = { modelControl.resetTemplate() },
                )
            }
            val templateLine = facts?.templateFirstLine
            val templateHash = facts?.templateHash
            when {
                templateHash != null -> {
                    SettingsDivider()
                    SettingsStackedRow(
                        label = stringResource(R.string.model_template_hash),
                        value = templateHash,
                    )
                    if (templateLine != null) SettingsNote(templateLine)
                }
                facts != null -> SettingsNote(stringResource(R.string.model_template_none))
            }
        }

        Spacer(Modifier.height(16.dp))
        val rescoped = modelControl.resolvedPrompt()
        SettingsSection(title = stringResource(R.string.model_prompts_title)) {
            SettingsRow(
                label = stringResource(R.string.model_prompt_global),
                value = promptPreview(globalPrompt) ?: stringResource(R.string.model_prompt_app_default),
                onClick = { editingGlobalPrompt = true },
                trailing = if (globalPrompt != null) {
                    {
                        TextButton(onClick = { modelControl.setGlobalSystemPrompt(null) }) { Text(resetLabel) }
                    }
                } else {
                    null
                },
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.model_prompt_model),
                value = promptPreview(overrides?.systemPrompt) ?: stringResource(R.string.model_prompt_inherit),
                onClick = { editingModelPrompt = true },
                trailing = if (overrides?.systemPrompt != null) {
                    {
                        TextButton(onClick = { modelControl.setModelSystemPrompt(null) }) { Text(resetLabel) }
                    }
                } else {
                    null
                },
            )
            SettingsDivider()
            SettingsChipRow(
                label = stringResource(R.string.model_prompt_presets),
                chips = modelControl.promptPresets().presets.map { preset ->
                    SettingsChip(
                        id = preset.id,
                        label = modelControl.localised(preset.name),
                        selected = globalPrompt == preset.systemPrompt.inLanguage(language),
                    )
                },
                onSelect = { id -> modelControl.applyPromptPreset(id) },
            )
            SettingsNote(stringResource(R.string.model_prompt_presets_note))
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.model_prompt_resolved),
                value = promptSourceLabel(rescoped.source),
            )
        }

        facts?.let { loaded ->
            Spacer(Modifier.height(16.dp))
            ModelFactsSection(loaded)
        }
    }

    if (editingStop) {
        SettingsLongTextDialog(
            title = stringResource(R.string.model_stop),
            initial = params.stop.joinToString("\n"),
            confirmLabel = saveLabel,
            dismissLabel = cancelLabel,
            onDismiss = { editingStop = false },
            onConfirm = { raw ->
                editingStop = false
                val stops = raw.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(app.sirrak.core.runtime.GenerationRanges.MAX_STOP_SEQUENCES)
                    .map { it.take(app.sirrak.core.runtime.GenerationRanges.MAX_STOP_LENGTH) }
                    .toList()
                val updated = params.copy(stop = stops)
                params = updated
                modelControl.saveParams(updated, setOf("stop"))
            },
        )
    }
    if (editingSeed) {
        SettingsLongTextDialog(
            title = stringResource(R.string.model_seed),
            initial = if (params.seed < 0) "" else params.seed.toString(),
            confirmLabel = saveLabel,
            dismissLabel = cancelLabel,
            singleLine = true,
            placeholder = stringResource(R.string.model_seed_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onDismiss = { editingSeed = false },
            onConfirm = { raw ->
                val text = raw.trim()
                // Empty means "random per request"; anything else must be a base-10 integer. Invalid
                // input leaves the dialog open so the value is never silently discarded.
                val parsed = if (text.isEmpty()) -1 else text.toIntOrNull()
                if (parsed != null) {
                    editingSeed = false
                    val updated = params.copy(seed = parsed.coerceAtLeast(-1))
                    params = updated
                    modelControl.saveParams(updated, setOf("seed"))
                }
            },
        )
    }
    if (editingGlobalPrompt) {
        SettingsLongTextDialog(
            title = stringResource(R.string.model_prompt_global),
            initial = globalPrompt.orEmpty(),
            confirmLabel = saveLabel,
            dismissLabel = cancelLabel,
            onDismiss = { editingGlobalPrompt = false },
            onConfirm = { raw ->
                editingGlobalPrompt = false
                modelControl.setGlobalSystemPrompt(raw)
            },
        )
    }
    if (editingModelPrompt) {
        SettingsLongTextDialog(
            title = stringResource(R.string.model_prompt_model),
            initial = overrides?.systemPrompt.orEmpty(),
            confirmLabel = saveLabel,
            dismissLabel = cancelLabel,
            onDismiss = { editingModelPrompt = false },
            onConfirm = { raw ->
                editingModelPrompt = false
                modelControl.setModelSystemPrompt(raw)
            },
        )
    }
    if (previewOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { previewOpen = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(
                    text = stringResource(R.string.model_template_preview),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                val rendered = previewText
                if (rendered == null) {
                    Text(
                        text = stringResource(R.string.model_template_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SettingsTextBlock(rendered, direction = TextDirection.Content)
                }
            }
        }
    }
    if (templatesOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val current = modelControl.resolvedTemplate()
        val builtins = modelControl.builtinTemplates()
        ModalBottomSheet(onDismissRequest = { templatesOpen = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(
                    text = stringResource(R.string.model_template_choose),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                TemplateRow(
                    label = stringResource(R.string.model_template_model_default),
                    selected = current.choice == TemplateChoice.ModelDefault,
                    onClick = {
                        modelControl.resetTemplate()
                        templatesOpen = false
                    },
                )
                builtins.forEach { name ->
                    TemplateRow(
                        label = name,
                        selected = current.choice == TemplateChoice.Builtin(name),
                        onClick = {
                            modelControl.setTemplate(TemplateChoice.Builtin(name))
                            templatesOpen = false
                        },
                    )
                }
                if (builtins.isEmpty()) {
                    Text(
                        text = stringResource(R.string.model_template_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

}

@Composable
private fun TemplateRow(label: String, selected: Boolean, onClick: () -> Unit) {
    SettingsRow(
        label = label,
        value = if (selected) stringResource(R.string.engine_selected) else null,
        modifier = Modifier.semantics { this.selected = selected },
        onClick = onClick,
    )
}

@Composable
private fun ModelFactsSection(facts: ModelFacts) {
    SettingsSection(title = stringResource(R.string.model_facts_title)) {
        val unknown = stringResource(R.string.fact_unknown)
        SettingsRow(
            label = stringResource(R.string.fact_architecture),
            value = facts.architecture ?: unknown,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.fact_context),
            value = facts.nCtxTrain.takeIf { it > 0 }?.toString() ?: unknown,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.fact_parameters),
            value = compactCount(facts.nParams),
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.fact_vocab),
            value = facts.nVocab.takeIf { it > 0 }?.toString() ?: unknown,
        )
    }
}

/**
 * Compact parameter count. The digits stay Latin technical values; the unit word comes from the
 * current language, so Arabic reads "١٫٥ مليار" style text rather than a hardcoded "B".
 */
@Composable
private fun compactCount(value: Long): String {
    val unknown = stringResource(R.string.fact_unknown)
    return when {
        value <= 0 -> unknown
        value >= 1_000_000_000L -> stringResource(
            R.string.fact_count_billion,
            String.format(Locale.US, "%.2f", value / 1_000_000_000.0),
        )
        value >= 1_000_000L -> stringResource(
            R.string.fact_count_million,
            String.format(Locale.US, "%.0f", value / 1_000_000.0),
        )
        else -> value.toString()
    }
}

/** First line of a prompt, truncated; null when there is nothing to show. */
private fun promptPreview(prompt: String?): String? =
    prompt?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.length > 48) it.take(47) + "…" else it
    }

@Composable
private fun defaultSourceLabel(source: DefaultSource): String = stringResource(
    when (source) {
        DefaultSource.CODE -> R.string.default_source_code
        DefaultSource.APP_DEFAULT -> R.string.default_source_app
        DefaultSource.PRESET -> R.string.default_source_preset
        DefaultSource.MODEL -> R.string.default_source_model
        DefaultSource.USER -> R.string.default_source_user
        DefaultSource.CONVERSATION -> R.string.default_source_conversation
    },
)

@Composable
private fun promptSourceLabel(source: PromptSource): String = stringResource(
    when (source) {
        PromptSource.RESOURCE -> R.string.prompt_source_resource
        PromptSource.APP_DEFAULT -> R.string.prompt_source_app
        PromptSource.GLOBAL -> R.string.prompt_source_global
        PromptSource.MODEL -> R.string.prompt_source_model
        PromptSource.CONVERSATION -> R.string.prompt_source_conversation
    },
)

@Composable
private fun templateSourceLabel(source: TemplateSource): String = stringResource(
    when (source) {
        TemplateSource.MODEL -> R.string.template_source_model
        TemplateSource.BUILTIN -> R.string.template_source_builtin
        TemplateSource.FALLBACK -> R.string.template_source_fallback
        TemplateSource.NONE -> R.string.template_source_none
    },
)

@Composable
private fun samplingModeLabel(mode: SamplingMode): String = stringResource(
    when (mode) {
        SamplingMode.DEFAULT -> R.string.sampling_default
        SamplingMode.GREEDY -> R.string.sampling_greedy
        SamplingMode.MIROSTAT_V2 -> R.string.sampling_mirostat
    },
)
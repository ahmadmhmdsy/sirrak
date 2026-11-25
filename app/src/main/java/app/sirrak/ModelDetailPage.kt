package app.sirrak

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.PageScaffold
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.SettingsService
import app.sirrak.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.launch

/**
 * The model page: everything about one model, where it belongs instead of buried in Settings.
 * It owns the per-model controls (the M5 precedence layers), the context window, the load/unload
 * lifecycle and the delete decision. The chat chip's passport and the Library cards open it.
 */
@Composable
fun ModelDetailPage(
    modelId: String?,
    store: ModelStore,
    engine: LlamaCppEngine,
    modelControl: AppModelControlService,
    settings: SettingsService,
    onBack: () -> Unit,
) {
    val models by store.models.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val overridesByModel by modelControl.modelSettingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf(false) }

    val model = models.firstOrNull { it.id == modelId } ?: models.firstOrNull { it.path == modelId }
    val isSelected = model != null && selected?.path == model.path
    val modelKey = remember(isSelected, model?.path, selected?.path) {
        if (isSelected) modelControl.currentKey() else null
    }
    // Reading the overrides map keeps the chosen window and the parameter rows in sync after an edit.
    val effectiveContext = remember(modelKey, overridesByModel, status) {
        modelControl.requestedContextTokens(modelKey)
    }
    val trainedContext = remember(modelKey, status) {
        modelControl.trainedContextTokens(modelKey)
    }
    val choices = remember(modelKey, status) { modelControl.contextChoices(modelKey) }
    val hasStoredContext = remember(modelKey, overridesByModel) {
        modelControl.storedContextTokens(modelKey) != null
    }

    PageScaffold(
        title = model?.displayName ?: stringResource(R.string.model_page_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.action_back),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (model == null) {
                    SettingsNote(stringResource(R.string.model_page_missing))
                } else {
                    SettingsSection(title = stringResource(R.string.library_engine_title)) {
                        SettingsRow(
                            label = stringResource(R.string.model_status),
                            value = engineStatusLabel(status),
                        )
                        SettingsDivider()
                        SettingsRow(
                            label = stringResource(R.string.model_disk),
                            value = model.sizeLabel,
                        )
                        model.quantization?.let { quantization ->
                            SettingsDivider()
                            SettingsRow(label = stringResource(R.string.model_quant), value = quantization)
                        }
                        if (!isSelected) {
                            SettingsDivider()
                            SettingsRow(
                                label = stringResource(R.string.model_page_select),
                                onClick = { store.select(model) },
                            )
                        } else {
                            SettingsDivider()
                            val ready = status is EngineStatus.Ready
                            SettingsRow(
                                label = stringResource(
                                    if (ready) R.string.library_engine_unload else R.string.library_engine_load,
                                ),
                                onClick = {
                                    if (ready) {
                                        engine.unload()
                                    } else {
                                        scope.launch {
                                            engine.load(model, modelControl.requestedContextTokens(modelKey))
                                        }
                                    }
                                },
                            )
                        }
                        if (status is EngineStatus.Failed) {
                            SettingsNote(stringResource(R.string.engine_passport_failed_body))
                        }
                    }
                    Spacer(Modifier.height(20.dp))

                    if (isSelected) {
                        SettingsSection(title = stringResource(R.string.model_context_title)) {
                            SettingsNote(
                                text = trainedContext?.let { context ->
                                    pluralStringResource(R.plurals.model_context_note, context, context)
                                } ?: stringResource(R.string.model_context_note_unknown),
                            )
                            choices.forEachIndexed { index, candidate ->
                                if (index > 0) SettingsDivider()
                                SettingsRow(
                                    label = candidate.toString(),
                                    value = if (candidate == effectiveContext) {
                                        stringResource(R.string.model_context_active)
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        modelControl.setContextTokens(candidate)
                                        if (status is EngineStatus.Ready) {
                                            scope.launch {
                                                engine.load(model, modelControl.requestedContextTokens(modelKey))
                                            }
                                        }
                                    },
                                )
                            }
                            if (hasStoredContext) {
                                SettingsDivider()
                                SettingsRow(
                                    label = stringResource(R.string.model_context_reset),
                                    onClick = {
                                        modelControl.setContextTokens(null)
                                        if (status is EngineStatus.Ready) {
                                            scope.launch {
                                                engine.load(model, modelControl.requestedContextTokens(modelKey))
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))

                        // The whole existing per-model control surface (params, template, prompts) lives
                        // here now; it reads and writes the selected model through the same service.
                        ModelControlSections(
                            modelControl = modelControl,
                            engine = engine,
                            settings = settings,
                        )
                    } else {
                        SettingsSection(title = stringResource(R.string.model_page_settings_title)) {
                            SettingsNote(stringResource(R.string.model_page_settings_locked))
                        }
                    }
                    Spacer(Modifier.height(20.dp))

                    SettingsSection(title = stringResource(R.string.model_page_danger_title)) {
                        SettingsRow(
                            label = stringResource(R.string.library_delete_confirm),
                            onClick = { pendingDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (pendingDelete && model != null) {
        val current = model
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(R.string.library_delete_title, current.displayName)) },
            text = { Text(stringResource(R.string.library_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(current)
                    pendingDelete = false
                    onBack()
                }) { Text(stringResource(R.string.library_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text(stringResource(R.string.library_delete_cancel))
                }
            },
        )
    }
}

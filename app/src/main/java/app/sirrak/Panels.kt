package app.sirrak

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.ModelChip
import app.sirrak.core.design.ScreenTitle
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsKeyValueRow
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.PageIds
import app.sirrak.core.runtime.SettingsService
import app.sirrak.engine.llamacpp.LlamaCppEngine
import app.sirrak.engine.llamacpp.LlamaProjector
import java.io.File
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * Chat header chip. It is a doorway, not a label: "no model" is a state with an action (open the
 * Library), a failed load offers a retry, and a ready model opens its passport. The chip must never
 * say two contradictory things at once — the defect this replaces showed "no model" and "could not
 * load" together because both states fell into the same `else`.
 */
@Composable
fun EngineModelChip(
    engine: InferenceEngine,
    store: ModelStore,
    navigator: AppNavigator,
    onLoad: (InstalledModel) -> Unit = {},
    onUnload: () -> Unit = {},
) {
    val status by engine.status.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val label = when (val current = status) {
        is EngineStatus.Ready -> current.label
        is EngineStatus.Loading -> current.label ?: engine.displayName
        is EngineStatus.Failed -> stringResource(R.string.engine_chip_failed)
        is EngineStatus.Unloaded -> if (selected != null) {
            stringResource(R.string.engine_chip_unloaded)
        } else {
            stringResource(R.string.engine_no_model)
        }
    }
    ModelChip(
        label = label,
        status = engineStatusLabel(status),
        onClick = { open = true },
    )
    if (open) {
        ModelDoorwaySheet(
            engine = engine,
            selected = selected,
            navigator = navigator,
            status = status,
            onLoad = onLoad,
            onUnload = onUnload,
            onOpenLibrary = { navigator.select("library") },
            onDismiss = { open = false },
        )
    }
}

@Composable
internal fun engineStatusLabel(status: EngineStatus): String = stringResource(
    when (status) {
        is EngineStatus.Ready -> R.string.engine_status_ready
        is EngineStatus.Loading -> R.string.engine_status_loading
        is EngineStatus.Failed -> R.string.engine_status_failed
        is EngineStatus.Unloaded -> R.string.engine_status_unloaded
    },
)

/** The chip's sheet: a doorway to the Library, a retry after failure, or the loaded model's passport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDoorwaySheet(
    engine: InferenceEngine,
    selected: InstalledModel?,
    navigator: AppNavigator,
    status: EngineStatus,
    onLoad: (InstalledModel) -> Unit,
    onUnload: () -> Unit,
    onOpenLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            when (status) {
                is EngineStatus.Ready -> {
                    ScreenTitle(stringResource(R.string.engine_passport_title))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.engine_passport_ready_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsSection(title = selected?.displayName ?: engine.displayName, emphasis = true) {
                        SettingsKeyValueRow(stringResource(R.string.model_status), engineStatusLabel(status))
                        selected?.let { model ->
                            SettingsDivider()
                            SettingsKeyValueRow(stringResource(R.string.model_disk), model.sizeLabel)
                            model.quantization?.let { quantization ->
                                SettingsDivider()
                                SettingsKeyValueRow(stringResource(R.string.model_quant), quantization)
                            }
                        }
                        engine.modelFacts?.architecture?.let { architecture ->
                            SettingsDivider()
                            SettingsKeyValueRow(stringResource(R.string.model_arch), architecture)
                        }
                        engine.modelFacts?.takeIf { it.nCtxTrain > 0 }?.let { facts ->
                            SettingsDivider()
                            SettingsKeyValueRow(stringResource(R.string.model_context_train), facts.nCtxTrain.toString())
                        }
                        if (engine.modelFacts?.hasModelTemplate == true) {
                            SettingsDivider()
                            SettingsKeyValueRow(
                                stringResource(R.string.model_template),
                                stringResource(R.string.model_template_model),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (selected != null) {
                        TextButton(
                            onClick = {
                                navigator.push(PageIds.MODEL_DETAIL, selected.id)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.model_page_title)) }
                    }
                    Button(onClick = { onUnload(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.library_engine_unload))
                    }
                    TextButton(onClick = { onOpenLibrary(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.engine_open_library))
                    }
                }
                is EngineStatus.Failed -> {
                    ScreenTitle(stringResource(R.string.engine_status_failed))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.engine_passport_failed_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (selected != null) {
                        Button(
                            onClick = { onLoad(selected); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.engine_retry)) }
                    }
                    TextButton(onClick = { onOpenLibrary(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.engine_open_library))
                    }
                }
                is EngineStatus.Loading -> {
                    ScreenTitle(stringResource(R.string.engine_status_loading))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.engine_passport_loading_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EngineStatus.Unloaded -> {
                    if (selected != null) {
                        ScreenTitle(stringResource(R.string.engine_passport_title))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.engine_chip_unloaded_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onLoad(selected); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.library_engine_load)) }
                        TextButton(onClick = { onOpenLibrary(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.engine_open_library))
                        }
                    } else {
                        ScreenTitle(stringResource(R.string.engine_no_model))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.engine_passport_no_model_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { onOpenLibrary(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.engine_download_cta))
                        }
                    }
                }
            }
        }
    }
}

/** Settings group for the demo provider: status only, no file management. */
@Composable
fun DemoEngineSettingsSection(engine: InferenceEngine) {
    val status by engine.status.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        SettingsSection(title = stringResource(R.string.engine_section_title)) {
            SettingsRow(
                label = stringResource(R.string.engine_provider),
                value = engine.displayName,
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.model_status),
                value = engineStatusLabel(status),
            )
            SettingsNote(stringResource(R.string.engine_demo_desc))
        }
    }
}

/**
 * Settings group for the real engine: import a GGUF file, choose the resident model, load/unload it,
 * and see the honest lifecycle state. All persistence lives in [ModelStore], not in this UI.
 */
@Composable
fun LlamaEngineSettingsSection(
    engine: LlamaCppEngine,
    store: ModelStore,
    modelControl: AppModelControlService,
    settings: SettingsService,
) {
    val models by store.models.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    // M9: projector import validates the file by actually opening its mtmd caps, then either keeps or
    // removes it; the warning is a string resource id so the copy stays in resources.
    var projectorWarning by remember { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importFailed = false
        scope.launch {
            val name = queryDisplayName(context, uri) ?: "model.gguf"
            val result = store.import(name) {
                context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            }
            importing = false
            importFailed = result.isFailure
        }
    }

    val projectorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val model = selected ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        projectorWarning = null
        scope.launch {
            val result = store.attachVisionProjector(model) {
                context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            }
            result.onSuccess { updated ->
                val caps = updated.visionProjectorPath?.let { LlamaProjector.inspect(it) }
                if (caps?.vision != true) {
                    // Do not keep a file the engine cannot use: a no-vision projector would load and
                    // then fail every image turn, which is worse than an honest import error.
                    store.detachVisionProjector(updated)
                    projectorWarning = R.string.engine_vision_unsupported
                }
            }.onFailure {
                projectorWarning = R.string.engine_vision_import_failed
            }
        }
    }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        SettingsSection(title = stringResource(R.string.engine_section_title)) {
            SettingsRow(
                label = stringResource(R.string.engine_provider),
                value = engine.displayName,
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.model_status),
                value = engineStatusLabel(status),
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.engine_vision_status),
                value = stringResource(
                    if (engine.visionEnabled) R.string.engine_vision_ready else R.string.engine_vision_off,
                ),
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.engine_selected_model),
                value = selected?.let { it.displayName + " · " + it.sizeLabel }
                    ?: stringResource(R.string.engine_no_model),
            )
            if (selected != null) {
                SettingsDivider()
                if (status is EngineStatus.Ready) {
                    SettingsRow(
                        label = stringResource(R.string.engine_unload),
                        onClick = { engine.unload() },
                    )
                } else {
                    SettingsRow(
                        label = stringResource(R.string.engine_load),
                        onClick = { selected?.let { scope.launch { engine.load(it) } } },
                    )
                }
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.engine_vision_projector),
                    value = selected?.visionProjectorPath?.let { File(it).name }
                        ?: stringResource(R.string.engine_vision_none),
                    onClick = { projectorPicker.launch(arrayOf("*/*")) },
                )
                if (selected?.hasVisionProjector == true) {
                    SettingsDivider()
                    SettingsRow(
                        label = stringResource(R.string.engine_vision_remove),
                        onClick = { selected?.let { store.detachVisionProjector(it) } },
                    )
                }
            }
            when {
                status is EngineStatus.Failed -> SettingsNote(stringResource(R.string.engine_status_failed))
                importing -> SettingsNote(stringResource(R.string.engine_importing))
                importFailed -> SettingsNote(stringResource(R.string.engine_import_failed))
                projectorWarning != null -> SettingsNote(stringResource(projectorWarning!!))
            }
            if (selected != null) SettingsNote(stringResource(R.string.engine_vision_note))
        }
        Spacer(Modifier.height(16.dp))
        SettingsSection(title = stringResource(R.string.engine_models_title)) {
            if (models.isEmpty()) {
                SettingsNote(stringResource(R.string.engine_no_models))
            } else {
                models.forEachIndexed { index, model ->
                    if (index > 0) SettingsDivider()
                    InstalledModelRow(
                        model = model,
                        selected = selected?.path == model.path,
                        onSelect = { store.select(model) },
                        onDelete = { store.delete(model) },
                    )
                }
            }
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.engine_import),
                onClick = { picker.launch(arrayOf("*/*")) },
            )
        }
        SettingsNote(stringResource(R.string.engine_local_note))
    }
    Spacer(Modifier.height(16.dp))
    ModelControlSections(modelControl = modelControl, engine = engine, settings = settings)
}

@Composable
private fun InstalledModelRow(
    model: InstalledModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val size = if (selected) {
        stringResource(R.string.engine_selected) + " · " + model.sizeLabel
    } else {
        model.sizeLabel
    }
    // The vision badge is data-driven: it appears exactly when a paired projector exists on disk.
    val value = if (model.hasVisionProjector) {
        size + " · " + stringResource(R.string.engine_vision_badge)
    } else {
        size
    }
    SettingsRow(
        label = model.displayName,
        value = value,
        onClick = onSelect,
        trailing = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_delete))
            }
        },
    )
}

/** SAF does not promise a display name, but the document provider almost always supplies one. */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
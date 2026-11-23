package app.sirrak.feature.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.sirrak.core.design.SettingsChoiceRow
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.speech.DictationLanguage
import app.sirrak.core.speech.DictationMode
import app.sirrak.core.speech.DictationSettings
import app.sirrak.core.speech.SpeechImportException
import app.sirrak.core.speech.SpeechModel
import app.sirrak.core.speech.SpeechModelStore
import app.sirrak.core.speech.SpeechReasons
import app.sirrak.core.speech.TranscriptionEngine
import app.sirrak.core.speech.TranscriptionStatus
import kotlinx.coroutines.launch

/**
 * Settings > Dictation. The Whisper weights are user-imported (never bundled), so this card is the
 * whole lifecycle: import a trio, pick which model is active, delete one, choose language and
 * interaction mode. Everything is data: the installed list comes from the store, the labels from
 * stable ids, and failures from stable reason codes.
 */
@Composable
fun VoiceSettingsSection(
    engine: TranscriptionEngine,
    models: SpeechModelStore,
    settings: DictationSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by models.models.collectAsStateWithLifecycle()
    val selected by models.selected.collectAsStateWithLifecycle()
    val engineStatus by engine.status.collectAsStateWithLifecycle()
    val language by settings.language.collectAsStateWithLifecycle()
    val mode by settings.mode.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SpeechModel?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) {
            failure = SpeechReasons.IMPORT_EMPTY
            return@rememberLauncherForActivityResult
        }
        val files = SpeechFilePicker.filesFrom(context, uris)
        if (files.isEmpty()) {
            failure = SpeechReasons.IMPORT_FAILED
            return@rememberLauncherForActivityResult
        }
        busy = true
        failure = null
        scope.launch {
            val result = models.import(files)
            busy = false
            result.exceptionOrNull()?.let { error ->
                failure = (error as? SpeechImportException)?.reason ?: SpeechReasons.IMPORT_FAILED
            }
        }
    }

    Column(modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        SettingsSection(title = stringResource(R.string.dictation_section_title)) {
            SettingsRow(
                label = stringResource(R.string.dictation_status),
                value = engineStatusLabel(engineStatus, busy),
            )
            SettingsRow(
                label = stringResource(R.string.dictation_model),
                value = selected?.displayName ?: stringResource(R.string.dictation_no_model),
            )
            selected?.let { model ->
                SettingsRow(
                    label = stringResource(R.string.dictation_size),
                    value = model.sizeLabel,
                )
            }
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.dictation_import),
                onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
            )
            if (installed.isNotEmpty()) {
                SettingsDivider()
                installed.forEach { model ->
                    SettingsRow(
                        label = model.displayName,
                        value = if (selected?.id == model.id) {
                            stringResource(R.string.dictation_selected)
                        } else {
                            model.sizeLabel
                        },
                        onClick = {
                            if (!busy && selected?.id != model.id) models.select(model.id)
                        },
                        trailing = {
                            IconButton(
                                onClick = { if (!busy) pendingDelete = model },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.dictation_remove),
                                )
                            }
                        },
                    )
                }
            }
            SettingsDivider()
            SettingsChoiceRow(
                label = stringResource(R.string.dictation_language),
                options = listOf(
                    stringResource(R.string.dictation_language_auto),
                    stringResource(R.string.dictation_language_arabic),
                    stringResource(R.string.dictation_language_english),
                ),
                selectedIndex = DictationLanguage.entries.indexOf(language),
                onSelect = { index -> settings.setLanguage(DictationLanguage.entries[index]) },
            )
            SettingsDivider()
            SettingsChoiceRow(
                label = stringResource(R.string.dictation_mode),
                options = listOf(
                    stringResource(R.string.dictation_mode_toggle),
                    stringResource(R.string.dictation_mode_hold),
                ),
                selectedIndex = DictationMode.entries.indexOf(mode),
                onSelect = { index -> settings.setMode(DictationMode.entries[index]) },
            )
            SettingsNote(stringResource(R.string.dictation_note))
            if (busy) SettingsNote(stringResource(R.string.dictation_working))
            failure?.let { reason -> SettingsNote(speechReasonLabel(reason)) }
        }
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dictation_remove_title)) },
            text = { Text(stringResource(R.string.dictation_remove_message, model.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        if (selected?.id == model.id) engine.unload()
                        models.delete(model)
                    },
                ) { Text(stringResource(R.string.dictation_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.dictation_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun engineStatusLabel(status: TranscriptionStatus, busy: Boolean): String = when {
    busy -> stringResource(R.string.dictation_status_working)
    status == TranscriptionStatus.READY -> stringResource(R.string.dictation_status_ready)
    status == TranscriptionStatus.LOADING -> stringResource(R.string.dictation_status_loading)
    status == TranscriptionStatus.FAILED -> stringResource(R.string.dictation_status_failed)
    else -> stringResource(R.string.dictation_status_unloaded)
}

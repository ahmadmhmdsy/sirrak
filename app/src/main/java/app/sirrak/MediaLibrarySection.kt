package app.sirrak

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.SettingsChoiceRow
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.feature.chat.DocumentPicker
import app.sirrak.media.MediaAccess
import app.sirrak.media.MediaImportResult
import app.sirrak.media.MediaKind
import app.sirrak.media.MediaLibrary
import app.sirrak.media.MediaPhase
import app.sirrak.media.MediaPolicy
import app.sirrak.media.MediaReasons
import app.sirrak.media.MediaRecord
import app.sirrak.media.MediaRetention
import app.sirrak.media.MediaRules
import app.sirrak.media.MediaSource
import app.sirrak.media.MediaTaskState
import app.sirrak.media.MediaText
import app.sirrak.media.MediaTranscriptStatus
import app.sirrak.media.MediaTranscriptionAvailability
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Library > Media segment: the real P5 shelf (documents, images, on-device audio transcripts).
 *
 * Rules the screen must keep true, all enforced in [MediaLibrary] rather than here:
 *  - every import first shows a two-choice decision sheet; the two choices carry their consequence;
 *  - a temporary item is never indexed, and its file does not survive the app process;
 *  - the per-item switch is the only way the assistant gains or loses read access, and it is disabled
 *    where access can never be granted (temporary items, images in this release);
 *  - audio is decoded and transcribed on the device by the existing Whisper engine; a missing model is
 *    explained with the exact Settings path instead of failing silently.
 *
 * The section owns no state beyond the open dialogs: everything durable lives in the media store, so
 * leaving and re-entering the Library cannot disagree with what is on disk.
 */
@Composable
fun MediaLibrarySection(media: MediaLibrary, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by media.records.collectAsStateWithLifecycle()
    val policy by media.policy.collectAsStateWithLifecycle()
    val task by media.task.collectAsStateWithLifecycle()

    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<MediaRecord?>(null) }
    var transcriptFor by remember { mutableStateOf<MediaRecord?>(null) }

    val availability = media.transcriptionAvailability()
    val availabilityReason = (availability as? MediaTranscriptionAvailability.Missing)?.reason
    val working = task as? MediaTaskState.Working

    val onPicked: (MediaKind, Uri) -> Unit = { kind, uri ->
        media.clearTask()
        notice = null
        val picked = DocumentPicker.source(context, uri)
        if (picked == null) {
            notice = MediaReasons.UNREADABLE
        } else {
            val source = MediaSource(
                displayName = picked.displayName,
                mimeType = picked.mimeType,
                sizeBytes = picked.sizeBytes,
                open = picked.openStream,
            )
            val audio = media.transcriptionAvailability()
            if (kind == MediaKind.AUDIO && audio is MediaTranscriptionAvailability.Missing) {
                notice = audio.reason
            } else {
                pending = PendingImport(kind, source)
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(MediaKind.DOCUMENT, uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(MediaKind.IMAGE, uri)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(MediaKind.AUDIO, uri)
    }

    val importChosen: (PendingImport, MediaRetention) -> Unit = { chosen, retention ->
        pending = null
        scope.launch {
            when (chosen.kind) {
                MediaKind.DOCUMENT -> media.importDocument(chosen.source, retention)
                MediaKind.IMAGE -> media.importImage(chosen.source, retention)
                MediaKind.AUDIO -> media.importAudio(chosen.source, retention)
            }
        }
    }

    val onDelete: (MediaRecord) -> Unit = { record -> pendingDelete = record }
    val interrupted = (task as? MediaTaskState.Failed)?.reason ?: notice

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SettingsSection(title = stringResource(R.string.media_title)) {
            SettingsNote(stringResource(R.string.media_intro))
            if (records.isEmpty()) {
                SettingsNote(stringResource(R.string.media_empty))
            }
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.media_import_document),
                onClick = if (working != null) {
                    null
                } else {
                    {
                        media.clearTask()
                        notice = null
                        documentPicker.launch(DocumentPicker.MIME_TYPES)
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.media_import_image),
                onClick = if (working != null) {
                    null
                } else {
                    {
                        media.clearTask()
                        notice = null
                        imagePicker.launch(arrayOf("image/*"))
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.media_import_audio),
                value = availabilityReason?.let { reason ->
                    stringResource(R.string.media_audio_unavailable_value, stringResource(mediaReasonLabel(reason)))
                },
                onClick = if (working != null) {
                    null
                } else {
                    {
                        media.clearTask()
                        notice = null
                        audioPicker.launch(arrayOf("audio/*"))
                    }
                },
            )
            if (working != null) {
                SettingsNote(workingLabel(working))
            }
            if (availabilityReason == MediaReasons.NO_SPEECH_MODEL) {
                SettingsNote(stringResource(R.string.media_no_speech_model_note))
            } else if (availabilityReason != null) {
                SettingsNote(stringResource(mediaReasonLabel(availabilityReason)))
            }
            if (interrupted != null && interrupted != MediaReasons.NO_SPEECH_MODEL) {
                SettingsNote(stringResource(mediaReasonLabel(interrupted)))
            }
            SettingsNote(
                stringResource(R.string.media_storage, MediaText.bytes(media.storageBytes())),
            )
            SettingsNote(stringResource(R.string.media_import_note))
            SettingsNote(stringResource(R.string.media_once_note))
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = stringResource(R.string.media_policy_title)) {
            SettingsChoiceRow(
                label = stringResource(R.string.media_policy_retention),
                options = listOf(
                    stringResource(R.string.media_retention_keep),
                    stringResource(R.string.media_retention_once),
                ),
                selectedIndex = if (policy.defaultRetention == MediaRetention.KEEP) 0 else 1,
                onSelect = { index ->
                    scope.launch {
                        media.setPolicy(
                            policy.copy(
                                defaultRetention = if (index == 0) {
                                    MediaRetention.KEEP
                                } else {
                                    MediaRetention.ONCE
                                },
                            ),
                        )
                    }
                },
            )
            SettingsDivider()
            SettingsChoiceRow(
                label = stringResource(R.string.media_policy_access),
                options = listOf(
                    stringResource(R.string.media_access_granted),
                    stringResource(R.string.media_access_denied),
                ),
                selectedIndex = if (policy.defaultAccess == MediaAccess.GRANTED) 0 else 1,
                onSelect = { index ->
                    scope.launch {
                        media.setPolicy(
                            policy.copy(
                                defaultAccess = if (index == 0) {
                                    MediaAccess.GRANTED
                                } else {
                                    MediaAccess.DENIED
                                },
                            ),
                        )
                    }
                },
            )
            SettingsNote(stringResource(R.string.media_policy_note))
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = stringResource(R.string.media_section_documents)) {
            if (records.none { it.kind == MediaKind.DOCUMENT }) {
                SettingsNote(stringResource(R.string.media_documents_empty))
            } else {
                records.filter { it.kind == MediaKind.DOCUMENT }.forEachIndexed { index, record ->
                    if (index > 0) SettingsDivider()
                    DocumentRow(
                        record = record,
                        onAccessChange = { granted ->
                            scope.launch {
                                media.setAccess(
                                    record.id,
                                    if (granted) MediaAccess.GRANTED else MediaAccess.DENIED,
                                )
                            }
                        },
                        onDelete = { onDelete(record) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = stringResource(R.string.media_section_images)) {
            if (records.none { it.kind == MediaKind.IMAGE }) {
                SettingsNote(stringResource(R.string.media_images_empty))
            } else {
                records.filter { it.kind == MediaKind.IMAGE }.forEachIndexed { index, record ->
                    if (index > 0) SettingsDivider()
                    ImageRow(
                        record = record,
                        file = media.file(record),
                        onDelete = { onDelete(record) },
                    )
                }
            }
            SettingsNote(stringResource(R.string.media_image_no_index_note))
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = stringResource(R.string.media_section_audio)) {
            if (records.none { it.kind == MediaKind.AUDIO }) {
                SettingsNote(stringResource(R.string.media_audio_empty))
            } else {
                records.filter { it.kind == MediaKind.AUDIO }.forEachIndexed { index, record ->
                    if (index > 0) SettingsDivider()
                    AudioRow(
                        record = record,
                        onOpenTranscript = { transcriptFor = record },
                        onRetry = { scope.launch { media.retryTranscription(record.id) } },
                        onAccessChange = { granted ->
                            scope.launch {
                                media.setAccess(
                                    record.id,
                                    if (granted) MediaAccess.GRANTED else MediaAccess.DENIED,
                                )
                            }
                        },
                        onDelete = { onDelete(record) },
                    )
                }
            }
        }

        SettingsNote(stringResource(R.string.media_evidence_note))
    }

    pending?.let { chosen ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(stringResource(R.string.media_sheet_title, chosen.source.displayName))
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.media_sheet_kind,
                            kindLabel(chosen.kind),
                            if (chosen.source.sizeBytes >= 0L) {
                                MediaText.bytes(chosen.source.sizeBytes)
                            } else {
                                stringResource(R.string.media_unknown_size)
                            },
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.media_sheet_body))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.media_sheet_default, defaultPolicyLabel(policy)))
                }
            },
            confirmButton = {
                TextButton(onClick = { importChosen(chosen, MediaRetention.KEEP) }) {
                    Text(stringResource(R.string.media_sheet_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = { importChosen(chosen, MediaRetention.ONCE) }) {
                    Text(stringResource(R.string.media_sheet_once))
                }
            },
        )
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.media_delete_title)) },
            text = { Text(stringResource(R.string.media_delete_body, record.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch { media.delete(record.id) }
                    },
                ) {
                    Text(stringResource(R.string.media_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.media_delete_cancel))
                }
            },
        )
    }

    transcriptFor?.let { record ->
        TranscriptDialog(
            record = record,
            load = { media.transcriptText(record.id) },
            onClose = { transcriptFor = null },
        )
    }
}

/** One import awaiting its keep/temporary decision; the picker URI is already resolved to a source. */
private class PendingImport(
    val kind: MediaKind,
    val source: MediaSource,
)

@Composable
private fun DocumentRow(
    record: MediaRecord,
    onAccessChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsRow(
        label = record.displayName,
        value = documentStatusLine(record),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccessSwitch(record = record, onAccessChange = onAccessChange)
                DeleteButton(record = record, onDelete = onDelete)
            }
        },
    )
}

@Composable
private fun AudioRow(
    record: MediaRecord,
    onOpenTranscript: () -> Unit,
    onRetry: () -> Unit,
    onAccessChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsRow(
        label = record.displayName,
        value = audioStatusLine(record),
        onClick = if (record.transcriptStatus == MediaTranscriptStatus.READY) onOpenTranscript else null,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (record.transcriptStatus == MediaTranscriptStatus.FAILED) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(
                                R.string.media_retry_transcription,
                                record.displayName,
                            ),
                        )
                    }
                }
                AccessSwitch(record = record, onAccessChange = onAccessChange)
                DeleteButton(record = record, onDelete = onDelete)
            }
        },
    )
}

/**
 * The per-item access switch. It is disabled where access can never be granted: a temporary item is
 * deleted before the assistant could read it, and V1 has no image extractor. A disabled switch with a
 * spoken label states that honestly instead of pretending the item is merely "off".
 */
@Composable
private fun AccessSwitch(record: MediaRecord, onAccessChange: (Boolean) -> Unit) {
    val canToggle = MediaRules.canGrantAgentAccess(record.retention, record.kind)
    val description = stringResource(R.string.media_access_toggle, record.displayName)
    Switch(
        checked = canToggle && record.access == MediaAccess.GRANTED,
        onCheckedChange = if (canToggle) onAccessChange else null,
        enabled = canToggle,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun DeleteButton(record: MediaRecord, onDelete: () -> Unit) {
    IconButton(onClick = onDelete) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.media_delete, record.displayName),
        )
    }
}

/**
 * Images get a real preview because "an image was stored" is not verifiable from a name alone. The
 * bitmap is decoded at a bounded sample size off the main thread, from the app-private copy; a file that
 * fails to decode still lists and can be deleted.
 */
@Composable
private fun ImageRow(record: MediaRecord, file: File?, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(file = file)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = imageStatusLine(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccessSwitch(record = record, onAccessChange = {})
            DeleteButton(record = record, onDelete = onDelete)
        }
    }
}

@Composable
private fun MediaThumbnail(file: File?) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file?.path) {
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) { decodeThumbnail(file) }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(44.dp),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun TranscriptDialog(record: MediaRecord, load: suspend () -> String?, onClose: () -> Unit) {
    var text by remember(record.id) { mutableStateOf<String?>(null) }
    var loading by remember(record.id) { mutableStateOf(true) }
    LaunchedEffect(record.id) {
        text = load()
        loading = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.media_transcript_title, record.displayName)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> Text(stringResource(R.string.media_audio_pending))
                    text.isNullOrBlank() -> Text(stringResource(R.string.media_transcript_missing))
                    else -> Text(text.orEmpty())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.media_transcript_close))
            }
        },
    )
}

@Composable
private fun documentStatusLine(record: MediaRecord): String {
    val size = MediaText.bytes(record.sizeBytes)
    if (record.isOnce) {
        return stringResource(
            R.string.media_once_status,
            stringResource(R.string.media_state_once),
            size,
        )
    }
    val access = stringResource(
        if (record.isIndexed) R.string.media_state_indexed else R.string.media_state_not_indexed,
    )
    return stringResource(R.string.media_document_status, size, access)
}

@Composable
private fun imageStatusLine(record: MediaRecord): String {
    val size = MediaText.bytes(record.sizeBytes)
    if (record.isOnce) {
        return stringResource(
            R.string.media_once_status,
            stringResource(R.string.media_state_once),
            size,
        )
    }
    return stringResource(
        R.string.media_image_status,
        size,
        stringResource(R.string.media_state_image_only),
    )
}

@Composable
private fun audioStatusLine(record: MediaRecord): String {
    val transcript = when (record.transcriptStatus) {
        MediaTranscriptStatus.READY -> stringResource(R.string.media_audio_ready)
        MediaTranscriptStatus.PENDING -> stringResource(R.string.media_audio_pending)
        MediaTranscriptStatus.FAILED -> stringResource(
            R.string.media_audio_failed,
            stringResource(mediaReasonLabel(record.transcriptFailure ?: MediaReasons.TRANSCRIPTION_FAILED)),
        )
        MediaTranscriptStatus.NOT_APPLICABLE -> stringResource(R.string.media_audio_no_transcript)
    }
    val tail = if (record.isOnce) {
        stringResource(R.string.media_state_once)
    } else {
        stringResource(
            if (record.isIndexed) R.string.media_state_indexed else R.string.media_state_not_indexed,
        )
    }
    return stringResource(R.string.media_audio_status, MediaText.bytes(record.sizeBytes), transcript, tail)
}

@Composable
private fun workingLabel(state: MediaTaskState.Working): String = stringResource(
    when (state.phase) {
        MediaPhase.COPYING -> R.string.media_working_copying
        MediaPhase.DECODING -> R.string.media_working_decoding
        MediaPhase.TRANSCRIBING -> R.string.media_working_transcribing
        MediaPhase.INDEXING -> R.string.media_working_indexing
    },
    state.displayName,
)

@Composable
private fun kindLabel(kind: MediaKind): String = stringResource(
    when (kind) {
        MediaKind.DOCUMENT -> R.string.media_kind_document
        MediaKind.IMAGE -> R.string.media_kind_image
        MediaKind.AUDIO -> R.string.media_kind_audio
    },
)

@Composable
private fun defaultPolicyLabel(policy: MediaPolicy): String =
    if (policy.defaultRetention == MediaRetention.ONCE) {
        stringResource(R.string.media_default_once)
    } else {
        stringResource(
            R.string.media_default_keep_access,
            stringResource(
                if (policy.defaultAccess == MediaAccess.GRANTED) {
                    R.string.media_access_granted
                } else {
                    R.string.media_access_denied
                },
            ),
        )
    }

/** Stable media failure code -> localised sentence; one mapping for the section and its banners. */
private fun mediaReasonLabel(reason: String): Int = when (reason) {
    MediaReasons.EMPTY -> R.string.media_reason_empty
    MediaReasons.TOO_LARGE -> R.string.media_reason_too_large
    MediaReasons.UNSUPPORTED_TYPE -> R.string.media_reason_unsupported_type
    MediaReasons.UNREADABLE -> R.string.media_reason_unreadable
    MediaReasons.STORAGE -> R.string.media_reason_storage
    MediaReasons.IMPORT_FAILED -> R.string.media_reason_import_failed
    MediaReasons.INDEX_FAILED -> R.string.media_reason_index_failed
    MediaReasons.INDEX_UNAVAILABLE -> R.string.media_reason_index_unavailable
    MediaReasons.NO_TRANSCRIBER -> R.string.media_reason_no_transcriber
    MediaReasons.NO_SPEECH_MODEL -> R.string.media_reason_no_speech_model
    MediaReasons.DECODE_FAILED -> R.string.media_reason_decode_failed
    MediaReasons.AUDIO_TOO_LONG -> R.string.media_reason_audio_too_long
    MediaReasons.TRANSCRIPTION_FAILED -> R.string.media_reason_transcription_failed
    MediaReasons.NO_SPEECH -> R.string.media_reason_no_speech
    MediaReasons.TRANSCRIPT_EMPTY -> R.string.media_reason_transcript_empty
    MediaReasons.NOT_TRANSCRIBED -> R.string.media_reason_not_transcribed
    else -> R.string.media_reason_generic
}

/** Decodes a bounded preview, never the full-size bitmap a camera image can carry. */
private fun decodeThumbnail(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMBNAIL_PX && bounds.outHeight / (sample * 2) >= THUMBNAIL_PX) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

private const val THUMBNAIL_PX = 96

package app.sirrak

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagDocumentStatus
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.rag.RagImportResult
import app.sirrak.core.rag.RagService
import app.sirrak.feature.chat.DocumentPicker
import app.sirrak.feature.chat.importRejectionLabel
import kotlinx.coroutines.launch

/**
 * The Library > Documents shelf: the durable view of the RAG library. Chat's picker is the quick path;
 * this surface is where a user can see what is on the device, retry a failed extraction, remove a
 * document and import one without a conversation open.
 *
 * All state comes from the one [RagService]: deleting here updates every conversation scope and every
 * citation presence through the same flow, so the two surfaces cannot disagree.
 */
@Composable
fun DocumentsLibrarySection(rag: RagService, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documents by rag.documents.collectAsStateWithLifecycle()
    val status by rag.status.collectAsStateWithLifecycle()
    var importedName by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retrying by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RagDocument?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val source = DocumentPicker.source(context, uri)
            if (source == null) {
                failure = RagImportRejection.UNSUPPORTED_TYPE.name
            } else {
                failure = null
                importedName = source.displayName
                scope.launch {
                    val result = rag.import(source)
                    importedName = null
                    if (result is RagImportResult.Rejected) failure = result.reason.name
                }
            }
        }
    }

    // No horizontal padding here: the Library screen owns the reading margins, so this shelf can
    // also be dropped into Settings later without double-padding.
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SettingsSection(title = stringResource(R.string.library_documents_title)) {
            if (documents.isEmpty()) {
                SettingsNote(stringResource(R.string.library_documents_empty))
            } else {
                documents.forEachIndexed { index, document ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        label = document.displayName,
                        value = documentStatusLine(document),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (document.status != RagDocumentStatus.READY) {
                                    IconButton(
                                        enabled = retrying != document.id,
                                        onClick = {
                                            retrying = document.id
                                            scope.launch {
                                                rag.reindex(document.id)
                                                retrying = null
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = stringResource(
                                                R.string.library_documents_retry,
                                                document.displayName,
                                            ),
                                        )
                                    }
                                }
                                IconButton(onClick = { pendingDelete = document }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            R.string.library_documents_delete,
                                            document.displayName,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.library_documents_import),
                onClick = { picker.launch(DocumentPicker.MIME_TYPES) },
            )
            if (importedName != null) {
                SettingsNote(
                    stringResource(R.string.library_documents_importing, importedName.orEmpty()),
                )
            }
            failure?.let { reason ->
                SettingsNote(stringResource(importRejectionLabel(reason)))
            }
            if (status.pendingCount > 0) {
                SettingsNote(
                    pluralStringResource(
                        R.plurals.library_documents_pending_note,
                        status.pendingCount,
                        status.pendingCount,
                    ),
                )
            }
            SettingsNote(stringResource(R.string.library_documents_note))
        }
    }

    pendingDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.library_documents_delete_title)) },
            text = {
                Text(stringResource(R.string.library_documents_delete_body, document.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch { rag.delete(document.id) }
                    },
                ) {
                    Text(stringResource(R.string.library_documents_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.library_documents_delete_cancel))
                }
            },
        )
    }
}

/** One line per document: lifecycle state plus the facts that make the index auditable. */
@Composable
private fun documentStatusLine(document: RagDocument): String {
    val state = stringResource(
        when (document.status) {
            RagDocumentStatus.READY -> R.string.library_document_status_ready
            RagDocumentStatus.EXTRACTED -> R.string.library_document_status_pending
            RagDocumentStatus.INDEXING -> R.string.library_document_status_indexing
            RagDocumentStatus.FAILED -> R.string.library_document_status_failed
        },
    )
    return when {
        document.status == RagDocumentStatus.READY -> pluralStringResource(
            R.plurals.library_document_ready_detail,
            document.pageCount,
            document.pageCount,
            document.chunkCount,
        )
        else -> state + " · " + formatBytes(document.sizeBytes)
    }
}

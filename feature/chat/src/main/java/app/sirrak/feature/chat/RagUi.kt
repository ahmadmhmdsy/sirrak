package app.sirrak.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.LocalSirrakTokens
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagDocumentStatus
import app.sirrak.core.runtime.Citation

/**
 * The M8 document surfaces: the conversation's grounding scope (chips above the composer), the
 * library picker and the citation chips/sheet under a grounded answer.
 *
 * Everything is data-driven: the picker renders whatever the library flow holds, the chips render the
 * scope ids resolved against it (a deleted document simply disappears), and every label is a
 * resource. The scope row stays visible even when empty so the feature is discoverable before the
 * user has imported anything; an empty state opens the same picker, which explains how to import.
 */

/** Status of one library document in words the user can act on. */
@Composable
private fun documentStatusLabel(document: RagDocument): String = stringResource(
    when (document.status) {
        RagDocumentStatus.READY -> R.string.documents_status_ready
        RagDocumentStatus.INDEXING -> R.string.documents_status_indexing
        RagDocumentStatus.EXTRACTED -> R.string.documents_status_pending
        RagDocumentStatus.FAILED -> R.string.documents_status_failed
    },
)

/**
 * The scope chips above the composer. When a ready document is out of scope it is not listed: the
 * picker is the only place documents can be added, so a user never wonders why an answer ignored a
 * document that looked attached.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentScopeRow(
    scope: List<RagDocument>,
    documentsAvailable: Boolean,
    onOpenPicker: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val hairline = LocalSirrakTokens.current.decorations.hairline
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        scope.forEach { document ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, hairline),
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp).size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = document.displayName,
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    IconButton(
                        onClick = { onRemove(document.id) },
                        enabled = enabled,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(
                                R.string.documents_scope_remove,
                                document.displayName,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, hairline),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, onClick = onOpenPicker, role = Role.Button)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (scope.isEmpty()) Icons.AutoMirrored.Outlined.LibraryBooks else Icons.Outlined.Add,
                    contentDescription = null,
                    tint = if (documentsAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (scope.isEmpty()) {
                        stringResource(R.string.documents_ask)
                    } else {
                        stringResource(R.string.documents_scope_add)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The document picker. Rows are checkboxes for READY documents; a document still waiting for an
 * embedding model explains itself instead of being selectable and then silently ignored. Import is
 * an action inside the sheet, so an empty library is a next step, not a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPickerSheet(
    documents: List<RagDocument>,
    scopeIds: Set<String>,
    documentsAvailable: Boolean,
    importing: Boolean,
    importRejection: String?,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onImport: () -> Unit,
    onDismissImportError: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.documents_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = if (documentsAvailable) {
                    stringResource(R.string.documents_picker_body)
                } else {
                    stringResource(R.string.documents_picker_embedding_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (importRejection != null) {
                Text(
                    text = stringResource(importRejectionLabel(importRejection)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismissImportError) {
                    Text(stringResource(R.string.action_close))
                }
            }
            if (documents.isEmpty()) {
                Text(
                    text = stringResource(R.string.documents_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSelectAll) {
                        Text(stringResource(R.string.documents_select_all))
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    documents.forEach { document ->
                        DocumentPickerRow(
                            document = document,
                            checked = document.id in scopeIds,
                            onToggle = { onToggle(document.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                TextButton(onClick = onImport, enabled = !importing) {
                    Text(
                        text = if (importing) {
                            stringResource(R.string.documents_importing)
                        } else {
                            stringResource(R.string.documents_import)
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.documents_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocumentPickerRow(
    document: RagDocument,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val selectable = document.isReady
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = selectable, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (selectable) {
                { onToggle() }
            } else {
                null
            },
            enabled = selectable,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = if (selectable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = documentStatusLabel(document),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The citation chips under a grounded answer. Each chip is a numbered-looking label with the document
 * name and page, and tapping it opens the exact excerpt that was used (already snapshotted on the
 * turn, so it survives deleting the document).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CitationChips(
    citations: List<Citation>,
    label: String,
    onCitationClick: (Citation) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (citations.isEmpty()) return
    val hairline = LocalSirrakTokens.current.decorations.hairline
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            citations.forEach { citation ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, hairline),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onCitationClick(citation) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = citation.documentName,
                            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                        Text(
                            text = " · " + stringResource(R.string.citation_page, citation.page),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The excerpt sheet behind one citation chip; read-only so the provenance stays inspectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitationSheet(
    citation: Citation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                text = citation.documentName,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.citation_page, citation.page),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = citation.snippet,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Maps a stable [app.sirrak.core.rag.RagImportRejection] code to the sentence the user sees. */
fun importRejectionLabel(reason: String): Int = when (reason) {
    "EMPTY" -> R.string.document_error_empty
    "TOO_LARGE" -> R.string.document_error_too_large
    "UNSUPPORTED_TYPE" -> R.string.document_error_unsupported
    "CORRUPT" -> R.string.document_error_corrupt
    "ENCRYPTED" -> R.string.document_error_encrypted
    "NO_TEXT" -> R.string.document_error_no_text
    "TOO_MANY_PAGES" -> R.string.document_error_too_many_pages
    "STORAGE" -> R.string.document_error_storage
    "EMBEDDING_UNAVAILABLE" -> R.string.document_error_embedding
    "INDEX_FAILED" -> R.string.document_error_index
    else -> R.string.document_error_generic
}

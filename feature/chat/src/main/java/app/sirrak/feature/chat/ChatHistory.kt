package app.sirrak.feature.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.ReadingColumn
import app.sirrak.core.design.ScreenTitle
import app.sirrak.core.runtime.ConversationExport
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.ConversationTitles
import app.sirrak.core.runtime.GenerationOverrides

/**
 * The conversation library surface (M6): search, open, rename, delete, share/export. It reads through
 * [ChatStore] only, so a database-backed library later needs no screen change. Search re-reads on each
 * query; conversations are small local documents, and this keeps the list honest while the library is
 * the single source of truth.
 */
@Composable
fun ConversationHistory(
    store: ChatStore,
    onClose: () -> Unit,
    onOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    // Bumped after mutations so the list re-reads the library instead of trusting a stale snapshot.
    var revision by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    var configuring by remember { mutableStateOf<ConversationSummary?>(null) }

    val summaries = remember(revision, query, store.conversationId) {
        if (query.isBlank()) store.conversations() else store.searchConversations(query)
    }
    val fallbackTitle = stringResource(R.string.nav_chat)
    val exportUser = stringResource(R.string.export_user_label)
    val exportAssistant = stringResource(R.string.brand_wordmark)
    val shareTitle = stringResource(R.string.share_title)

    fun export(summary: ConversationSummary, asJson: Boolean) {
        val conversation = store.loadConversation(summary.id) ?: return
        val title = ConversationTitles.display(summary.title, conversation.turns, fallbackTitle)
        val body = if (asJson) {
            ConversationExport.json(conversation)
        } else {
            ConversationExport.markdown(conversation, title, exportUser, exportAssistant)
        }
        val mime = if (asJson) ConversationFiles.MIME_JSON else ConversationFiles.MIME_MARKDOWN
        val fileName = ConversationFiles.fileNameFor(title, summary.id, if (asJson) "json" else "md")
        val saved = ConversationFiles.saveToDownloads(context, fileName, mime, body)
        Toast.makeText(
            context,
            if (saved) R.string.export_saved else R.string.export_failed,
            Toast.LENGTH_SHORT,
        ).show()
        if (!saved) ConversationFiles.share(context, shareTitle, title, body, mime)
    }

    ReadingColumn(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScreenTitle(text = stringResource(R.string.history_title))
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    store.newConversation()
                    onOpened()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddComment,
                    contentDescription = stringResource(R.string.action_new_chat),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.history_search)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = RoundedCornerShape(14.dp),
        )

        if (summaries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(
                        if (query.isBlank()) R.string.history_empty else R.string.history_no_results,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(summaries, key = { it.id }) { summary ->
                    ConversationRow(
                        summary = summary,
                        active = summary.id == store.conversationId,
                        fallbackTitle = fallbackTitle,
                        onOpen = {
                            if (store.openConversation(summary.id)) onOpened()
                        },
                        onConfigure = { configuring = summary },
                        onRename = { renaming = summary },
                        onDelete = { deleting = summary },
                        onExport = { asJson -> export(summary, asJson) },
                    )
                }
            }
        }
    }

    val configureTarget = configuring
    if (configureTarget != null) {
        ConversationSettingsDialog(
            summary = configureTarget,
            store = store,
            onDismiss = { configuring = null },
            onSaved = {
                configuring = null
                revision++
            },
        )
    }

    val renameTarget = renaming
    if (renameTarget != null) {
        RenameConversationDialog(
            summary = renameTarget,
            fallbackTitle = fallbackTitle,
            onDismiss = { renaming = null },
            onSave = { title ->
                store.renameConversation(renameTarget.id, title)
                renaming = null
                revision++
            },
        )
    }

    val deleteTarget = deleting
    if (deleteTarget != null) {
        DeleteConversationDialog(
            onDismiss = { deleting = null },
            onConfirm = {
                store.deleteConversation(deleteTarget.id)
                deleting = null
                revision++
            },
        )
    }
}

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    active: Boolean,
    fallbackTitle: String,
    onOpen: () -> Unit,
    onConfigure: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val title = summary.title ?: summary.derivedTitle ?: fallbackTitle
    val background = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen, role = Role.Button)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                // A title is often in another script than the app locale; direction follows the
                // first strong character so an English title keeps its punctuation on the left.
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.preview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary.preview,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.history_messages, summary.messageCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.conversation_settings)) },
                    onClick = {
                        menuOpen = false
                        onConfigure()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_markdown)) },
                    onClick = {
                        menuOpen = false
                        onExport(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_json)) },
                    onClick = {
                        menuOpen = false
                        onExport(true)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** Explicit titles are optional: the field starts at the current display title, blank clears it. */
@Composable
private fun RenameConversationDialog(
    summary: ConversationSummary,
    fallbackTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(summary.id) {
        mutableStateOf(summary.title ?: summary.derivedTitle ?: fallbackTitle)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.rename_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Per-conversation layers (M6): a prompt override and stop sequences. Both are text, both are
 * optional, and an empty field inherits the global/model layer instead of freezing a copy. The
 * dialog reads the stored conversation, so opening it twice cannot lose the other override fields.
 */
@Composable
private fun ConversationSettingsDialog(
    summary: ConversationSummary,
    store: ChatStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val existing = remember(summary.id) { store.loadConversation(summary.id) }
    var prompt by remember(summary.id) { mutableStateOf(existing?.systemPrompt.orEmpty()) }
    var stops by remember(summary.id) {
        mutableStateOf(existing?.overrides?.stop.orEmpty().joinToString("\n"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversation_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.conversation_system_prompt)) },
                    placeholder = { Text(stringResource(R.string.conversation_system_prompt_hint)) },
                )
                OutlinedTextField(
                    value = stops,
                    onValueChange = { stops = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.conversation_stop_sequences)) },
                    placeholder = { Text(stringResource(R.string.conversation_stop_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedStops = stops.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .take(4)
                        .toList()
                    val base = existing?.overrides ?: GenerationOverrides()
                    val overrides = base.copy(stop = parsedStops.takeIf { it.isNotEmpty() })
                        .takeIf { !it.isEmpty() }
                    store.updateConversationConfig(
                        id = summary.id,
                        systemPrompt = prompt.trim().ifEmpty { null },
                        overrides = overrides,
                    )
                    onSaved()
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DeleteConversationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

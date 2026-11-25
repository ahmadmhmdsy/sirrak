package app.sirrak.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.AnswerActions
import app.sirrak.core.design.AssistantProse
import app.sirrak.core.design.ChatAnswerContext
import app.sirrak.core.design.ChatComposer
import app.sirrak.core.design.EmptyConversation
import app.sirrak.core.design.LocalChatAnswerContext
import app.sirrak.core.design.ReadingColumn
import app.sirrak.core.design.SlotList
import app.sirrak.core.design.SlotRow
import app.sirrak.core.design.SuggestionCard
import app.sirrak.core.design.UserNote
import app.sirrak.core.design.Wordmark
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.runtime.AttachmentDecision
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.Citation
import app.sirrak.core.runtime.ComposerTextRequests
import app.sirrak.core.runtime.Slots
import kotlinx.coroutines.delay

/**
 * The conversation surface. Chrome comes from the shell; this screen owns the header, the transcript,
 * the empty state and the composer. The engine chip in the header is contributed, not coded here.
 */
@Composable
fun ChatScreen(
    store: ChatStore,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * M10: dictation (or any future producer) appends into this composer through this channel. Null
     * keeps the screen exactly as before, so a composition without the voice feature still works.
     */
    composerTextRequests: ComposerTextRequests? = null,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val suggestions = remember(store) { store.suggestions }
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // M10: a dictated transcript arrives as a request, not as a second owner of the text field. It
    // appends to whatever is already typed (one space between), so dictating mid-sentence does not
    // replace the draft.
    LaunchedEffect(composerTextRequests) {
        composerTextRequests?.requests?.collect { text ->
            input = if (input.isBlank()) text else input.trimEnd() + " " + text
        }
    }
    // M7 attachment surfaces. The seed holds the clipboard text captured when the user tapped the
    // shared-text row, so composing the sheet itself never reads the clipboard.
    var showAttachSheet by remember { mutableStateOf(false) }
    var showSharedText by remember { mutableStateOf(false) }
    var sharedTextSeed by remember { mutableStateOf("") }
    // M8 document grounding: the picker sheet, the tapped citation excerpt and the SAF launcher.
    var showDocumentPicker by remember { mutableStateOf(false) }
    var selectedCitation by remember { mutableStateOf<Citation?>(null) }
    val context = LocalContext.current
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val source = DocumentPicker.source(context, uri)
            if (source == null) {
                // The picker accepted a file whose name/stream could not be resolved; the same
                // rejection code keeps the UI message path identical to a late extraction refusal.
                store.reportDocumentImportError(RagImportRejection.UNSUPPORTED_TYPE.name)
            } else {
                store.importDocument(source, scope)
            }
        }
    }
    // M9 image attach: gallery vs camera is a UI-only fork; failures use the same dialog pattern as
    // the shared-text flow instead of a snackbar host the chat surface does not have.
    var showImageSource by remember { mutableStateOf(false) }
    var imageAttachError by remember { mutableStateOf<ImageAttachError?>(null) }
    var pendingCapture by remember { mutableStateOf<ImagePicker.Capture?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val source = ImagePicker.source(context, uri)
            if (source == null || !store.attachImage(source)) {
                imageAttachError = if (store.attachmentGate.decision(AttachmentKind.IMAGE) == AttachmentDecision.Enabled) {
                    ImageAttachError.REJECTED
                } else {
                    ImageAttachError.NO_VISION
                }
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val capture = pendingCapture
        pendingCapture = null
        if (saved && capture != null && !store.attachImage(capture.source())) {
            imageAttachError = ImageAttachError.REJECTED
        }
    }
    // Momentary copy confirmation; resets itself so no snackbar host has to be plumbed in.
    var copiedAnswerId by remember { mutableStateOf<Long?>(null) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copiedAnswerId) {
        if (copiedAnswerId != null) {
            delay(1_800)
            copiedAnswerId = null
        }
    }

    if (showHistory) {
        ConversationHistory(
            store = store,
            onClose = { showHistory = false },
            onOpened = { showHistory = false },
            modifier = modifier.imePadding(),
        )
        return
    }

    val streamingLength = (store.items.lastOrNull() as? ChatStore.Item.Assistant)
        ?.takeIf { it.streaming }?.text?.length

    LaunchedEffect(store.items.size, streamingLength) {
        if (store.items.isNotEmpty()) {
            listState.scrollToItem(store.items.lastIndex)
            // scrollToItem pins the item's top; the answer is often taller than the viewport, so
            // follow to the true bottom as well. scrollBy clamps at the end of the list.
            listState.scrollBy(10_000f)
            // Restored content measures a frame late, so a long answered conversation could open
            // with its actions row just below the fold. Once layout has settled (and no stream is
            // still growing), follow to the real end a second time.
            withFrameNanos { }
            if (streamingLength == null && listState.canScrollForward) {
                listState.scrollBy(10_000f)
            }
        }
    }

    ReadingColumn(modifier = modifier.imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark(stringResource(R.string.brand_wordmark))
            Spacer(Modifier.width(12.dp))
            // The trailing host owns whatever the engine contributes; weight keeps a long model
            // name from crowding the wordmark (the chip itself ellipsises inside this box).
            Box(
                modifier = Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.CenterEnd,
            ) {
                SlotList(Slots.ChatTopBarTrailing)
            }
            // Saved conversations are always reachable; a new chat never destroys the old one.
            IconButton(onClick = { showHistory = true }) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = stringResource(R.string.action_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (store.items.isNotEmpty()) {
                IconButton(onClick = { store.newConversation() }) {
                    Icon(
                        imageVector = Icons.Outlined.AddComment,
                        contentDescription = stringResource(R.string.action_new_chat),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (store.items.isEmpty()) {
                // The composer (meter, document scope, input) leaves a short reading area on a
                // 720x1280 phone; the compact variant drops the girih mark and tightens spacing so
                // the promise, the privacy line and the first suggestion card are fully visible
                // without a scroll, while the deck below still scrolls.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxHeight < 300.dp
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                        EmptyConversation(
                            promise = stringResource(R.string.empty_promise),
                            privacy = stringResource(R.string.empty_privacy),
                            suggestionsTitle = stringResource(R.string.suggestions_title),
                            compact = compact,
                            suggestions = {
                                suggestions.forEach { suggestion ->
                                    SuggestionCard(
                                        prompt = suggestion.prompt,
                                        gloss = suggestion.gloss,
                                        onClick = { store.send(suggestion.prompt, scope) },
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(store.items, key = { it.id }) { item ->
                        when (item) {
                            is ChatStore.Item.User -> Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End,
                            ) {
                                // An attachment-only question has no bubble: the chips are the turn.
                                if (item.text.isNotBlank()) UserNote(item.text)
                                if (item.attachments.isNotEmpty()) {
                                    AttachmentChipRow(
                                        attachments = item.attachments,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = if (item.text.isNotBlank()) 8.dp else 0.dp),
                                        // Sent chips sit at the reading end under the note (RTL-mirrored).
                                        arrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    )
                                }
                            }
                            is ChatStore.Item.Assistant -> Column {
                                AssistantProse(
                                    text = item.text,
                                    streaming = item.streaming,
                                    sources = item.sources,
                                    sourcesLabel = stringResource(R.string.sources_label),
                                )
                                if (!item.streaming && item.citations.isNotEmpty()) {
                                    CitationChips(
                                        citations = item.citations,
                                        label = stringResource(R.string.citations_label),
                                        onCitationClick = { selectedCitation = it },
                                        modifier = Modifier.padding(top = 10.dp),
                                    )
                                }
                                if (!item.streaming && !item.failed && item.text.isNotBlank()) {
                                    // Regenerate only the newest answer: older turns cannot be re-run
                                    // without discarding everything after them.
                                    val isNewest = item.id == store.items.lastOrNull()?.id
                                    AnswerActions(
                                            copyLabel = stringResource(R.string.action_copy_answer),
                                            copiedLabel = stringResource(R.string.action_copied),
                                            copied = copiedAnswerId == item.id,
                                            onCopy = {
                                                clipboard.setText(AnnotatedString(item.text))
                                                copiedAnswerId = item.id
                                            },
                                            regenerateLabel = if (isNewest) {
                                                stringResource(R.string.action_regenerate)
                                            } else {
                                                null
                                            },
                                            onRegenerate = if (isNewest) {
                                                { store.regenerate(scope) }
                                            } else {
                                                null
                                            },
                                        ) {
                                            // Contributed actions: the provider tells them which
                                            // answer they act on (P3 report, M13 read-aloud).
                                            CompositionLocalProvider(
                                                LocalChatAnswerContext provides ChatAnswerContext(
                                                    messageId = item.id.toString(),
                                                    answerText = item.text,
                                                ),
                                            ) {
                                                SlotRow(Slots.ChatAnswerActions)
                                            }
                                        }
                                }
                                if (item.failed) {
                                    val reason = item.failureReason
                                    Text(
                                        text = stringResource(
                                            when (reason) {
                                                ChatStore.NO_MODEL -> R.string.error_no_model
                                                ChatStore.STOPPED -> R.string.error_stopped
                                                ChatStore.RAG_NO_SOURCES -> R.string.error_rag_no_sources
                                                ChatStore.PROCESS_LOST -> R.string.error_engine_process_lost
                                                else -> R.string.error_engine
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        // A user-stopped turn is a state, not an error: keep it quiet.
                                        color = if (reason == ChatStore.STOPPED) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                    Row {
                                        TextButton(onClick = { store.retry(scope) }) {
                                            Text(stringResource(R.string.action_retry))
                                        }
                                        if (reason == ChatStore.NO_MODEL) {
                                            TextButton(onClick = onOpenSettings) {
                                                Text(stringResource(R.string.action_open_settings))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // A meter of a conversation that does not exist yet is noise over the empty state (and it
        // costs the empty state a whole row on small phones); it appears with the first turn.
        if (store.items.isNotEmpty()) {
            PromptMeter(
                budget = store.tokenBudget,
                speed = store.lastSpeed,
                streaming = store.streaming,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        // M8: what this conversation is grounded in. Always present when RAG is composed, so the
        // capability is discoverable before the first document is imported; the picker explains the
        // empty library instead of the composer staying silent.
        if (store.ragEnabled) {
            val scopedDocuments = store.documents.filter { it.id in store.documentScope }
            DocumentScopeRow(
                scope = scopedDocuments,
                documentsAvailable = store.documentsAvailable,
                enabled = !store.streaming,
                onOpenPicker = { showDocumentPicker = true },
                onRemove = { store.removeDocumentFromScope(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        // Queued attachments sit between the meter and the composer: they belong to the next request,
        // not to the transcript yet, and each chip can be removed before sending.
        AttachmentChipRow(
            attachments = store.pendingAttachments,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            onRemove = { store.removePendingAttachment(it.id) },
        )

        // M9 (VIS-4): an image turn gets prompt shortcuts once an image is queued, so the user does
        // not have to guess what a vision model can answer. A chip only fills the composer.
        if (!store.streaming && store.pendingAttachments.any { it.kind == AttachmentKind.IMAGE }) {
            ImagePromptSuggestionRow(
                suggestions = ImagePromptSuggestion.entries,
                onSelect = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        // M10: contributed rows above the composer (dictation status strip). The chat screen owns the
        // position; the voice feature owns the content.
        SlotList(key = Slots.ChatComposerAbove)

        ChatComposer(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.chat_placeholder),
            sendDescription = stringResource(R.string.action_send),
            stopDescription = stringResource(R.string.action_stop),
            streaming = store.streaming,
            onSend = {
                val text = input
                if (text.isNotBlank() || store.pendingAttachments.isNotEmpty()) {
                    input = ""
                    store.send(text, scope)
                }
            },
            onStop = { store.stop() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            trailing = { SlotList(Slots.ChatComposerTrailing) },
            leading = {
                // Attaching is a request to add to this turn; while an answer streams the turn is
                // already on its way, so the affordance is present but disabled (tonally, not hidden).
                IconButton(
                    onClick = { showAttachSheet = true },
                    enabled = !store.streaming,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = stringResource(R.string.action_attach),
                        tint = if (store.streaming) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            // A queued attachment is enough to send: its text is the question.
            sendEnabled = input.isNotBlank() || store.pendingAttachments.isNotEmpty(),
        )
    }

    // The sheet steps aside while the dialog is up; cancelling the dialog brings it back so the user
    // does not have to find the paperclip again.
    if (showAttachSheet && !showSharedText) {
        AttachmentSheet(
            gate = store.attachmentGate,
            onDismiss = { showAttachSheet = false },
            actions = mapOf(
                AttachmentKind.SHARED_TEXT to {
                    // Read the clipboard only here, on an explicit tap. A failed read opens an empty
                    // dialog (and the empty-text error) instead of silently attaching nothing.
                    sharedTextSeed = runCatching { clipboard.getText()?.text }.getOrNull().orEmpty()
                    showSharedText = true
                },
                AttachmentKind.IMAGE to {
                    // One image source dialog keeps the sheet to one row per kind while still
                    // offering both gallery and camera.
                    showAttachSheet = false
                    showImageSource = true
                },
                AttachmentKind.DOCUMENT to {
                    // A document is not queued on the turn: it joins the conversation's grounding
                    // scope through the RAG library, which is also why the sheet hands off to it.
                    showAttachSheet = false
                    showDocumentPicker = true
                },
            ),
        )
    }
    if (showDocumentPicker) {
        DocumentPickerSheet(
            documents = store.documents,
            scopeIds = store.documentScope.toSet(),
            documentsAvailable = store.documentsAvailable,
            importing = store.importingDocument,
            importRejection = store.documentImportRejection,
            onToggle = { id ->
                if (id in store.documentScope) {
                    store.removeDocumentFromScope(id)
                } else {
                    store.addDocumentToScope(id)
                }
            },
            onSelectAll = { store.selectAllReadyDocuments() },
            onImport = { documentPicker.launch(DocumentPicker.MIME_TYPES) },
            onDismissImportError = { store.dismissDocumentImportError() },
            onDismiss = { showDocumentPicker = false },
        )
    }
    selectedCitation?.let { citation ->
        CitationSheet(citation = citation, onDismiss = { selectedCitation = null })
    }
    if (showSharedText) {
        SharedTextDialog(
            initialText = sharedTextSeed,
            onDismiss = { showSharedText = false },
            onAttach = { text, name -> store.attachSharedText(text, name) },
            onAttached = {
                showSharedText = false
                showAttachSheet = false
            },
        )
    }
    if (showImageSource) {
        ImageSourceDialog(
            onDismiss = { showImageSource = false },
            onGallery = {
                showImageSource = false
                imagePicker.launch(ImagePicker.MIME_TYPES)
            },
            onCamera = {
                showImageSource = false
                val capture = ImagePicker.captureTarget(context)
                if (capture == null) {
                    imageAttachError = ImageAttachError.REJECTED
                } else {
                    pendingCapture = capture
                    cameraLauncher.launch(capture.uri)
                }
            },
        )
    }
    imageAttachError?.let { error ->
        AlertDialog(
            onDismissRequest = { imageAttachError = null },
            title = { Text(stringResource(R.string.image_source_title)) },
            text = {
                Text(
                    stringResource(
                        if (error == ImageAttachError.NO_VISION) {
                            R.string.image_attach_no_vision
                        } else {
                            R.string.image_attach_failed
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { imageAttachError = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/** Why the last image attach produced no queued chip; both map to a localised sentence. */
private enum class ImageAttachError { NO_VISION, REJECTED }

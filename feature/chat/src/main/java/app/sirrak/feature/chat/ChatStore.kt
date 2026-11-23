package app.sirrak.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sirrak.core.rag.CitationParser
import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.rag.RagImportResult
import app.sirrak.core.rag.RagLimits
import app.sirrak.core.rag.RagPrompt
import app.sirrak.core.rag.RagService
import app.sirrak.core.rag.RagStatus
import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentGate
import app.sirrak.core.runtime.AttachmentImport
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.AttachmentPolicy
import app.sirrak.core.runtime.AttachmentPrompt
import app.sirrak.core.runtime.AttachmentSource
import app.sirrak.core.runtime.AttachmentStore
import app.sirrak.core.runtime.AttachmentValidation
import app.sirrak.core.runtime.ChatBudget
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.Citation
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ConversationStore
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineReasons
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationOverrides
import app.sirrak.core.runtime.GenerationSettingsProvider
import app.sirrak.core.runtime.GenerationSpeed
import app.sirrak.core.runtime.ImageInput
import app.sirrak.core.runtime.ImagePreparer
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.Role
import app.sirrak.core.runtime.SourceRef
import app.sirrak.core.runtime.TokenEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Conversation state holder. Knows nothing about Compose screens, Android or a concrete engine: it
 * talks to [InferenceEngine] and is driven by a caller-supplied scope, so a real engine swap is one
 * composition line. Failed or stopped turns stay visible instead of vanishing.
 *
 * Conversation memory is a [ConversationStore] seam (nullable: previews and tests stay in-memory).
 * The store restores on creation and saves after every change that a user would expect to survive
 * process death: the question immediately, and the answer once generation settles.
 *
 * M7 attachments are an unsent queue ([pendingAttachments]) plus durable metadata on each user turn;
 * the bytes live behind [AttachmentStore]. The queue belongs to the conversation on screen, so
 * switching or deleting a conversation removes what was never sent instead of leaving orphan files.
 */
class ChatStore(
    private val engine: InferenceEngine,
    private val library: ContentRepository,
    /** Optional app-owned scope: turns keep streaming when the screen leaves composition. */
    private val appScope: CoroutineScope? = null,
    /** Optional durable memory; null keeps this store purely in-memory. */
    private val conversation: ConversationStore? = null,
    /**
     * Supplies the per-request generation layers (params, template, system prompt). Defaults to the
     * engine's own defaults so tests and demos never depend on the model-control service.
     */
    private val generationSettings: GenerationSettingsProvider = GenerationSettingsProvider.Default,
    /**
     * Optional multi-conversation library (M6). When present it owns durability and replaces
     * [conversation]: the screen can switch, rename and delete conversations, and the last one opened
     * is restored on launch. Null keeps the single-transcript seam for tests and previews.
     */
    private val conversations: ConversationLibrary? = null,
    /** Context window of the loaded model, for the prompt meter; config owns the real value. */
    private val contextTokens: Int = ChatBudget.DEFAULT_CONTEXT_TOKENS,
    /** Wall clock for approximate output speed; injectable so tests are deterministic. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Optional app-private attachment storage (M7). Null keeps the store text-only: [attachSharedText]
     * refuses instead of silently dropping the user's text, and the composer's attach sheet has
     * nothing to save into. Storage owns the bytes; this store keeps the durable metadata on the user
     * turns plus the unsent [pendingAttachments] queue.
     */
    private val attachments: AttachmentStore? = null,
    /**
     * M9: prepares attached image bytes for a vision model (decode, EXIF, downscale, re-encode). Null
     * keeps the store text-only; an image turn then fails with [EngineReasons.VISION_UNAVAILABLE]
     * instead of sending the question without its image.
     */
    private val imagePreparer: ImagePreparer? = null,
    /**
     * Optional document-RAG service (M8). Null keeps the store exactly as it was before M8: the
     * composer offers no document scope and every answer is generated from the transcript alone. When
     * present, the conversation can be grounded in a set of library documents; retrieval happens
     * before generation on a background dispatcher, and only cited chunks are kept on the answer.
     */
    private val rag: RagService? = null,
) {
    sealed interface Item {
        val id: Long

        /**
         * One question. [attachments] describes what the user attached to *this* turn; the content
         * itself stays in [AttachmentStore] and reaches the model through [AttachmentPrompt]. Default
         * empty keeps M3-M6 call sites and test fakes source-compatible.
         */
        data class User(
            override val id: Long,
            val text: String,
            val attachments: List<Attachment> = emptyList(),
        ) : Item

        data class Assistant(
            override val id: Long,
            val text: String,
            val streaming: Boolean,
            val sources: List<SourceRef>,
            val failed: Boolean,
            /** Stable code the UI maps to a localised message; null when the turn succeeded. */
            val failureReason: String? = null,
            /**
             * M8 provenance: the numbered sources the model actually cited, snapshotted here so the
             * chips and the tap-through excerpt keep working after the document is deleted. Empty on
             * ungrounded answers and on failed turns.
             */
            val citations: List<Citation> = emptyList(),
        ) : Item
    }

    val items = mutableStateListOf<Item>()

    var streaming by mutableStateOf(false)
        private set

    /**
     * Attachments the user picked but has not sent. They belong to [conversationId]: the bytes are
     * already in app-private storage, so switching or deleting a conversation must remove them (see
     * [discardPending]) rather than leave files no transcript points at.
     */
    private val pending = mutableStateListOf<Attachment>()

    /** Read-only view of the unsent queue; Compose observers see additions and removals. */
    val pendingAttachments: List<Attachment> get() = pending

    /** The RAG library as last observed; empty when no [RagService] is composed. */
    private val libraryDocuments = mutableStateListOf<RagDocument>()
    val documents: List<RagDocument> get() = libraryDocuments

    /** Aggregate RAG readiness (embedding state plus library counts); null without a service. */
    var ragStatus by mutableStateOf<RagStatus?>(null)
        private set

    /** True while a document import/embed/index pass is running; the picker shows it inline. */
    var importingDocument by mutableStateOf(false)
        private set

    /** Stable rejection code of the last failed import (see [RagImportRejection]); cleared on retry. */
    var documentImportRejection by mutableStateOf<String?>(null)
        private set

    /**
     * Documents that ground the conversation on screen. Kept as plain ids: the library owns text,
     * chunks and vectors, so a deleted document simply drops out instead of leaving a dangling copy
     * in the transcript. Persisted on the conversation, so reopening a chat restores its scope.
     */
    private val scopedDocuments = mutableStateListOf<String>()
    val documentScope: List<String> get() = scopedDocuments

    /** Whether document grounding can run now (an embedding model is installed and ready). */
    val documentsAvailable: Boolean get() = rag != null && ragStatus?.documentsAvailable == true

    /** Localised-in-UI availability of the document scope picker. */
    val ragEnabled: Boolean get() = rag != null

    /**
     * What the loaded engine can actually consume, derived from [InferenceEngine.capabilities] on
     * every read so an engine swap needs no store rebuild. The attach sheet renders its rows from
     * this gate, so a text-only engine never offers a kind it would silently ignore (M7 exit gate).
     */
    val attachmentGate: AttachmentGate
        get() = AttachmentGate(
            // A text-only engine still cannot read a document *inline*; grounding is handled by the
            // RAG pipeline, which is why a ready embedding backend is enough to enable the row.
            engine.capabilities.copy(documents = engine.capabilities.documents || documentsAvailable),
        )

    /** Exposed for the engine chip/notice; never mutated by the store. */
    val engineStatus: StateFlow<EngineStatus> get() = engine.status

    /** Id/title of the conversation on screen; null when the single-transcript seam is used. */
    var conversationId by mutableStateOf<String?>(null)
        private set
    var conversationTitle by mutableStateOf<String?>(null)
        private set

    /**
     * How full the model's context window the current prompt is. Estimated (see [TokenEstimate]) and
     * labelled as such: the point is to warn before the engine silently drops the oldest turns.
     */
    var tokenBudget by mutableStateOf(ChatBudget.compute(emptyList(), null, contextTokens, DEFAULT_ANSWER_RESERVE))
        private set

    /** Approximate output speed of the last completed answer; null until one finishes. */
    var lastSpeed by mutableStateOf<Float?>(null)
        private set

    /** Estimated tokens in the last completed answer; null until one finishes. */
    var lastCompletionTokens by mutableStateOf<Int?>(null)
        private set

    /** Persisted shape of the conversation on screen, so every library save is a copy, not a guess. */
    private var current: Conversation? = null

    private var job: Job? = null
    private var nextId = 0L

    /**
     * Out-of-order IO writes must never resurrect an older transcript: every requested save carries a
     * version and only a save newer than the last written one reaches the disk.
     */
    private val persistLock = Any()
    private var requestedSave = 0L
    private var writtenSave = 0L

    init {
        if (conversations != null) {
            restoreOrCreateConversation()
        } else {
            conversation?.load()?.forEach(::restore)
        }
        observeRag()
        refreshBudget()
    }

    private fun restore(turn: ChatTurn) {
        // A turn with an attachment but no text is still a question the user asked; only a turn with
        // neither is an empty artifact of an older save.
        if (turn.text.isBlank() && turn.attachments.isEmpty()) return
        items += when (turn.role) {
            Role.USER -> Item.User(nextId++, turn.text, turn.attachments)
            Role.ASSISTANT -> Item.Assistant(
                id = nextId++,
                text = turn.text,
                streaming = false,
                sources = emptyList(),
                failed = false,
                citations = turn.citations,
            )
        }
    }

    val suggestions get() = library.suggestions()

    /**
     * Stores explicitly pasted/shared text as an attachment on the active conversation and queues it
     * for the next send. False (and nothing stored) when the text is blank, over the shared-text cap,
     * when no conversation is active, or when no [AttachmentStore] is composed: the composer's dialog
     * shows a localised reason instead of pretending the paste worked.
     */
    fun attachSharedText(text: String, displayName: String): Boolean {
        val store = attachments ?: return false
        val conversation = conversationId ?: return false
        if (AttachmentPolicy.validateSharedText(text) !is AttachmentValidation.Accepted) return false
        val name = displayName.trim().ifEmpty { firstLineOf(text) }
        return when (val result = store.saveSharedText(conversation, text, name)) {
            is AttachmentImport.Stored -> {
                pending += result.attachment
                true
            }
            is AttachmentImport.Rejected -> false
        }
    }

    /**
     * M9: stores a picked or captured image and queues it for the next send. False when no store or
     * conversation is composed, the gate says the model cannot see images, or the file is not an
     * accepted image; the composer maps that to a localised reason.
     */
    fun attachImage(source: AttachmentSource): Boolean {
        val store = attachments ?: return false
        val conversation = conversationId ?: return false
        if (!attachmentGate.isEnabled(AttachmentKind.IMAGE)) return false
        return when (val result = store.import(conversation, source)) {
            is AttachmentImport.Stored -> {
                if (result.attachment.kind != AttachmentKind.IMAGE) {
                    // A picker can hand back anything; never queue a document as if it were an image.
                    store.delete(conversation, result.attachment.id)
                    false
                } else {
                    pending += result.attachment
                    true
                }
            }
            is AttachmentImport.Rejected -> false
        }
    }

    /**
     * Drops one queued attachment (the chip's remove button) and deletes its file from the active
     * conversation. Removing an unsent attachment must not leave an orphan behind for a later
     * conversation switch to trip over, so the store owns both halves of the removal. Unknown ids are
     * ignored: a stale chip must not delete a file it no longer owns.
     */
    fun removePendingAttachment(id: String) {
        val index = pending.indexOfFirst { it.id == id }
        if (index < 0) return
        pending.removeAt(index)
        val conversation = conversationId ?: return
        attachments?.delete(conversation, id)
    }

    /**
     * Chip label fallback when the caller supplies no name. The first line is user data, not UI copy,
     * so it needs no localisation resource; it is bounded like a file name.
     */
    private fun firstLineOf(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(60)

    // ---- document grounding (M8) -------------------------------------------------------------

    /**
     * Subscribes to the RAG service's library and readiness. The store mirrors them as Compose state
     * (Compose cannot observe a StateFlow without a lifecycle) and prunes a conversation scope when a
     * document disappears, so a deleted document cannot keep costing a retrieval round trip. Without
     * an app-owned scope (tests, previews) the values are read once and refreshed by [refreshRagState]
     * instead of being observed.
     */
    private fun observeRag() {
        val rag = rag ?: return
        refreshDocuments(rag.documents.value)
        ragStatus = rag.status.value
        val scope = appScope ?: return
        scope.launch {
            rag.documents.collect { refreshDocuments(it) }
        }
        scope.launch {
            rag.status.collect { ragStatus = it }
        }
    }

    /** Re-reads library state for callers outside the observed flow (tests, one-shot refreshes). */
    fun refreshRagState() {
        val rag = rag ?: return
        refreshDocuments(rag.documents.value)
        ragStatus = rag.status.value
    }

    private fun refreshDocuments(documents: List<RagDocument>) {
        libraryDocuments.clear()
        libraryDocuments.addAll(documents)
        val known = documents.mapTo(HashSet()) { it.id }
        val dropped = scopedDocuments.filterNot { it in known }
        if (dropped.isNotEmpty()) {
            scopedDocuments.removeAll(dropped)
            persistScope()
        }
    }

    /**
     * Adds a document to the conversation's grounding scope. Only a known library document is
     * accepted: a stale id from a picker must not silently widen retrieval to the whole library.
     */
    fun addDocumentToScope(documentId: String) {
        val service = rag ?: return
        if (service.findDocument(documentId) == null) return
        if (documentId in scopedDocuments) return
        scopedDocuments += documentId
        persistScope()
    }

    /** Adds every READY document; the cross-document ask in one tap. */
    fun selectAllReadyDocuments() {
        val ready = libraryDocuments.filter { it.isReady }.map { it.id }
        if (ready.isEmpty()) return
        scopedDocuments.clear()
        scopedDocuments.addAll(ready)
        persistScope()
    }

    /** Removes one document from the scope; the transcript keeps its old citations untouched. */
    fun removeDocumentFromScope(documentId: String) {
        if (scopedDocuments.remove(documentId)) persistScope()
    }

    /** Drops the whole scope, returning the conversation to ungrounded answers. */
    fun clearDocumentScope() {
        if (scopedDocuments.isEmpty()) return
        scopedDocuments.clear()
        persistScope()
    }

    /**
     * Imports a document into the local library and, on success, adds it to this conversation's scope.
     * Returns false when no RAG service is composed or an import is already running; the outcome
     * arrives through [documents] (success) or [documentImportRejection] (a stable code).
     */
    fun importDocument(source: ExtractionSource, callerScope: CoroutineScope): Boolean {
        val service = rag ?: return false
        if (importingDocument) return false
        importingDocument = true
        documentImportRejection = null
        (appScope ?: callerScope).launch {
            val outcome: RagImportResult? = runCatching { service.import(source) }.getOrNull()
            importingDocument = false
            when (outcome) {
                is RagImportResult.Imported -> addDocumentToScope(outcome.document.id)
                is RagImportResult.Rejected -> documentImportRejection = outcome.reason.name
                null -> documentImportRejection = RagImportRejection.CORRUPT.name
            }
        }
        return true
    }

    /** Dismisses the last import rejection; the picker keeps the user in place to try again. */
    fun dismissDocumentImportError() {
        documentImportRejection = null
    }

    /** Records a failure that happened before the import started (e.g. an unreadable picker result). */
    fun reportDocumentImportError(reason: String) {
        documentImportRejection = reason
    }

    /** Persists the scope on the conversation on screen; the transcript is written by the same path. */
    private fun persistScope() {
        current = current?.copy(documentIds = scopedDocuments.toList())
        if (conversations != null) persist()
    }

    /**
     * Sends one question. A blank prompt is allowed while attachments are queued: the attachment text
     * *is* the question then. Both empty, or a running stream, is a no-op, never an empty turn on
     * screen. The queue is snapshotted and cleared before generation, so a second send cannot inherit
     * the same attachment and a failed turn still keeps exactly what was sent.
     */
    fun send(rawPrompt: String, callerScope: CoroutineScope) {
        val prompt = rawPrompt.trim()
        if (streaming) return
        val snapshot = pending.toList()
        if (prompt.isEmpty() && snapshot.isEmpty()) return
        val history = history()
        items += Item.User(nextId++, prompt, snapshot)
        pending.clear()
        // Persist the question before generation starts: a process kill mid-answer must not lose it.
        persist()
        // A missing model is a first-class state, not an empty answer: fail the turn immediately
        // and say why, so the UI can point at Settings instead of stalling.
        if (engine.status.value !is EngineStatus.Ready) {
            items += failedTurn(NO_MODEL)
            return
        }
        val images = snapshot.filter { it.kind == AttachmentKind.IMAGE }
        // An image the loaded model cannot see must fail the turn, never be dropped silently.
        if (images.isNotEmpty() && !attachmentGate.isEnabled(AttachmentKind.IMAGE)) {
            items += failedTurn(EngineReasons.VISION_UNAVAILABLE)
            return
        }
        // M9: an image turn answers about the image itself, so document grounding only applies to
        // text-only turns; mixing retrieved text into a "what is in this picture" request confuses
        // small VLMs more than it helps (multi-modal RAG is v1.1).
        if (grounded(prompt, snapshot) && images.isEmpty()) {
            generateGrounded(prompt, snapshot, history, scopedDocuments.toList(), appScope ?: callerScope)
        } else {
            generate(
                prompt = AttachmentPrompt.compose(prompt, snapshot),
                history = history,
                scope = appScope ?: callerScope,
                imageAttachments = images,
            )
        }
    }

    /** Grounding needs a question of its own and an explicit document scope; otherwise plain chat. */
    private fun grounded(question: String, attachments: List<Attachment>): Boolean =
        rag != null && question.isNotBlank() && scopedDocuments.isNotEmpty()

    fun retry(callerScope: CoroutineScope) {
        if (streaming) return
        val lastUser = items.lastOrNull { it is Item.User } as? Item.User ?: return
        val lastAssistant = items.lastOrNull() as? Item.Assistant
        val history = history().dropLast(1)
        if (lastAssistant != null) {
            items.remove(lastAssistant)
            // The failed/stopped answer was never saved; writing now also drops it from disk.
            persist()
        }
        if (engine.status.value !is EngineStatus.Ready) {
            items += failedTurn(NO_MODEL)
            return
        }
        val images = lastUser.attachments.filter { it.kind == AttachmentKind.IMAGE }
        if (images.isNotEmpty() && !attachmentGate.isEnabled(AttachmentKind.IMAGE)) {
            items += failedTurn(EngineReasons.VISION_UNAVAILABLE)
            return
        }
        if (grounded(lastUser.text, lastUser.attachments) && images.isEmpty()) {
            generateGrounded(
                question = lastUser.text,
                attachments = lastUser.attachments,
                history = history,
                scope = scopedDocuments.toList(),
                callerScope = appScope ?: callerScope,
            )
        } else {
            generate(
                prompt = AttachmentPrompt.compose(lastUser.text, lastUser.attachments),
                history = history,
                scope = appScope ?: callerScope,
                imageAttachments = images,
            )
        }
    }

    /**
     * Asks for a different answer to the last question, replacing the answer already on screen.
     * No-op while streaming and when the last turn is not a finished answer: [retry] owns failed
     * turns, so this action never silently repeats one.
     */
    fun regenerate(callerScope: CoroutineScope) {
        if (streaming) return
        val lastUser = items.lastOrNull { it is Item.User } as? Item.User ?: return
        val lastAssistant = items.lastOrNull() as? Item.Assistant ?: return
        if (lastAssistant.streaming || lastAssistant.failed || lastAssistant.text.isBlank()) return
        // history() contains the answer being replaced; the question travels as the prompt, exactly
        // as it did on the first send, so both last entries are dropped and nothing is asked twice.
        val history = history().dropLast(2)
        items.remove(lastAssistant)
        // A rejected answer must not survive a process kill mid-stream: drop it from disk now.
        persist()
        if (engine.status.value !is EngineStatus.Ready) {
            items += failedTurn(NO_MODEL)
            return
        }
        val images = lastUser.attachments.filter { it.kind == AttachmentKind.IMAGE }
        if (images.isNotEmpty() && !attachmentGate.isEnabled(AttachmentKind.IMAGE)) {
            items += failedTurn(EngineReasons.VISION_UNAVAILABLE)
            return
        }
        if (grounded(lastUser.text, lastUser.attachments) && images.isEmpty()) {
            generateGrounded(
                question = lastUser.text,
                attachments = lastUser.attachments,
                history = history,
                scope = scopedDocuments.toList(),
                callerScope = appScope ?: callerScope,
            )
        } else {
            generate(
                prompt = AttachmentPrompt.compose(lastUser.text, lastUser.attachments),
                history = history,
                scope = appScope ?: callerScope,
                imageAttachments = images,
            )
        }
    }

    fun stop() {
        engine.stop()
        job?.cancel()
        job = null
        val lastAssistant = items.lastOrNull() as? Item.Assistant
        if (lastAssistant != null) {
            update(lastAssistant.id) {
                if (it.text.isBlank()) {
                    // Stopping before the first token is a user choice, not an engine failure.
                    it.copy(streaming = false, failed = true, failureReason = STOPPED)
                } else {
                    // Keep whatever arrived: a partial answer is still an answer, not an error.
                    it.copy(streaming = false)
                }
            }
        }
        streaming = false
        persist()
    }

    /** Wipes the visible conversation and its saved copy; the only place history is destroyed. */
    fun clear() {
        if (conversations != null) {
            // With a library, "start over" means a fresh conversation: history is never destroyed here.
            newConversation()
            return
        }
        engine.stop()
        job?.cancel()
        job = null
        streaming = false
        items.clear()
        val store = conversation ?: return
        // Invalidate every queued save before deleting, so a late write cannot bring history back.
        synchronized(persistLock) {
            requestedSave++
            writtenSave = requestedSave
        }
        store.clear()
    }

    // ---- conversation library (M6) -----------------------------------------------------------

    /** Summaries for the history list, newest first; empty with the single-transcript seam. */
    fun conversations(): List<ConversationSummary> = conversations?.list() ?: emptyList()

    /** Full-text search over saved conversations (title and every turn). */
    fun searchConversations(query: String): List<ConversationSummary> =
        conversations?.search(query) ?: emptyList()

    /** Reads a saved conversation (for export/preview) without changing which one is on screen. */
    fun loadConversation(id: String): Conversation? = conversations?.find(id)

    /** Renames any conversation in the library; the header only updates when it is the active one. */
    fun renameConversation(id: String, title: String?) {
        val library = conversations ?: return
        if (!library.rename(id, title)) return
        if (id == conversationId) conversationTitle = title?.trim()?.ifBlank { null }
    }

    /** Starts and activates a fresh conversation, keeping the current transcript in the library. */
    fun newConversation() {
        val library = conversations ?: run { clear(); return }
        stop()
        val created = library.create()
        library.setActiveId(created.id)
        adopt(created)
    }

    /** Opens a saved conversation; false (and no change) when the id is unknown or a stream is running. */
    fun openConversation(id: String): Boolean {
        val library = conversations ?: return false
        if (streaming) return false
        val target = library.find(id) ?: return false
        adopt(target)
        library.setActiveId(id)
        return true
    }

    /**
     * Deletes a conversation; deleting the active one opens the newest remaining or a fresh one. The
     * attachment cascade runs for *any* deleted conversation, not only the open one, so a chat removed
     * from the history list cannot leave its attachment directory behind (INP-1). When the open
     * conversation goes, the unsent queue is cleared without per-file deletes: the cascade already
     * removed the whole conversation directory.
     */
    fun deleteConversation(id: String): Boolean {
        val library = conversations ?: return false
        if (id == conversationId) stop()
        if (!library.delete(id)) return false
        attachments?.deleteConversation(id)
        if (id != conversationId) return true
        pending.clear()
        val next = library.list().firstOrNull()?.let { library.find(it.id) }
        if (next != null) {
            adopt(next)
            library.setActiveId(next.id)
        } else {
            val created = library.create()
            library.setActiveId(created.id)
            adopt(created)
        }
        return true
    }

    /** Renames the conversation on screen; blank clears the explicit title back to the derived one. */
    fun renameCurrentConversation(title: String?) {
        val id = conversationId ?: return
        renameConversation(id, title)
    }

    private fun restoreOrCreateConversation() {
        val library = conversations ?: return
        val active = library.activeId()?.let(library::find)
            ?: library.list().firstOrNull()?.let { library.find(it.id) }
            ?: library.create()
        adopt(active)
        library.setActiveId(active.id)
    }

    /** Makes [conversation] the on-screen transcript; the only place restored turns are built. */
    private fun adopt(conversation: Conversation) {
        // Queued attachments belong to the outgoing conversation; delete them before the id moves,
        // while the store still knows which conversation owns the files.
        discardPending()
        items.clear()
        nextId = 0L
        conversation.turns.forEach(::restore)
        current = conversation
        conversationId = conversation.id
        conversationTitle = conversation.title
        // The scope belongs to the conversation, not the store: reopening a chat restores exactly the
        // documents it was grounded in.
        scopedDocuments.clear()
        scopedDocuments.addAll(conversation.documentIds)
        refreshBudget()
    }

    /**
     * Reads/writes the per-conversation layers: a prompt override and a sparse generation override.
     * The active request picks them up on the next send; the meter refresh keeps the footer honest.
     */
    fun updateConversationConfig(
        id: String,
        systemPrompt: String?,
        overrides: GenerationOverrides?,
    ): Boolean {
        val library = conversations ?: return false
        val existing = library.find(id) ?: return false
        val updated = existing.copy(
            systemPrompt = systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
            overrides = overrides?.takeIf { !it.isEmpty() },
        )
        if (!library.save(updated)) return false
        if (id == conversationId) {
            current = updated
            refreshBudget()
        }
        return true
    }

    /** The effective layers for the next request: the provider applies the conversation layer. */
    private fun requestOptions(): GenerationOptions {
        val conversation = current ?: return generationSettings.options()
        return generationSettings.options(conversation.overrides, conversation.systemPrompt)
    }

    /**
     * Drops every queued attachment, deleting the files from the conversation they were stored under.
     * Runs before [conversationId] changes, which is the only moment the store still knows the owner;
     * this is what keeps switching away from a chat from leaving orphan attachments. A cascade delete
     * clears [pending] itself (the whole directory is already gone) and never reaches here first.
     */
    private fun discardPending() {
        if (pending.isEmpty()) return
        val owner = conversationId
        val store = attachments
        if (owner != null && store != null) pending.toList().forEach { store.delete(owner, it.id) }
        pending.clear()
    }

    /**
     * Recomputes the prompt meter from what the next request would actually send: user turns are
     * measured with their attachment block, because that text occupies the window too. Queued (unsent)
     * attachments are deliberately ignored: they are not part of the request yet.
     */
    private fun refreshBudget() {
        val options = requestOptions()
        val budget = ChatBudget.compute(
            turns = rendered(persistedTurns()),
            systemPrompt = options.systemPrompt,
            contextTokens = contextTokens,
            maxAnswerTokens = options.params.maxTokens,
        )
        // A grounded request also carries the retrieved source block; charging its ceiling keeps the
        // meter honest instead of pretending grounding is free window space.
        tokenBudget = if (rag != null && scopedDocuments.isNotEmpty()) {
            budget.copy(estimatedTokens = budget.estimatedTokens + RAG_CONTEXT_TOKENS)
        } else {
            budget
        }
    }

    private fun failedTurn(reason: String): Item.Assistant = Item.Assistant(
        id = nextId++,
        text = "",
        streaming = false,
        sources = emptyList(),
        failed = true,
        failureReason = reason,
    )

    /**
     * Turns for the engine. User turns carry their attachment content inline (labelled untrusted data
     * by [AttachmentPrompt]) so the model reads what the user actually attached on earlier turns too;
     * the raw text and the metadata stay untouched on the item for persistence and the chips.
     */
    private fun history(): List<ChatTurn> = rendered(items.mapNotNull(::turnOf))

    /** The engine view of [turns]: user attachment text inlined, assistant turns left alone. */
    private fun rendered(turns: List<ChatTurn>): List<ChatTurn> = turns.map { turn ->
        if (turn.role == Role.USER) {
            turn.copy(text = AttachmentPrompt.compose(turn.text, turn.attachments))
        } else {
            turn
        }
    }

    /** One item as a durable turn; null for an answer that is still streaming or never completed. */
    private fun turnOf(item: Item): ChatTurn? = when (item) {
        is Item.User -> ChatTurn(Role.USER, item.text, item.attachments)
        is Item.Assistant ->
            if (!item.streaming && !item.failed && item.text.isNotBlank()) {
                ChatTurn(Role.ASSISTANT, item.text, citations = item.citations)
            } else {
                null
            }
    }

    /**
     * Writes the durable transcript. Successful turns only: a failed or still-streaming answer is a
     * transient UI state, and the question alone is what a restart must keep.
     */
    private fun persist() {
        // The meter describes the next request, so refresh it with the transcript just changed.
        refreshBudget()
        if (conversations != null) {
            persistToLibrary()
            return
        }
        val store = conversation ?: return
        val turns = persistedTurns()
        if (appScope == null) {
            // Tests and previews: synchronous, so a fake store can be asserted immediately.
            synchronized(persistLock) {
                store.save(turns)
                requestedSave++
                writtenSave = requestedSave
            }
            return
        }
        val version = synchronized(persistLock) { ++requestedSave }
        appScope.launch(Dispatchers.IO) {
            synchronized(persistLock) {
                if (version <= writtenSave) return@synchronized
                runCatching { store.save(turns) }
                writtenSave = version
            }
        }
    }

    /**
     * Successful turns only, capped by the store contract: what a restart must keep. Attachment
     * metadata rides on the user turn as stored and the content is never duplicated into the durable
     * transcript; only the engine view ([history]) renders it. An attachment-only turn (blank prompt)
     * is kept because the attachment is the question.
     */
    private fun persistedTurns(): List<ChatTurn> = items.mapNotNull(::turnOf)
        .filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        .takeLast(ConversationStore.MAX_TURNS)

    /**
     * Writes the transcript into the active conversation of the library. The library owns file names
     * and timestamps; this store only knows the turns it asked to keep.
     */
    private fun persistToLibrary() {
        val library = conversations ?: return
        val conversation = current ?: return
        val snapshot = conversation.copy(
            turns = persistedTurns(),
            documentIds = scopedDocuments.toList(),
        )
        current = snapshot
        if (appScope == null) {
            synchronized(persistLock) {
                runCatching { library.save(snapshot) }
                requestedSave++
                writtenSave = requestedSave
            }
            return
        }
        val version = synchronized(persistLock) { ++requestedSave }
        appScope.launch(Dispatchers.IO) {
            synchronized(persistLock) {
                if (version <= writtenSave) return@synchronized
                runCatching { library.save(snapshot) }
                writtenSave = version
            }
        }
    }

    private fun generate(
        prompt: String,
        history: List<ChatTurn>,
        scope: CoroutineScope,
        citationsFor: ((answer: String) -> List<Citation>)? = null,
        imageAttachments: List<Attachment> = emptyList(),
    ) {
        val assistantId = nextId++
        items += Item.Assistant(assistantId, text = "", streaming = true, sources = emptyList(), failed = false)
        streaming = true
        job = scope.launch {
            // Image bytes are read and prepared off the main thread; a failure fails the turn with a
            // stable reason instead of asking the model about an image it never received.
            val images = prepareImageInputs(imageAttachments)
            if (images == null) {
                update(assistantId) {
                    it.copy(streaming = false, failed = true, failureReason = EngineReasons.VISION_IMAGE_FAILED)
                }
                streaming = false
                persist()
                return@launch
            }
            streamInto(assistantId, prompt, history, citationsFor, images)
        }
    }

    /**
     * M9: reads every queued image's stored bytes and hands them to [ImagePreparer]. Empty means the
     * turn has no images; null means at least one image could not be prepared.
     */
    private fun prepareImageInputs(attachments: List<Attachment>): List<ImageInput>? {
        val images = attachments.filter { it.kind == AttachmentKind.IMAGE }
        if (images.isEmpty()) return emptyList()
        val store = this.attachments ?: return null
        val preparer = imagePreparer ?: return null
        val conversation = conversationId ?: return null
        val prepared = ArrayList<ImageInput>(images.size)
        for (attachment in images) {
            val bytes = runCatching {
                store.open(conversation, attachment)?.use { it.readBytes() }
            }.getOrNull() ?: return null
            val input = runCatching { preparer.prepare(bytes, attachment.mimeType) }.getOrNull() ?: return null
            prepared += input
        }
        return prepared
    }

    /**
     * Grounded turn (M8): retrieval runs before generation and the prompt is the numbered-source
     * document built by [RagPrompt]. The assistant placeholder is on screen immediately so a slow
     * retrieval looks like thinking, not like a dead send.
     *
     * When retrieval finds nothing, the turn fails with [RAG_NO_SOURCES] instead of generating from
     * the model's own memory: silence here would be a grounded answer without grounding.
     */
    private fun generateGrounded(
        question: String,
        attachments: List<Attachment>,
        history: List<ChatTurn>,
        scope: List<String>,
        callerScope: CoroutineScope,
    ) {
        val assistantId = nextId++
        items += Item.Assistant(assistantId, text = "", streaming = true, sources = emptyList(), failed = false)
        streaming = true
        job = callerScope.launch {
            val hits = try {
                rag?.retrieve(question, scope).orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                emptyList()
            }
            if (hits.isEmpty()) {
                update(assistantId) {
                    it.copy(streaming = false, failed = true, failureReason = RAG_NO_SOURCES)
                }
                streaming = false
                persist()
                return@launch
            }
            val numbered = RagPrompt.compose(question, hits)
            // Attachments from the same turn still reach the model, labelled as untrusted data and
            // placed before the grounded instructions so the numbered-source contract stays last.
            val attachmentBlock = AttachmentPrompt.render(attachments)
            val prompt = if (attachmentBlock.isEmpty()) {
                numbered.prompt
            } else {
                attachmentBlock + "\n\n" + numbered.prompt
            }
            val citationsFor: (String) -> List<Citation> = { answer ->
                val cited = CitationParser.parse(answer, numbered.sources).map { citation ->
                    Citation(
                        documentId = citation.documentId,
                        chunkId = citation.chunkId,
                        documentName = citation.documentName,
                        page = citation.page,
                        snippet = citation.snippet,
                    )
                }
                // Small quantized models frequently answer the question correctly but drop the
                // inline [n] markers. The numbered sources are still the provenance the answer was
                // grounded on, so surface them (with the real page and excerpt) instead of an answer
                // that appears to have no source at all. When the model does cite, only what it
                // cited is kept: a hallucinated [9] still cannot create a citation.
                cited.ifEmpty {
                    numbered.sources.map { source ->
                        Citation(
                            documentId = source.documentId,
                            chunkId = source.chunkId,
                            documentName = source.documentName,
                            page = source.page,
                            snippet = source.snippet,
                        )
                    }
                }
            }
            streamInto(assistantId, prompt, history, citationsFor)
        }
    }

    /** The shared streaming loop for plain and grounded turns. */
    private suspend fun streamInto(
        assistantId: Long,
        prompt: String,
        history: List<ChatTurn>,
        citationsFor: ((answer: String) -> List<Citation>)?,
        images: List<ImageInput> = emptyList(),
    ) {
        val startedAt = clock()
        try {
            val chunks = if (images.isEmpty()) {
                engine.stream(prompt, history, requestOptions())
            } else {
                engine.streamWithImages(prompt, history, images, requestOptions())
            }
            chunks.collect { chunk ->
                when (chunk) {
                    is EngineChunk.Delta -> update(assistantId) { it.copy(text = it.text + chunk.text) }
                    is EngineChunk.Completed -> {
                        val finished = (items.firstOrNull { it.id == assistantId } as? Item.Assistant)?.text.orEmpty()
                        update(assistantId) {
                            it.copy(
                                streaming = false,
                                sources = chunk.sources,
                                citations = citationsFor?.invoke(finished).orEmpty(),
                            )
                        }
                        streaming = false
                        recordSpeed(finished, startedAt)
                        persist()
                    }
                    is EngineChunk.Failed -> {
                        update(assistantId) {
                            it.copy(streaming = false, failed = true, failureReason = chunk.reason)
                        }
                        streaming = false
                        persist()
                    }
                }
            }
            if (streaming) {
                val finished = (items.firstOrNull { it.id == assistantId } as? Item.Assistant)?.text.orEmpty()
                update(assistantId) {
                    it.copy(
                        streaming = false,
                        citations = citationsFor?.invoke(finished).orEmpty(),
                    )
                }
                streaming = false
                recordSpeed(finished, startedAt)
                persist()
            }
        } catch (cancelled: CancellationException) {
            // A stop() already clears this; a scope cancellation must not leave a stale caret.
            update(assistantId) { it.copy(streaming = false) }
            streaming = false
            persist()
            throw cancelled
        } finally {
            job = null
        }
    }

    /** Honest approximation: tokens estimated from the text, wall time from the request start. */
    private fun recordSpeed(text: String, startedAt: Long) {
        val duration = (clock() - startedAt).coerceAtLeast(0L)
        lastCompletionTokens = TokenEstimate.tokens(text)
        lastSpeed = GenerationSpeed.tokensPerSecond(text, duration)
    }

    private fun update(id: Long, transform: (Item.Assistant) -> Item.Assistant) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val current = items[index] as? Item.Assistant ?: return
            items[index] = transform(current)
        }
    }

    companion object {
        /** Prompt-meter reserve when no request is in flight. */
        private const val DEFAULT_ANSWER_RESERVE = 512

        /** Estimated prefill ceiling of the numbered-source block (see [RagLimits]). */
        private val RAG_CONTEXT_TOKENS: Int =
            app.sirrak.core.runtime.TokenEstimate.tokens(
                "x".repeat(RagLimits.Default.contextMaxChars),
            )

        /** Engine had no loaded model when the user sent a message. */
        const val NO_MODEL = EngineReasons.NO_MODEL

        /** Generation produced no text before stopping/failing. */
        const val NO_OUTPUT = EngineReasons.NO_OUTPUT

        /** The user explicitly stopped the turn. */
        const val STOPPED = EngineReasons.STOPPED

        /** Retrieval found no source in the conversation's document scope (M8). */
        const val RAG_NO_SOURCES = EngineReasons.RAG_NO_SOURCES
        const val PROCESS_LOST = EngineReasons.PROCESS_LOST
    }
}
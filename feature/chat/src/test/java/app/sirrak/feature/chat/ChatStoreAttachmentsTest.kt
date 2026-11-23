package app.sirrak.feature.chat

import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentBlockReason
import app.sirrak.core.runtime.AttachmentDecision
import app.sirrak.core.runtime.AttachmentImport
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.AttachmentLimits
import app.sirrak.core.runtime.AttachmentPolicy
import app.sirrak.core.runtime.AttachmentPrompt
import app.sirrak.core.runtime.AttachmentRejection
import app.sirrak.core.runtime.AttachmentSource
import app.sirrak.core.runtime.AttachmentStore
import app.sirrak.core.runtime.AttachmentValidation
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ConversationStore
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.ConversationTitles
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.EngineCapabilities
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.Role
import app.sirrak.core.runtime.Suggestion
import app.sirrak.core.runtime.TokenEstimate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7 attachments in the chat store: one untrusted-text seam for the engine, durable metadata on the
 * user turn, a queue of unsent attachments that belongs to exactly one conversation, and the cascade
 * that keeps deleted or abandoned conversations from leaving attachment files behind. The UI layer is
 * exercised through these values (chips read [ChatStore.pendingAttachments] and
 * [ChatStore.Item.User.attachments]), so the tests assert store state instead of pixels.
 */
class ChatStoreAttachmentsTest {

    /** Records the exact prompt/history one send hands to the engine, and can fail on demand. */
    private class RecordingEngine(
        initial: EngineStatus = EngineStatus.Ready("Qwen"),
        override val capabilities: EngineCapabilities = EngineCapabilities.TextOnly,
        private val chunks: List<EngineChunk> = listOf(
            EngineChunk.Delta("جواب"),
            EngineChunk.Completed(emptyList()),
        ),
    ) : InferenceEngine {
        override val id = "recording"
        override val displayName = "Recording"
        override val status = MutableStateFlow(initial)
        val prompts = mutableListOf<String>()
        val histories = mutableListOf<List<ChatTurn>>()

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
            prompts += prompt
            histories += history
            chunks.forEach { emit(it) }
        }
    }

    /** In-memory attachment storage that records every call the cascade makes. */
    private class FakeAttachmentStore : AttachmentStore {
        val stored = LinkedHashMap<String, MutableList<Attachment>>()
        val deleted = mutableListOf<Pair<String, String>>()
        val deletedConversations = mutableListOf<String>()
        var saveCalls = 0
        private var next = 0

        override fun list(conversationId: String): List<Attachment> = stored[conversationId].orEmpty()

        override fun import(conversationId: String, source: AttachmentSource): AttachmentImport =
            AttachmentImport.Rejected(AttachmentRejection.UNSUPPORTED_TYPE)

        override fun saveSharedText(
            conversationId: String,
            text: String,
            displayName: String,
        ): AttachmentImport {
            saveCalls++
            val validation = AttachmentPolicy.validateSharedText(text)
            if (validation is AttachmentValidation.Rejected) return AttachmentImport.Rejected(validation.reason)
            val id = "att-" + next++
            val attachment = Attachment(
                id = id,
                kind = AttachmentKind.SHARED_TEXT,
                displayName = displayName,
                mimeType = "text/plain",
                sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                localPath = "attachments/" + conversationId + "/" + id + ".txt",
                extractedText = text,
            )
            stored.getOrPut(conversationId) { mutableListOf() } += attachment
            return AttachmentImport.Stored(attachment)
        }

        override fun delete(conversationId: String, attachmentId: String): Boolean {
            deleted += conversationId to attachmentId
            return stored[conversationId]?.removeAll { it.id == attachmentId } == true
        }

        override fun deleteConversation(conversationId: String) {
            deletedConversations += conversationId
            stored.remove(conversationId)
        }
    }

    private class FakeConversation(initial: List<ChatTurn> = emptyList()) : ConversationStore {
        var turns: List<ChatTurn> = initial
        var saveCalls = 0

        override fun load(): List<ChatTurn> = turns

        override fun save(turns: List<ChatTurn>) {
            this.turns = turns
            saveCalls++
        }

        override fun clear() {
            turns = emptyList()
        }
    }

    /** The multi-conversation seam, because attachments are stored per conversation id. */
    private class FakeLibrary : ConversationLibrary {
        val conversations = LinkedHashMap<String, Conversation>()
        var active: String? = null
        private var next = 0
        private var clock = 1_000L

        override fun list(): List<ConversationSummary> = conversations.values
            .sortedByDescending { it.updatedAt }
            .map {
                ConversationSummary(
                    id = it.id,
                    title = it.title,
                    preview = "",
                    messageCount = it.turns.size,
                    updatedAt = it.updatedAt,
                    derivedTitle = ConversationTitles.derive(it.turns),
                )
            }

        override fun find(id: String): Conversation? = conversations[id]

        override fun create(title: String?): Conversation {
            val conversation = Conversation(
                id = "id-" + next++,
                title = title,
                createdAt = clock++,
                updatedAt = clock++,
            )
            conversations[conversation.id] = conversation
            return conversation
        }

        override fun save(conversation: Conversation): Boolean {
            if (!conversations.containsKey(conversation.id)) return false
            conversations[conversation.id] = conversation.copy(updatedAt = clock++)
            return true
        }

        override fun rename(id: String, title: String?): Boolean {
            val conversation = conversations[id] ?: return false
            conversations[id] = conversation.copy(title = title?.trim()?.ifBlank { null })
            return true
        }

        override fun delete(id: String): Boolean {
            if (conversations.remove(id) == null) return false
            if (active == id) active = list().firstOrNull()?.id
            return true
        }

        override fun activeId(): String? = active

        override fun setActiveId(id: String?): Boolean {
            if (id != null && !conversations.containsKey(id)) return false
            active = id
            return true
        }

        fun seed(
            id: String,
            turns: List<ChatTurn> = emptyList(),
            updatedAt: Long = 1_000L,
        ): Conversation {
            val conversation = Conversation(
                id = id,
                title = null,
                createdAt = updatedAt,
                updatedAt = updatedAt,
                turns = turns,
            )
            conversations[id] = conversation
            return conversation
        }
    }

    private class FakeContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
    }

    private suspend fun ChatStore.awaitIdle() {
        while (streaming) kotlinx.coroutines.yield()
    }

    private fun attachment(
        id: String = "saved-1",
        displayName: String = "clip.txt",
        text: String? = "محتوى الملف",
    ): Attachment = Attachment(
        id = id,
        kind = AttachmentKind.SHARED_TEXT,
        displayName = displayName,
        mimeType = "text/plain",
        sizeBytes = text?.length?.toLong() ?: 0L,
        localPath = "attachments/one/" + id + ".txt",
        extractedText = text,
    )

    private fun library(vararg ids: String, active: String = ids.first()): FakeLibrary {
        val library = FakeLibrary()
        ids.forEachIndexed { index, id -> library.seed(id, updatedAt = 1_000L + index) }
        library.active = active
        return library
    }

    @Test
    fun `attaching shared text stores it and queues it for the next send`() {
        val library = library("one")
        val storage = FakeAttachmentStore()
        val store = ChatStore(RecordingEngine(), FakeContent(), conversations = library, attachments = storage)

        assertTrue(store.attachSharedText("نص من الحافظة", "clip.txt"))

        val pending = store.pendingAttachments.single()
        assertEquals(AttachmentKind.SHARED_TEXT, pending.kind)
        assertEquals("clip.txt", pending.displayName)
        assertEquals("نص من الحافظة", pending.extractedText)
        assertEquals(listOf(pending), storage.list("one"))
        assertEquals(1, storage.saveCalls)
    }

    @Test
    fun `blank and overlong shared text are rejected before they reach storage`() {
        val storage = FakeAttachmentStore()
        val store = ChatStore(
            RecordingEngine(),
            FakeContent(),
            conversations = library("one"),
            attachments = storage,
        )

        assertFalse(store.attachSharedText("   ", "clip.txt"))
        assertFalse(
            store.attachSharedText(
                "x".repeat(AttachmentLimits.Default.sharedTextChars + 1),
                "clip.txt",
            ),
        )

        assertEquals(0, storage.saveCalls)
        assertTrue(store.pendingAttachments.isEmpty())
    }

    @Test
    fun `shared text needs both a store and an active conversation`() {
        val withoutStore = ChatStore(RecordingEngine(), FakeContent(), conversations = library("one"))
        assertFalse(withoutStore.attachSharedText("نص", "clip.txt"))

        // The single-transcript seam has no conversation id, so there is nowhere to own the file.
        val storage = FakeAttachmentStore()
        val withoutConversation = ChatStore(RecordingEngine(), FakeContent(), attachments = storage)
        assertFalse(withoutConversation.attachSharedText("نص", "clip.txt"))

        assertEquals(0, storage.saveCalls)
    }

    @Test
    fun `sending attaches pending files to the user turn and clears the queue`() = runBlocking {
        val storage = FakeAttachmentStore()
        val engine = RecordingEngine()
        val store = ChatStore(
            engine,
            FakeContent(),
            conversations = library("one"),
            attachments = storage,
        )
        store.attachSharedText("محتوى الملف", "clip.txt")

        store.send("ما هذا؟", this)
        store.awaitIdle()

        val user = store.items.first() as ChatStore.Item.User
        assertEquals("ما هذا؟", user.text)
        assertEquals("clip.txt", user.attachments.single().displayName)
        assertTrue(store.pendingAttachments.isEmpty())

        val prompt = engine.prompts.single()
        assertTrue(prompt.startsWith("ما هذا؟"))
        assertTrue(prompt.contains(AttachmentPrompt.UNTRUSTED_HEADER))
        assertTrue(prompt.contains("<attachment name=\"clip.txt\""))
        assertTrue(prompt.contains("محتوى الملف"))
    }

    @Test
    fun `a send with only an attachment is a real turn`() = runBlocking {
        val engine = RecordingEngine()
        val store = ChatStore(
            engine,
            FakeContent(),
            conversations = library("one"),
            attachments = FakeAttachmentStore(),
        )
        store.attachSharedText("محتوى الملف", "clip.txt")

        store.send("   ", this)
        store.awaitIdle()

        val user = store.items.first() as ChatStore.Item.User
        assertEquals("", user.text)
        assertEquals(1, user.attachments.size)
        assertEquals(2, store.items.size)
        assertTrue(engine.prompts.single().startsWith(AttachmentPrompt.UNTRUSTED_HEADER))
        assertTrue(engine.prompts.single().contains("محتوى الملف"))
    }

    @Test
    fun `an empty send with no attachment does nothing`() = runBlocking {
        val engine = RecordingEngine()
        val store = ChatStore(
            engine,
            FakeContent(),
            conversations = library("one"),
            attachments = FakeAttachmentStore(),
        )

        store.send("   ", this)

        assertTrue(store.items.isEmpty())
        assertTrue(engine.prompts.isEmpty())
    }

    @Test
    fun `the saved transcript keeps attachment metadata on the user turn`() = runBlocking {
        val library = library("one")
        val store = ChatStore(
            RecordingEngine(),
            FakeContent(),
            conversations = library,
            attachments = FakeAttachmentStore(),
        )
        store.attachSharedText("محتوى الملف", "clip.txt")

        store.send("سؤال", this)
        store.awaitIdle()

        val user = library.find("one")!!.turns.first { it.role == Role.USER }
        assertEquals("سؤال", user.text)
        assertEquals("clip.txt", user.attachments.single().displayName)
        assertEquals("محتوى الملف", user.attachments.single().extractedText)
    }

    @Test
    fun `a restored conversation keeps the attachments on its user turns`() {
        val saved = attachment(id = "saved-1", displayName = "notes.txt")
        val conversation = FakeConversation(
            listOf(
                ChatTurn(Role.USER, "سؤال", listOf(saved)),
                ChatTurn(Role.ASSISTANT, "جواب"),
            ),
        )

        val store = ChatStore(RecordingEngine(), FakeContent(), conversation = conversation)

        val user = store.items.first() as ChatStore.Item.User
        assertEquals(listOf(saved), user.attachments)
    }

    @Test
    fun `an attachment-only question survives restore`() {
        val saved = attachment(id = "saved-1")
        val conversation = FakeConversation(
            listOf(
                ChatTurn(Role.USER, "", listOf(saved)),
                ChatTurn(Role.ASSISTANT, "جواب"),
            ),
        )

        val store = ChatStore(RecordingEngine(), FakeContent(), conversation = conversation)

        val user = store.items.first() as ChatStore.Item.User
        assertEquals("", user.text)
        assertEquals(listOf(saved), user.attachments)
    }

    @Test
    fun `removing a queued attachment deletes the stored file and the chip`() {
        val storage = FakeAttachmentStore()
        val store = ChatStore(
            RecordingEngine(),
            FakeContent(),
            conversations = library("one"),
            attachments = storage,
        )
        store.attachSharedText("الأول", "first.txt")
        store.attachSharedText("الثاني", "second.txt")
        val removed = store.pendingAttachments.first()

        store.removePendingAttachment(removed.id)

        assertEquals(listOf("second.txt"), store.pendingAttachments.map { it.displayName })
        assertEquals(listOf("one" to removed.id), storage.deleted)
        assertTrue(storage.list("one").none { it.id == removed.id })

        // An unknown id is a stale chip, not a reason to delete anything from storage.
        store.removePendingAttachment("missing")
        assertEquals(1, storage.deleted.size)
    }

    @Test
    fun `switching conversations drops queued attachments from the conversation they came from`() {
        val library = FakeLibrary()
        library.seed("a", updatedAt = 1_000L)
        library.seed("b", updatedAt = 2_000L)
        library.active = "a"
        val storage = FakeAttachmentStore()
        val store = ChatStore(RecordingEngine(), FakeContent(), conversations = library, attachments = storage)
        store.attachSharedText("نص غير مرسل", "clip.txt")
        val queued = store.pendingAttachments.single()

        assertTrue(store.openConversation("b"))

        assertEquals("b", store.conversationId)
        assertTrue(store.pendingAttachments.isEmpty())
        assertEquals(listOf("a" to queued.id), storage.deleted)
        assertTrue(storage.list("a").isEmpty())
    }

    @Test
    fun `deleting a conversation cascades its stored attachments even when it is not open`() {
        val library = FakeLibrary()
        library.seed("a", updatedAt = 1_000L)
        library.seed("b", updatedAt = 2_000L)
        library.active = "b"
        val storage = FakeAttachmentStore()
        val store = ChatStore(RecordingEngine(), FakeContent(), conversations = library, attachments = storage)

        assertTrue(store.deleteConversation("a"))

        assertEquals(listOf("a"), storage.deletedConversations)
        assertEquals("b", store.conversationId)
    }

    @Test
    fun `deleting the open conversation cascades and clears the queue without per-file deletes`() {
        val library = FakeLibrary()
        library.seed("a", updatedAt = 1_000L)
        library.seed("b", updatedAt = 2_000L)
        library.active = "b"
        val storage = FakeAttachmentStore()
        val store = ChatStore(RecordingEngine(), FakeContent(), conversations = library, attachments = storage)
        store.attachSharedText("نص غير مرسل", "clip.txt")

        assertTrue(store.deleteConversation("b"))

        assertEquals(listOf("b"), storage.deletedConversations)
        // The conversation directory is gone; deleting each file again would be noise, not safety.
        assertTrue(storage.deleted.isEmpty())
        assertTrue(store.pendingAttachments.isEmpty())
        assertEquals("a", store.conversationId)
    }

    @Test
    fun `the gate follows the engine capabilities`() {
        val textOnly = ChatStore(RecordingEngine(), FakeContent())
        assertTrue(textOnly.attachmentGate.isEnabled(AttachmentKind.SHARED_TEXT))
        assertEquals(
            AttachmentDecision.Disabled(AttachmentBlockReason.DOCUMENT_BACKEND),
            textOnly.attachmentGate.decision(AttachmentKind.DOCUMENT),
        )
        assertEquals(
            AttachmentDecision.Disabled(AttachmentBlockReason.VISION_MODEL),
            textOnly.attachmentGate.decision(AttachmentKind.IMAGE),
        )
        assertEquals(
            AttachmentDecision.Disabled(AttachmentBlockReason.AUDIO_BACKEND),
            textOnly.attachmentGate.decision(AttachmentKind.AUDIO_TRANSCRIPT),
        )

        val vision = ChatStore(
            RecordingEngine(capabilities = EngineCapabilities(images = true)),
            FakeContent(),
        )
        assertTrue(vision.attachmentGate.isEnabled(AttachmentKind.IMAGE))
        assertFalse(vision.attachmentGate.isEnabled(AttachmentKind.DOCUMENT))
    }

    @Test
    fun `earlier attachment text is rendered into the engine history of the next send`() = runBlocking {
        val engine = RecordingEngine()
        val store = ChatStore(
            engine,
            FakeContent(),
            conversations = library("one"),
            attachments = FakeAttachmentStore(),
        )
        store.attachSharedText("محتوى الملف", "clip.txt")

        store.send("السؤال الأول", this)
        store.awaitIdle()
        store.send("السؤال الثاني", this)
        store.awaitIdle()

        val history = engine.histories[1]
        assertEquals(Role.USER, history.first().role)
        assertTrue(history.first().text.startsWith("السؤال الأول"))
        assertTrue(history.first().text.contains(AttachmentPrompt.UNTRUSTED_HEADER))
        assertTrue(history.first().text.contains("محتوى الملف"))
        assertEquals(ChatTurn(Role.ASSISTANT, "جواب"), history[1])
    }

    @Test
    fun `retry reuses the question together with its attachments`() = runBlocking {
        val engine = RecordingEngine(chunks = listOf(EngineChunk.Failed("out-of-memory")))
        val store = ChatStore(
            engine,
            FakeContent(),
            conversations = library("one"),
            attachments = FakeAttachmentStore(),
        )
        store.attachSharedText("محتوى الملف", "clip.txt")

        store.send("سؤال", this)
        store.awaitIdle()
        store.retry(this)
        store.awaitIdle()

        assertEquals(2, engine.prompts.size)
        assertEquals(engine.prompts[0], engine.prompts[1])
        assertTrue(engine.prompts[1].contains("<attachment name=\"clip.txt\""))
    }

    @Test
    fun `regenerate reuses the question together with its attachments`() = runBlocking {
        val engine = RecordingEngine()
        val store = ChatStore(
            engine,
            FakeContent(),
            conversations = library("one"),
            attachments = FakeAttachmentStore(),
        )
        store.attachSharedText("محتوى الملف", "clip.txt")

        store.send("سؤال", this)
        store.awaitIdle()
        store.regenerate(this)
        store.awaitIdle()

        assertEquals(2, engine.prompts.size)
        assertEquals(engine.prompts[0], engine.prompts[1])
        assertTrue(engine.prompts[1].contains("محتوى الملف"))
    }

    @Test
    fun `the prompt meter counts attachment text that is actually sent`() = runBlocking {
        val body = "م".repeat(400)
        val store = ChatStore(
            RecordingEngine(),
            FakeContent(),
            conversations = library("one"),
            attachments = FakeAttachmentStore(),
        )
        store.attachSharedText(body, "clip.txt")

        store.send("سؤال", this)
        store.awaitIdle()

        assertTrue(store.tokenBudget.estimatedTokens >= TokenEstimate.tokens(body))
        assertTrue(store.tokenBudget.estimatedTokens > TokenEstimate.tokens("سؤال"))
    }

    @Test
    fun `queued attachments do not inflate the meter before they are sent`() {
        val store = ChatStore(
            RecordingEngine(),
            FakeContent(),
            conversations = library("one"),
            attachments = FakeAttachmentStore(),
        )

        store.attachSharedText("م".repeat(400), "clip.txt")

        assertEquals(0, store.tokenBudget.estimatedTokens)
    }
}

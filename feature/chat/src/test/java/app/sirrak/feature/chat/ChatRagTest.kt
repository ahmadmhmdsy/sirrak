package app.sirrak.feature.chat

import app.sirrak.core.rag.EmbeddingStatus
import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.RagChunk
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagDocumentStatus
import app.sirrak.core.rag.RagHit
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.rag.RagImportResult
import app.sirrak.core.rag.RagLimits
import app.sirrak.core.rag.RagService
import app.sirrak.core.rag.RagStatus
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.ConversationTitles
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.Suggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8 chat integration: the store is where retrieval, the numbered prompt, citation parsing and
 * conversation-scope persistence meet. Fakes prove the seam rules, not LiteRT behaviour (the device
 * evaluation in doc 22 covers the real embeddings):
 *
 * - ungrounded sends stay exactly as they were in M7 and never call retrieval;
 * - a scoped send retrieves first, builds the numbered prompt and keeps the cited sources, falling
 *   back to the retrieved set when the model omits inline [n] markers;
 * - no hits is a failed turn with a stable code, never an answer guessed from memory;
 * - the scope belongs to the conversation and survives a reopen;
 * - a deleted document drops out of the scope instead of silently widening retrieval.
 */
class ChatRagTest {

    private class FakeRag(
        initial: List<RagDocument> = listOf(document("d1", "مقدمة في القياس.pdf")),
        private val hits: List<RagHit> = listOf(hit()),
    ) : RagService {
        override val documents = MutableStateFlow(initial)
        override val status = MutableStateFlow(
            RagStatus(
                documentCount = initial.size,
                readyCount = initial.count { it.isReady },
                pendingCount = initial.count { it.status == RagDocumentStatus.EXTRACTED },
                embedding = EmbeddingStatus.Ready("embeddinggemma-2-270m", 768),
            ),
        )
        var retrieveCalls = 0
        var lastQuestion: String? = null
        var lastScope: List<String>? = null

        override fun findDocument(id: String): RagDocument? = documents.value.firstOrNull { it.id == id }
        override suspend fun import(source: ExtractionSource): RagImportResult =
            RagImportResult.Rejected(RagImportRejection.UNSUPPORTED_TYPE)
        override suspend fun delete(documentId: String): Boolean {
            documents.value = documents.value.filterNot { it.id == documentId }
            return true
        }
        override suspend fun reindex(documentId: String): Boolean = false
        override fun chunks(documentId: String): List<RagChunk> = emptyList()
        override fun searchDocuments(query: String): List<RagDocument> = documents.value
        override suspend fun retrieve(
            question: String,
            documentIds: List<String>,
            limits: RagLimits,
        ): List<RagHit> {
            retrieveCalls++
            lastQuestion = question
            lastScope = documentIds
            return hits
        }
    }

    private class RagEngine(
        private val chunks: List<EngineChunk> = listOf(
            EngineChunk.Delta("القياس [1] هو الاستدلال من الأصل إلى الفرع."),
            EngineChunk.Completed(emptyList()),
        ),
    ) : InferenceEngine {
        override val id = "rag-fake"
        override val displayName = "RAG fake"
        override val status = MutableStateFlow(EngineStatus.Ready("fake"))
        val prompts = ArrayList<String>()
        var streamCalls = 0

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
            streamCalls++
            prompts += prompt
            chunks.forEach { emit(it) }
        }
    }

    private class FakeContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
    }

    /** In-memory implementation of the seam the real app fills with FileConversationLibrary. */
    private class MemoryConversationLibrary : ConversationLibrary {
        private val stored = LinkedHashMap<String, Conversation>()
        private var active: String? = null
        private var next = 0

        override fun list(): List<ConversationSummary> = stored.values
            .sortedByDescending { it.updatedAt }
            .map { conversation ->
                ConversationSummary(
                    id = conversation.id,
                    title = conversation.title,
                    preview = conversation.turns.lastOrNull()?.text?.take(60).orEmpty(),
                    messageCount = conversation.turns.size,
                    updatedAt = conversation.updatedAt,
                    derivedTitle = ConversationTitles.derive(conversation.turns),
                )
            }

        override fun find(id: String): Conversation? = stored[id]

        override fun create(title: String?): Conversation {
            val created = Conversation("c-" + next++, title, createdAt = 0, updatedAt = 0)
            stored[created.id] = created
            return created
        }

        override fun save(conversation: Conversation): Boolean {
            if (stored[conversation.id] == null) return false
            stored[conversation.id] = conversation
            return true
        }

        override fun rename(id: String, title: String?): Boolean {
            val existing = stored[id] ?: return false
            stored[id] = existing.copy(title = title)
            return true
        }

        override fun delete(id: String): Boolean {
            stored.remove(id) ?: return false
            if (active == id) active = stored.keys.lastOrNull()
            return true
        }

        override fun activeId(): String? = active

        override fun setActiveId(id: String?): Boolean {
            active = id
            return true
        }
    }

    private suspend fun ChatStore.awaitIdle() {
        while (streaming) kotlinx.coroutines.yield()
    }

    private fun store(
        engine: InferenceEngine,
        rag: RagService? = null,
        conversations: ConversationLibrary? = null,
    ) = ChatStore(
        engine = engine,
        library = FakeContent(),
        conversations = conversations,
        rag = rag,
    )

    @Test
    fun `an empty scope sends the plain prompt and never retrieves`() = runBlocking {
        val engine = RagEngine()
        val rag = FakeRag()
        val chat = store(engine, rag)
        chat.refreshRagState()

        chat.send("ما هو القياس؟", this)
        chat.awaitIdle()

        assertEquals(0, rag.retrieveCalls)
        assertEquals(1, engine.streamCalls)
        assertEquals("ما هو القياس؟", engine.prompts.single())
        assertTrue((chat.items[1] as ChatStore.Item.Assistant).citations.isEmpty())
    }

    @Test
    fun `a scoped send retrieves with the numbered prompt and keeps cited sources`() = runBlocking {
        val engine = RagEngine()
        val rag = FakeRag()
        val chat = store(engine, rag)
        chat.refreshRagState()
        chat.addDocumentToScope("d1")

        chat.send("ما هو القياس؟", this)
        chat.awaitIdle()

        assertEquals(1, rag.retrieveCalls)
        assertEquals(listOf("d1"), rag.lastScope)
        assertEquals("ما هو القياس؟", rag.lastQuestion)
        val prompt = engine.prompts.single()
        assertTrue(prompt.contains("SOURCES:"))
        assertTrue(prompt.contains("[1]"))
        assertTrue(prompt.contains("QUESTION: ما هو القياس؟"))
        val assistant = chat.items[1] as ChatStore.Item.Assistant
        assertFalse(assistant.failed)
        assertEquals(1, assistant.citations.size)
        val citation = assistant.citations.single()
        assertEquals("d1", citation.documentId)
        assertEquals("c1", citation.chunkId)
        assertEquals(3, citation.page)
        assertEquals("مقدمة في القياس.pdf", citation.documentName)
        assertTrue(citation.snippet.isNotBlank())
    }

    @Test
    fun `a grounded answer without inline markers keeps the retrieved sources as its citations`() =
        runBlocking {
            val engine = RagEngine(
                chunks = listOf(
                    EngineChunk.Delta("القياس هو الاستدلال من الأصل إلى الفرع."),
                    EngineChunk.Completed(emptyList()),
                ),
            )
            val chat = store(engine, FakeRag())
            chat.refreshRagState()
            chat.addDocumentToScope("d1")

            chat.send("ما هو القياس؟", this)
            chat.awaitIdle()

            val assistant = chat.items[1] as ChatStore.Item.Assistant
            assertFalse(assistant.failed)
            assertEquals(1, assistant.citations.size)
            val citation = assistant.citations.single()
            assertEquals("d1", citation.documentId)
            assertEquals("c1", citation.chunkId)
            assertEquals(3, citation.page)
            assertEquals("مقدمة في القياس.pdf", citation.documentName)
            assertTrue(citation.snippet.isNotBlank())
        }

    @Test
    fun `no retrieval hit fails the turn instead of answering from memory`() = runBlocking {
        val engine = RagEngine()
        val rag = FakeRag(hits = emptyList())
        val chat = store(engine, rag)
        chat.refreshRagState()
        chat.addDocumentToScope("d1")

        chat.send("سؤال خارج المستندات", this)
        chat.awaitIdle()

        assertEquals(1, rag.retrieveCalls)
        assertEquals(0, engine.streamCalls)
        val assistant = chat.items[1] as ChatStore.Item.Assistant
        assertTrue(assistant.failed)
        assertEquals(ChatStore.RAG_NO_SOURCES, assistant.failureReason)
        assertEquals("", assistant.text)
    }

    @Test
    fun `the scope is saved on the conversation and restored when it is reopened`() = runBlocking {
        val library = MemoryConversationLibrary()
        val first = store(RagEngine(), FakeRag(), conversations = library)
        first.refreshRagState()
        first.addDocumentToScope("d1")
        val conversationId = first.conversationId
        assertTrue(conversationId != null)

        // A fresh store over the same library is a process restart: the conversation is re-adopted.
        val second = store(RagEngine(), FakeRag(), conversations = library)
        assertEquals(conversationId, second.conversationId)
        second.refreshRagState()
        assertEquals(listOf("d1"), second.documentScope)
    }

    @Test
    fun `a deleted document is pruned from the scope and from the save`() = runBlocking {
        val library = MemoryConversationLibrary()
        val rag = FakeRag(initial = listOf(document("d1", "أ.pdf"), document("d2", "ب.pdf")))
        val chat = store(RagEngine(), rag, conversations = library)
        chat.refreshRagState()
        chat.addDocumentToScope("d1")
        chat.addDocumentToScope("d2")
        assertEquals(listOf("d1", "d2"), chat.documentScope)

        // Deletion arrives through the library flow; without an app scope the store is refreshed by
        // the caller exactly as the UI does when it returns to the screen.
        rag.documents.value = listOf(document("d2", "ب.pdf"))
        chat.refreshRagState()

        assertEquals(listOf("d2"), chat.documentScope)
        val saved = library.find(chat.conversationId.orEmpty())
        assertEquals(listOf("d2"), saved?.documentIds)
    }

    @Test
    fun `selecting all ready documents skips ones that still wait for the embedding model`() = runBlocking {
        val rag = FakeRag(
            initial = listOf(
                document("d1", "جاهز.pdf", status = RagDocumentStatus.READY),
                document("d2", "منتظر.pdf", status = RagDocumentStatus.EXTRACTED),
            ),
        )
        val chat = store(RagEngine(), rag)
        chat.refreshRagState()

        chat.selectAllReadyDocuments()

        assertEquals(listOf("d1"), chat.documentScope)
    }

    @Test
    fun `a grounded send charges the source block to the prompt meter`() = runBlocking {
        val library = MemoryConversationLibrary()
        val chat = store(RagEngine(), FakeRag(), conversations = library)
        chat.refreshRagState()
        val ungrounded = chat.tokenBudget.estimatedTokens
        val window = chat.tokenBudget.contextTokens

        chat.addDocumentToScope("d1")

        assertTrue(chat.tokenBudget.estimatedTokens > ungrounded)
        assertEquals(window, chat.tokenBudget.contextTokens)
    }

    @Test
    fun `retry keeps the scope of the failed question`() = runBlocking {
        val engine = RagEngine(chunks = listOf(EngineChunk.Delta("جزئي")))
        val rag = FakeRag()
        val chat = store(engine, rag)
        chat.refreshRagState()
        chat.addDocumentToScope("d1")

        chat.send("سؤال", this)
        chat.awaitIdle()
        assertEquals(1, rag.retrieveCalls)
        val promptsBefore = engine.prompts.size

        chat.retry(this)
        chat.awaitIdle()

        assertEquals(2, rag.retrieveCalls)
        assertEquals(promptsBefore + 1, engine.prompts.size)
        assertNull((chat.items[1] as ChatStore.Item.Assistant).failureReason)
    }

    private companion object {
        fun document(
            id: String,
            name: String,
            status: RagDocumentStatus = RagDocumentStatus.READY,
        ): RagDocument = RagDocument(
            id = id,
            displayName = name,
            formatId = "pdf",
            sizeBytes = 4096,
            importedAt = 0,
            pageCount = 4,
            chunkCount = 6,
            status = status,
        )

        fun hit(): RagHit = RagHit(
            documentId = "d1",
            documentName = "مقدمة في القياس.pdf",
            chunk = RagChunk(
                id = "c1",
                documentId = "d1",
                ordinal = 0,
                text = "القياس حمل معلوم على معلوم لاستخراج حكم شرعي.",
                page = 3,
            ),
            rank = 1,
            fusedScore = 0.9f,
        )
    }
}


package app.sirrak.feature.chat

import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.Role
import app.sirrak.core.runtime.Suggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-conversation behaviour of the chat store: a conversation library replaces the single M3
 * transcript, the newest or last-opened conversation is restored, and switching never loses turns.
 */
class ChatStoreConversationsTest {

    private class FakeEngine(
        initial: EngineStatus = EngineStatus.Ready("Qwen"),
        private val chunks: List<EngineChunk> = listOf(
            EngineChunk.Delta("مرحباً"),
            EngineChunk.Completed(emptyList()),
        ),
    ) : InferenceEngine {
        override val id = "fake"
        override val displayName = "Fake"
        override val status = MutableStateFlow(initial)

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
            chunks.forEach { emit(it) }
        }
    }

    private class FakeContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
    }

    private class FakeLibrary : ConversationLibrary {
        val conversations = LinkedHashMap<String, Conversation>()
        var active: String? = null
        var createCalls = 0
        var saveCalls = 0
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
                    derivedTitle = app.sirrak.core.runtime.ConversationTitles.derive(it.turns),
                )
            }

        override fun search(query: String): List<ConversationSummary> {
            val needle = query.trim()
            if (needle.isEmpty()) return list()
            return list().filter { summary ->
                val conversation = conversations.getValue(summary.id)
                app.sirrak.core.runtime.ConversationSearch.matches(conversation, needle)
            }
        }

        override fun find(id: String): Conversation? = conversations[id]

        override fun create(title: String?): Conversation {
            createCalls++
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
            saveCalls++
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

        fun seed(id: String, turns: List<ChatTurn>, updatedAt: Long): Conversation {
            val conversation = Conversation(id, null, createdAt = updatedAt, updatedAt = updatedAt, turns = turns)
            conversations[id] = conversation
            return conversation
        }
    }

    private suspend fun ChatStore.awaitIdle() {
        while (streaming) kotlinx.coroutines.yield()
    }

    @Test
    fun `first launch creates and activates exactly one conversation`() {
        val library = FakeLibrary()

        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        assertEquals(1, library.createCalls)
        assertEquals("id-0", store.conversationId)
        assertEquals("id-0", library.active)
        assertTrue(store.items.isEmpty())
        assertEquals(1, store.conversations().size)
    }

    @Test
    fun `the last active conversation is restored with its transcript`() {
        val library = FakeLibrary()
        library.seed("older", listOf(ChatTurn(Role.USER, "قديم")), updatedAt = 1_000L)
        library.seed("newer", listOf(ChatTurn(Role.USER, "نشط")), updatedAt = 2_000L)
        library.active = "older"

        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        assertEquals("older", store.conversationId)
        assertEquals(1, store.items.size)
        assertEquals("قديم", (store.items.first() as ChatStore.Item.User).text)
        // Restoring an existing conversation must not create a second one.
        assertEquals(0, library.createCalls)
    }

    @Test
    fun `a new conversation starts empty and keeps the old transcript`() {
        val library = FakeLibrary()
        val seeded = library.seed("one", listOf(ChatTurn(Role.USER, "سؤال"), ChatTurn(Role.ASSISTANT, "جواب")), updatedAt = 1_000L)
        library.active = "one"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)
        assertEquals(2, store.items.size)

        store.newConversation()

        assertTrue(store.items.isEmpty())
        assertEquals(2, library.conversations.size)
        assertEquals(2, library.conversations.getValue(seeded.id).turns.size)
        assertEquals(store.conversationId, library.active)
        assertNotEquals("one", store.conversationId)
    }

    @Test
    fun `opening another conversation shows its transcript and marks it active`() {
        val library = FakeLibrary()
        library.seed("a", listOf(ChatTurn(Role.USER, "أ")), updatedAt = 1_000L)
        library.seed("b", listOf(ChatTurn(Role.USER, "ب")), updatedAt = 2_000L)
        library.active = "b"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        assertTrue(store.openConversation("a"))

        assertEquals("a", store.conversationId)
        assertEquals("a", library.active)
        assertEquals("أ", (store.items.first() as ChatStore.Item.User).text)
        assertFalse(store.openConversation("missing"))
        assertEquals("a", store.conversationId)
    }

    @Test
    fun `a completed exchange is saved into the active conversation only`() = runBlocking {
        val library = FakeLibrary()
        library.seed("other", listOf(ChatTurn(Role.USER, "آخر")), updatedAt = 500L)
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)
        // Restore may open the newest seeded conversation; start a clean one for this exchange.
        store.newConversation()
        val activeId = store.conversationId!!

        store.send("سؤال", this)
        store.awaitIdle()

        val current = library.conversations.getValue(activeId)
        assertEquals(listOf(ChatTurn(Role.USER, "سؤال"), ChatTurn(Role.ASSISTANT, "مرحباً")), current.turns)
        assertEquals(1, library.conversations.getValue("other").turns.size)
    }

    @Test
    fun `deleting the active conversation opens the newest remaining one`() {
        val library = FakeLibrary()
        library.seed("a", listOf(ChatTurn(Role.USER, "أ")), updatedAt = 1_000L)
        library.seed("b", listOf(ChatTurn(Role.USER, "ب")), updatedAt = 2_000L)
        library.active = "b"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        assertTrue(store.deleteConversation("b"))

        assertNull(library.find("b"))
        assertEquals("a", store.conversationId)
        assertEquals("a", library.active)
        assertEquals("أ", (store.items.first() as ChatStore.Item.User).text)
    }

    @Test
    fun `deleting the last conversation starts a fresh one`() {
        val library = FakeLibrary()
        library.seed("only", listOf(ChatTurn(Role.USER, "وحيد")), updatedAt = 1_000L)
        library.active = "only"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        assertTrue(store.deleteConversation("only"))

        assertEquals(1, store.conversations().size)
        assertEquals(store.conversationId, library.active)
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun `deleting a non-active conversation leaves the current transcript alone`() {
        val library = FakeLibrary()
        library.seed("a", listOf(ChatTurn(Role.USER, "أ")), updatedAt = 1_000L)
        library.seed("b", listOf(ChatTurn(Role.USER, "ب")), updatedAt = 2_000L)
        library.active = "b"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        assertTrue(store.deleteConversation("a"))

        assertEquals("b", store.conversationId)
        assertEquals("ب", (store.items.first() as ChatStore.Item.User).text)
    }

    @Test
    fun `a saved conversation can be loaded without switching to it`() {
        val library = FakeLibrary()
        library.seed("a", listOf(ChatTurn(Role.USER, "أ")), updatedAt = 1_000L)
        library.seed("b", listOf(ChatTurn(Role.USER, "ب")), updatedAt = 2_000L)
        library.active = "b"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        val loaded = store.loadConversation("a")

        assertEquals("أ", loaded!!.turns.first().text)
        assertEquals("b", store.conversationId)
        assertNull(store.loadConversation("missing"))
    }

    @Test
    fun `any conversation can be renamed without opening it`() {
        val library = FakeLibrary()
        library.seed("a", listOf(ChatTurn(Role.USER, "أ")), updatedAt = 1_000L)
        library.seed("b", listOf(ChatTurn(Role.USER, "ب")), updatedAt = 2_000L)
        library.active = "b"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        store.renameConversation("a", "عنوان أ")

        assertEquals("عنوان أ", library.find("a")!!.title)
        assertNull(store.conversationTitle)
    }

    @Test
    fun `conversations can be searched through the store`() {
        val library = FakeLibrary()
        library.seed("coffee", listOf(ChatTurn(Role.USER, "سؤال عن القهوة")), updatedAt = 1_000L)
        library.seed("weather", listOf(ChatTurn(Role.USER, "كيف الطقس؟")), updatedAt = 2_000L)
        library.active = "weather"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        assertEquals(listOf("coffee"), store.searchConversations("القهوة").map { it.id })
        assertEquals(2, store.searchConversations("  ").size)
    }

    @Test
    fun `renaming the current conversation is stored without changing the transcript`() {
        val library = FakeLibrary()
        library.seed("one", listOf(ChatTurn(Role.USER, "سؤال")), updatedAt = 1_000L)
        library.active = "one"
        val store = ChatStore(FakeEngine(), FakeContent(), conversations = library)

        store.renameCurrentConversation("خطة الأسبوع")

        assertEquals("خطة الأسبوع", library.find("one")!!.title)
        assertEquals(1, store.items.size)
    }
}

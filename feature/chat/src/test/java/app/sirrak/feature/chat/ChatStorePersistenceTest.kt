package app.sirrak.feature.chat

import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.ConversationStore
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence is a seam, not a feature: the store must survive process death without knowing what a
 * file, a database or SharedPreferences is. These tests drive it with an in-memory conversation.
 */
class ChatStorePersistenceTest {

    private class FakeEngine(
        initial: EngineStatus = EngineStatus.Ready("Qwen"),
        private val chunks: List<EngineChunk> = listOf(
            EngineChunk.Delta("مرحباً"),
            EngineChunk.Completed(emptyList()),
        ),
        private val chunkDelayMs: Long = 0,
    ) : InferenceEngine {
        override val id = "fake"
        override val displayName = "Fake"
        override val status = MutableStateFlow(initial)

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
            chunks.forEach {
                if (chunkDelayMs > 0) kotlinx.coroutines.delay(chunkDelayMs)
                emit(it)
            }
        }
    }

    private class FakeContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
    }

    private class FakeConversation(initial: List<ChatTurn> = emptyList()) : ConversationStore {
        var turns: List<ChatTurn> = initial
        var saveCalls = 0
        var clearCalls = 0

        override fun load(): List<ChatTurn> = turns

        override fun save(turns: List<ChatTurn>) {
            this.turns = turns
            saveCalls++
        }

        override fun clear() {
            turns = emptyList()
            clearCalls++
        }
    }

    private suspend fun ChatStore.awaitIdle() {
        while (streaming) kotlinx.coroutines.yield()
    }

    private suspend fun ChatStore.awaitFirstDelta() {
        while ((items.lastOrNull() as? ChatStore.Item.Assistant)?.text?.isEmpty() != false) {
            kotlinx.coroutines.yield()
        }
    }

    @Test
    fun `creation restores saved turns as a visible conversation`() = runBlocking {
        val saved = listOf(
            ChatTurn(Role.USER, "سؤال قديم"),
            ChatTurn(Role.ASSISTANT, "جواب قديم"),
        )
        val store = ChatStore(FakeEngine(), FakeContent(), conversation = FakeConversation(saved))

        assertEquals(2, store.items.size)
        assertEquals("سؤال قديم", (store.items[0] as ChatStore.Item.User).text)
        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertEquals("جواب قديم", assistant.text)
        assertFalse(assistant.streaming)
        assertFalse(assistant.failed)
    }

    @Test
    fun `a completed exchange is saved as the user turn then the answer`() = runBlocking {
        val conversation = FakeConversation()
        val store = ChatStore(FakeEngine(), FakeContent(), conversation = conversation)

        store.send("سؤال", this)
        store.awaitIdle()

        assertEquals(
            listOf(ChatTurn(Role.USER, "سؤال"), ChatTurn(Role.ASSISTANT, "مرحباً")),
            conversation.turns,
        )
    }

    @Test
    fun `a user turn is saved even when generation fails`() = runBlocking {
        val conversation = FakeConversation()
        val engine = FakeEngine(chunks = listOf(EngineChunk.Failed("out-of-memory")))
        val store = ChatStore(engine, FakeContent(), conversation = conversation)

        store.send("سؤال", this)
        store.awaitIdle()

        assertEquals(listOf(ChatTurn(Role.USER, "سؤال")), conversation.turns)
    }

    @Test
    fun `a partial answer stopped by the user is saved`() = runBlocking {
        val conversation = FakeConversation()
        val engine = FakeEngine(
            chunks = listOf(EngineChunk.Delta("جزئي"), EngineChunk.Completed(emptyList())),
            chunkDelayMs = 50,
        )
        val store = ChatStore(engine, FakeContent(), conversation = conversation)

        store.send("سؤال", this)
        store.awaitFirstDelta()
        store.stop()
        store.awaitIdle()

        assertEquals(ChatTurn(Role.USER, "سؤال"), conversation.turns[0])
        val answer = conversation.turns.last()
        assertEquals(Role.ASSISTANT, answer.role)
        assertTrue(answer.text.isNotEmpty())
    }

    @Test
    fun `clear also erases the saved conversation`() = runBlocking {
        val conversation = FakeConversation()
        val store = ChatStore(FakeEngine(), FakeContent(), conversation = conversation)

        store.send("سؤال", this)
        store.awaitIdle()
        store.clear()

        assertTrue(conversation.turns.isEmpty())
        assertEquals(1, conversation.clearCalls)
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun `restored turns and new turns keep distinct ids`() = runBlocking {
        val conversation = FakeConversation(
            listOf(ChatTurn(Role.USER, "قديم"), ChatTurn(Role.ASSISTANT, "جواب")),
        )
        val store = ChatStore(FakeEngine(), FakeContent(), conversation = conversation)

        store.send("جديد", this)
        store.awaitIdle()

        val ids = store.items.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertNotEquals(store.items[0].id, store.items[2].id)
    }
}

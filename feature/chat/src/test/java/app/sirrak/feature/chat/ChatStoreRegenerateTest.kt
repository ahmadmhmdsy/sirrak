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
import app.sirrak.core.runtime.SourceRef
import app.sirrak.core.runtime.Suggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regenerating an answer is a conversation action, not a fresh send: it must keep the question,
 * replace exactly the last completed answer, and re-persist the transcript without duplicating the
 * question into the engine history.
 */
class ChatStoreRegenerateTest {

    /** One scripted chunk list per engine call, so each generation can differ. */
    private class ScriptedEngine(
        private val script: List<List<EngineChunk>>,
        private val chunkDelayMs: Long = 0,
    ) : InferenceEngine {
        override val id = "scripted"
        override val displayName = "Scripted"
        override val status = MutableStateFlow<EngineStatus>(EngineStatus.Ready("Qwen"))
        var streamCalls = 0
        val prompts = mutableListOf<String>()
        val histories = mutableListOf<List<ChatTurn>>()

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
            val chunks = script.getOrElse(streamCalls) { script.last() }
            streamCalls++
            prompts += prompt
            histories += history
            chunks.forEach {
                if (chunkDelayMs > 0) kotlinx.coroutines.delay(chunkDelayMs)
                emit(it)
            }
        }

        override fun stop() = Unit
    }

    private class FakeContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
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

    private suspend fun ChatStore.awaitIdle() {
        while (streaming) kotlinx.coroutines.yield()
    }

    private suspend fun ChatStore.awaitFirstDelta() {
        while ((items.lastOrNull() as? ChatStore.Item.Assistant)?.text?.isEmpty() != false) {
            kotlinx.coroutines.yield()
        }
    }

    @Test
    fun `regenerate replaces the last answer and reuses the question`() = runBlocking {
        val engine = ScriptedEngine(
            listOf(
                listOf(EngineChunk.Delta("الجواب الأول"), EngineChunk.Completed(emptyList())),
                listOf(EngineChunk.Delta("الجواب الثاني"), EngineChunk.Completed(emptyList())),
            ),
        )
        val store = ChatStore(engine, FakeContent())

        store.send("ما هو سِرِّك؟", this)
        store.awaitIdle()
        store.regenerate(this)
        store.awaitIdle()

        assertEquals(2, store.items.size)
        assertEquals("ما هو سِرِّك؟", (store.items[0] as ChatStore.Item.User).text)
        val answer = store.items[1] as ChatStore.Item.Assistant
        assertEquals("الجواب الثاني", answer.text)
        assertFalse(answer.streaming)
        assertFalse(answer.failed)
        assertEquals(listOf("ما هو سِرِّك؟", "ما هو سِرِّك؟"), engine.prompts)
    }

    @Test
    fun `regenerate sends the history before the question, never the question twice`() = runBlocking {
        val engine = ScriptedEngine(
            listOf(
                listOf(EngineChunk.Delta("واحد"), EngineChunk.Completed(emptyList())),
                listOf(EngineChunk.Delta("اثنان"), EngineChunk.Completed(emptyList())),
                listOf(EngineChunk.Delta("ثلاثة"), EngineChunk.Completed(emptyList())),
            ),
        )
        val store = ChatStore(engine, FakeContent())

        store.send("الأول", this)
        store.awaitIdle()
        store.send("الثاني", this)
        store.awaitIdle()
        store.regenerate(this)
        store.awaitIdle()

        assertEquals(3, engine.streamCalls)
        assertEquals("الثاني", engine.prompts[2])
        assertEquals(
            listOf(ChatTurn(Role.USER, "الأول"), ChatTurn(Role.ASSISTANT, "واحد")),
            engine.histories[2],
        )
        assertEquals("ثلاثة", (store.items.last() as ChatStore.Item.Assistant).text)
    }

    @Test
    fun `regenerate persists the replaced transcript`() = runBlocking {
        val conversation = FakeConversation()
        val engine = ScriptedEngine(
            listOf(
                listOf(EngineChunk.Delta("قديم"), EngineChunk.Completed(emptyList())),
                listOf(EngineChunk.Delta("جديد"), EngineChunk.Completed(emptyList())),
            ),
        )
        val store = ChatStore(engine, FakeContent(), conversation = conversation)

        store.send("سؤال", this)
        store.awaitIdle()
        store.regenerate(this)
        store.awaitIdle()

        assertEquals(
            listOf(ChatTurn(Role.USER, "سؤال"), ChatTurn(Role.ASSISTANT, "جديد")),
            conversation.turns,
        )
    }

    @Test
    fun `regenerate drops the old answer from disk before the new one arrives`() = runBlocking {
        val conversation = FakeConversation()
        val engine = ScriptedEngine(
            listOf(
                listOf(EngineChunk.Delta("قديم"), EngineChunk.Completed(emptyList())),
                listOf(EngineChunk.Delta("جديد"), EngineChunk.Completed(emptyList())),
            ),
            chunkDelayMs = 50,
        )
        val store = ChatStore(engine, FakeContent(), conversation = conversation)

        store.send("سؤال", this)
        store.awaitIdle()
        store.regenerate(this)

        // The old answer is gone the moment the user asks again, so a process kill mid-stream
        // cannot resurrect an answer the user rejected.
        assertEquals(listOf(ChatTurn(Role.USER, "سؤال")), conversation.turns)
        assertTrue((store.items.last() as ChatStore.Item.Assistant).streaming)

        store.awaitIdle()
        assertEquals(
            listOf(ChatTurn(Role.USER, "سؤال"), ChatTurn(Role.ASSISTANT, "جديد")),
            conversation.turns,
        )
    }

    @Test
    fun `regenerate is ignored while an answer is streaming`() = runBlocking {
        val engine = ScriptedEngine(
            listOf(
                listOf(EngineChunk.Delta("جزئي"), EngineChunk.Completed(emptyList())),
                listOf(EngineChunk.Delta("آخر"), EngineChunk.Completed(emptyList())),
            ),
            chunkDelayMs = 50,
        )
        val store = ChatStore(engine, FakeContent())

        store.send("سؤال", this)
        store.awaitFirstDelta()
        store.regenerate(this)
        store.awaitIdle()

        assertEquals(1, engine.streamCalls)
        assertEquals(2, store.items.size)
    }

    @Test
    fun `regenerate does nothing on an empty conversation`() = runBlocking {
        val engine = ScriptedEngine(listOf(listOf(EngineChunk.Completed(emptyList()))))
        val store = ChatStore(engine, FakeContent())

        store.regenerate(this)

        assertEquals(0, engine.streamCalls)
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun `regenerate leaves a failed answer to retry`() = runBlocking {
        val engine = ScriptedEngine(
            listOf(
                listOf(EngineChunk.Failed("out-of-memory")),
                listOf(EngineChunk.Delta("جواب"), EngineChunk.Completed(emptyList())),
            ),
        )
        val store = ChatStore(engine, FakeContent())

        store.send("سؤال", this)
        store.awaitIdle()
        store.regenerate(this)
        store.awaitIdle()

        assertEquals(1, engine.streamCalls)
        assertTrue((store.items[1] as ChatStore.Item.Assistant).failed)
    }

    @Test
    fun `regenerate resets the sources of the replaced answer`() = runBlocking {
        val engine = ScriptedEngine(
            listOf(
                listOf(
                    EngineChunk.Delta("قديم"),
                    EngineChunk.Completed(listOf(SourceRef("مصدر قديم"))),
                ),
                listOf(EngineChunk.Delta("جديد"), EngineChunk.Completed(emptyList())),
            ),
        )
        val store = ChatStore(engine, FakeContent())

        store.send("سؤال", this)
        store.awaitIdle()
        store.regenerate(this)
        store.awaitIdle()

        assertTrue((store.items[1] as ChatStore.Item.Assistant).sources.isEmpty())
    }
}

package app.sirrak.feature.chat

import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.GenerationSettingsProvider
import app.sirrak.core.runtime.TemplateChoice
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.SourceRef
import app.sirrak.core.runtime.Suggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {

    private class FakeEngine(
        initial: EngineStatus,
        private val chunks: List<EngineChunk> = listOf(
            EngineChunk.Delta("مرحباً"),
            EngineChunk.Delta(" بك"),
            EngineChunk.Completed(emptyList()),
        ),
        private val chunkDelayMs: Long = 0,
    ) : InferenceEngine {
        override val id = "fake"
        override val displayName = "Fake"
        override val status = MutableStateFlow(initial)
        var streamCalls = 0
        var stopCalls = 0

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
            streamCalls++
            chunks.forEach {
                if (chunkDelayMs > 0) kotlinx.coroutines.delay(chunkDelayMs)
                emit(it)
            }
        }

        override fun stop() {
            stopCalls++
        }
    }

    /** Lets a coroutine started on the runBlocking scope run before we assert on the UI state. */
    private suspend fun ChatStore.awaitIdle() {
        while (streaming) kotlinx.coroutines.yield()
    }

    /** Waits until the assistant turn has received at least one delta. */
    private suspend fun ChatStore.awaitFirstDelta() {
        while ((items.lastOrNull() as? ChatStore.Item.Assistant)?.text?.isEmpty() != false) {
            kotlinx.coroutines.yield()
        }
    }

    /** Records the resolved generation layers a send hands to the engine. */
    private class RecordingEngine(initial: EngineStatus) : InferenceEngine {
        override val id = "recording"
        override val displayName = "Recording"
        override val status = MutableStateFlow(initial)
        var received: GenerationOptions? = null

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> =
            flowOf(EngineChunk.Completed(emptyList()))

        override fun stream(
            prompt: String,
            history: List<ChatTurn>,
            options: GenerationOptions,
        ): Flow<EngineChunk> {
            received = options
            return flowOf(EngineChunk.Delta("ok"), EngineChunk.Completed(emptyList()))
        }
    }

    private class FakeContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
    }

    @Test
    fun `sending without a loaded model fails immediately and does not stream`() = runBlocking {
        val engine = FakeEngine(EngineStatus.Unloaded)
        val store = ChatStore(engine, FakeContent())

        store.send("ما هي القياس؟", this)
        store.awaitIdle()

        assertEquals(2, store.items.size)
        val user = store.items[0] as ChatStore.Item.User
        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertEquals("ما هي القياس؟", user.text)
        assertTrue(assistant.failed)
        assertEquals(ChatStore.NO_MODEL, assistant.failureReason)
        assertEquals("", assistant.text)
        assertEquals(0, engine.streamCalls)
        assertFalse(store.streaming)
    }

    @Test
    fun `a ready engine streams deltas and completes the turn`() = runBlocking {
        val engine = FakeEngine(EngineStatus.Ready("Qwen"))
        val store = ChatStore(engine, FakeContent())

        store.send("مرحباً", this)
        store.awaitIdle()

        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertEquals("مرحباً بك", assistant.text)
        assertFalse(assistant.streaming)
        assertFalse(assistant.failed)
        assertEquals(1, engine.streamCalls)
        assertFalse(store.streaming)
    }

    @Test
    fun `a failed chunk records its reason`() = runBlocking {
        val engine = FakeEngine(
            EngineStatus.Ready("Qwen"),
            chunks = listOf(EngineChunk.Delta("نصف"), EngineChunk.Failed("out-of-memory")),
        )
        val store = ChatStore(engine, FakeContent())

        store.send("اختبار", this)
        store.awaitIdle()

        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertTrue(assistant.failed)
        assertEquals("out-of-memory", assistant.failureReason)
        assertEquals("نصف", assistant.text)
    }

    @Test
    fun `stopping before any token is a stopped turn, not an engine failure`() = runBlocking {
        val engine = FakeEngine(EngineStatus.Ready("Qwen"), chunkDelayMs = 50)
        val store = ChatStore(engine, FakeContent())

        store.send("اختبار", this)
        kotlinx.coroutines.yield()
        store.stop()
        store.awaitIdle()

        assertEquals(1, engine.stopCalls)
        assertEquals(2, store.items.size)
        assertFalse(store.streaming)
        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertFalse(assistant.streaming)
        assertTrue(assistant.failed)
        assertEquals(ChatStore.STOPPED, assistant.failureReason)
        assertEquals("", assistant.text)
    }

    @Test
    fun `stopping after text keeps the partial answer without an error`() = runBlocking {
        val engine = FakeEngine(EngineStatus.Ready("Qwen"), chunkDelayMs = 50)
        val store = ChatStore(engine, FakeContent())

        store.send("اختبار", this)
        store.awaitFirstDelta()
        store.stop()
        store.awaitIdle()

        assertEquals(1, engine.stopCalls)
        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertFalse(assistant.streaming)
        assertFalse(assistant.failed)
        assertTrue(assistant.text.isNotEmpty())
    }

    @Test
    fun `send hands the provider generation options to the engine`() = runBlocking {
        val engine = RecordingEngine(EngineStatus.Ready("Qwen"))
        val options = GenerationOptions(
            params = GenerationParams(temperature = 1.25f, seed = 7),
            template = TemplateChoice.Builtin("chatml"),
            systemPrompt = "resolved prompt",
        )
        val store = ChatStore(engine, FakeContent(), generationSettings = { options })

        store.send("مرحباً", this)
        store.awaitIdle()

        assertEquals(options, engine.received)
        assertEquals("ok", (store.items[1] as ChatStore.Item.Assistant).text)
    }

    @Test
    fun `a retry keeps only one assistant turn`() = runBlocking {
        val engine = FakeEngine(
            EngineStatus.Ready("Qwen"),
            chunks = listOf(EngineChunk.Failed("model-missing")),
        )
        val store = ChatStore(engine, FakeContent())

        store.send("سؤال", this)
        store.awaitIdle()
        store.retry(this)
        store.awaitIdle()

        assertEquals(2, store.items.size)
        assertEquals(2, engine.streamCalls)
        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertTrue(assistant.failed)
    }

    @Test
    fun `sources from the completion are kept on the turn`() = runBlocking {
        val engine = FakeEngine(
            EngineStatus.Ready("Qwen"),
            chunks = listOf(EngineChunk.Delta("جواب"), EngineChunk.Completed(listOf(SourceRef("مصدر")))),
        )
        val store = ChatStore(engine, FakeContent())

        store.send("سؤال", this)
        store.awaitIdle()

        val assistant = store.items[1] as ChatStore.Item.Assistant
        assertEquals(listOf(SourceRef("مصدر")), assistant.sources)
    }
}
package app.sirrak.feature.chat

import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.ConversationTitles
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationOverrides
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.GenerationSettingsProvider
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 reading-surface budgets: the prompt meter, approximate output speed and the per-conversation
 * layers (system prompt + generation overrides) that must win over the global ones.
 */
class ChatStoreBudgetTest {

    private class RecordingEngine(
        private val answer: String = "جواب",
        private val onBeforeCompleted: () -> Unit = {},
    ) : InferenceEngine {
        var lastOptions: GenerationOptions? = null
        override val id = "fake"
        override val displayName = "Fake"
        override val status = MutableStateFlow<EngineStatus>(EngineStatus.Ready("Qwen"))

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> =
            stream(prompt, history, GenerationOptions())

        override fun stream(prompt: String, history: List<ChatTurn>, options: GenerationOptions): Flow<EngineChunk> {
            lastOptions = options
            return flow {
                emit(EngineChunk.Delta(answer))
                onBeforeCompleted()
                emit(EngineChunk.Completed(emptyList()))
            }
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
            systemPrompt: String? = null,
            overrides: GenerationOverrides? = null,
        ): Conversation {
            val conversation = Conversation(
                id = id,
                title = null,
                createdAt = updatedAt,
                updatedAt = updatedAt,
                turns = turns,
                systemPrompt = systemPrompt,
                overrides = overrides,
            )
            conversations[id] = conversation
            return conversation
        }
    }

    private val global = GenerationSettingsProvider {
        GenerationOptions(params = GenerationParams(temperature = 0.9f), systemPrompt = "global prompt")
    }

    @Test
    fun `the prompt meter reports the estimate against the context window`() {
        val library = FakeLibrary()
        library.seed("one", listOf(ChatTurn(Role.USER, "a".repeat(8_000))), updatedAt = 1_000L)
        library.active = "one"

        val store = ChatStore(RecordingEngine(), FakeContent(), conversations = library, contextTokens = 2_048)

        assertEquals(2_048, store.tokenBudget.contextTokens)
        assertEquals(1_536, store.tokenBudget.promptBudget)
        assertTrue(store.tokenBudget.overflow)
    }

    @Test
    fun `a fresh conversation starts with an empty prompt meter`() {
        val store = ChatStore(RecordingEngine(), FakeContent(), conversations = FakeLibrary(), contextTokens = 2_048)

        assertEquals(0, store.tokenBudget.estimatedTokens)
        assertFalse(store.tokenBudget.overflow)
        assertFalse(store.tokenBudget.nearLimit)
    }

    @Test
    fun `conversation overrides win over the global generation layers`() = runBlocking {
        val library = FakeLibrary()
        library.seed(
            "one",
            turns = listOf(ChatTurn(Role.USER, "سؤال")),
            systemPrompt = "كن موجزاً",
            overrides = GenerationOverrides(temperature = 0.2f, stop = listOf("###")),
        )
        library.active = "one"
        val engine = RecordingEngine()
        val store = ChatStore(engine, FakeContent(), conversations = library, generationSettings = global)

        store.send("سؤال آخر", this)
        while (store.streaming) kotlinx.coroutines.yield()

        val options = engine.lastOptions!!
        assertEquals(0.2f, options.params.temperature, 0.0001f)
        assertEquals(listOf("###"), options.params.stop)
        assertEquals("كن موجزاً", options.systemPrompt)
        // Fields the conversation does not override still come from the global layer.
        assertEquals(0.95f, options.params.topP, 0.0001f)
    }

    @Test
    fun `without overrides the global layers reach the engine unchanged`() = runBlocking {
        val library = FakeLibrary()
        library.active = "id-0"
        val engine = RecordingEngine()
        val store = ChatStore(engine, FakeContent(), conversations = library, generationSettings = global)

        store.send("سؤال", this)
        while (store.streaming) kotlinx.coroutines.yield()

        val options = engine.lastOptions!!
        assertEquals(0.9f, options.params.temperature, 0.0001f)
        assertEquals("global prompt", options.systemPrompt)
    }

    @Test
    fun `a conversation override can be saved and read back`() {
        val library = FakeLibrary()
        val seeded = library.seed("one", listOf(ChatTurn(Role.USER, "سؤال")))
        library.active = "one"
        val store = ChatStore(RecordingEngine(), FakeContent(), conversations = library)

        assertTrue(
            store.updateConversationConfig(
                seeded.id,
                systemPrompt = "كن شاعراً",
                overrides = GenerationOverrides(stop = listOf("</s>")),
            ),
        )

        val saved = library.find(seeded.id)!!
        assertEquals("كن شاعراً", saved.systemPrompt)
        assertEquals(listOf("</s>"), saved.overrides!!.stop)
        assertEquals("كن شاعراً", store.loadConversation(seeded.id)!!.systemPrompt)
        assertFalse(store.updateConversationConfig("missing", systemPrompt = "x", overrides = null))
    }

    @Test
    fun `generated answers record approximate output speed`() = runBlocking {
        var now = 0L
        val answer = "a".repeat(400) // ~100 estimated tokens
        val engine = RecordingEngine(answer = answer, onBeforeCompleted = { now = 2_000L })
        val store = ChatStore(
            engine,
            FakeContent(),
            conversations = FakeLibrary(),
            clock = { now },
        )

        store.send("سؤال", this)
        while (store.streaming) kotlinx.coroutines.yield()

        assertEquals(50f, store.lastSpeed!!, 0.01f)
        assertEquals(100, store.lastCompletionTokens)
    }
}

package app.sirrak.feature.chat

import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentImport
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.AttachmentPolicy
import app.sirrak.core.runtime.AttachmentRejection
import app.sirrak.core.runtime.AttachmentSource
import app.sirrak.core.runtime.AttachmentStore
import app.sirrak.core.runtime.AttachmentValidation
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.ConversationTitles
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.EngineCapabilities
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineReasons
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.ImageInput
import app.sirrak.core.runtime.ImagePreparer
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.Role
import app.sirrak.core.runtime.Suggestion
import java.io.InputStream
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
 * M9 vision in the chat store: an image is only queued when the loaded engine can see, the prepared
 * bytes (never the raw camera file) reach the engine through streamWithImages, and every failure mode
 * fails the turn with a stable reason instead of quietly asking a text-only model about a picture.
 */
class ChatStoreVisionTest {

    /** Records whether a turn took the text path or the vision path and what it received. */
    private class VisionEngine(
        override val capabilities: EngineCapabilities = EngineCapabilities(text = true, images = true),
    ) : InferenceEngine {
        override val id = "vision"
        override val displayName = "Vision"
        override val status = MutableStateFlow<EngineStatus>(EngineStatus.Ready("SmolVLM"))
        val prompts = mutableListOf<String>()
        val imageBatches = mutableListOf<List<ImageInput>>()
        var textTurns = 0

        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
            textTurns++
            prompts += prompt
            emit(EngineChunk.Delta("text-only"))
            emit(EngineChunk.Completed(emptyList()))
        }

        override fun streamWithImages(
            prompt: String,
            history: List<ChatTurn>,
            images: List<ImageInput>,
            options: GenerationOptions,
        ): Flow<EngineChunk> = flow {
            prompts += prompt
            imageBatches += images
            emit(EngineChunk.Delta("قطة"))
            emit(EngineChunk.Completed(emptyList()))
        }
    }

    private class FakePreparer(private val fail: Boolean = false) : ImagePreparer {
        val calls = mutableListOf<Pair<Int, String>>()

        override fun prepare(bytes: ByteArray, mimeType: String): ImageInput? {
            calls += bytes.size to mimeType
            if (fail) return null
            return ImageInput("prepared-image".toByteArray(), "image/jpeg")
        }
    }

    /** In-memory image storage; the bytes behind [open] are what the preparer must receive. */
    private class ImageStore : AttachmentStore {
        val stored = LinkedHashMap<String, MutableList<Attachment>>()
        val bytes = LinkedHashMap<String, ByteArray>()
        var opens = 0
        private var next = 0

        override fun list(conversationId: String): List<Attachment> = stored[conversationId].orEmpty()

        override fun import(conversationId: String, source: AttachmentSource): AttachmentImport {
            val validation = AttachmentPolicy.validateFile(
                declaredMimeType = source.declaredMimeType,
                fileName = source.displayName,
                sizeBytes = source.sizeBytes,
            )
            if (validation is AttachmentValidation.Rejected) return AttachmentImport.Rejected(validation.reason)
            val accepted = validation as AttachmentValidation.Accepted
            if (accepted.kind != AttachmentKind.IMAGE) {
                return AttachmentImport.Rejected(AttachmentRejection.UNSUPPORTED_TYPE)
            }
            val id = "img-" + next++
            val attachment = Attachment(
                id = id,
                kind = AttachmentKind.IMAGE,
                displayName = source.displayName,
                mimeType = accepted.mimeType,
                sizeBytes = accepted.sizeBytes,
                localPath = "attachments/" + conversationId + "/" + id + ".jpg",
            )
            bytes[id] = source.openStream().use { it.readBytes() }
            stored.getOrPut(conversationId) { mutableListOf() } += attachment
            return AttachmentImport.Stored(attachment)
        }

        override fun open(conversationId: String, attachment: Attachment): InputStream? {
            opens++
            return bytes[attachment.id]?.inputStream()
        }

        override fun saveSharedText(
            conversationId: String,
            text: String,
            displayName: String,
        ): AttachmentImport = AttachmentImport.Rejected(AttachmentRejection.UNSUPPORTED_TYPE)

        override fun delete(conversationId: String, attachmentId: String): Boolean {
            bytes.remove(attachmentId)
            return stored[conversationId]?.removeAll { it.id == attachmentId } == true
        }

        override fun deleteConversation(conversationId: String) {
            stored.remove(conversationId)
        }
    }

    private class FakeLibrary : ConversationLibrary {
        val conversations = LinkedHashMap<String, Conversation>()
        var active: String? = null
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
                id = "id-" + conversations.size,
                title = title,
                createdAt = clock++,
                updatedAt = clock++,
            )
            conversations[conversation.id] = conversation
            active = conversation.id
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

        fun seed(id: String, turns: List<ChatTurn> = emptyList()): Conversation {
            val conversation = Conversation(
                id = id,
                title = null,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                turns = turns,
            )
            conversations[id] = conversation
            active = id
            return conversation
        }
    }

    private class FakeContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
    }

    private fun imageAttachment(id: String = "saved-image"): Attachment = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 12L,
        localPath = "attachments/one/" + id + ".jpg",
    )

    private fun imageSource(bytes: String = "camera-bytes"): AttachmentSource = AttachmentSource(
        displayName = "photo.jpg",
        declaredMimeType = "image/jpeg",
        sizeBytes = bytes.length.toLong(),
    ) { bytes.byteInputStream() }

    private fun store(
        engine: VisionEngine,
        library: FakeLibrary,
        storage: ImageStore,
        preparer: ImagePreparer?,
    ): ChatStore = ChatStore(
        engine = engine,
        library = FakeContent(),
        conversations = library,
        attachments = storage,
        imagePreparer = preparer,
    )

    private suspend fun ChatStore.awaitIdle() {
        while (streaming) kotlinx.coroutines.yield()
    }

    @Test
    fun `attaching an image queues it only when the engine can see`() {
        val vision = VisionEngine()
        val visionLibrary = FakeLibrary().apply { seed("one") }
        val visionStore = store(vision, visionLibrary, ImageStore(), FakePreparer())

        assertTrue(visionStore.attachImage(imageSource()))
        assertEquals(AttachmentKind.IMAGE, visionStore.pendingAttachments.single().kind)

        val textOnly = VisionEngine(capabilities = EngineCapabilities.TextOnly)
        val textLibrary = FakeLibrary().apply { seed("one") }
        val textStore = store(textOnly, textLibrary, ImageStore(), FakePreparer())

        assertFalse(textStore.attachImage(imageSource()))
        assertTrue(textStore.pendingAttachments.isEmpty())
    }

    @Test
    fun `an image turn sends prepared bytes through streamWithImages`() = runBlocking {
        val engine = VisionEngine()
        val library = FakeLibrary().apply { seed("one") }
        val storage = ImageStore()
        val preparer = FakePreparer()
        val store = store(engine, library, storage, preparer)

        assertTrue(store.attachImage(imageSource("raw-camera-bytes")))
        store.send("ماذا في الصورة؟", this)
        store.awaitIdle()

        assertEquals(0, engine.textTurns)
        val images = engine.imageBatches.single()
        assertEquals(1, images.size)
        assertEquals("prepared-image", String(images.single().bytes))
        assertEquals("image/jpeg", images.single().mimeType)
        // The preparer saw the stored bytes and the stored MIME type, not the picker's claims.
        assertEquals(listOf("raw-camera-bytes".length to "image/jpeg"), preparer.calls)
        assertEquals(1, storage.opens)
        // The question survives; the image itself is not rendered as an attachment text block.
        assertTrue(engine.prompts.single().contains("ماذا في الصورة؟"))
        assertFalse(engine.prompts.single().contains("raw-camera-bytes"))
        assertEquals("قطة", (store.items.last() as ChatStore.Item.Assistant).text)
    }

    @Test
    fun `a failed preparation fails the turn with VISION_IMAGE_FAILED`() = runBlocking {
        val engine = VisionEngine()
        val library = FakeLibrary().apply { seed("one") }
        val store = store(engine, library, ImageStore(), FakePreparer(fail = true))

        assertTrue(store.attachImage(imageSource()))
        store.send("ماذا في الصورة؟", this)
        store.awaitIdle()

        val answer = store.items.last() as ChatStore.Item.Assistant
        assertTrue(answer.failed)
        assertEquals(EngineReasons.VISION_IMAGE_FAILED, answer.failureReason)
        assertTrue(engine.imageBatches.isEmpty())
        assertTrue(engine.prompts.isEmpty())
    }

    @Test
    fun `a missing preparer fails the image turn instead of sending the question alone`() = runBlocking {
        val engine = VisionEngine()
        val library = FakeLibrary().apply { seed("one") }
        val store = store(engine, library, ImageStore(), preparer = null)

        assertTrue(store.attachImage(imageSource()))
        store.send("ماذا في الصورة؟", this)
        store.awaitIdle()

        val answer = store.items.last() as ChatStore.Item.Assistant
        assertTrue(answer.failed)
        assertEquals(EngineReasons.VISION_IMAGE_FAILED, answer.failureReason)
        assertTrue(engine.imageBatches.isEmpty())
    }

    @Test
    fun `retrying an image turn on a text-only engine fails with VISION_UNAVAILABLE`() = runBlocking {
        val engine = VisionEngine(capabilities = EngineCapabilities.TextOnly)
        val library = FakeLibrary().apply {
            seed(
                "one",
                turns = listOf(
                    ChatTurn(Role.USER, "ماذا في الصورة؟", attachments = listOf(imageAttachment())),
                    ChatTurn(Role.ASSISTANT, "لا أستطيع رؤية الصور."),
                ),
            )
        }
        val store = store(engine, library, ImageStore(), FakePreparer())

        store.retry(this)
        val answer = store.items.last() as ChatStore.Item.Assistant
        assertTrue(answer.failed)
        assertEquals(EngineReasons.VISION_UNAVAILABLE, answer.failureReason)
        assertTrue(engine.imageBatches.isEmpty())
        assertNull(answer.text.takeIf { it.isNotEmpty() })
    }
}

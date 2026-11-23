package app.sirrak

import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ConversationStore
import app.sirrak.core.runtime.Role
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileConversationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): FileConversationStore = FileConversationStore(tmp.root)

    @Test
    fun `missing file loads as an empty conversation`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `saved turns are loaded by a new instance`() {
        val turns = listOf(
            ChatTurn(Role.USER, "مرحباً"),
            ChatTurn(Role.ASSISTANT, "أهلاً بك، كيف أساعدك؟"),
        )

        store().save(turns)

        assertEquals(turns, store().load())
    }

    @Test
    fun `corrupt file loads as an empty conversation instead of crashing`() {
        File(tmp.root, FileConversationStore.FILE_NAME).writeText("{not json at all")

        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `clear removes the stored conversation`() {
        store().save(listOf(ChatTurn(Role.USER, "سؤال")))

        store().clear()

        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `only the newest turns are kept when the conversation grows`() {
        val many = (1..ConversationStore.MAX_TURNS + 20).map { ChatTurn(Role.USER, "t$it") }

        store().save(many)

        val loaded = store().load()
        assertEquals(ConversationStore.MAX_TURNS, loaded.size)
        assertEquals("t" + (many.size - ConversationStore.MAX_TURNS + 1), loaded.first().text)
    }

    @Test
    fun `blank text is never written`() {
        store().save(listOf(ChatTurn(Role.USER, "  "), ChatTurn(Role.ASSISTANT, "")))

        assertTrue(store().load().isEmpty())
    }
}

package app.sirrak.core.runtime

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The JSON exit door must be able to come back in: export, decode, same conversation. */
class ConversationBackupTest {

    private val conversation = Conversation(
        id = "c-1",
        title = "خطة السفر",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        turns = listOf(
            ChatTurn(Role.USER, "كيف الطقس؟"),
            ChatTurn(Role.ASSISTANT, "مشمس.\nخذ قبعة."),
        ),
        systemPrompt = "أجب بالعربية",
    )

    @Test
    fun `json export round-trips through fromJson`() {
        val decoded = ConversationExport.fromJson(ConversationExport.json(conversation))

        assertEquals(conversation, decoded)
    }

    @Test
    fun `round-trip keeps an Arabic transcript byte-for-byte`() {
        val decoded = ConversationExport.fromJson(ConversationExport.json(conversation))!!

        assertEquals("مشمس.\nخذ قبعة.", decoded.turns.last().text)
        assertEquals("أجب بالعربية", decoded.systemPrompt)
    }

    @Test
    fun `fromJson rejects unreadable or incomplete input`() {
        assertNull(ConversationExport.fromJson("not json at all"))
        assertNull(ConversationExport.fromJson("{}"))
        assertNull(ConversationExport.fromJson("{\"id\":\"\"}"))
        assertNull(ConversationExport.fromJson(Json.parseToJsonElement("{\"id\":\"c\",\"turns\":42}").toString()))
    }
}

package app.sirrak.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure conversation helpers: titles, search, export and the token meter. They carry no Android or
 * engine dependency, so the whole reading surface can be reasoned about without a device.
 */
class ConversationsTest {

    private fun conversation(
        id: String = "c1",
        title: String? = null,
        turns: List<ChatTurn> = emptyList(),
        systemPrompt: String? = null,
    ) = Conversation(
        id = id,
        title = title,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        turns = turns,
        systemPrompt = systemPrompt,
    )

    // ---- titles -----------------------------------------------------------------------------

    @Test
    fun `title derives from the first user question`() {
        val derived = ConversationTitles.derive(
            listOf(
                ChatTurn(Role.ASSISTANT, "ignored"),
                ChatTurn(Role.USER, "  كيف  أتعلم\n Kotlin؟  "),
            ),
        )

        assertEquals("كيف أتعلم Kotlin؟", derived)
    }

    @Test
    fun `title is null when there is no user question yet`() {
        assertNull(ConversationTitles.derive(emptyList()))
        assertNull(ConversationTitles.derive(listOf(ChatTurn(Role.USER, "   "))))
        assertNull(ConversationTitles.derive(listOf(ChatTurn(Role.ASSISTANT, "جواب"))))
    }

    @Test
    fun `title is cut at the documented length`() {
        val long = "ا".repeat(ConversationTitles.MAX_LENGTH + 30)

        val derived = ConversationTitles.derive(listOf(ChatTurn(Role.USER, long)))

        assertEquals(ConversationTitles.MAX_LENGTH, derived!!.length)
    }

    @Test
    fun `display prefers an explicit title, then the derived one, then the fallback`() {
        val turns = listOf(ChatTurn(Role.USER, "سؤال"))

        assertEquals("عنوان", ConversationTitles.display("عنوان", turns, "محادثة"))
        assertEquals("سؤال", ConversationTitles.display("  ", turns, "محادثة"))
        assertEquals("محادثة", ConversationTitles.display(null, emptyList(), "محادثة"))
    }

    // ---- search -----------------------------------------------------------------------------

    @Test
    fun `search matches an explicit title case-insensitively`() {
        val c = conversation(title = "Trip to Tokyo", turns = listOf(ChatTurn(Role.USER, "مرحبا")))

        assertTrue(ConversationSearch.matches(c, "tokyo"))
        assertTrue(ConversationSearch.matches(c, "TOKYO"))
        assertFalse(ConversationSearch.matches(c, "Cairo"))
    }

    @Test
    fun `search matches any turn text, not only the title`() {
        val c = conversation(turns = listOf(ChatTurn(Role.USER, "سؤال"), ChatTurn(Role.ASSISTANT, "الجواب عن القهوة")))

        assertTrue(ConversationSearch.matches(c, "القهوة"))
        assertFalse(ConversationSearch.matches(c, "الشاي"))
    }

    @Test
    fun `a blank search returns every conversation and filter keeps order`() {
        val first = conversation(id = "a", turns = listOf(ChatTurn(Role.USER, "واحد")))
        val second = conversation(id = "b", turns = listOf(ChatTurn(Role.USER, "اثنان")))

        assertEquals(listOf(first, second), ConversationSearch.filter(listOf(first, second), "   "))
        assertEquals(listOf(second), ConversationSearch.filter(listOf(first, second), "اثنان"))
    }

    // ---- export -----------------------------------------------------------------------------

    @Test
    fun `markdown export uses the supplied labels and separates turns`() {
        val c = conversation(
            title = "السفر",
            turns = listOf(
                ChatTurn(Role.USER, "كيف الطقس؟"),
                ChatTurn(Role.ASSISTANT, "مشمس."),
            ),
        )

        val markdown = ConversationExport.markdown(
            conversation = c,
            title = "السفر",
            userLabel = "أنت",
            assistantLabel = "سرّاق",
        )

        assertEquals(
            "# السفر\n\n**أنت**\n\nكيف الطقس؟\n\n**سرّاق**\n\nمشمس.\n",
            markdown,
        )
    }

    @Test
    fun `markdown export skips blank turns`() {
        val c = conversation(turns = listOf(ChatTurn(Role.USER, " "), ChatTurn(Role.USER, "سؤال")))

        val markdown = ConversationExport.markdown(c, "عنوان", "You", "Sirrak")

        assertFalse(markdown.contains("**You**\n\n \n"))
        assertTrue(markdown.contains("**You**\n\nسؤال"))
    }

    @Test
    fun `json export carries a schema version and every turn`() {
        val c = conversation(
            title = "خطة",
            turns = listOf(ChatTurn(Role.USER, "سؤال"), ChatTurn(Role.ASSISTANT, "جواب")),
            systemPrompt = "كن موجزاً",
        )

        val root = Json.parseToJsonElement(ConversationExport.json(c)).jsonObject

        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("c1", root.getValue("id").jsonPrimitive.content)
        assertEquals("خطة", root.getValue("title").jsonPrimitive.content)
        val turns = root.getValue("turns").jsonArray
        assertEquals(2, turns.size)
        assertEquals("USER", turns[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("سؤال", turns[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("جواب", turns[1].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `json export round-trips per-conversation generation overrides`() {
        val c = conversation(turns = listOf(ChatTurn(Role.USER, "سؤال"))).copy(
            overrides = GenerationOverrides(temperature = 0.3f, stop = listOf("</s>", "###")),
        )

        val restored = ConversationExport.fromJson(ConversationExport.json(c))!!

        assertEquals(0.3f, restored.overrides!!.temperature!!, 0.0001f)
        assertEquals(listOf("</s>", "###"), restored.overrides!!.stop)
        assertNull(restored.overrides!!.topP)
    }

    // ---- token meter ------------------------------------------------------------------------

    @Test
    fun `token estimate is zero for blank text and rounds chars up`() {
        assertEquals(0, TokenEstimate.tokens("   "))
        assertEquals(1, TokenEstimate.tokens("abcd"))
        assertEquals(2, TokenEstimate.tokens("abcde"))
    }

    @Test
    fun `token estimate adds a per-message overhead`() {
        val turns = listOf(ChatTurn(Role.USER, "abcd"), ChatTurn(Role.ASSISTANT, "abcd"))

        assertEquals(1 + 1 + TokenEstimate.MESSAGE_OVERHEAD * 2, TokenEstimate.estimate(turns))
    }

    @Test
    fun `near-limit warning fires at the documented fraction only when the limit is known`() {
        assertTrue(TokenEstimate.nearLimit(estimated = 850, limit = 1_000))
        assertFalse(TokenEstimate.nearLimit(estimated = 840, limit = 1_000))
        assertFalse(TokenEstimate.nearLimit(estimated = 10, limit = 0))
    }

    @Test
    fun `conversation defaults keep the transcript and prompt optional`() {
        val c = Conversation(id = "x", title = null, createdAt = 1L, updatedAt = 2L)

        assertTrue(c.turns.isEmpty())
        assertNull(c.systemPrompt)
    }
}

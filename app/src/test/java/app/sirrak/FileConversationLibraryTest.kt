package app.sirrak

import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationStore
import app.sirrak.core.runtime.Role
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable conversation library: an index plus one transcript per conversation, with the M3 single
 * file imported once as the first conversation. Real files, fake clock, no Android runtime.
 */
class FileConversationLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private var ids = 0

    private fun library(): FileConversationLibrary = FileConversationLibrary(
        dir = tmp.root,
        clock = { now },
        nextId = { "c-" + (ids++) },
    )

    private fun turns(vararg texts: String): List<ChatTurn> =
        texts.mapIndexed { index, text -> ChatTurn(if (index % 2 == 0) Role.USER else Role.ASSISTANT, text) }

    private fun FileConversationLibrary.saved(id: String, vararg texts: String): Conversation {
        val existing = find(id)!!
        val updated = existing.copy(turns = turns(*texts))
        assertTrue(save(updated))
        return find(id)!!
    }

    // ---- create / list / find ---------------------------------------------------------------

    @Test
    fun `create persists an empty conversation and an index on disk`() {
        val created = library().create()

        assertTrue(File(tmp.root, "conversations/index.json").isFile)
        assertTrue(File(tmp.root, "conversations/${created.id}.json").isFile)
        val library = library()
        assertEquals(1, library.list().size)
        assertEquals(0, library.list().first().messageCount)
        assertTrue(library.find(created.id)!!.turns.isEmpty())
    }

    @Test
    fun `created ids stay unique even for the same clock tick`() {
        val library = library()
        val a = library.create()
        now = 1_000L
        val b = library.create()

        assertEquals(2, library.list().size)
        assertFalse(a.id == b.id)
    }

    @Test
    fun `saved turns survive a new instance with count and preview`() {
        val library = library()
        val created = library.create()
        now = 2_000L
        library.saved(created.id, "سؤال", "جواب\nسطر ثانٍ")

        val reopened = library()
        val found = reopened.find(created.id)!!
        assertEquals(listOf("سؤال", "جواب\nسطر ثانٍ"), found.turns.map { it.text })

        val summary = reopened.list().first()
        assertEquals(created.id, summary.id)
        assertEquals(2, summary.messageCount)
        assertEquals("جواب", summary.preview)
        assertEquals(2_000L, summary.updatedAt)
    }

    @Test
    fun `arabic text and the system prompt round-trip unchanged`() {
        val library = library()
        val created = library.create()
        assertTrue(
            library.save(
                created.copy(
                    turns = turns("ما عاصمة اليابان؟", "طوكيو."),
                    systemPrompt = "أجب بالعربية فقط",
                ),
            ),
        )

        val found = library().find(created.id)!!
        assertEquals("طوكيو.", found.turns.last().text)
        assertEquals("أجب بالعربية فقط", found.systemPrompt)
    }

    @Test
    fun `list is newest first and a later save moves a conversation to the top`() {
        val library = library()
        now = 1_000L
        val a = library.create()
        now = 2_000L
        val b = library.create()

        assertEquals(listOf(b.id, a.id), library.list().map { it.id })

        now = 3_000L
        library.saved(a.id, "سؤال")
        assertEquals(listOf(a.id, b.id), library().list().map { it.id })
    }

    // ---- rename / delete / active -----------------------------------------------------------

    @Test
    fun `an explicit title is stored and a blank title clears it`() {
        val library = library()
        val created = library.create()

        assertTrue(library.rename(created.id, "  خطة السفر  "))
        assertEquals("خطة السفر", library().find(created.id)!!.title)

        assertTrue(library.rename(created.id, "   "))
        assertNull(library.find(created.id)!!.title)
    }

    @Test
    fun `rename and delete reject unknown ids`() {
        val library = library()

        assertFalse(library.rename("missing", "عنوان"))
        assertFalse(library.delete("missing"))
        assertFalse(library.setActiveId("missing"))
    }

    @Test
    fun `delete removes the transcript and falls back to the newest remaining conversation`() {
        val library = library()
        now = 1_000L
        val a = library.create()
        now = 2_000L
        val b = library.create()
        assertTrue(library.setActiveId(b.id))

        assertTrue(library.delete(b.id))

        assertNull(library.find(b.id))
        assertFalse(File(tmp.root, "conversations/${b.id}.json").exists())
        assertEquals(a.id, library.activeId())

        assertTrue(library.delete(a.id))
        assertNull(library.activeId())
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `active conversation survives a new instance`() {
        val library = library()
        val created = library.create()

        assertTrue(library.setActiveId(created.id))

        assertEquals(created.id, library().activeId())
        assertTrue(library().setActiveId(null))
        assertNull(library().activeId())
    }

    // ---- legacy migration -------------------------------------------------------------------

    @Test
    fun `the M3 single file is imported once and kept as a migrated copy`() {
        FileConversationStore(tmp.root).save(turns("سؤال قديم", "جواب قديم"))

        val library = library()
        val summaries = library.list()

        assertEquals(1, summaries.size)
        val imported = library.find(summaries.first().id)!!
        assertEquals("سؤال قديم", imported.turns.first().text)
        assertEquals("جواب قديم", imported.turns.last().text)
        assertNull(imported.title)
        assertEquals(imported.id, library.activeId())
        assertFalse(File(tmp.root, "conversation.json").exists())
        assertTrue(File(tmp.root, "conversation.json.migrated").isFile)

        // A second launch must not import it again.
        assertEquals(1, library().list().size)
    }

    @Test
    fun `a missing legacy file simply starts empty`() {
        val library = library()

        assertTrue(library.list().isEmpty())
        assertNull(library.activeId())
    }

    @Test
    fun `a corrupt legacy file is ignored without blocking the library`() {
        File(tmp.root, "conversation.json").writeText("{not json")

        val library = library()

        assertTrue(library.list().isEmpty())
        assertNotNull(library.create())
    }

    // ---- recovery ---------------------------------------------------------------------------

    @Test
    fun `a corrupt index is backed up and rebuilt from the transcripts`() {
        val library = library()
        val created = library.create()
        library.saved(created.id, "سؤال", "جواب")
        File(tmp.root, "conversations/index.json").writeText("not json at all")

        val recovered = library()

        assertEquals(1, recovered.list().size)
        assertEquals("جواب", recovered.find(created.id)!!.turns.last().text)
        assertTrue(File(tmp.root, "conversations/index.json.corrupt").isFile)
    }

    @Test
    fun `a corrupt transcript is skipped instead of crashing the library`() {
        val library = library()
        val created = library.create()
        library.saved(created.id, "سؤال")
        File(tmp.root, "conversations/${created.id}.json").writeText("{ broken")

        val recovered = library()

        assertTrue(recovered.list().isEmpty())
        assertNull(recovered.find(created.id))
        assertEquals(0, recovered.create().turns.size)
    }

    @Test
    fun `the list preview strips markdown block markers`() {
        val library = library()
        val conversation = library.create()
        library.save(
            conversation.copy(
                turns = listOf(
                    ChatTurn(Role.USER, "سؤال"),
                    ChatTurn(Role.ASSISTANT, "## العرض الغني\n\nنص."),
                ),
            ),
        )

        assertEquals("العرض الغني", library.list().single().preview)
    }

    @Test
    fun `search matches a word from any turn, not just the preview`() {
        val library = library()
        val coffee = library.create()
        now = 2_000L
        library.saved(coffee.id, "سؤال عن القهوة", "جواب طويل")
        val weather = library.create()
        now = 3_000L
        library.saved(weather.id, "كيف الطقس؟", "مشمس")

        assertEquals(listOf(coffee.id), library.search("القهوة").map { it.id })
        assertEquals(listOf(weather.id), library.search("الطقس").map { it.id })
        assertTrue(library.search("   ").isEmpty() || library.search("   ").size == 2)
        assertEquals(2, library.search("").size)
    }

    @Test
    fun `the list exposes the derived first question until the user renames`() {
        val library = library()
        val created = library.create()
        now = 2_000L
        library.saved(created.id, "كيف أتعلم Kotlin؟", "بالممارسة")

        val summary = library.list().first()
        assertNull(summary.title)
        assertEquals("كيف أتعلم Kotlin؟", summary.derivedTitle)
    }

    @Test
    fun `only the newest turns are kept when a conversation grows past the cap`() {
        val library = library()
        val created = library.create()
        val many = (1..ConversationStore.MAX_TURNS + 20).map { ChatTurn(Role.USER, "t" + it) }

        val current = library.find(created.id)!!
        assertTrue(library.save(current.copy(turns = many)))

        val loaded = library.find(created.id)!!
        assertEquals(ConversationStore.MAX_TURNS, loaded.turns.size)
        assertEquals("t" + (ConversationStore.MAX_TURNS + 20), loaded.turns.last().text)
    }

    // ---- attachments (M7) -------------------------------------------------------------------

    @Test
    fun `attachment metadata round-trips through save and find`() {
        val library = library()
        val created = library.create()
        val document = Attachment(
            id = "a1",
            kind = AttachmentKind.DOCUMENT,
            displayName = "تقرير.pdf",
            mimeType = "application/pdf",
            sizeBytes = 42L,
            localPath = "attachments/${created.id}/a1.pdf",
            extractedText = "نص مستخرج",
            meta = mapOf("source" to "picker"),
        )
        val shared = Attachment(
            id = "a2",
            kind = AttachmentKind.SHARED_TEXT,
            displayName = "clip.txt",
            mimeType = "text/plain",
            sizeBytes = 5L,
            localPath = "attachments/${created.id}/a2.txt",
            extractedText = "hello",
            meta = mapOf("source" to "clipboard"),
        )
        val turns = listOf(
            ChatTurn(Role.USER, "اقرأ المرفق", listOf(document, shared)),
            ChatTurn(Role.ASSISTANT, "تم"),
        )

        assertTrue(library.save(created.copy(turns = turns)))

        val reopened = library().find(created.id)!!
        assertEquals(turns, reopened.turns)
        assertEquals(listOf(document, shared), reopened.turns.first().attachments)
        assertTrue(reopened.turns.last().attachments.isEmpty())
        assertTrue(
            File(tmp.root, "conversations/${created.id}.json").readText().contains("attachments"),
        )
    }

    @Test
    fun `a legacy transcript without attachments still loads`() {
        val conversations = File(tmp.root, "conversations").apply { mkdirs() }
        File(conversations, "legacy.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "legacy",
              "title": null,
              "createdAt": 10,
              "updatedAt": 20,
              "turns": [
                {"role": "USER", "text": "سؤال قديم"},
                {"role": "ASSISTANT", "text": "جواب قديم"}
              ]
            }
            """.trimIndent(),
        )

        val library = library()
        val legacy = library.find("legacy")

        assertNotNull(legacy)
        assertEquals(listOf("سؤال قديم", "جواب قديم"), legacy!!.turns.map { it.text })
        assertTrue(legacy.turns.all { it.attachments.isEmpty() })
        assertEquals(1, library.list().size)
    }

    @Test
    fun `corrupt attachment entries are skipped without losing the transcript`() {
        val conversations = File(tmp.root, "conversations").apply { mkdirs() }
        File(conversations, "mixed.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "mixed",
              "createdAt": 1,
              "updatedAt": 2,
              "turns": [
                {
                  "role": "USER",
                  "text": "with one broken attachment",
                  "attachments": [
                    {"id": "a1", "kind": "ALIEN", "displayName": "x", "mimeType": "text/plain", "sizeBytes": 1},
                    {"kind": "DOCUMENT", "displayName": "no id", "mimeType": "application/pdf", "sizeBytes": 2},
                    {
                      "id": "a3",
                      "kind": "DOCUMENT",
                      "displayName": "ok.txt",
                      "mimeType": "text/plain",
                      "sizeBytes": 3,
                      "localPath": "attachments/mixed/a3.txt"
                    }
                  ]
                },
                {"role": "ASSISTANT", "text": "unknown attachment shape", "attachments": {"unexpected": true}}
              ]
            }
            """.trimIndent(),
        )

        val turns = library().find("mixed")!!.turns

        assertEquals(2, turns.size)
        assertEquals("with one broken attachment", turns.first().text)
        assertEquals(listOf("a3"), turns.first().attachments.map { it.id })
        assertTrue(turns.last().attachments.isEmpty())
    }
}

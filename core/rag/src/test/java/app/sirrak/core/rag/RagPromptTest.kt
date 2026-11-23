package app.sirrak.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPromptTest {

    private fun hit(
        id: String,
        text: String,
        page: Int = 1,
        documentName: String = "مستند",
    ): RagHit = RagHit(
        documentId = "doc-" + id,
        documentName = documentName,
        chunk = RagChunk(id = id, documentId = "doc-" + id, ordinal = 0, text = text, page = page),
        rank = 1,
        fusedScore = 1f,
    )

    @Test
    fun compose_containsInstructionsSourcesQuestionAndAnswerHeader() {
        val prompt = RagPrompt.compose("ما هو الموعد؟", listOf(hit("c1", "الموعد يوم الأحد.")))
        assertTrue(prompt.prompt.contains("untrusted"))
        assertTrue(prompt.prompt.contains("SOURCES:"))
        assertTrue(prompt.prompt.contains("QUESTION: ما هو الموعد؟"))
        assertTrue(prompt.prompt.contains("ANSWER:"))
        assertTrue(prompt.prompt.contains("[1] مستند (page 1): الموعد يوم الأحد."))
        assertEquals(1, prompt.sources.size)
        assertEquals("c1", prompt.sources.first().chunkId)
    }

    @Test
    fun compose_numbersSourcesInRankOrder() {
        val prompt = RagPrompt.compose(
            "سؤال",
            listOf(hit("c1", "الأول", page = 2), hit("c2", "الثاني", page = 5)),
        )
        assertEquals(listOf(1, 2), prompt.sources.map { it.number })
        assertEquals(listOf(2, 5), prompt.sources.map { it.page })
        assertTrue(prompt.prompt.indexOf("[1]") < prompt.prompt.indexOf("[2]"))
    }

    @Test
    fun compose_respectsSnippetBudgetAndFlagsTruncation() {
        val policy = RagPromptPolicy.Default.copy(maxContextChars = 60, maxSnippetChars = 60)
        val prompt = RagPrompt.compose(
            "سؤال",
            listOf(hit("c1", "كلمة ".repeat(100)), hit("c2", "نص آخر")),
            policy,
        )
        assertTrue(prompt.truncated)
        val snippetTotal = prompt.sources.sumOf { it.snippet.length }
        assertTrue(snippetTotal <= policy.maxContextChars)
        assertFalse(prompt.prompt.isBlank())
    }

    @Test
    fun compose_noHitsIsExplicit() {
        val prompt = RagPrompt.compose("سؤال", emptyList())
        assertTrue(prompt.sources.isEmpty())
        assertTrue(prompt.prompt.contains("(no matching sources were found)"))
    }

    @Test
    fun compose_handlesHitsWithoutText() {
        val prompt = RagPrompt.compose("سؤال", listOf(hit("c1", "   ")))
        assertTrue(prompt.sources.isEmpty())
    }

    @Test
    fun defaultPolicyIsEditableData() {
        val custom = RagPromptPolicy.Default.copy(instructions = listOf("CUSTOM RULE"))
        val prompt = RagPrompt.compose("q", emptyList(), custom)
        assertTrue(prompt.prompt.contains("CUSTOM RULE"))
        assertFalse(prompt.prompt.contains("Answer the user's question using only"))
    }

    @Test
    fun citation_parseOrdersDeduplicatesAndIgnoresUnknown() {
        val sources = listOf(
            RagSource(1, "d1", "مستند أول", "c1", 3, "نص أول"),
            RagSource(2, "d2", "مستند ثاني", "c2", 7, "نص ثاني"),
        )
        val answer = "نعم [2] ثم [9] ثم [1] ثم [2]."
        val citations = CitationParser.parse(answer, sources)
        assertEquals(listOf("c2", "c1"), citations.map { it.chunkId })
        assertEquals(listOf(7, 3), citations.map { it.page })
        assertEquals(setOf(1, 2), CitationParser.citedNumbers(answer, sources))
    }

    @Test
    fun citation_parseRespectsMaxCitations() {
        val sources = (1..5).map { RagSource(it, "d" + it, "مستند " + it, "c" + it, 1, "نص") }
        val answer = "[1] [2] [3] [4] [5]"
        assertEquals(2, CitationParser.parse(answer, sources, maxCitations = 2).size)
        assertTrue(CitationParser.parse(answer, sources, maxCitations = 0).isEmpty())
    }

    @Test
    fun citation_parseWithoutMarkersIsEmpty() {
        val sources = listOf(RagSource(1, "d1", "مستند", "c1", 1, "نص"))
        assertTrue(CitationParser.parse("لا توجد إشارات هنا.", sources).isEmpty())
    }
}

package app.sirrak.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    private fun pages(vararg texts: String): List<ExtractedPage> =
        texts.mapIndexed { index, text -> ExtractedPage(index + 1, text) }

    private fun longArabicText(sentences: Int): String =
        (1..sentences).joinToString(" ") { "هذه جملة رقم " + it + " تشرح موضوع البحث بالتفصيل." }

    @Test
    fun chunk_emptyPagesProducesNothing() {
        assertEquals(ChunkingResult(emptyList()), TextChunker().chunk("doc", emptyList()))
    }

    @Test
    fun chunk_respectsTargetAndHardMaximum() {
        val limits = RagLimits.Default
        val result = TextChunker(limits).chunk("doc", pages(longArabicText(40)))
        assertTrue(result.chunks.size > 1)
        for (chunk in result.chunks) {
            assertTrue(chunk.text.isNotBlank())
            assertTrue("chunk too long: " + chunk.text.length, chunk.text.length <= limits.maxChunkChars)
        }
        assertFalse(result.truncated)
    }

    @Test
    fun chunk_idsAreDeterministicAndOrdinalAligned() {
        val input = pages(longArabicText(20))
        val first = TextChunker().chunk("doc-1", input).chunks
        val second = TextChunker().chunk("doc-1", input).chunks
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.text }, second.map { it.text })
        first.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
            assertEquals("doc-1#" + index, chunk.id)
            assertEquals("doc-1", chunk.documentId)
        }
    }

    @Test
    fun chunk_consecutiveChunksOverlap() {
        val chunks = TextChunker().chunk("doc", pages(longArabicText(40))).chunks
        assertTrue(chunks.size >= 3)
        val tail = chunks[0].text.trim().takeLast(40).trim()
        val head = chunks[1].text.trim().take(80)
        val tailWords = tail.split(' ').takeLast(3).joinToString(" ")
        assertTrue(
            "expected overlap, head=" + head + " tail=" + tail,
            head.contains(tailWords) || tailWords.split(' ').all { it in head },
        )
    }

    @Test
    fun chunk_tracksPageNumbers() {
        val chunks = TextChunker().chunk("doc", pages(longArabicText(15), longArabicText(15))).chunks
        assertTrue(chunks.any { it.page == 2 })
        assertEquals(1, chunks.first().page)
    }

    @Test
    fun chunk_neverMixesPagesInOneChunk() {
        val chunks = TextChunker().chunk("doc", pages("صفحة قصيرة أولى.", "صفحة قصيرة ثانية.")).chunks
        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].page)
        assertEquals(2, chunks[1].page)
        assertTrue(chunks[0].text.contains("أولى"))
        assertTrue(chunks[1].text.contains("ثانية"))
    }

    @Test
    fun chunk_detectsShortHeadingLine() {
        val body = "عنوان المستند\n" + longArabicText(20)
        val chunk = TextChunker().chunk("doc", pages(body)).chunks.first()
        assertEquals("عنوان المستند", chunk.heading)
    }

    @Test
    fun chunk_longProseFirstLineIsNotAHeading() {
        val body = longArabicText(20)
        val chunk = TextChunker().chunk("doc", pages(body)).chunks.first()
        assertNull(chunk.heading)
    }

    @Test
    fun chunk_hardSplitsSentencesWithoutBreakingWords() {
        val limits = RagLimits.Default
        val noSpaces = "كلمة ".repeat(400).trim()
        val chunks = TextChunker(limits).chunk("doc", pages(noSpaces)).chunks
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk -> assertTrue(chunk.text.length <= limits.maxChunkChars) }
    }

    @Test
    fun chunk_capsAtMaxChunksAndFlagsTruncation() {
        val limits = RagLimits.Default.copy(
            targetChunkChars = 100,
            maxChunkChars = 150,
            overlapChars = 10,
            minChunkChars = 10,
            maxChunksPerDocument = 2,
        )
        val result = TextChunker(limits).chunk("doc", pages(longArabicText(60)))
        assertEquals(2, result.chunks.size)
        assertTrue(result.truncated)
    }

    @Test
    fun chunk_skipsBlankPages() {
        val chunks = TextChunker().chunk("doc", pages("   ", longArabicText(10), "\n\n")).chunks
        assertEquals(1, chunks.size)
        assertEquals(2, chunks.first().page)
    }

    @Test
    fun chunk_normalizedTextIsMatchingForm() {
        val chunk = TextChunker().chunk("doc", pages("أحمد ذهب إلى المدرسة")).chunks.first()
        assertEquals("احمد ذهب الي المدرسه", chunk.normalized)
        assertNotNull(chunk.text)
    }
}

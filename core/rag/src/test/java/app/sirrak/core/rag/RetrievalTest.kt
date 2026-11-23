package app.sirrak.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {

    private var ordinal = 0

    private fun chunk(id: String, documentId: String, text: String): RagChunk =
        RagChunk(id = id, documentId = documentId, ordinal = ordinal++, text = text, page = 1)

    private fun hit(id: String, documentId: String, text: String): RagHit = RagHit(
        documentId = documentId,
        documentName = documentId,
        chunk = chunk(id, documentId, text),
        rank = 1,
        fusedScore = 1f,
    )

    @Test
    fun bm25_ranksChunkWithTermFirst() {
        val chunks = listOf(
            chunk("c1", "d1", "سعر الكتاب مرتفع"),
            chunk("c2", "d1", "موعد الاجتماع غدا"),
        )
        val hits = Bm25Index.build(chunks).search("موعد", 5)
        assertEquals("c2", hits.first().chunk.id)
        assertTrue(hits.first().score > 0.0)
    }

    @Test
    fun bm25_matchesNormalizedForms() {
        val chunks = listOf(
            chunk("c1", "d1", "أحمد قرأ كِتَابًا جديدا"),
            chunk("c2", "d1", "موضوع آخر تماما"),
        )
        val hits = Bm25Index.build(chunks).search("احمد كتاب", 5)
        assertEquals("c1", hits.first().chunk.id)
    }

    @Test
    fun bm25_allowedScopeFiltersDocuments() {
        val chunks = listOf(
            chunk("a1", "doc-a", "ميزانية المشروع"),
            chunk("b1", "doc-b", "ميزانية المشروع"),
        )
        val hits = Bm25Index.build(chunks).search("ميزانية", 5, setOf("doc-b"))
        assertEquals(1, hits.size)
        assertEquals("doc-b", hits.first().chunk.documentId)
    }

    @Test
    fun bm25_respectsLimitAndRejectsBlankQuery() {
        val chunks = (1..5).map { chunk("c" + it, "d1", "كلمة مشتركة رقم " + it) }
        assertEquals(2, Bm25Index.build(chunks).search("كلمة", 2).size)
        assertTrue(Bm25Index.build(chunks).search("  ", 5).isEmpty())
    }

    @Test
    fun bm25_emptyIndexIsEmpty() {
        assertTrue(Bm25Index.build(emptyList()).search("anything", 5).isEmpty())
    }

    @Test
    fun rrf_fusesRanksAcrossLists() {
        val fused = RrfFusion.fuse(listOf(listOf("a", "b"), listOf("b", "c")), k = 60, limit = 3)
        assertEquals(listOf("b", "a", "c"), fused.map { it.first })
        assertTrue(fused.first().second > fused.last().second)
    }

    @Test
    fun rrf_tiesKeepFirstSeenOrder() {
        val fused = RrfFusion.fuse(listOf(listOf("a"), listOf("b")), k = 60)
        assertEquals(listOf("a", "b"), fused.map { it.first })
    }

    @Test
    fun rrf_requiresPositiveK() {
        assertThrows(IllegalArgumentException::class.java) { RrfFusion.fuse(listOf(listOf("a")), k = 0) }
    }

    @Test
    fun hybrid_usesBothLexicalAndVectorSignals() {
        val chunks = listOf(
            chunk("c1", "d1", "سعر الكتاب مرتفع"),
            chunk("c2", "d1", "موعد الاجتماع غدا"),
        )
        val vectors = mapOf("c1" to floatArrayOf(0.1f, 1f), "c2" to floatArrayOf(1f, 0.1f))
        val hits = HybridRetriever().retrieve(
            question = "سعر",
            chunks = chunks,
            vectors = vectors,
            queryVector = floatArrayOf(1f, 0f),
            documentNames = mapOf("d1" to "المستند"),
        )
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.chunk.id == "c1" && it.lexicalScore > 0f })
        assertTrue(hits.any { it.chunk.id == "c2" && it.vectorScore > 0f })
        assertEquals("المستند", hits.first().documentName)
    }

    @Test
    fun hybrid_scopeOnlyReturnsAllowedDocumentChunks() {
        val chunks = listOf(
            chunk("a1", "doc-a", "ميزانية المشروع"),
            chunk("b1", "doc-b", "ميزانية المشروع"),
        )
        val hits = HybridRetriever().retrieve(
            question = "ميزانية",
            chunks = chunks,
            vectors = emptyMap(),
            queryVector = null,
            documentNames = emptyMap(),
            allowedDocumentIds = setOf("doc-a"),
        )
        assertEquals(listOf("a1"), hits.map { it.chunk.id })
    }

    @Test
    fun hybrid_ignoresVectorDimensionMismatch() {
        val chunks = listOf(chunk("c1", "d1", "موضوع"))
        val hits = HybridRetriever().retrieve(
            question = "موضوع",
            chunks = chunks,
            vectors = mapOf("c1" to floatArrayOf(1f, 2f, 3f)),
            queryVector = floatArrayOf(1f, 0f),
            documentNames = emptyMap(),
        )
        assertEquals(1, hits.size)
        assertEquals(0f, hits.first().vectorScore)
        assertTrue(hits.first().lexicalScore > 0f)
    }

    @Test
    fun hybrid_emptyQuestionOrNoScopedChunksIsEmpty() {
        val chunks = listOf(chunk("c1", "d1", "نص"))
        assertTrue(
            HybridRetriever().retrieve("", chunks, emptyMap(), null, emptyMap()).isEmpty(),
        )
        assertTrue(
            HybridRetriever().retrieve(
                "نص", chunks, emptyMap(), null, emptyMap(), allowedDocumentIds = setOf("missing"),
            ).isEmpty(),
        )
    }

    @Test
    fun hybrid_respectsContextTopK() {
        val chunks = (1..6).map { chunk("c" + it, "d1", "جملة البحث رقم " + it) }
        val hits = HybridRetriever().retrieve(
            question = "جملة البحث",
            chunks = chunks,
            vectors = emptyMap(),
            queryVector = null,
            documentNames = emptyMap(),
        )
        assertEquals(RagLimits.Default.contextTopK, hits.size)
        assertEquals((1..hits.size).toList(), hits.map { it.rank })
    }

    @Test
    fun hybrid_bilingualTextIsRetrievable() {
        val chunks = listOf(chunk("c1", "d1", "The meeting is scheduled for Sunday"))
        val hits = HybridRetriever().retrieve(
            question = "meeting Sunday", chunks, emptyMap(), null, emptyMap(),
        )
        assertFalse(hits.isEmpty())
        assertNotNull(hits.first().chunk.normalized)
    }
}

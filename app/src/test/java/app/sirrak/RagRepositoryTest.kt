package app.sirrak

import app.sirrak.core.rag.ArabicText
import app.sirrak.core.rag.DocumentFormats
import app.sirrak.core.rag.EmbeddingProvider
import app.sirrak.core.rag.EmbeddingStatus
import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.ExtractorRegistry
import app.sirrak.core.rag.RagDocumentStatus
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.rag.RagImportResult
import app.sirrak.core.rag.RagLimits
import app.sirrak.core.rag.defaultExtractors
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic bag-of-tokens embedding: shared words produce high cosine without a native model. */
private class FakeEmbeddingProvider(
    override val dimensions: Int = 64,
    override val modelId: String = "fake-embedding",
) : EmbeddingProvider {
    override fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
        val vector = FloatArray(dimensions)
        for (token in ArabicText.tokens(text)) {
            val slot = (token.hashCode() and Int.MAX_VALUE) % dimensions
            vector[slot] += 1f
        }
        if (vector.all { it == 0f }) vector[0] = 0.001f
        vector
    }

    override fun close() {}
}

private class FakeEmbeddingBackend : EmbeddingBackend {
    private var current: EmbeddingProvider? = null
    private val state = MutableStateFlow<EmbeddingStatus>(EmbeddingStatus.Missing)
    override val status: StateFlow<EmbeddingStatus> = state

    override suspend fun provider(): EmbeddingProvider? = current

    fun install(provider: EmbeddingProvider?) {
        current = provider
        state.value = if (provider == null) {
            EmbeddingStatus.Missing
        } else {
            EmbeddingStatus.Ready(provider.modelId, provider.dimensions)
        }
    }
}

class RagRepositoryTest {

    private val root: File = Files.createTempDirectory("sirrak-rag-repo").toFile()
    private val backend = FakeEmbeddingBackend()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun repository(limits: RagLimits = RagLimits.Default): RagRepository = RagRepository(
        store = FileRagStore(root),
        embeddings = backend,
        extractors = ExtractorRegistry(defaultExtractors(), DocumentFormats.all, limits),
        limits = limits,
        io = Dispatchers.Unconfined,
        externalScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun source(name: String, text: String): ExtractionSource {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ExtractionSource(name, "text/plain", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }

    private fun imported(result: RagImportResult): app.sirrak.core.rag.RagDocument =
        (result as RagImportResult.Imported).document

    @Test
    fun importWithoutEmbeddingKeepsDocumentExtracted() = runBlocking {
        val repository = repository()
        val document = imported(repository.import(source("ملاحظات.txt", "موعد الاجتماع يوم الأحد. " + "نص ".repeat(200))))

        assertEquals(RagDocumentStatus.EXTRACTED, document.status)
        assertTrue(document.chunkCount > 0)
        assertEquals(1, repository.documents.value.size)
        assertEquals(1, repository.status.value.pendingCount)
        assertNull(repository.storeVectorsForTest(document.id))
    }

    @Test
    fun importWithEmbeddingProducesReadyIndex() = runBlocking {
        backend.install(FakeEmbeddingProvider())
        val repository = repository()
        val document = imported(repository.import(source("تقرير.txt", "التقرير السنوي للمشروع. " + "تفاصيل ".repeat(200))))

        assertEquals(RagDocumentStatus.READY, document.status)
        assertNotNull(document.indexedAt)
        assertEquals("fake-embedding", document.embeddingModelId)
        assertEquals(1, repository.status.value.readyCount)
        assertNotNull(repository.storeVectorsForTest(document.id))
    }

    @Test
    fun importRejectsEmptyUnsupportedAndTooLarge() = runBlocking {
        val repository = repository()
        assertEquals(
            RagImportResult.Rejected(RagImportRejection.EMPTY),
            repository.import(ExtractionSource("empty.txt", "text/plain", 0L) { ByteArrayInputStream(ByteArray(0)) }),
        )
        // Detection trusts either a known extension or a known MIME: a .exe that claims text/plain
        // would legitimately be read as text, so the unsupported case must disagree on both.
        assertEquals(
            RagImportResult.Rejected(RagImportRejection.UNSUPPORTED_TYPE),
            repository.import(
                ExtractionSource("photo.jpg", "image/jpeg", 3L) {
                    ByteArrayInputStream(byteArrayOf(1, 2, 3))
                },
            ),
        )
        val tinyLimits = RagLimits.Default.copy(maxDocumentBytes = 8)
        assertEquals(
            RagImportResult.Rejected(RagImportRejection.TOO_LARGE),
            repository(tinyLimits).import(source("big.txt", "x".repeat(64))),
        )
    }

    @Test
    fun retrieveReturnsScoredHitsFromReadyDocuments() = runBlocking {
        backend.install(FakeEmbeddingProvider())
        val repository = repository()
        imported(repository.import(source("المواعيد.txt", "موعد الاجتماع يوم الأحد الساعة العاشرة. " + "تفاصيل ".repeat(120))))
        imported(repository.import(source("الميزانية.txt", "ميزانية المشروع لهذا العام كبيرة. " + "أرقام ".repeat(120))))

        val hits = repository.retrieve("موعد الاجتماع", emptyList())
        assertFalse(hits.isEmpty())
        assertEquals("المواعيد.txt", hits.first().documentName)
    }

    @Test
    fun retrieveRespectsConversationScope() = runBlocking {
        backend.install(FakeEmbeddingProvider())
        val repository = repository()
        val first = imported(repository.import(source("الأول.txt", "كلمة مشتركة في المستند الأول. " + "نص ".repeat(100))))
        imported(repository.import(source("الثاني.txt", "كلمة مشتركة في المستند الثاني. " + "نص ".repeat(100))))

        val scoped = repository.retrieve("كلمة مشتركة", listOf(first.id))
        assertFalse(scoped.isEmpty())
        assertTrue(scoped.all { it.documentId == first.id })
    }

    @Test
    fun retrieveWithoutEmbeddingIsHonestlyEmpty() = runBlocking {
        val repository = repository()
        imported(repository.import(source("doc.txt", "نص عربي طويل " + "جملة ".repeat(100))))
        assertTrue(repository.retrieve("نص", emptyList()).isEmpty())
    }

    @Test
    fun installingEmbeddingReindexesPendingDocuments() = runBlocking {
        val repository = repository()
        val document = imported(repository.import(source("معلق.txt", "نص ينتظر التضمين. " + "محتوى ".repeat(100))))
        assertEquals(RagDocumentStatus.EXTRACTED, document.status)

        backend.install(FakeEmbeddingProvider())

        val updated = repository.documents.value.first { it.id == document.id }
        assertEquals(RagDocumentStatus.READY, updated.status)
        assertEquals(0, repository.status.value.pendingCount)
        assertNotNull(repository.storeVectorsForTest(document.id))
    }

    @Test
    fun explicitReindexCompletesAPendingDocument() = runBlocking {
        val repository = repository()
        val document = imported(repository.import(source("معلق.txt", "نص ينتظر إعادة الفهرسة. " + "محتوى ".repeat(100))))
        backend.install(FakeEmbeddingProvider())

        // A fresh repository loads the ready index from disk; still call through the public path.
        val fresh = repository()
        assertTrue(fresh.reindex(document.id))
        assertFalse(fresh.reindex("d-missing"))
        assertEquals(RagDocumentStatus.READY, fresh.findDocument(document.id)!!.status)
    }

    @Test
    fun documentsSurviveRepositoryRestart() = runBlocking {
        backend.install(FakeEmbeddingProvider())
        val first = repository()
        imported(first.import(source("محفوظ.txt", "نص محفوظ على القرص. " + "تفاصيل ".repeat(100))))

        val second = repository()
        assertEquals(1, second.documents.value.size)
        assertEquals(RagDocumentStatus.READY, second.documents.value.first().status)
        assertEquals(1, second.status.value.readyCount)
    }

    @Test
    fun deleteRemovesDocumentAndItsIndex() = runBlocking {
        backend.install(FakeEmbeddingProvider())
        val repository = repository()
        val document = imported(repository.import(source("مؤقت.txt", "نص للحذف. " + "حذف ".repeat(100))))
        assertTrue(repository.delete(document.id))
        assertTrue(repository.documents.value.isEmpty())
        assertNull(repository.findDocument(document.id))
        assertFalse(repository.delete(document.id))
    }

    @Test
    fun displayNameIsSanitisedAndStored() = runBlocking {
        backend.install(FakeEmbeddingProvider())
        val repository = repository()
        val document = imported(repository.import(source("  تقرير   طويل.txt ", "محتوى " + "نص ".repeat(100))))
        assertEquals("تقرير طويل.txt", document.displayName)
    }
}

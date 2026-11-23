package app.sirrak

import app.sirrak.core.rag.DocumentFormats
import app.sirrak.core.rag.EmbeddingProvider
import app.sirrak.core.rag.EmbeddingStatus
import app.sirrak.core.rag.ExtractedDocument
import app.sirrak.core.rag.ExtractionOutcome
import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.ExtractorRegistry
import app.sirrak.core.rag.HybridRetriever
import app.sirrak.core.rag.RagChunk
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagDocumentStatus
import app.sirrak.core.rag.RagHit
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.rag.RagImportResult
import app.sirrak.core.rag.RagLimits
import app.sirrak.core.rag.RagService
import app.sirrak.core.rag.RagStatus
import app.sirrak.core.rag.TextChunker
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app-side implementation of the M8 RAG service: import -> extract -> chunk -> embed -> store ->
 * retrieve. Every expensive step runs on [io]; the public surface is suspend/StateFlow so the UI never
 * blocks and every screen sees the same readiness.
 *
 * Design rules that matter for later milestones:
 * - extraction and embedding are separate steps: a document imported before the embedding model exists
 *   is EXTRACTED (not lost), and installing the model automatically reindexes pending documents;
 * - the store is the source of truth; [documents] is a cache refreshed after each durable change, so a
 *   process death cannot leave the UI claiming an index that is not on disk;
 * - retrieval never invents vectors: if the model is missing, it returns no hits instead of pretending
 *   lexical matches are semantic ones (the BM25 path still runs when the query embedding fails);
 * - every rejection is a stable code ([RagImportRejection]) the UI localises, never a hardcoded message.
 */
class RagRepository(
    private val store: FileRagStore,
    private val embeddings: EmbeddingBackend,
    private val extractors: ExtractorRegistry,
    private val limits: RagLimits = RagLimits.Default,
    private val chunker: TextChunker = TextChunker(limits),
    private val retriever: HybridRetriever = HybridRetriever(limits),
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextId: () -> String = {
        "d-" + UUID.randomUUID().toString().replace("-", "").take(12)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    externalScope: CoroutineScope? = null,
) : RagService {

    private val ownJob = SupervisorJob()
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(ownJob + Dispatchers.Default)

    private val mutationLock = Mutex()
    private val cacheLock = Any()
    private val chunkCache = HashMap<String, List<RagChunk>>()
    private val vectorCache = HashMap<String, Map<String, FloatArray>>()

    private val _documents = MutableStateFlow<List<RagDocument>>(emptyList())
    override val documents: StateFlow<List<RagDocument>> = _documents

    private val _status = MutableStateFlow(RagStatus())
    override val status: StateFlow<RagStatus> = _status

    @Volatile
    private var indexing = false

    init {
        refreshFromStore()
        scope.launch {
            embeddings.status.collect { embedding ->
                publishStatus(embedding)
                if (embedding is EmbeddingStatus.Ready) reindexPendingDocuments()
            }
        }
    }

    /** Stops the readiness collector; the composition calls this when the app is going away. */
    fun close() {
        ownJob.cancel()
    }

    override fun findDocument(id: String): RagDocument? = store.metadata(id)

    override fun searchDocuments(query: String): List<RagDocument> {
        val needle = query.trim()
        if (needle.isEmpty()) return _documents.value
        val normalised = app.sirrak.core.rag.ArabicText.normalizeForMatch(needle)
        return _documents.value.filter { document ->
            document.displayName.contains(needle, ignoreCase = true) ||
                app.sirrak.core.rag.ArabicText.normalizeForMatch(document.displayName).contains(normalised)
        }
    }

    override fun chunks(documentId: String): List<RagChunk> = loadChunks(documentId)

    /** Test seam: the stored vectors for one document, or null when it is not indexed. */
    internal fun storeVectorsForTest(documentId: String): StoredVectors? = store.vectors(documentId)

    override suspend fun import(source: ExtractionSource): RagImportResult = withContext(io) {
        mutationLock.withLock { importLocked(source) }
    }

    override suspend fun delete(documentId: String): Boolean = withContext(io) {
        mutationLock.withLock {
            val deleted = store.delete(documentId)
            if (deleted) {
                invalidate(documentId)
                refreshFromStore()
            }
            deleted
        }
    }

    override suspend fun reindex(documentId: String): Boolean = withContext(io) {
        mutationLock.withLock { reindexLocked(documentId) }
    }

    override suspend fun retrieve(
        question: String,
        documentIds: List<String>,
        limits: RagLimits,
    ): List<RagHit> = withContext(io) {
        if (question.isBlank()) return@withContext emptyList()
        val scope = documentIds.toSet().takeIf { it.isNotEmpty() }
        val ready = _documents.value.filter { document ->
            document.isReady && (scope == null || document.id in scope)
        }
        if (ready.isEmpty()) return@withContext emptyList()
        val provider = openProvider() ?: return@withContext emptyList()
        val queryVector = embedQuery(provider, question)
        val chunks = ArrayList<RagChunk>()
        val vectors = HashMap<String, FloatArray>()
        for (document in ready) {
            chunks += loadChunks(document.id)
            store.vectors(document.id)?.byChunkId?.let { vectors.putAll(it) }
        }
        if (chunks.isEmpty()) return@withContext emptyList()
        val names = ready.associate { it.id to it.displayName }
        retriever.retrieve(
            question = question,
            chunks = chunks,
            vectors = vectors,
            queryVector = queryVector,
            documentNames = names,
            allowedDocumentIds = scope,
        )
    }

    // ---- import / reindex ---------------------------------------------------------------------

    private suspend fun importLocked(source: ExtractionSource): RagImportResult {
        if (source.sizeBytes == 0L) return RagImportResult.Rejected(RagImportRejection.EMPTY)
        if (source.sizeBytes > limits.maxDocumentBytes) {
            return RagImportResult.Rejected(RagImportRejection.TOO_LARGE)
        }
        val format = extractors.detect(source)
            ?: return RagImportResult.Rejected(RagImportRejection.UNSUPPORTED_TYPE)
        if (!extractors.supports(source)) {
            return RagImportResult.Rejected(RagImportRejection.UNSUPPORTED_TYPE)
        }
        val id = uniqueDocumentId()
        val extension = format.extensions.firstOrNull() ?: "bin"
        val storedBytes = try {
            store.writeOriginal(id, extension, source.openStream, limits.maxDocumentBytes)
        } catch (tooLarge: RagTooLargeException) {
            store.delete(id)
            return RagImportResult.Rejected(RagImportRejection.TOO_LARGE)
        } catch (error: Exception) {
            store.delete(id)
            return RagImportResult.Rejected(RagImportRejection.STORAGE)
        }
        val fileSource = storedSource(
            documentId = id,
            displayName = source.displayName,
            formatId = format.id,
        )
            ?: run {
                store.delete(id)
                return RagImportResult.Rejected(RagImportRejection.STORAGE)
            }
        val extracted = when (val outcome = extractors.extract(fileSource)) {
            is ExtractionOutcome.Rejected -> {
                store.delete(id)
                return RagImportResult.Rejected(outcome.reason)
            }
            is ExtractionOutcome.Extracted -> outcome.document
        }
        val chunking = chunker.chunk(id, extracted.pages)
        if (chunking.chunks.isEmpty()) {
            store.delete(id)
            return RagImportResult.Rejected(RagImportRejection.NO_TEXT)
        }
        var document = RagDocument(
            id = id,
            displayName = sanitiseDisplayName(source.displayName),
            formatId = format.id,
            sizeBytes = storedBytes,
            importedAt = clock(),
            pageCount = extracted.pages.size,
            chunkCount = chunking.chunks.size,
            status = RagDocumentStatus.EXTRACTED,
        )
        val indexed = indexChunks(document, chunking.chunks)
        document = indexed.document
        try {
            store.writeIndex(document, chunking.chunks, indexed.vectors, indexed.dimensions)
        } catch (error: Exception) {
            store.delete(id)
            return RagImportResult.Rejected(RagImportRejection.STORAGE)
        }
        invalidate(id)
        refreshFromStore()
        return RagImportResult.Imported(document)
    }

    private suspend fun reindexLocked(documentId: String): Boolean {
        val document = store.metadata(documentId) ?: return false
        val source = storedSource(
            documentId = documentId,
            displayName = document.displayName,
            formatId = document.formatId,
        ) ?: return false
        val extracted = when (val outcome = extractors.extract(source)) {
            is ExtractionOutcome.Rejected -> {
                store.writeMetadata(
                    document.copy(status = RagDocumentStatus.FAILED, failureReason = outcome.reason.name),
                )
                refreshFromStore()
                return false
            }
            is ExtractionOutcome.Extracted -> outcome.document
        }
        val chunking = chunker.chunk(documentId, extracted.pages)
        if (chunking.chunks.isEmpty()) return false
        val indexed = indexChunks(
            document.copy(
                pageCount = extracted.pages.size,
                chunkCount = chunking.chunks.size,
                status = RagDocumentStatus.EXTRACTED,
                failureReason = null,
            ),
            chunking.chunks,
        )
        store.writeIndex(indexed.document, chunking.chunks, indexed.vectors, indexed.dimensions)
        invalidate(documentId)
        refreshFromStore()
        return true
    }

    /** Called on the embedding Ready transition; pending EXTRACTED documents become READY. */
    private suspend fun reindexPendingDocuments() {
        if (indexing) return
        val pending = _documents.value.filter { it.status == RagDocumentStatus.EXTRACTED }
        if (pending.isEmpty()) return
        indexing = true
        publishStatus(embeddings.status.value)
        try {
            for (document in pending) {
                mutationLock.withLock { reindexLocked(document.id) }
            }
        } finally {
            indexing = false
            publishStatus(embeddings.status.value)
        }
    }

    /** Runs one document's chunks through the embedding model and attaches provenance/errors. */
    private suspend fun indexChunks(document: RagDocument, chunks: List<RagChunk>): IndexOutcome {
        val provider = openProvider()
            ?: return IndexOutcome(
                document.copy(
                    status = RagDocumentStatus.EXTRACTED,
                    failureReason = REASON_EMBEDDING_MISSING,
                ),
                vectors = null,
                dimensions = 0,
            )
        val vectors = try {
            embedChunks(provider, chunks)
        } catch (error: Exception) {
            return IndexOutcome(
                document.copy(
                    status = RagDocumentStatus.EXTRACTED,
                    failureReason = REASON_INDEX_FAILED,
                ),
                vectors = null,
                dimensions = 0,
            )
        }
        return IndexOutcome(
            document.copy(
                status = RagDocumentStatus.READY,
                indexedAt = clock(),
                embeddingModelId = provider.modelId,
                failureReason = null,
            ),
            vectors = vectors,
            dimensions = provider.dimensions,
        )
    }

    private suspend fun embedChunks(
        provider: EmbeddingProvider,
        chunks: List<RagChunk>,
    ): Map<String, FloatArray> {
        val vectors = LinkedHashMap<String, FloatArray>(chunks.size)
        for (batch in chunks.chunked(limits.embeddingBatchSize.coerceAtLeast(1))) {
            val inputs = batch.map { chunk -> chunk.text.take(limits.maxEmbeddingChars) }
            val embedded = provider.embed(inputs)
            if (embedded.size != inputs.size) throw IllegalStateException("embedding count mismatch")
            batch.forEachIndexed { index, chunk ->
                val vector = embedded[index]
                if (vector.size != provider.dimensions) {
                    throw IllegalStateException("embedding dimension mismatch")
                }
                vectors[chunk.id] = vector
            }
        }
        return vectors
    }

    private suspend fun openProvider(): EmbeddingProvider? = try {
        embeddings.provider()
    } catch (error: Exception) {
        null
    }

    private suspend fun embedQuery(provider: EmbeddingProvider, question: String): FloatArray? = try {
        provider.embed(listOf(question)).firstOrNull()?.takeIf { it.size == provider.dimensions }
    } catch (error: Exception) {
        null
    }

    /**
     * Re-opens a stored original for extraction. The format is carried by the document metadata, not
     * by the original picker source, so reindexing a document after process death (when the picker
     * source is long gone) reads the right MIME and extension again.
     */
    private fun storedSource(
        documentId: String,
        displayName: String,
        formatId: String,
    ): ExtractionSource? {
        val size = store.originalSize(documentId)
        if (size <= 0L) return null
        val format = DocumentFormats.all.firstOrNull { it.id == formatId }
        return ExtractionSource(
            displayName = displayName,
            mimeType = format?.mimeTypes?.firstOrNull(),
            sizeBytes = size,
        ) {
            store.originalStream(documentId) ?: throw IOException("stored original is missing")
        }
    }

    // ---- cached reads --------------------------------------------------------------------------

    private fun loadChunks(documentId: String): List<RagChunk> {
        synchronized(cacheLock) { chunkCache[documentId] }?.let { return it }
        val chunks = store.chunks(documentId)
        synchronized(cacheLock) { chunkCache[documentId] = chunks }
        return chunks
    }

    @Suppress("unused")
    private fun loadVectors(documentId: String): Map<String, FloatArray>? {
        synchronized(cacheLock) { vectorCache[documentId] }?.let { return it }
        val stored = store.vectors(documentId)?.byChunkId ?: return null
        synchronized(cacheLock) { vectorCache[documentId] = stored }
        return stored
    }

    private fun invalidate(documentId: String) {
        synchronized(cacheLock) {
            chunkCache.remove(documentId)
            vectorCache.remove(documentId)
        }
    }

    // ---- state --------------------------------------------------------------------------------

    private fun refreshFromStore() {
        val loaded = store.list().map { document ->
            // INDEXING is a live state; a process death returns the document to EXTRACTED.
            if (document.status == RagDocumentStatus.INDEXING) {
                document.copy(status = RagDocumentStatus.EXTRACTED)
            } else {
                document
            }
        }
        _documents.value = loaded
        synchronized(cacheLock) {
            chunkCache.clear()
            vectorCache.clear()
        }
        publishStatus(embeddings.status.value)
    }

    private fun publishStatus(embedding: EmbeddingStatus) {
        val documents = _documents.value
        _status.value = RagStatus(
            documentCount = documents.size,
            readyCount = documents.count { it.isReady },
            pendingCount = documents.count { it.status == RagDocumentStatus.EXTRACTED },
            embedding = embedding,
            indexing = indexing,
        )
    }

    private fun uniqueDocumentId(): String {
        repeat(100) {
            val candidate = nextId()
            if (store.metadata(candidate) == null && _documents.value.none { it.id == candidate }) {
                return candidate
            }
        }
        return "d-" + UUID.randomUUID().toString().replace("-", "").take(12)
    }

    private fun sanitiseDisplayName(name: String): String {
        val trimmed = name.trim().replace(Regex("\\s+"), " ")
        return trimmed.take(MAX_DISPLAY_NAME).ifEmpty { "document" }
    }

    private data class IndexOutcome(
        val document: RagDocument,
        val vectors: Map<String, FloatArray>?,
        val dimensions: Int,
    )

    companion object {
        const val REASON_EMBEDDING_MISSING = "rag_embedding_missing"
        const val REASON_INDEX_FAILED = "rag_index_failed"
        private const val MAX_DISPLAY_NAME = 120
    }
}

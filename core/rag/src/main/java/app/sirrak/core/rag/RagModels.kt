package app.sirrak.core.rag

import kotlinx.coroutines.flow.StateFlow

/**
 * The M8 document-RAG contracts. Everything in this file is Android-free: the extraction, chunking,
 * retrieval and prompt layers are pure Kotlin so they run identically on device and in JVM tests.
 *
 * The single seam that matters: a document is imported once into a library that owns its bytes and
 * index; a conversation only references document ids, so the same document can ground many chats and
 * deleting it is one cascade that cannot leave a transcript pointing at nothing.
 */

/** Lifecycle of one imported document. [INDEXING] is a live state and is never persisted. */
enum class RagDocumentStatus {
    /** Text and chunks are extracted; vectors are missing because no embedding model is available. */
    EXTRACTED,

    /** Vectors are being computed right now (in-memory only; a process death restarts as EXTRACTED). */
    INDEXING,

    /** Extraction and vectors are complete: the document can ground answers. */
    READY,

    /** Extraction or indexing failed; [RagDocument.failureReason] carries the localisable code. */
    FAILED,
}

/**
 * One document in the local RAG library. Metadata only: page text, chunks and vectors live in the
 * app-owned store, never in the transcript. [formatId] is a stable [DocumentFormats] id, not an enum
 * ordinal, so adding or reordering formats cannot silently re-label saved documents.
 */
data class RagDocument(
    val id: String,
    val displayName: String,
    val formatId: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val pageCount: Int,
    val chunkCount: Int,
    val status: RagDocumentStatus,
    val indexedAt: Long? = null,
    val embeddingModelId: String? = null,
    val failureReason: String? = null,
) {
    val isReady: Boolean get() = status == RagDocumentStatus.READY
}

/**
 * One retrieval unit. [text] is the original (display) text; [normalized] is the Arabic-aware matching
 * form (diacritics/tatweel removed, alef/ya/ta-marbuta unified, digits to ASCII). Keeping both means
 * BM25 runs on stable tokens while the model and the UI receive readable text.
 *
 * [page] is 1-based for paged formats and 1 for flow formats (TXT/MD); [heading] is the closest detected
 * heading/paragraph label, used only for citation display.
 */
data class RagChunk(
    val id: String,
    val documentId: String,
    val ordinal: Int,
    val text: String,
    val page: Int,
    val heading: String? = null,
    val normalized: String = ArabicText.normalizeForMatch(text),
) {
    val charCount: Int get() = text.length
}

/** One retrieval result: the chunk plus every component score, so evaluation can explain a rank. */
data class RagHit(
    val documentId: String,
    val documentName: String,
    val chunk: RagChunk,
    val rank: Int,
    val fusedScore: Float,
    val vectorScore: Float = 0f,
    val lexicalScore: Float = 0f,
)

/** A numbered source in a grounded prompt; the model cites by [number]. */
data class RagSource(
    val number: Int,
    val documentId: String,
    val documentName: String,
    val chunkId: String,
    val page: Int,
    val snippet: String,
)

/** The prompt plus the sources it numbered, kept together so citation parsing uses the same numbers. */
data class RagPromptResult(
    val prompt: String,
    val sources: List<RagSource>,
    val truncated: Boolean,
)

/** One citation parsed out of a generated answer. */
data class RagCitation(
    val documentId: String,
    val chunkId: String,
    val documentName: String,
    val page: Int,
    val snippet: String,
)

/**
 * Tuning limits as data, not constants scattered through the pipeline (founder rule: reusable,
 * expandable, editable). Callers may pass a modified copy; the defaults are the M8 acceptance values
 * measured in doc 22. Sizes are characters for chunking (language-neutral) and cosine for retrieval.
 */
data class RagLimits(
    val maxDocumentBytes: Long = 50L * 1024 * 1024,
    val maxPages: Int = 500,
    val targetChunkChars: Int = 700,
    val maxChunkChars: Int = 1100,
    val overlapChars: Int = 120,
    val minChunkChars: Int = 80,
    val maxChunksPerDocument: Int = 400,
    val queryTopK: Int = 8,
    val contextTopK: Int = 3,
    val contextMaxChars: Int = 1600,
    val rrfK: Int = 60,
    val embeddingBatchSize: Int = 8,
    val maxEmbeddingChars: Int = 2000,
) {
    init {
        require(targetChunkChars > 0) { "targetChunkChars must be positive" }
        require(maxChunkChars >= targetChunkChars) { "maxChunkChars must be >= targetChunkChars" }
        require(overlapChars >= 0 && overlapChars < targetChunkChars) {
            "overlapChars must be in [0, targetChunkChars)"
        }
        require(queryTopK > 0 && contextTopK > 0) { "retrieval windows must be positive" }
        require(contextTopK <= queryTopK) { "contextTopK cannot exceed queryTopK" }
    }

    companion object {
        val Default = RagLimits()
    }
}

/** Why an import was refused; the app maps each code to a localised message. */
enum class RagImportRejection {
    EMPTY,
    TOO_LARGE,
    UNSUPPORTED_TYPE,
    CORRUPT,
    ENCRYPTED,
    NO_TEXT,
    TOO_MANY_PAGES,
    STORAGE,
    EMBEDDING_UNAVAILABLE,
    INDEX_FAILED,
}

/** Outcome of importing one source into the library. */
sealed interface RagImportResult {
    data class Imported(val document: RagDocument) : RagImportResult
    data class Rejected(val reason: RagImportRejection) : RagImportResult
}

/** Where a queried answer comes from; a UI sheet can show the excerpt without re-reading the file. */
data class RagCitationHit(
    val citation: RagCitation,
    val snippet: String,
)

/** Observable embedding-engine state. Codes are localised by the app, never baked English copy. */
sealed interface EmbeddingStatus {
    data object Missing : EmbeddingStatus
    data class Loading(val modelName: String? = null) : EmbeddingStatus
    data class Ready(val modelName: String, val dimensions: Int) : EmbeddingStatus
    data class Failed(val reason: String) : EmbeddingStatus
}

/** Aggregate RAG state the UI reads: library counts plus embedding readiness. */
data class RagStatus(
    val documentCount: Int = 0,
    val readyCount: Int = 0,
    val pendingCount: Int = 0,
    val embedding: EmbeddingStatus = EmbeddingStatus.Missing,
    val indexing: Boolean = false,
) {
    /** Documents can be attached when an embedding backend exists; indexing may still be running. */
    val documentsAvailable: Boolean get() = embedding is EmbeddingStatus.Ready
}

/**
 * The one embedding seam. Implementations may wrap LiteRT-LM (product) or a deterministic fake in
 * tests; retrieval never names a concrete engine. [embed] is blocking on purpose: the caller decides
 * which dispatcher a 270M-parameter forward pass runs on.
 */
interface EmbeddingProvider {
    val modelId: String
    val dimensions: Int

    /** Embeds one batch in order; returns one vector per input, all with [dimensions] entries. */
    fun embed(texts: List<String>): List<FloatArray>

    fun close()
}

/** Opens an embedding backend from a model file path; the engine module implements this. */
interface EmbeddingProviderFactory {
    fun open(modelPath: String): EmbeddingProvider
}

/**
 * The RAG library seam consumed by the app wiring (settings/library UI). Implementations own storage,
 * indexing and deletion; callers never see files or vectors.
 */
interface RagLibrary {
    val documents: StateFlow<List<RagDocument>>
    val status: StateFlow<RagStatus>

    fun findDocument(id: String): RagDocument?

    /** Imports (extracts + indexes when an embedding model is ready). Duplicates are allowed. */
    suspend fun import(source: ExtractionSource): RagImportResult

    suspend fun delete(documentId: String): Boolean

    /** Re-extracts and re-embeds one document; used after an embedding-model change. */
    suspend fun reindex(documentId: String): Boolean

    fun chunks(documentId: String): List<RagChunk>

    fun searchDocuments(query: String): List<RagDocument>
}

/**
 * The retrieval seam the chat feature depends on. [documentIds] is the conversation's scope; empty
 * means "the whole library" (RAG-6 cross-document ask).
 */
interface RagRetriever {
    suspend fun retrieve(
        question: String,
        documentIds: List<String> = emptyList(),
        limits: RagLimits = RagLimits.Default,
    ): List<RagHit>
}

/** Everything the app composes: one object for library + retrieval + readiness. */
interface RagService : RagLibrary, RagRetriever

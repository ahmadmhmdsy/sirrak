package app.sirrak

import app.sirrak.core.rag.RagChunk
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagDocumentStatus
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Raised when an import is larger than the configured document ceiling. */
class RagTooLargeException : Exception("document exceeds the configured size limit")

/** Raised when local storage cannot complete a write; the repository maps it to STORAGE. */
class RagStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The M8 document store: app-private files, one directory per document.
 *
 * Layout under filesDir/rag:
 *   documents/<id>/metadata.json   document facts + lifecycle status (written last, atomically)
 *   documents/<id>/original.<ext>  the exact imported bytes (re-index works after a restart)
 *   documents/<id>/chunks.json     extracted chunks, ordinal order
 *   documents/<id>/vectors.bin     float vectors in ordinal order, with a header
 *
 * Rules:
 * - every metadata write is atomic (temp + rename), so a kill mid-write leaves the previous document;
 * - a document directory without metadata is an interrupted import and is ignored by [list];
 * - deleting resolves the document directory inside the store root only (no path traversal);
 * - vectors are optional: extraction can succeed while embedding is unavailable, and a later reindex
 *   adds vectors. Writing an EXTRACTED index removes stale vectors rather than serving old numbers.
 *
 * Pure JVM: the constructor takes a directory and there is no Context, so tests exercise the real
 * filesystem deterministically. The app wires filesDir; nothing here knows about Android.
 */
class FileRagStore(rootDir: File) {

    private val root = File(rootDir, ROOT_DIR)
    private val documentsDir = File(root, DOCUMENTS_DIR)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        documentsDir.mkdirs()
    }

    /** Newest import first; interrupted imports (no metadata) are skipped. */
    fun list(): List<RagDocument> {
        val dirs = documentsDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> readMetadata(dir.name) }.sortedByDescending { it.importedAt }
    }

    fun metadata(id: String): RagDocument? = readMetadata(id)

    /** Persists only the mutable document facts (status, counts, index timestamp). */
    fun writeMetadata(document: RagDocument) {
        val dir = requireDocumentDir(document.id)
        dir.mkdirs()
        writeAtomically(File(dir, METADATA_FILE), json.encodeToString(document.toDto()))
    }

    /**
     * Copies the imported bytes into the document directory, enforcing [maxBytes] while streaming.
     * Returns the byte count actually written. The original is written before extraction so a later
     * reindex never depends on the user's picker still being reachable.
     */
    fun writeOriginal(
        id: String,
        extension: String,
        openStream: () -> InputStream,
        maxBytes: Long,
    ): Long {
        val dir = requireDocumentDir(id)
        dir.mkdirs()
        val target = File(dir, ORIGINAL_STEM + "." + sanitiseExtension(extension))
        val temp = File(dir, target.name + TEMP_SUFFIX)
        try {
            var total = 0L
            BufferedInputStream(openStream()).use { input ->
                BufferedOutputStream(temp.outputStream()).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw RagTooLargeException()
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total <= 0L) throw RagStorageException("the source stream produced no bytes")
            if (!replace(temp, target)) throw RagStorageException("could not move the imported file into place")
            return total
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    /** Re-opens the stored original for reindexing; null when the file is missing. */
    fun originalStream(id: String): InputStream? {
        val dir = documentDirOrNull(id) ?: return null
        val original = dir.listFiles { file -> file.isFile && file.name.startsWith(ORIGINAL_STEM + ".") }
            ?.firstOrNull() ?: return null
        return try {
            original.inputStream()
        } catch (error: FileNotFoundException) {
            null
        }
    }

    /**
     * Writes the chunk list, optional vectors and then the metadata (the commit point). A null vector
     * map removes any stale vectors, so a document downgraded to EXTRACTED cannot keep old embeddings.
     */
    fun writeIndex(
        document: RagDocument,
        chunks: List<RagChunk>,
        vectors: Map<String, FloatArray>?,
        dimensions: Int,
    ) {
        val dir = requireDocumentDir(document.id)
        dir.mkdirs()
        writeAtomically(File(dir, CHUNKS_FILE), json.encodeToString(chunks.map { it.toDto() }))
        val vectorsFile = File(dir, VECTORS_FILE)
        if (vectors == null || vectors.isEmpty()) {
            vectorsFile.delete()
        } else {
            writeVectorsAtomically(vectorsFile, chunks, vectors, dimensions)
        }
        writeMetadata(document)
    }

    fun chunks(id: String): List<RagChunk> {
        val dir = documentDirOrNull(id) ?: return emptyList()
        val file = File(dir, CHUNKS_FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChunkDto>>(file.readText()).map { it.toChunk(id) }
        }.getOrElse { emptyList() }
    }

    /** Size of the stored original in bytes; 0 when it is missing. */
    fun originalSize(id: String): Long {
        val dir = documentDirOrNull(id) ?: return 0L
        return dir.listFiles { file -> file.isFile && file.name.startsWith(ORIGINAL_STEM + ".") }
            ?.firstOrNull()?.length() ?: 0L
    }

    /**
     * Reads vectors in chunk order together with the dimensions they were produced with. Returns null
     * when the file is absent or the chunk count no longer matches, so a stale or corrupt index
     * degrades to "not indexed" instead of producing meaningless similarity numbers.
     */
    fun vectors(id: String): StoredVectors? {
        val dir = documentDirOrNull(id) ?: return null
        val chunks = chunks(id)
        if (chunks.isEmpty()) return null
        val file = File(dir, VECTORS_FILE)
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != VECTORS_MAGIC) return null
                if (input.readInt() != VECTORS_VERSION) return null
                val dimensions = input.readInt()
                val count = input.readInt()
                if (dimensions <= 0 || dimensions > MAX_VECTOR_DIMENSIONS || count != chunks.size) return null
                val result = LinkedHashMap<String, FloatArray>(count)
                for (index in 0 until count) {
                    val vector = FloatArray(dimensions)
                    for (position in 0 until dimensions) vector[position] = input.readFloat()
                    result[chunks[index].id] = vector
                }
                if (input.read() != -1) return null
                StoredVectors(dimensions, result)
            }
        } catch (error: Throwable) {
            null
        }
    }

    /** Removes one document and its bytes; the directory is confined to this store's root. */
    fun delete(id: String): Boolean {
        val dir = documentDirOrNull(id) ?: return false
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    private fun readMetadata(id: String): RagDocument? {
        val dir = documentDirOrNull(id) ?: return null
        val file = File(dir, METADATA_FILE)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<DocumentDto>(file.readText()).toDocument()
        }.getOrNull()
    }

    private fun documentDirOrNull(id: String): File? {
        if (!ID_PATTERN.matches(id)) return null
        val dir = File(documentsDir, id)
        val canonicalRoot = documentsDir.canonicalFile
        val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        if (canonicalDir.parentFile != canonicalRoot) return null
        return canonicalDir
    }

    private fun requireDocumentDir(id: String): File =
        documentDirOrNull(id) ?: throw RagStorageException("invalid document id")

    private fun writeVectorsAtomically(
        target: File,
        chunks: List<RagChunk>,
        vectors: Map<String, FloatArray>,
        dimensions: Int,
    ) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            DataOutputStream(BufferedOutputStream(temp.outputStream())).use { output ->
                output.writeInt(VECTORS_MAGIC)
                output.writeInt(VECTORS_VERSION)
                output.writeInt(dimensions)
                output.writeInt(chunks.size)
                for (chunk in chunks) {
                    val vector = vectors[chunk.id]
                        ?: throw RagStorageException("missing vector for chunk " + chunk.id)
                    if (vector.size != dimensions) {
                        throw RagStorageException("vector dimension mismatch for chunk " + chunk.id)
                    }
                    for (value in vector) output.writeFloat(value)
                }
            }
            if (!replace(temp, target)) throw RagStorageException("could not commit the vector index")
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            temp.parentFile?.mkdirs()
            temp.writeText(content)
            if (!replace(temp, target)) throw RagStorageException("could not commit " + target.name)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    /** Rename is atomic on the same filesystem; fall back to delete+rename for stubborn files. */
    private fun replace(temp: File, target: File): Boolean {
        if (temp.renameTo(target)) return true
        target.delete()
        return temp.renameTo(target)
    }

    private fun sanitiseExtension(extension: String): String {
        val lowered = extension.lowercase().filter { it.isLetterOrDigit() }
        return lowered.takeIf { it.isNotEmpty() } ?: "bin"
    }

    private fun RagDocument.toDto(): DocumentDto = DocumentDto(
        id = id,
        displayName = displayName,
        formatId = formatId,
        sizeBytes = sizeBytes,
        importedAt = importedAt,
        pageCount = pageCount,
        chunkCount = chunkCount,
        status = status.name,
        indexedAt = indexedAt,
        embeddingModelId = embeddingModelId,
        failureReason = failureReason,
    )

    private fun RagChunk.toDto(): ChunkDto = ChunkDto(
        id = id,
        ordinal = ordinal,
        text = text,
        page = page,
        heading = heading,
    )

    // ---- serialised shapes -------------------------------------------------------------------

    @Serializable
    private data class DocumentDto(
        val id: String,
        val displayName: String,
        val formatId: String,
        val sizeBytes: Long,
        val importedAt: Long,
        val pageCount: Int,
        val chunkCount: Int,
        val status: String,
        val indexedAt: Long? = null,
        val embeddingModelId: String? = null,
        val failureReason: String? = null,
    ) {
        fun toDocument(): RagDocument = RagDocument(
            id = id,
            displayName = displayName,
            formatId = formatId,
            sizeBytes = sizeBytes,
            importedAt = importedAt,
            pageCount = pageCount,
            chunkCount = chunkCount,
            status = RagDocumentStatus.entries.firstOrNull { it.name == status }
                ?: RagDocumentStatus.EXTRACTED,
            indexedAt = indexedAt,
            embeddingModelId = embeddingModelId,
            failureReason = failureReason,
        )
    }

    @Serializable
    private data class ChunkDto(
        val id: String,
        val ordinal: Int,
        val text: String,
        val page: Int,
        val heading: String? = null,
    ) {
        fun toChunk(documentId: String): RagChunk = RagChunk(
            id = id,
            documentId = documentId,
            ordinal = ordinal,
            text = text,
            page = page,
            heading = heading,
        )
    }

    companion object {
        internal const val ROOT_DIR = "rag"
        internal const val DOCUMENTS_DIR = "documents"
        internal const val METADATA_FILE = "metadata.json"
        internal const val CHUNKS_FILE = "chunks.json"
        internal const val VECTORS_FILE = "vectors.bin"
        internal const val ORIGINAL_STEM = "original"
        private const val TEMP_SUFFIX = ".tmp"
        private const val COPY_BUFFER = 16 * 1024
        private const val VECTORS_MAGIC = 0x52414756
        private const val VECTORS_VERSION = 1
        private const val MAX_VECTOR_DIMENSIONS = 4096
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** One document's vectors plus the embedding dimensions they were produced with. */
data class StoredVectors(val dimensions: Int, val byChunkId: Map<String, FloatArray>)

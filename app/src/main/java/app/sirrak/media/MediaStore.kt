package app.sirrak.media

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Raised when an import is larger than [MediaLimits.MAX_IMPORT_BYTES]; the library maps it to a code. */
class MediaTooLargeException(val limitBytes: Long) :
    Exception("media import exceeds the configured size limit")

/** Raised when local storage cannot complete a write; the library maps it to a code. */
class MediaStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One stored file inside the media root: a relative name (never an absolute path) plus real bytes. */
data class MediaFile(val fileName: String, val sizeBytes: Long)

/** The whole persisted shelf: records newest first plus the remembered policy. */
data class MediaSnapshot(
    val policy: MediaPolicy = MediaPolicy(),
    val records: List<MediaRecord> = emptyList(),
)

/**
 * The P5 media store: app-private files plus one JSON index, and nothing else.
 *
 * Layout under filesDir/media:
 *   index.json            policy + record metadata, written last and atomically
 *   files/<id>.<ext>      the exact imported bytes
 *   files/<id>.transcript.txt   the on-device transcript for an audio item
 *
 * Rules, in the same spirit as FileRagStore:
 *  - a record without its file is an interrupted import: it is dropped, never shown as a broken row;
 *  - a file without a record is an interrupted import too: it is removed on the next open;
 *  - every write is temp + rename, so a kill mid-write leaves the previous index intact;
 *  - replacing an id deletes that id's old files first, so a retried import cannot leave two copies;
 *  - file names are validated against the store root, so persisted metadata can never point outside it.
 *
 * Pure JVM: the constructor takes a directory and there is no Context, so tests exercise the real
 * filesystem deterministically. The Android layer only passes filesDir.
 */
class FileMediaStore(rootDir: File) {

    private val root = File(rootDir, ROOT_DIR)
    private val filesDir = File(root, FILES_DIR)
    private val indexFile = File(root, INDEX_FILE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        filesDir.mkdirs()
    }

    /**
     * Reads the shelf. A record whose file is gone is dropped; an unreferenced file is removed. When
     * the index exists but cannot be parsed the files are left alone (a corrupt index must not delete
     * the user's documents) and an empty shelf is reported.
     */
    fun snapshot(): MediaSnapshot {
        val loaded = readIndex()
        val known = linkedMapOf<String, MediaRecord>()
        loaded.records.forEach { record ->
            if (record.id.isBlank()) return@forEach
            if (resolve(record.fileName) == null) return@forEach
            known.putIfAbsent(record.id, record)
        }
        val records = known.values.sortedByDescending { it.createdAt }
        // A missing index means the shelf is genuinely empty, so stray files are interrupted imports
        // and are removed. A corrupt index is different: those files are the user's, and deleting them
        // because the metadata broke would be data loss, so they are kept for recovery.
        if (!loaded.corrupt) {
            pruneOrphanFiles(records)
        }
        return MediaSnapshot(policy = loaded.policy, records = records)
    }

    /** Persists the whole shelf atomically. Callers own ordering and policy; this file owns durability. */
    fun write(snapshot: MediaSnapshot) {
        root.mkdirs()
        writeAtomically(indexFile, json.encodeToString(snapshot.toDto()))
    }

    /**
     * Copies one picked stream into the shelf, enforcing [maxBytes] while streaming. An existing id's
     * files are removed first, so a retried import replaces rather than accumulates.
     */
    fun writeMedia(
        id: String,
        displayName: String,
        maxBytes: Long = MediaLimits.MAX_IMPORT_BYTES,
        open: () -> InputStream,
    ): MediaFile {
        val target = File(filesDir, id + "." + extensionOf(displayName))
        deleteFilesForId(id)
        val temp = File(filesDir, target.name + TEMP_SUFFIX)
        var total = 0L
        try {
            BufferedInputStream(open()).use { input ->
                BufferedOutputStream(temp.outputStream()).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw MediaTooLargeException(maxBytes)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total <= 0L) throw MediaStorageException("the source stream produced no bytes")
            if (!replace(temp, target)) {
                throw MediaStorageException("could not move the imported file into place")
            }
            return MediaFile(fileName = target.name, sizeBytes = total)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    /** Writes an audio item's transcript as UTF-8 text next to its source file. */
    fun writeTranscript(id: String, text: String): MediaFile {
        val target = File(filesDir, id + TRANSCRIPT_SUFFIX)
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeAtomically(target, bytes)
        return MediaFile(fileName = target.name, sizeBytes = bytes.size.toLong())
    }

    /** The stored bytes for [record]; null when the file is missing or the name escapes the root. */
    fun fileOf(record: MediaRecord): File? = resolve(record.fileName)

    /** The stored transcript for [record]; null when the item has none. */
    fun transcriptOf(record: MediaRecord): File? =
        record.transcriptFileName?.let { resolve(it) }

    /** Removes every stored file of one record (idempotent; a missing file is not an error). */
    fun deleteFiles(record: MediaRecord) {
        deleteFilesForId(record.id)
    }

    /** Bytes actually occupied by the shelf on disk, transcripts included. */
    fun storageBytes(): Long =
        filesDir.listFiles()?.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }
            ?.sumOf { it.length() } ?: 0L

    // ---------------------------------------------------------------------------------------------

    private data class LoadedIndex(
        val policy: MediaPolicy,
        val records: List<MediaRecord>,
        /** The index exists but could not be parsed: keep the files untouched so nothing is lost. */
        val corrupt: Boolean,
    )

    private fun readIndex(): LoadedIndex {
        if (!indexFile.isFile) return LoadedIndex(MediaPolicy.Default, emptyList(), corrupt = false)
        return try {
            val dto = json.decodeFromString<SnapshotDto>(indexFile.readText(Charsets.UTF_8))
            LoadedIndex(policy = dto.toPolicy(), records = dto.records.mapNotNull { it.toRecord() }, corrupt = false)
        } catch (error: Throwable) {
            // Keep the unreadable file for diagnosis; never delete user files on a parse failure.
            runCatching { indexFile.renameTo(File(root, INDEX_FILE + ".corrupt")) }
            LoadedIndex(MediaPolicy.Default, emptyList(), corrupt = true)
        }
    }

    /** Deletes stored files no surviving record references; interrupted imports are not shelf items. */
    private fun pruneOrphanFiles(records: List<MediaRecord>) {
        val referenced = HashSet<String>()
        records.forEach { record ->
            referenced += record.fileName
            record.transcriptFileName?.let { referenced += it }
        }
        filesDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.endsWith(TEMP_SUFFIX) || file.name !in referenced) {
                file.delete()
            }
        }
    }

    private fun deleteFilesForId(id: String) {
        if (!isSafeName(id)) return
        filesDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name == id || file.name.startsWith(id + "."))) file.delete()
        }
    }

    /** Resolves a persisted relative name inside the file directory only (no traversal, no absolute). */
    private fun resolve(fileName: String): File? {
        if (!isSafeName(fileName)) return null
        val resolved = File(filesDir, fileName)
        return resolved.takeIf { it.isFile }
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..") &&
            name != TEMP_SUFFIX

    private fun extensionOf(displayName: String): String {
        val raw = displayName.substringAfterLast('.', "").lowercase()
        val cleaned = raw.filter { it in 'a'..'z' || it in '0'..'9' }
        return cleaned.take(MAX_EXTENSION).ifEmpty { DEFAULT_EXTENSION }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            temp.outputStream().use { it.write(bytes) }
            if (!replace(temp, target)) throw MediaStorageException("could not write " + target.name)
        } catch (error: Throwable) {
            temp.delete()
            throw if (error is MediaStorageException) error else MediaStorageException(
                "could not write " + target.name,
                error,
            )
        }
    }

    private fun writeAtomically(target: File, text: String) =
        writeAtomically(target, text.toByteArray(Charsets.UTF_8))

    private fun replace(temp: File, target: File): Boolean {
        if (java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            ) != null
        ) {
            return true
        }
        return temp.renameTo(target)
    }

    // --- JSON DTOs ------------------------------------------------------------------------------

    @Serializable
    private data class SnapshotDto(
        val version: Int = 1,
        val defaultRetention: String = "KEEP",
        val defaultAccess: String = "GRANTED",
        val records: List<RecordDto> = emptyList(),
    ) {
        fun toPolicy(): MediaPolicy = MediaPolicy(
            defaultRetention = defaultRetention.toRetention(),
            defaultAccess = defaultAccess.toAccess(),
        )
    }

    @Serializable
    private data class RecordDto(
        val id: String,
        val kind: String,
        val displayName: String,
        val sizeBytes: Long,
        val retention: String,
        val access: String,
        val createdAt: Long,
        val fileName: String,
        val mimeType: String? = null,
        val transcriptStatus: String = "NOT_APPLICABLE",
        val transcriptFileName: String? = null,
        val transcriptFailure: String? = null,
        val indexedDocumentId: String? = null,
        val conversationId: String? = null,
        val consumedAt: Long? = null,
    ) {
        /** Returns null for a record that cannot be repaired (unknown kind or no file name). */
        fun toRecord(): MediaRecord? {
            if (id.isBlank() || fileName.isBlank()) return null
            return MediaRecord(
                id = id,
                kind = kind.toKind() ?: return null,
                displayName = displayName.ifBlank { fileName },
                sizeBytes = sizeBytes.coerceAtLeast(0L),
                retention = retention.toRetention(),
                access = access.toAccess(),
                createdAt = createdAt,
                fileName = fileName,
                mimeType = mimeType,
                transcriptStatus = transcriptStatus.toTranscriptStatus(),
                transcriptFileName = transcriptFileName,
                transcriptFailure = transcriptFailure,
                indexedDocumentId = indexedDocumentId,
                conversationId = conversationId,
                consumedAt = consumedAt,
            )
        }
    }

    private fun MediaRecord.toDto(): RecordDto = RecordDto(
        id = id,
        kind = kind.name,
        displayName = displayName,
        sizeBytes = sizeBytes,
        retention = retention.name,
        access = access.name,
        createdAt = createdAt,
        fileName = fileName,
        mimeType = mimeType,
        transcriptStatus = transcriptStatus.name,
        transcriptFileName = transcriptFileName,
        transcriptFailure = transcriptFailure,
        indexedDocumentId = indexedDocumentId,
        conversationId = conversationId,
        consumedAt = consumedAt,
    )

    private fun MediaSnapshot.toDto(): SnapshotDto = SnapshotDto(
        version = SCHEMA_VERSION,
        defaultRetention = policy.defaultRetention.name,
        defaultAccess = policy.defaultAccess.name,
        records = records.map { it.toDto() },
    )

    companion object {
        private const val ROOT_DIR = "media"
        private const val FILES_DIR = "files"
        private const val INDEX_FILE = "index.json"
        private const val SCHEMA_VERSION = 1
        private const val COPY_BUFFER = 64 * 1024
        private const val MAX_EXTENSION = 8
        private const val DEFAULT_EXTENSION = "bin"
        private const val TEMP_SUFFIX = ".part"
        private const val TRANSCRIPT_SUFFIX = ".transcript.txt"
    }
}
/** Enum codecs for the index: a value this build does not know falls back to the safe default. */
private fun String.toKind(): MediaKind? = MediaKind.entries.firstOrNull { it.name == this }

private fun String.toRetention(): MediaRetention =
    MediaRetention.entries.firstOrNull { it.name == this } ?: MediaRetention.KEEP

private fun String.toAccess(): MediaAccess =
    MediaAccess.entries.firstOrNull { it.name == this } ?: MediaAccess.GRANTED

private fun String.toTranscriptStatus(): MediaTranscriptStatus =
    MediaTranscriptStatus.entries.firstOrNull { it.name == this }
        ?: MediaTranscriptStatus.NOT_APPLICABLE

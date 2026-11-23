package app.sirrak

import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentImport
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.AttachmentLimits
import app.sirrak.core.runtime.AttachmentPolicy
import app.sirrak.core.runtime.AttachmentRejection
import app.sirrak.core.runtime.AttachmentSource
import app.sirrak.core.runtime.AttachmentStore
import app.sirrak.core.runtime.AttachmentValidation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * M7 app-private attachment storage: one directory per conversation, one file per attachment and a
 * JSON sidecar that describes it. Every quota comes from [AttachmentLimits] through
 * [AttachmentPolicy], so shrinking a cap or adding a MIME type never touches this file.
 *
 * Durability rules:
 * - bytes are copied through a temp file and renamed only after the rolling cap accepted them; a
 *   rejected or failed import leaves no partial file and no sidecar behind;
 * - metadata is written atomically (temp file + rename) after the bytes are in place;
 * - conversation and attachment ids are sanitised into single path segments and every deletion is
 *   checked to stay inside [root]/attachments, so a hostile id cannot escape the sandbox;
 * - [list] skips unreadable or unknown sidecars instead of failing the whole conversation.
 *
 * The constructor takes a directory (not a Context), a clock and an id supplier so JVM tests drive
 * real files deterministically. No Android imports on purpose.
 */
class FileAttachmentStore(
    private val root: File,
    private val limits: AttachmentLimits = AttachmentLimits.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : AttachmentStore {

    private val attachmentsRoot = File(root, ATTACHMENTS_DIR)
    private val lock = Any()
    private val metaJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun list(conversationId: String): List<Attachment> = synchronized(lock) {
        val dir = conversationDir(conversationId) ?: return@synchronized emptyList()
        val sidecars = dir.listFiles { file -> file.isFile && file.name.endsWith(SIDECAR_SUFFIX) }
            ?: return@synchronized emptyList()
        sidecars.mapNotNull { readSidecar(it) }
            .sortedWith(compareBy<StoredMeta>({ it.createdAt }, { it.attachment.id }))
            .map { it.attachment }
    }

    override fun import(conversationId: String, source: AttachmentSource): AttachmentImport =
        synchronized(lock) {
            val dir = conversationDir(conversationId)
                ?: return@synchronized AttachmentImport.Rejected(AttachmentRejection.EMPTY)
            val mimeType = AttachmentPolicy.mimeOf(source.displayName, source.declaredMimeType)
            val kind = AttachmentPolicy.kindOf(mimeType)
                ?: return@synchronized AttachmentImport.Rejected(AttachmentRejection.UNSUPPORTED_TYPE)
            // A trustworthy declared size is validated before opening (and consuming) the stream;
            // a zero/unknown size is decided by the bytes themselves.
            if (source.sizeBytes > 0L) {
                val validation = AttachmentPolicy.validateFile(
                    source.declaredMimeType,
                    source.displayName,
                    source.sizeBytes,
                    limits,
                )
                if (validation is AttachmentValidation.Rejected) {
                    return@synchronized AttachmentImport.Rejected(validation.reason)
                }
            }
            val cap = AttachmentPolicy.capFor(kind, limits)
            val extension = AttachmentPolicy.extensionOf(mimeType)
            val staged = stage(dir)
            var total = 0L
            var tooLarge = false
            try {
                source.openStream().use { input ->
                    FileOutputStream(staged).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > cap) {
                                tooLarge = true
                                break
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (error: Throwable) {
                staged.delete()
                throw error
            }
            if (tooLarge) {
                staged.delete()
                return@synchronized AttachmentImport.Rejected(AttachmentRejection.TOO_LARGE)
            }
            if (total <= 0L) {
                staged.delete()
                return@synchronized AttachmentImport.Rejected(AttachmentRejection.EMPTY)
            }
            val displayName = sanitiseDisplayName(
                source.displayName,
                ATTACHMENT_FALLBACK_PREFIX + extension,
            )
            AttachmentImport.Stored(
                commit(
                    dir = dir,
                    staged = staged,
                    extension = extension,
                    kind = kind,
                    mimeType = mimeType,
                    sizeBytes = total,
                    displayName = displayName,
                    extractedText = null,
                    meta = emptyMap(),
                ),
            )
        }

    override fun saveSharedText(
        conversationId: String,
        text: String,
        displayName: String,
    ): AttachmentImport = synchronized(lock) {
        val dir = conversationDir(conversationId)
            ?: return@synchronized AttachmentImport.Rejected(AttachmentRejection.EMPTY)
        val accepted = when (val validation = AttachmentPolicy.validateSharedText(text, limits)) {
            is AttachmentValidation.Rejected ->
                return@synchronized AttachmentImport.Rejected(validation.reason)
            is AttachmentValidation.Accepted -> validation
        }
        val extension = AttachmentPolicy.extensionOf(accepted.mimeType)
        val staged = stage(dir)
        try {
            FileOutputStream(staged).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            }
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
        AttachmentImport.Stored(
            commit(
                dir = dir,
                staged = staged,
                extension = extension,
                kind = AttachmentKind.SHARED_TEXT,
                mimeType = accepted.mimeType,
                sizeBytes = accepted.sizeBytes,
                displayName = sanitiseDisplayName(displayName, SHARED_TEXT_FALLBACK_NAME),
                extractedText = text,
                meta = mapOf(META_SOURCE to META_SOURCE_CLIPBOARD),
            ),
        )
    }

    override fun delete(conversationId: String, attachmentId: String): Boolean = synchronized(lock) {
        val dir = conversationDir(conversationId) ?: return@synchronized false
        if (!dir.isDirectory) return@synchronized false
        val id = safeName(attachmentId, MAX_ATTACHMENT_NAME)
        if (id.isEmpty()) return@synchronized false
        val sidecar = sidecarFile(dir, id)
        if (!sidecar.isFile) return@synchronized false
        val related = dir.listFiles { file ->
            file.isFile && file.name != sidecar.name && file.name.startsWith(id + ".")
        } ?: emptyArray()
        related.forEach { it.delete() }
        sidecar.delete()
        !sidecar.exists()
    }

    override fun deleteConversation(conversationId: String) {
        synchronized(lock) {
            val dir = conversationDir(conversationId) ?: return
            if (!dir.isDirectory) return
            val base = runCatching { attachmentsRoot.canonicalFile }.getOrNull() ?: return
            val target = runCatching { dir.canonicalFile }.getOrNull() ?: return
            // Only a direct child of root/attachments may be removed, whatever the id claimed.
            if (target.parentFile?.path != base.path) return
            target.deleteRecursively()
        }
    }

    // ---- writing -----------------------------------------------------------------------------

    /** Reserves a temp file inside [dir]; the directory exists after this call. */
    private fun stage(dir: File): File {
        ensureDirectory(dir)
        return File(dir, TEMP_PREFIX + UUID.randomUUID().toString().replace("-", "") + TEMP_SUFFIX)
    }

    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("could not create the attachment directory")
        }
    }

    /** Moves the accepted temp bytes into place and writes their sidecar, cleaning up on failure. */
    private fun commit(
        dir: File,
        staged: File,
        extension: String,
        kind: AttachmentKind,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
        extractedText: String?,
        meta: Map<String, String>,
    ): Attachment {
        val id = allocateId(dir)
        val stored = File(dir, id + "." + extension)
        try {
            if (stored.exists() && !stored.delete()) {
                throw IOException("could not replace the existing attachment")
            }
            if (!staged.renameTo(stored)) {
                staged.copyTo(stored, overwrite = true)
                staged.delete()
            }
        } catch (error: Throwable) {
            staged.delete()
            stored.delete()
            throw error
        }
        val attachment = Attachment(
            id = id,
            kind = kind,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localPath = relativePath(dir, stored),
            extractedText = extractedText,
            meta = meta,
        )
        try {
            writeSidecar(dir, attachment)
        } catch (error: Throwable) {
            stored.delete()
            throw error
        }
        return attachment
    }

    private fun allocateId(dir: File): String {
        val base = safeName(nextId(), MAX_ATTACHMENT_NAME).ifEmpty { ATTACHMENT_ID_FALLBACK }
        var candidate = base
        var suffix = 1
        while (idTaken(dir, candidate)) {
            candidate = base + "-" + suffix
            suffix += 1
        }
        return candidate
    }

    private fun idTaken(dir: File, candidate: String): Boolean =
        sidecarFile(dir, candidate).exists() ||
            dir.listFiles { file -> file.isFile && file.name.startsWith(candidate + ".") }
                ?.isNotEmpty() == true

    private fun writeSidecar(dir: File, attachment: Attachment) {
        val dto = AttachmentMetaDto(
            id = attachment.id,
            kind = attachment.kind.name,
            displayName = attachment.displayName,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            localPath = attachment.localPath,
            extractedText = attachment.extractedText,
            meta = attachment.meta,
            createdAt = clock(),
        )
        writeAtomically(sidecarFile(dir, attachment.id), metaJson.encodeToString(dto))
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, target.name + TEMP_FILE_SUFFIX)
        try {
            temp.writeText(content)
            if (target.exists() && !target.delete()) {
                target.writeText(content)
                temp.delete()
                return
            }
            if (!temp.renameTo(target)) {
                target.writeText(content)
                temp.delete()
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    // ---- reading -----------------------------------------------------------------------------

    /**
     * M9: opens the stored bytes of an attachment for the vision pipeline. The path must resolve
     * inside the attachments root *and* belong to [conversationId], so a metadata edit cannot point
     * the reader at another conversation's file.
     */
    override fun open(conversationId: String, attachment: Attachment): InputStream? = synchronized(lock) {
        val path = attachment.localPath ?: return@synchronized null
        if (!isInsideAttachmentsRoot(path)) return@synchronized null
        val dir = conversationDir(conversationId) ?: return@synchronized null
        val file = File(root, path)
        val sameConversation = runCatching { file.parentFile?.canonicalFile == dir.canonicalFile }
            .getOrDefault(false)
        // The file name is the attachment id by construction (commit names it <id>.<ext>); requiring it
        // keeps a metadata edit from borrowing another attachment's bytes in the same conversation.
        val ownsFile = file.name.substringBeforeLast('.') == attachment.id
        if (!sameConversation || !ownsFile || !file.isFile) return@synchronized null
        runCatching { FileInputStream(file) }.getOrNull()
    }

    private fun readSidecar(file: File): StoredMeta? {
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val dto = runCatching { metaJson.decodeFromString<AttachmentMetaDto>(raw) }
            .getOrNull() ?: return null
        if (dto.schemaVersion != META_SCHEMA_VERSION) return null
        if (dto.id.isBlank() || file.name != dto.id + SIDECAR_SUFFIX) return null
        if (dto.sizeBytes < 0L) return null
        val kind = AttachmentKind.entries.firstOrNull { it.name == dto.kind } ?: return null
        val localPath = dto.localPath
        if (localPath != null && !isInsideAttachmentsRoot(localPath)) return null
        return StoredMeta(
            createdAt = dto.createdAt,
            attachment = Attachment(
                id = dto.id,
                kind = kind,
                displayName = dto.displayName.trim().ifEmpty { kind.name.lowercase() },
                mimeType = dto.mimeType.ifBlank { OCTET_STREAM },
                sizeBytes = dto.sizeBytes,
                localPath = localPath,
                extractedText = dto.extractedText,
                meta = dto.meta,
            ),
        )
    }

    /** True when a stored relative path resolves to a file under root/attachments. */
    private fun isInsideAttachmentsRoot(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        if (File(relativePath).isAbsolute) return false
        val base = runCatching { attachmentsRoot.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { File(root, relativePath).canonicalFile }.getOrNull() ?: return false
        return target.path.startsWith(base.path + File.separator)
    }

    // ---- names and paths ---------------------------------------------------------------------

    /**
     * The directory for one conversation, or null when the id cannot name a safe folder. The id is
     * reduced to one segment, so "..", separators and blanks cannot point outside the store.
     */
    private fun conversationDir(conversationId: String): File? {
        val name = safeName(conversationId, MAX_CONVERSATION_NAME)
        if (name.isEmpty()) return null
        val dir = File(attachmentsRoot, name)
        val base = runCatching { attachmentsRoot.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        return dir.takeIf { target.parentFile?.path == base.path }
    }

    private fun safeName(raw: String, maxLength: Int): String {
        val filtered = raw.trim().map { character ->
            if (character in ALLOWED_FILE_CHARACTERS) character else '_'
        }.joinToString("")
        return filtered.trim('.', ' ').take(maxLength).trim('.', ' ')
    }

    private fun sanitiseDisplayName(raw: String, fallback: String): String {
        val cleaned = raw.map { character ->
            when {
                character == '/' || character == '\\' || character.isISOControl() -> ' '
                else -> character
            }
        }.joinToString("")
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_DISPLAY_NAME)
            .trim()
        return cleaned.ifEmpty { fallback }
    }

    private fun relativePath(dir: File, file: File): String =
        ATTACHMENTS_DIR + "/" + dir.name + "/" + file.name

    private fun sidecarFile(dir: File, attachmentId: String): File =
        File(dir, attachmentId + SIDECAR_SUFFIX)

    private data class StoredMeta(val createdAt: Long, val attachment: Attachment)

    @Serializable
    private data class AttachmentMetaDto(
        val schemaVersion: Int = META_SCHEMA_VERSION,
        val id: String,
        val kind: String,
        val displayName: String = "",
        val mimeType: String = OCTET_STREAM,
        val sizeBytes: Long = 0L,
        val localPath: String? = null,
        val extractedText: String? = null,
        val meta: Map<String, String> = emptyMap(),
        val createdAt: Long = 0L,
    )

    companion object {
        internal const val ATTACHMENTS_DIR = "attachments"
        private const val SIDECAR_SUFFIX = ".json"
        private const val TEMP_FILE_SUFFIX = ".tmp"
        private const val TEMP_PREFIX = "tmp-"
        private const val TEMP_SUFFIX = ".part"
        private const val META_SCHEMA_VERSION = 1
        private const val MAX_DISPLAY_NAME = 80
        private const val MAX_CONVERSATION_NAME = 64
        private const val MAX_ATTACHMENT_NAME = 64
        private const val BUFFER_SIZE = 8 * 1024
        private const val ATTACHMENT_ID_FALLBACK = "attachment"
        private const val SHARED_TEXT_FALLBACK_NAME = "Shared text"
        private const val ATTACHMENT_FALLBACK_PREFIX = "attachment."
        private const val META_SOURCE = "source"
        private const val META_SOURCE_CLIPBOARD = "clipboard"
        private const val OCTET_STREAM = "application/octet-stream"
        private const val ALLOWED_FILE_CHARACTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_."
        private val WHITESPACE = Regex("\\s+")
    }
}

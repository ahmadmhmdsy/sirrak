package app.sirrak.feature.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.sirrak.core.rag.ExtractionSource
import java.io.IOException

/**
 * Resolves a Storage Access Framework document into the RAG import seam. Kept in the feature module
 * because only a UI has the Activity context and the picker's temporary read grant; the pipeline
 * itself stays Android-free and sees plain bytes.
 *
 * The size may be unknown (-1): the repository enforces the real cap while copying, so an honest
 * unknown is better than refusing a valid picker result. A missing name or unreadable stream returns
 * null and the caller shows a localised "unreadable file" state instead of importing a blank document.
 */
object DocumentPicker {
    /**
     * Picker filter: exactly the formats [app.sirrak.core.rag.ExtractorRegistry] can extract in this
     * build. A picker that hides unsupported DOC/ODT files is friendlier than a late rejection.
     */
    val MIME_TYPES: Array<String> = arrayOf(
        "application/pdf",
        "text/plain",
        "text/markdown",
        "application/epub+zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    fun source(context: Context, uri: Uri): ExtractionSource? {
        val resolver = context.contentResolver
        // The import coroutine outlives the picker callback; keep the read grant alive where the
        // provider allows it. A provider that refuses is still readable inside the current process.
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName(resolver, uri) ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val mime = resolver.getType(uri)
        val size = sizeBytes(resolver, uri)
        return ExtractionSource(displayName = name, mimeType = mime, sizeBytes = size) {
            resolver.openInputStream(uri) ?: throw IOException("picker returned no stream for " + uri)
        }
    }

    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sizeBytes(resolver: android.content.ContentResolver, uri: Uri): Long {
        val declared = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
        }.getOrNull()
        if (declared != null && declared > 0L) return declared
        return runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: -1L
    }
}

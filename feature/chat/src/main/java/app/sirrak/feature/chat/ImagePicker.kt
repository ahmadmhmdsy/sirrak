package app.sirrak.feature.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.sirrak.core.runtime.AttachmentSource
import java.io.File
import java.io.IOException

/**
 * M9: resolves a gallery pick or a camera capture into the [AttachmentSource] seam.
 *
 * The chat feature never touches Bitmap/EXIF itself: it hands raw bytes to the store and the app's
 * ImagePreparer bounds them. Camera capture goes through the app's FileProvider (declared in the app
 * manifest), so no CAMERA permission is needed and the photo never leaves app-private cache.
 */
object ImagePicker {

    /** Picker filter: the formats the vision preparer decodes. */
    val MIME_TYPES: Array<String> = arrayOf("image/png", "image/jpeg", "image/webp")

    /** A camera destination prepared before the capture intent is launched. */
    class Capture(val file: File, val uri: Uri) {
        /** Reads the captured bytes; length is unknown until the camera app finishes writing. */
        fun source(): AttachmentSource = AttachmentSource(
            displayName = "camera.jpg",
            declaredMimeType = "image/jpeg",
            sizeBytes = file.length().takeIf { it > 0L } ?: -1L,
        ) {
            if (!file.isFile) throw IOException("camera produced no file")
            file.inputStream()
        }
    }

    fun source(context: Context, uri: Uri): AttachmentSource? {
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName(resolver, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val size = sizeBytes(resolver, uri)
        return AttachmentSource(displayName = name, declaredMimeType = mime, sizeBytes = size) {
            resolver.openInputStream(uri) ?: throw IOException("picker returned no stream for " + uri)
        }
    }

    /**
     * Creates a fresh cache file plus the content:// Uri the camera app writes to. Null when the
     * provider is not configured in the manifest, which is a composition bug rather than a user error.
     */
    fun captureTarget(context: Context): Capture? {
        val dir = File(context.cacheDir, CAPTURE_DIRECTORY)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, "capture-" + System.currentTimeMillis() + ".jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        }.getOrNull() ?: return null
        return Capture(file, uri)
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

    private const val CAPTURE_DIRECTORY = "captures"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
}

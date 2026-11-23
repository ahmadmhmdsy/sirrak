package app.sirrak.feature.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.sirrak.core.speech.SpeechModelFile
import java.io.IOException

/**
 * Turns SAF selections into [SpeechModelFile]s. The model files are arbitrary binary blobs with no
 * registered MIME type, so the picker offers everything and the store's layout rules decide whether a
 * selection is a Whisper trio. Metadata is read now; bytes are streamed only during the import.
 */
object SpeechFilePicker {

    fun filesFrom(context: Context, uris: List<Uri>): List<SpeechModelFile> =
        uris.mapNotNull { uri ->
            val name = displayName(context, uri) ?: return@mapNotNull null
            val size = sizeOf(context, uri) ?: 0L
            SpeechModelFile(name = name, sizeBytes = size) {
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("cannot open " + name)
            }
        }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun sizeOf(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else {
                    null
                }
            }
}

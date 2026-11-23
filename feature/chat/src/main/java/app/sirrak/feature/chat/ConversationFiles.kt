package app.sirrak.feature.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * The exit door for a conversation: the system share sheet, or a real file in the public Downloads
 * collection on API 29+. No storage permission, no network, no provider: the receiving app or the
 * file manager owns the copy from then on. Kept in one place so every export format behaves the same.
 */
internal object ConversationFiles {
    const val MIME_MARKDOWN = "text/markdown"
    const val MIME_JSON = "application/json"

    /** Opens the share sheet with the transcript as text; the chooser title is localised by the caller. */
    fun share(context: Context, chooserTitle: String, subject: String, body: String, mimeType: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    /**
     * Writes [content] under Download/Sirrak. Returns false on older APIs or failure; the caller then
     * falls back to [share] rather than pretending the export happened.
     */
    fun saveToDownloads(context: Context, fileName: String, mimeType: String, content: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Sirrak")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: return false
            true
        }.getOrDefault(false)
    }

    /**
     * A safe, human-readable file name: the display title when there is one, the stable id otherwise,
     * with path separators and reserved characters folded to dashes so the export cannot escape its
     * folder or fail on Windows-derived file managers.
     */
    fun fileNameFor(title: String, id: String, extension: String): String {
        val base = title.trim().ifEmpty { "sirrak-" + id }
        val safe = base.replace(FORBIDDEN, "-").take(MAX_NAME_LENGTH)
        return safe + "." + extension
    }

    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|]")
    private const val MAX_NAME_LENGTH = 60
}

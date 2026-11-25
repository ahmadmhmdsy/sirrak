package app.sirrak.media

import java.util.Locale

/**
 * User-facing number formatting for the shelf, kept pure so "Latin digits only" is a tested fact.
 *
 * Locale-default formatting would render Arabic-Indic digits under an Arabic locale ("٥٠ م.ب"): the
 * shelf, its storage line and its decision sheet must show Latin digits, so every number below is
 * formatted with [Locale.US] and the unit letters stay Latin.
 */
object MediaText {

    /** Blunt binary size, matching the rest of the app's file rows but with a forced locale. */
    fun bytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> format("%.0f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> format("%.0f kB", bytes / 1_000.0)
        else -> bytes.toString() + " B"
    }

    /** A guessed extension for the store's file name; the display name keeps the original. */
    fun extensionOf(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        if (dot <= 0 || dot == displayName.length - 1) return ""
        val candidate = displayName.substring(dot + 1)
        if (candidate.length > 8) return ""
        return if (candidate.all { it.isLetterOrDigit() }) candidate.lowercase(Locale.US) else ""
    }

    private fun format(pattern: String, value: Double): String =
        String.format(Locale.US, pattern, value)
}

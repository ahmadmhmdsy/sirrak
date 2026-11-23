package app.sirrak.core.rag

/**
 * Arabic-aware text handling for retrieval. Two rules drive everything here:
 *
 * 1. **Matching is normalised, display is not.** Diacritics, tatweel, alef/hamza and ta-marbuta
 *    variants are noise for a retriever; the stored [RagChunk.text] and every prompt snippet keep the
 *    user's original script, so the model reads real Arabic and citations look like the document.
 * 2. **The rules are data.** New normalisation steps are added to [normalizeForMatch] once and every
 *    consumer (chunking, BM25, duplicate detection) inherits them; no call site re-implements them.
 */
object ArabicText {

    /** Diacritics (tashkeel), Quranic marks and tatweel carry no retrieval signal. */
    private val DIACRITICS = Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")

    /** Zero-width and bidirectional control characters can hide inside copied text. */
    private val INVISIBLE = Regex("[\u200B-\u200F\u202A-\u202E\u2060\uFEFF]")

    private val WHITESPACE = Regex("[\\s\\u00A0]+")

    /** Splits on anything that is not a Unicode letter or digit; keeps Arabic, Latin and digits. */
    private val TOKEN_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The matching form: lower-cased, invisible marks removed, diacritics/tatweel stripped, alef and
     * hamza forms unified, ta-marbuta folded to ha, Arabic-Indic digits converted to ASCII, and every
     * run of punctuation/whitespace collapsed to one space. Empty input stays empty.
     */
    fun normalizeForMatch(text: String): String {
        if (text.isEmpty()) return text
        val cleaned = INVISIBLE.replace(DIACRITICS.replace(text, ""), "")
        val out = StringBuilder(cleaned.length)
        var pendingSpace = false
        for (raw in cleaned) {
            val ch = foldLetter(raw)
            when {
                ch.isDigit() -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(ch)
                }
                ch.isLetter() -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(ch.lowercaseChar())
                }
                else -> pendingSpace = true
            }
        }
        return out.toString()
    }

    /** Matching tokens for BM25 and query processing, in order of appearance. */
    fun tokens(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return normalizeForMatch(text)
            .split(TOKEN_SEPARATORS)
            .filter { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH }
    }

    /**
     * Splits [text] into sentences at Arabic/English terminal punctuation and line breaks. Sentence
     * boundaries are what keeps chunk overlap Arabic-aware instead of cutting a word in half. A period
     * between digits (a decimal) does not end a sentence; trailing punctuation stays with its sentence.
     */
    fun splitSentences(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            current.append(ch)
            val endsSentence = when (ch) {
                '\n' -> true
                '.', '!', '?', '\u061F', '\u06D4', '\u2026' -> {
                    val next = text.getOrNull(i + 1)
                    // "3.5" and "1.5B" are not sentence breaks; a digit or letter directly after the
                    // dot means the token continues.
                    ch != '.' || next == null || next.isWhitespace() || next == '\n'
                }
                else -> false
            }
            if (endsSentence) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) out += sentence
                current.setLength(0)
                // Collapse the whitespace run after the boundary.
                while (i + 1 < text.length && text[i + 1].isWhitespace()) i++
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    /** True when the character is in one of the Arabic script blocks this app cares about. */
    fun isArabicLetter(ch: Char): Boolean = ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF'

    private fun foldLetter(ch: Char): Char = when (ch) {
        '\u0623', '\u0625', '\u0622', '\u0671', '\u0672', '\u0673' -> '\u0627'
        '\u0649', '\u064A', '\u06CC' -> '\u064A'
        '\u0629' -> '\u0647'
        '\u0624' -> '\u0648'
        '\u0626' -> '\u064A'
        '\u06A9' -> '\u0643'
        in '\u0660'..'\u0669' -> ('0' + (ch - '\u0660'))
        in '\u06F0'..'\u06F9' -> ('0' + (ch - '\u06F0'))
        else -> ch
    }

    /** Very long runs (URLs, hashes) are noise for BM25 and would blow up the vocabulary. */
    private const val MAX_TOKEN_LENGTH = 40
}

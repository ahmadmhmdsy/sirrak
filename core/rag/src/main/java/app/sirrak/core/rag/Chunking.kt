package app.sirrak.core.rag

/** Result of chunking one extracted document. [truncated] means the cap dropped trailing chunks. */
data class ChunkingResult(val chunks: List<RagChunk>, val truncated: Boolean = false)

/**
 * Splits extracted pages into overlapping, sentence-aligned chunks. Arabic-aware because it splits on
 * [ArabicText.splitSentences] (Arabic question mark, full stop, line breaks) rather than on spaces.
 * A chunk never spans a page break: [RagChunk.page] is citation provenance, so mixing two pages in
 * one chunk would attribute the second page's text to the first (see doc 22, M8 citation fidelity).
 *
 * Every parameter comes from [RagLimits]: target size, hard maximum, overlap and the per-document cap
 * can be tuned from one place without touching this algorithm. Chunk ids are deterministic
 * (documentId + "#" + ordinal) so re-indexing the same document produces the same citation ids.
 */
class TextChunker(private val limits: RagLimits = RagLimits.Default) {

    fun chunk(documentId: String, pages: List<ExtractedPage>): ChunkingResult {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        if (pages.isEmpty()) return ChunkingResult(emptyList())

        val drafts = ArrayList<Draft>()
        var current = StringBuilder()
        var currentPage = pages.first().number
        var currentHeading: String? = null

        fun emit() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) drafts += Draft(text, currentPage, currentHeading)
            current = StringBuilder()
        }

        for (page in pages) {
            val pageText = clean(page.text)
            if (pageText.isEmpty()) continue
            val heading = headingOf(pageText)
            for (sentence in ArabicText.splitSentences(pageText)) {
                for (piece in hardSplit(sentence)) {
                    if (current.isEmpty()) {
                        currentPage = page.number
                        currentHeading = heading
                        current.append(overlapPrefix(drafts.lastOrNull()?.text))
                        current.append(piece)
                        continue
                    }
                    val separator = if (current.lastOrNull()?.isWhitespace() == true) 0 else 1
                    if (current.length + separator + piece.length <= limits.targetChunkChars) {
                        if (separator == 1) current.append(' ')
                        current.append(piece)
                    } else {
                        emit()
                        currentPage = page.number
                        currentHeading = heading
                        current.append(overlapPrefix(drafts.lastOrNull()?.text))
                        current.append(piece)
                    }
                }
            }
            // A chunk never spans a page break: the page number on a citation must point at the
            // page the quoted text is actually on. Short pages yield short chunks; mergeTinyTail
            // only folds within the same page.
            if (current.isNotEmpty()) emit()
        }
        emit()

        val merged = mergeTinyTail(drafts)
        val capped = if (merged.size > limits.maxChunksPerDocument) {
            merged.subList(0, limits.maxChunksPerDocument)
        } else {
            merged
        }
        val chunks = capped.mapIndexed { ordinal, draft ->
            RagChunk(
                id = chunkId(documentId, ordinal),
                documentId = documentId,
                ordinal = ordinal,
                text = draft.text,
                page = draft.page,
                heading = draft.heading,
            )
        }
        return ChunkingResult(chunks, truncated = merged.size > capped.size)
    }

    /** A chunk id stable across re-indexing: order + document id, nothing time-based. */
    fun chunkId(documentId: String, ordinal: Int): String = documentId + "#" + ordinal

    private data class Draft(val text: String, val page: Int, val heading: String?)

    /** CRLF -> LF, control chars dropped, runs of blank lines collapsed to a paragraph break. */
    private fun clean(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { ch -> ch == '\n' || ch == '\t' || !ch.isISOControl() }
        .replace(Regex("[\\t\\u00A0]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /**
     * A heading is a short first line that does not end a sentence: a title, not prose. This is a
     * heuristic display label for citations, never used for ranking, and any format can override it
     * later by supplying the label on the page.
     */
    private fun headingOf(pageText: String): String? {
        val first = pageText.lineSequence().firstOrNull()?.trim().orEmpty()
        if (first.isEmpty() || first.length > HEADING_MAX_CHARS) return null
        if (first.lastOrNull() in SENTENCE_END_CHARS) return null
        return first
    }

    /** Sentences longer than the hard maximum are split at word boundaries, never mid-word. */
    private fun hardSplit(sentence: String): List<String> {
        if (sentence.length <= limits.maxChunkChars) return listOf(sentence)
        val out = ArrayList<String>()
        var remaining = sentence.trim()
        while (remaining.length > limits.maxChunkChars) {
            val window = remaining.substring(0, limits.maxChunkChars)
            val cut = window.lastIndexOf(' ')
            val at = if (cut > limits.maxChunkChars / 2) cut else limits.maxChunkChars
            out += remaining.substring(0, at).trim()
            remaining = remaining.substring(at).trim()
        }
        if (remaining.isNotEmpty()) out += remaining
        return out
    }

    /** The tail of the previous chunk, cut at a word boundary and bounded by the overlap setting. */
    private fun overlapPrefix(previous: String?): String {
        if (previous == null || limits.overlapChars == 0) return ""
        val tail = previous.takeLast(limits.overlapChars)
        val firstSpace = tail.indexOf(' ')
        val aligned = if (firstSpace in 1 until tail.length - 1) tail.substring(firstSpace + 1) else tail
        return aligned.trim().let { if (it.isEmpty()) "" else it + " " }
    }

    /** A final chunk shorter than the minimum is folded into its predecessor when it still fits. */
    private fun mergeTinyTail(drafts: List<Draft>): List<Draft> {
        if (drafts.size < 2) return drafts
        val last = drafts.last()
        val previous = drafts[drafts.size - 2]
        // Never fold across a page break: that would silently move the tail's text onto another page.
        if (last.page != previous.page) return drafts
        if (last.text.length >= limits.minChunkChars) return drafts
        if (previous.text.length + 1 + last.text.length > limits.maxChunkChars) return drafts
        val mergedText = previous.text + " " + last.text
        return drafts.dropLast(2) + Draft(mergedText, previous.page, previous.heading)
    }

    private companion object {
        const val HEADING_MAX_CHARS = 80
        val SENTENCE_END_CHARS = setOf('.', '!', '?', '\u061F', '\u06D4', '\u2026')
    }
}

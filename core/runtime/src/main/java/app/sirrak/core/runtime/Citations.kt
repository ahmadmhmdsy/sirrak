package app.sirrak.core.runtime

/**
 * One grounded-answer citation (M8). The transcript keeps a snapshot of the source label and excerpt at
 * answer time, so a citation stays readable even after the document is deleted; only the document id
 * becomes a dangling reference, and the UI treats a missing document as "no longer in the library".
 *
 * Deliberately Android-free: :feature:chat renders these, the app fills them from :core:rag results.
 */
data class Citation(
    val documentId: String,
    val chunkId: String,
    val documentName: String,
    /** 1-based page for paged formats, 1 for flow formats. */
    val page: Int,
    /** Short source excerpt shown when the user taps the citation chip. */
    val snippet: String,
)

package app.sirrak.core.rag

import java.io.InputStream

/**
 * Format detection, extraction outcome and the extractor registry. Formats are data: adding DOCX
 * support is one [DocumentFormat] entry plus one [DocumentExtractor] implementation, and a format the
 * build cannot read is honestly refused instead of being sent to an engine that cannot use it.
 */

/** One importable container. Ids are stable strings used in saved metadata and library labels. */
data class DocumentFormat(
    val id: String,
    val mimeTypes: Set<String>,
    val extensions: Set<String>,
)

/** The formats this build can extract text from (doc 04 RAG-1). */
object DocumentFormats {
    val PDF = DocumentFormat("pdf", setOf("application/pdf"), setOf("pdf"))
    val DOCX = DocumentFormat(
        "docx",
        setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        setOf("docx"),
    )
    val EPUB = DocumentFormat("epub", setOf("application/epub+zip"), setOf("epub"))
    val TXT = DocumentFormat("txt", setOf("text/plain"), setOf("txt", "text"))
    val MARKDOWN = DocumentFormat("md", setOf("text/markdown", "text/x-markdown"), setOf("md", "markdown"))

    val all: List<DocumentFormat> = listOf(PDF, DOCX, EPUB, TXT, MARKDOWN)

    /** Extension first (mime from pickers is often generic), then mime type. */
    fun detect(displayName: String, mimeType: String?): DocumentFormat? =
        detectIn(all, displayName, mimeType)

    /** Detection against a caller-supplied format list, so a registry can add its own formats. */
    fun detectIn(candidates: List<DocumentFormat>, displayName: String, mimeType: String?): DocumentFormat? {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty()) {
            candidates.firstOrNull { extension in it.extensions }?.let { return it }
        }
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.isEmpty()) return null
        return candidates.firstOrNull { mime in it.mimeTypes }
    }
}

/** Where an import reads from: a file name, a picker mime and a stream factory (re-openable bytes). */
data class ExtractionSource(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val openStream: () -> InputStream,
)

/** One extracted page. Flow formats (TXT/MD) produce one page; EPUB produces one per spine item. */
data class ExtractedPage(val number: Int, val text: String)

/** Extraction result kept independent of the storage layer; chunking consumes only this. */
data class ExtractedDocument(
    val formatId: String,
    val pages: List<ExtractedPage>,
    val warnings: List<String> = emptyList(),
) {
    val plainText: String get() = pages.joinToString("\n\n") { it.text }.trim()
}

/** One format implementation; the registry dispatches by [formatId]. */
interface DocumentExtractor {
    val formatId: String

    /** @throws ExtractionFailure with a typed reason for expected problems (encrypted, no text...). */
    fun extract(source: ExtractionSource, limits: RagLimits): ExtractedDocument
}

/** Expected extraction failure; unexpected exceptions are mapped to CORRUPT by the registry. */
class ExtractionFailure(
    val reason: RagImportRejection,
    cause: Throwable? = null,
) : Exception("extraction failed: " + reason.name, cause)

/** Registry: validates whole-file limits before any format parser sees the bytes. */
class ExtractorRegistry(
    extractors: List<DocumentExtractor>,
    private val formats: List<DocumentFormat> = DocumentFormats.all,
    private val limits: RagLimits = RagLimits.Default,
) {
    private val byFormat: Map<String, DocumentExtractor> = extractors.associateBy { it.formatId }

    fun detect(source: ExtractionSource): DocumentFormat? =
        DocumentFormats.detectIn(formats, source.displayName, source.mimeType)

    /** Whether this build can read the source's format at all; the UI uses it for the import hint. */
    fun supports(source: ExtractionSource): Boolean = detect(source)?.let { it.id in byFormat } == true

    fun extract(source: ExtractionSource): ExtractionOutcome {
        if (source.sizeBytes == 0L) return ExtractionOutcome.Rejected(RagImportRejection.EMPTY)
        if (source.sizeBytes > limits.maxDocumentBytes) {
            return ExtractionOutcome.Rejected(RagImportRejection.TOO_LARGE)
        }
        val format = detect(source) ?: return ExtractionOutcome.Rejected(RagImportRejection.UNSUPPORTED_TYPE)
        val extractor = byFormat[format.id]
            ?: return ExtractionOutcome.Rejected(RagImportRejection.UNSUPPORTED_TYPE)
        val extracted = try {
            extractor.extract(source, limits)
        } catch (failure: ExtractionFailure) {
            return ExtractionOutcome.Rejected(failure.reason)
        } catch (t: Throwable) {
            return ExtractionOutcome.Rejected(RagImportRejection.CORRUPT)
        }
        if (extracted.pages.size > limits.maxPages) {
            return ExtractionOutcome.Rejected(RagImportRejection.TOO_MANY_PAGES)
        }
        if (extracted.plainText.isBlank()) return ExtractionOutcome.Rejected(RagImportRejection.NO_TEXT)
        return ExtractionOutcome.Extracted(extracted)
    }
}

/** Result of [ExtractorRegistry.extract]. */
sealed interface ExtractionOutcome {
    data class Extracted(val document: ExtractedDocument) : ExtractionOutcome
    data class Rejected(val reason: RagImportRejection) : ExtractionOutcome
}

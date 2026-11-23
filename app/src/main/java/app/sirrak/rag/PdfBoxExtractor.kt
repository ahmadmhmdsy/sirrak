package app.sirrak.rag

import app.sirrak.core.rag.DocumentExtractor
import app.sirrak.core.rag.DocumentFormats
import app.sirrak.core.rag.ExtractedDocument
import app.sirrak.core.rag.ExtractedPage
import app.sirrak.core.rag.ExtractionFailure
import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.rag.RagLimits
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PDF text-layer extraction for the M8 document pipeline.
 *
 * This is the only [DocumentExtractor] that lives in `:app` rather than in the pure-JVM `:core:rag`
 * module: PDFBox-Android reads its standard-14 font metrics and glyph lists from Android assets, so the
 * process needs a one-time resource initialisation that only an Android process can perform. The
 * extractor deliberately does not depend on `android.content.Context`; instead it receives that setup
 * as [initialize] and invokes it exactly once, immediately before the first document load:
 *
 * ```kotlin
 * // App wiring (once per process):
 * PdfBoxExtractor { PDFBoxResourceLoader.init(application) }
 * ```
 *
 * Omitting the initializer is valid for JVM tests and for callers that have already initialised
 * PDFBox-Android; the default is a no-op. The lambda is guarded by an [AtomicBoolean], so it never runs
 * twice even when two imports start concurrently.
 *
 * Behaviour, in the terms of the storage-neutral contract:
 * - one [ExtractedPage] per page that has a text layer, with the original 1-based page number, so a
 *   blank page is skipped without renumbering the pages after it;
 * - image-only/scanned PDFs (or a PDF whose pages are all blank) fail with [RagImportRejection.NO_TEXT];
 *   reading their pixels belongs to the future OCR milestone, not to this text extractor;
 * - any encrypted or password-protected file fails with [RagImportRejection.ENCRYPTED]; the import
 *   flow never prompts for a password and never guesses one;
 * - a file larger than [RagLimits.maxDocumentBytes] fails with [RagImportRejection.TOO_LARGE] before the
 *   stream is opened when [ExtractionSource.sizeBytes] already proves it, and the actual read is
 *   bounded as well so a lying size cannot exhaust the process;
 * - parse/strip I/O and parser runtime errors become [RagImportRejection.CORRUPT] with the original
 *   cause, never a crash;
 * - extraction stops with [RagImportRejection.TOO_MANY_PAGES] once more than [RagLimits.maxPages]
 *   non-blank pages would be returned (the registry would reject the same document anyway).
 *
 * The document is always closed through [PDDocument.use], and document text is never logged. The byte
 * budget is enforced with one bounded read; the bytes are handed to PDFBox once and not re-read.
 */
class PdfBoxExtractor(
    private val initialize: () -> Unit = {},
) : DocumentExtractor {

    override val formatId: String = DocumentFormats.PDF.id

    /** Guards [initialize] so wiring cannot accidentally pay the resource cost once per document. */
    private val initialized = AtomicBoolean(false)

    override fun extract(source: ExtractionSource, limits: RagLimits): ExtractedDocument {
        val ceiling = byteCeiling(limits)
        if (source.sizeBytes > ceiling) throw ExtractionFailure(RagImportRejection.TOO_LARGE)

        val bytes = try {
            readBounded(source, ceiling)
        } catch (failure: ExtractionFailure) {
            throw failure
        } catch (error: IOException) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, error)
        } catch (error: RuntimeException) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, error)
        }
        if (bytes.isEmpty()) throw ExtractionFailure(RagImportRejection.EMPTY)

        ensureInitialized()
        val document = openDocument(bytes)

        return try {
            document.use { pdf ->
                rejectEncrypted(pdf)
                val pages = extractPages(pdf, limits)
                if (pages.isEmpty()) throw ExtractionFailure(RagImportRejection.NO_TEXT)
                ExtractedDocument(formatId = formatId, pages = pages)
            }
        } catch (failure: ExtractionFailure) {
            throw failure
        } catch (error: IOException) {
            // Includes a close() failure: the bytes were parsed, so CORRUPT is the honest code.
            throw ExtractionFailure(RagImportRejection.CORRUPT, error)
        } catch (error: RuntimeException) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, error)
        }
    }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) initialize()
    }

    private fun openDocument(bytes: ByteArray): PDDocument = try {
        PDDocument.load(bytes)
    } catch (failure: InvalidPasswordException) {
        throw ExtractionFailure(RagImportRejection.ENCRYPTED)
    } catch (error: IOException) {
        throw ExtractionFailure(RagImportRejection.CORRUPT, error)
    } catch (error: RuntimeException) {
        throw ExtractionFailure(RagImportRejection.CORRUPT, error)
    }

    /**
     * PDFBox opens PDFs that only carry an owner password, because the empty user password decrypts the
     * content. The import contract still treats every encrypted file as unreadable here: it has no UI
     * to ask for a password, and silently importing a restricted document would misrepresent it.
     */
    private fun rejectEncrypted(document: PDDocument) {
        if (document.isEncrypted) throw ExtractionFailure(RagImportRejection.ENCRYPTED)
    }

    private fun extractPages(document: PDDocument, limits: RagLimits): List<ExtractedPage> {
        val pageCount = document.numberOfPages
        if (pageCount <= 0) return emptyList()

        val stripper = PageCollectingTextStripper()
        stripper.sortByPosition = true
        try {
            // One pass over the document; the subclass captures each page as PDFBox writes it.
            stripper.getText(document)
        } catch (error: IOException) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, error)
        } catch (error: RuntimeException) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, error)
        }

        val pages = ArrayList<ExtractedPage>()
        for (pageNumber in 1..pageCount) {
            val text = normalize(stripper.pageText(pageNumber))
            if (text.isEmpty()) continue
            pages += ExtractedPage(number = pageNumber, text = text)
            if (pages.size > limits.maxPages) {
                throw ExtractionFailure(RagImportRejection.TOO_MANY_PAGES)
            }
        }
        return pages
    }

    /** One bounded read; [ceiling] is checked again on every chunk so sizeBytes is advisory, not trusted. */
    private fun readBounded(source: ExtractionSource, ceiling: Long): ByteArray {
        val initialCapacity = when {
            source.sizeBytes in 1..MAX_PREALLOCATED_BYTES -> source.sizeBytes.toInt()
            else -> READ_BUFFER_BYTES
        }
        val out = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        source.openStream().use { input ->
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > ceiling) throw ExtractionFailure(RagImportRejection.TOO_LARGE)
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /**
     * PDFBox emits the platform line separator and can leave trailing whitespace on a page. Normalising
     * here keeps chunk boundaries and page text identical between the JVM test runtime (CRLF) and
     * Android (LF) without touching the words themselves.
     */
    private fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text.replace("\r\n", "\n").replace('\r', '\n').trim()
    }

    private fun byteCeiling(limits: RagLimits): Long =
        limits.maxDocumentBytes.coerceAtMost(MAX_BYTE_ARRAY)

    /**
     * Captures the text PDFBox writes for each page in a single stripping pass. [PDFTextStripper] routes
     * every word, separator and line break through [writeString], so overriding it captures the page
     * body exactly as the base class would have written it. Pages without contents never reach
     * [startPage]; [pageText] simply returns null for them, which the caller treats as blank.
     */
    private class PageCollectingTextStripper : PDFTextStripper() {
        private val pageTexts = HashMap<Int, String>()
        private val currentPage = StringBuilder()

        override fun startPage(page: PDPage) {
            super.startPage(page)
            currentPage.setLength(0)
        }

        override fun writeString(text: String) {
            currentPage.append(text)
        }

        override fun endPage(page: PDPage) {
            super.endPage(page)
            pageTexts[getCurrentPageNo()] = currentPage.toString()
        }

        fun pageText(pageNumber: Int): String? = pageTexts[pageNumber]
    }

    private companion object {
        /** PDFBox materialises the parsed file in one byte array; longer input cannot be represented. */
        val MAX_BYTE_ARRAY = (Int.MAX_VALUE - 8).toLong()
        const val READ_BUFFER_BYTES = 16 * 1024
        const val MAX_PREALLOCATED_BYTES = 1024L * 1024
    }
}

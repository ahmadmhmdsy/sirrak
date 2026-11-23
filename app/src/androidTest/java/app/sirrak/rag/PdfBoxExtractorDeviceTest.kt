package app.sirrak.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sirrak.core.rag.DocumentFormats
import app.sirrak.core.rag.ExtractionFailure
import app.sirrak.core.rag.ExtractionOutcome
import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.ExtractorRegistry
import app.sirrak.core.rag.RagImportRejection
import app.sirrak.core.rag.RagLimits
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests for [PdfBoxExtractor]. PDF extraction is the one RAG step that cannot run in a plain
 * JVM unit test: PDFBox-Android loads its standard-14 font metrics and glyph lists from the AAR's
 * Android assets, which requires a real device/emulator Context. Production performs that one-time
 * `PDFBoxResourceLoader.init(application)` through the extractor's initializer; these tests do the
 * same and then exercise the real parser against PDFs generated in memory by PDFBox itself.
 *
 * Fixtures stay English because the standard-14 Type1 fonts used to generate them have no Arabic
 * glyphs; the extractor is script-agnostic and follows each PDF's ToUnicode mapping, which is the same
 * code path for Arabic PDFs produced by a word processor.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxExtractorDeviceTest {

    private val limits: RagLimits = RagLimits.Default

    @Before
    fun preparePdfBoxResources() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun twoPagePdfYieldsNumberedPagesWithTheirText() {
        val bytes = pdfWithText("First page English text", "Second page English text")

        val extracted = PdfBoxExtractor().extract(source(bytes), limits)

        assertEquals(DocumentFormats.PDF.id, extracted.formatId)
        assertEquals(listOf(1, 2), extracted.pages.map { it.number })
        assertTrue(extracted.pages[0].text.contains("First page English text"))
        assertTrue(extracted.pages[1].text.contains("Second page English text"))
        assertFalse(extracted.pages[0].text.contains("Second page English text"))
    }

    @Test
    fun blankPagesAreSkippedWithoutRenumberingFollowingPages() {
        val bytes = pdfWithText("Before the gap", null, "After the gap")

        val extracted = PdfBoxExtractor().extract(source(bytes), limits)

        assertEquals(listOf(1, 3), extracted.pages.map { it.number })
        assertTrue(extracted.pages[0].text.contains("Before the gap"))
        assertTrue(extracted.pages[1].text.contains("After the gap"))
    }

    @Test
    fun pdfWithoutTextLayerIsRejectedAsNoText() {
        val failure = failureOf(blankPdf(pageCount = 2))

        assertEquals(RagImportRejection.NO_TEXT, failure.reason)
    }

    @Test
    fun registryMapsTheNoTextFailureToItsTypedRejection() {
        val registry = ExtractorRegistry(
            extractors = listOf(PdfBoxExtractor()),
            formats = DocumentFormats.all,
            limits = limits,
        )

        val outcome = registry.extract(source(blankPdf(pageCount = 2)))

        assertEquals(ExtractionOutcome.Rejected(RagImportRejection.NO_TEXT), outcome)
    }

    @Test
    fun corruptBytesAreRejectedAsCorrupt() {
        val failure = failureOf("not a pdf at all".toByteArray(Charsets.UTF_8))

        assertEquals(RagImportRejection.CORRUPT, failure.reason)
        assertTrue(failure.cause != null)
    }

    @Test
    fun encryptedPdfIsRejectedAsEncrypted() {
        val failure = failureOf(encryptedPdf(userPassword = "user-secret"))

        assertEquals(RagImportRejection.ENCRYPTED, failure.reason)
    }

    @Test
    fun ownerPasswordOnlyPdfIsStillRejectedAsEncrypted() {
        // PDFBox can open this file because its user password is empty, but the import contract never
        // silently accepts an encrypted container.
        val failure = failureOf(encryptedPdf(userPassword = ""))

        assertEquals(RagImportRejection.ENCRYPTED, failure.reason)
    }

    @Test
    fun initializerRunsExactlyOnceAcrossDocuments() {
        val initializations = AtomicInteger()
        val extractor = PdfBoxExtractor { initializations.incrementAndGet() }

        extractor.extract(source(pdfWithText("One")), limits)
        extractor.extract(source(pdfWithText("Two")), limits)

        assertEquals(1, initializations.get())
    }

    @Test
    fun oversizedSourceIsRejectedBeforeTheStreamIsOpened() {
        val smallLimits = limits.copy(maxDocumentBytes = 1024L)
        val source = ExtractionSource(
            displayName = "huge.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1025L,
            openStream = { error("the extractor must not open an over-limit stream") },
        )

        val failure = assertThrows(ExtractionFailure::class.java) {
            PdfBoxExtractor().extract(source, smallLimits)
        }

        assertEquals(RagImportRejection.TOO_LARGE, failure.reason)
    }

    @Test
    fun emptySourceIsRejectedAsEmpty() {
        val source = ExtractionSource(
            displayName = "empty.pdf",
            mimeType = "application/pdf",
            sizeBytes = 0L,
            openStream = { ByteArrayInputStream(ByteArray(0)) },
        )

        val failure = assertThrows(ExtractionFailure::class.java) {
            PdfBoxExtractor().extract(source, limits)
        }

        assertEquals(RagImportRejection.EMPTY, failure.reason)
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun failureOf(bytes: ByteArray): ExtractionFailure {
        val source = source(bytes)
        return assertThrows(ExtractionFailure::class.java) {
            PdfBoxExtractor().extract(source, limits)
        }
    }

    private fun source(bytes: ByteArray, displayName: String = "document.pdf"): ExtractionSource =
        ExtractionSource(
            displayName = displayName,
            mimeType = "application/pdf",
            sizeBytes = bytes.size.toLong(),
            openStream = { ByteArrayInputStream(bytes) },
        )

    /** One page per entry; a null entry adds a page with no content stream at all. */
    private fun pdfWithText(vararg pageTexts: String?): ByteArray = PDDocument().use { document ->
        pageTexts.forEach { document.addPage(PDPage(PDRectangle.LETTER)) }
        val font = PDType1Font.HELVETICA
        pageTexts.forEachIndexed { index, text ->
            if (text == null) return@forEachIndexed
            PDPageContentStream(document, document.getPage(index)).use { content ->
                content.beginText()
                content.setFont(font, 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText(text)
                content.endText()
            }
        }
        save(document)
    }

    private fun blankPdf(pageCount: Int): ByteArray = PDDocument().use { document ->
        repeat(pageCount) { document.addPage(PDPage(PDRectangle.LETTER)) }
        save(document)
    }

    private fun encryptedPdf(userPassword: String): ByteArray = PDDocument().use { document ->
        document.addPage(PDPage(PDRectangle.LETTER))
        PDPageContentStream(document, document.getPage(0)).use { content ->
            content.beginText()
            content.setFont(PDType1Font.HELVETICA, 12f)
            content.newLineAtOffset(72f, 720f)
            content.showText("Confidential text")
            content.endText()
        }
        val policy = StandardProtectionPolicy("owner-secret", userPassword, AccessPermission())
        policy.encryptionKeyLength = 128
        document.protect(policy)
        save(document)
    }

    private fun save(document: PDDocument): ByteArray {
        val out = ByteArrayOutputStream()
        document.save(out)
        return out.toByteArray()
    }
}

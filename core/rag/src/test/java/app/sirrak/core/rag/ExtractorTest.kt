package app.sirrak.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractorTest {

    private fun source(
        name: String,
        bytes: ByteArray,
        mime: String? = null,
    ): ExtractionSource = ExtractionSource(name, mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            for ((name, bytes) in entries) {
                stream.putNextEntry(ZipEntry(name))
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun registry(limits: RagLimits = RagLimits.Default): ExtractorRegistry =
        ExtractorRegistry(defaultExtractors(), DocumentFormats.all, limits)

    private val docxDocumentXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:p><w:r><w:t>عنوان المستند</w:t></w:r></w:p>
            <w:p><w:r><w:t>جملة أولى.</w:t><w:tab/><w:t>تابعة</w:t></w:r></w:p>
            <w:p><w:r><w:t>الجملة الثانية.</w:t><w:br/><w:t>بعد الكسر</w:t></w:r></w:p>
          </w:body>
        </w:document>
    """.trimIndent()

    private fun docxBytes(documentXml: String = docxDocumentXml): ByteArray = zip(
        listOf(
            "[Content_Types].xml" to text("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"),
            "word/document.xml" to text(documentXml),
        ),
    )

    private fun epubBytes(spineChapters: List<String> = listOf("ch1.xhtml", "ch2.xhtml")): ByteArray {
        val manifest = spineChapters.mapIndexed { index, href ->
            "<item id=\"ch" + (index + 1) + "\" href=\"" + href + "\" media-type=\"application/xhtml+xml\"/>"
        }.joinToString("")
        val spine = spineChapters.indices.joinToString("") { "<itemref idref=\"ch" + (it + 1) + "\"/>" }
        val opf = "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\">" +
            "<manifest>" + manifest + "</manifest><spine>" + spine + "</spine></package>"
        val container = "<?xml version=\"1.0\"?>" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
            "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "mimetype" to text("application/epub+zip")
        entries += "META-INF/container.xml" to text(container)
        entries += "OEBPS/content.opf" to text(opf)
        spineChapters.forEachIndexed { index, href ->
            val body = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
                "<h1>الفصل " + (index + 1) + "</h1><p>نص الفصل " + (index + 1) + ".</p></body></html>"
            entries += ("OEBPS/" + href) to text(body)
        }
        return zip(entries)
    }

    @Test
    fun formats_detectByExtensionThenMime() {
        assertEquals(DocumentFormats.PDF, DocumentFormats.detect("book.PDF", null))
        assertEquals(DocumentFormats.DOCX, DocumentFormats.detect("report.docx", "application/octet-stream"))
        assertEquals(DocumentFormats.MARKDOWN, DocumentFormats.detect("notes.md", null))
        assertEquals(DocumentFormats.TXT, DocumentFormats.detect("no-extension", "text/plain"))
        assertEquals(null, DocumentFormats.detect("archive.zip", null))
    }

    @Test
    fun registry_rejectsEmptyTooLargeAndUnsupported() {
        val tiny = RagLimits.Default.copy(maxDocumentBytes = 4)
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.EMPTY),
            registry().extract(source("empty.txt", ByteArray(0))),
        )
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.TOO_LARGE),
            registry(tiny).extract(source("big.txt", text("12345"))),
        )
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.UNSUPPORTED_TYPE),
            registry().extract(source("app.exe", text("MZ"))),
        )
    }

    @Test
    fun registry_pdfIsUnsupportedUntilAppRegistersAndroidExtractor() {
        val pdf = source("doc.pdf", text("%PDF-1.4"))
        assertFalse(registry().supports(pdf))
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.UNSUPPORTED_TYPE),
            registry().extract(pdf),
        )
    }

    @Test
    fun txt_extractsSinglePageAndStripsBom() {
        val outcome = registry().extract(source("notes.txt", text("\uFEFFمرحبا بالعالم")))
        val document = (outcome as ExtractionOutcome.Extracted).document
        assertEquals(DocumentFormats.TXT.id, document.formatId)
        assertEquals(1, document.pages.size)
        assertEquals("مرحبا بالعالم", document.plainText)
    }

    @Test
    fun txt_blankTextRejected() {
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.NO_TEXT),
            registry().extract(source("blank.txt", text("   \n  "))),
        )
    }

    @Test
    fun markdown_extractsWithMarkdownFormatId() {
        val outcome = registry().extract(source("readme.md", text("# عنوان\nنص")))
        assertEquals(DocumentFormats.MARKDOWN.id, (outcome as ExtractionOutcome.Extracted).document.formatId)
    }

    @Test
    fun docx_extractsParagraphsTabsAndBreaks() {
        val outcome = registry().extract(source("report.docx", docxBytes()))
        val document = (outcome as ExtractionOutcome.Extracted).document
        assertEquals(DocumentFormats.DOCX.id, document.formatId)
        assertEquals(1, document.pages.size)
        assertTrue(document.plainText.contains("عنوان المستند"))
        assertTrue(document.plainText.contains("جملة أولى.\tتابعة"))
        assertTrue(document.plainText.contains("الجملة الثانية."))
    }

    @Test
    fun docx_oleContainerRejectedAsEncrypted() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.ENCRYPTED),
            registry().extract(source("secret.docx", ole)),
        )
    }

    @Test
    fun docx_missingDocumentXmlRejectedCorrupt() {
        val bytes = zip(listOf("word/styles.xml" to text("<styles/>")))
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.CORRUPT),
            registry().extract(source("bad.docx", bytes)),
        )
    }

    @Test
    fun docx_noTextRejected() {
        val empty = "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>"
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.NO_TEXT),
            registry().extract(source("empty.docx", docxBytes(empty))),
        )
    }

    @Test
    fun epub_extractsOnePagePerSpineItem() {
        val outcome = registry().extract(source("book.epub", epubBytes()))
        val document = (outcome as ExtractionOutcome.Extracted).document
        assertEquals(DocumentFormats.EPUB.id, document.formatId)
        assertEquals(2, document.pages.size)
        assertEquals(1, document.pages[0].number)
        assertEquals(2, document.pages[1].number)
        assertTrue(document.pages[0].text.contains("الفصل 1"))
        assertTrue(document.pages[1].text.contains("نص الفصل 2"))
    }

    @Test
    fun epub_missingContainerRejectedCorrupt() {
        val bytes = zip(listOf("mimetype" to text("application/epub+zip")))
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.CORRUPT),
            registry().extract(source("bad.epub", bytes)),
        )
    }

    @Test
    fun epub_tooManyPagesRejected() {
        val limits = RagLimits.Default.copy(maxPages = 1)
        assertEquals(
            ExtractionOutcome.Rejected(RagImportRejection.TOO_MANY_PAGES),
            registry(limits).extract(source("book.epub", epubBytes())),
        )
    }

    @Test
    fun corruptZipRejectedNotCrashed() {
        val outcome = registry().extract(source("bad.docx", text("this is not a zip archive")))
        assertEquals(ExtractionOutcome.Rejected(RagImportRejection.CORRUPT), outcome)
    }

    @Test
    fun registry_isExtensibleWithNewFormats() {
        val fakeFormat = DocumentFormat("fake", setOf("application/x-fake"), setOf("fake"))
        val extractor = object : DocumentExtractor {
            override val formatId: String = fakeFormat.id
            override fun extract(source: ExtractionSource, limits: RagLimits): ExtractedDocument {
                val bytes = source.openStream().use { it.readBytes() }
                return ExtractedDocument(fakeFormat.id, listOf(ExtractedPage(1, String(bytes, Charsets.UTF_8))))
            }
        }
        val registry = ExtractorRegistry(listOf(extractor), listOf(fakeFormat))
        val outcome = registry.extract(source("data.fake", text("محتوى")))
        assertEquals("fake", (outcome as ExtractionOutcome.Extracted).document.formatId)
        assertEquals("محتوى", outcome.document.plainText)
    }
}

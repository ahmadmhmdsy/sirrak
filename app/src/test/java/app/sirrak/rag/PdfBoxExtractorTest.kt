package app.sirrak.rag

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
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [PdfBoxExtractor], with every PDF fixture generated in-memory by PDFBox itself.
 *
 * PDFBox-Android normally obtains its standard-14 AFM metrics and glyph lists from the AAR's
 * `assets/` directory through `PDFBoxResourceLoader.init(context)`. That loader needs a real Android
 * `Context`, which a JVM unit test cannot provide, so the test helper copies the dependency's own
 * resources from Gradle's transformed AAR (or from the AAR in the modules cache) beside the test
 * classes once; PDFBox's documented classpath fallback then finds them. Production never needs this
 * helper: the app passes `PDFBoxResourceLoader.init(application)` through the extractor's initializer.
 *
 * The fixtures intentionally stay English because the standard-14 Type1 fonts used to generate a PDF
 * have no Arabic glyphs; the extractor itself is script-agnostic and relies on each PDF's ToUnicode
 * mapping, which is the same path regardless of language.
 */
class PdfBoxExtractorTest {

    private val limits: RagLimits = RagLimits.Default

    @Before
    fun preparePdfBoxResources() {
        PdfBoxJvmAssets.ensure()
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
        assertEquals(2, extracted.plainText.lines().count { it.isNotBlank() })
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
    fun sourceStreamFailureIsRejectedAsCorrupt() {
        val source = ExtractionSource(
            displayName = "broken.pdf",
            mimeType = "application/pdf",
            sizeBytes = 128L,
            openStream = { throw IOException("cannot read the source") },
        )

        val failure = assertThrows(ExtractionFailure::class.java) {
            PdfBoxExtractor().extract(source, limits)
        }

        assertEquals(RagImportRejection.CORRUPT, failure.reason)
        assertTrue(failure.cause is IOException)
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
    fun streamLargerThanItsAdvertisedSizeIsStillRejectedAsTooLarge() {
        val payload = ByteArray(2048) { ' '.code.toByte() }
        val source = ExtractionSource(
            displayName = "understated.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1L,
            openStream = { ByteArrayInputStream(payload) },
        )

        val failure = assertThrows(ExtractionFailure::class.java) {
            PdfBoxExtractor().extract(source, limits.copy(maxDocumentBytes = 1024L))
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

/**
 * Bridges PDFBox-Android's AAR assets onto the JVM unit-test classpath.
 *
 * The dependency ships its AFM/glyph resources as AAR assets, not as JAR resources, so the classpath
 * fallback inside PDFBox cannot see them in a plain JVM test. Gradle's transformed AAR may expose
 * only a runtime jar, with the `assets` directory in a sibling transform, so the helper checks:
 * 1. `assets` beside the PDFBox classes or the transformed AAR;
 * 2. the Gradle transforms tree (where the exploded AAR keeps its `assets`);
 * 3. the original AAR in the Gradle modules cache, extracting its asset entries.
 *
 * Copying them beside the compiled test classes makes PDFBox's classpath fallback work while keeping
 * production wiring untouched. This is test infrastructure only and never runs on device.
 */
private object PdfBoxJvmAssets {

    private const val RESOURCE_ROOT = "com/tom_roush/pdfbox/resources"
    private const val GLYPH_LIST = "$RESOURCE_ROOT/glyphlist/glyphlist.txt"

    fun ensure() {
        if (classpathHas(GLYPH_LIST)) return

        val targetRoot = codeSource(PdfBoxExtractorTest::class.java)
        check(targetRoot.isDirectory) {
            "PDFBox JVM assets need a directory test-classpath root, found: " + targetRoot
        }
        val assetsRoot = findAssetsDirectory()
        when {
            assetsRoot != null ->
                File(assetsRoot, RESOURCE_ROOT).copyRecursively(
                    File(targetRoot, RESOURCE_ROOT),
                    overwrite = true,
                )

            else -> {
                val aar = findAar()
                    ?: error(
                        "Cannot locate PDFBox-Android assets for JVM tests. Checked next to " +
                            codeSource(PDFBoxResourceLoader::class.java) +
                            ", the Gradle transforms cache and the Gradle modules cache.",
                    )
                extractAssetsFromAar(aar, targetRoot)
            }
        }
        check(classpathHas(GLYPH_LIST)) {
            "Copying PDFBox assets did not make /$GLYPH_LIST visible on the test classpath."
        }
    }

    private fun classpathHas(path: String): Boolean =
        PdfBoxExtractorTest::class.java.getResource("/$path") != null

    /** The exploded AAR keeps `assets` beside `jars/classes.jar`; other transforms keep only a runtime jar. */
    private fun findAssetsDirectory(): File? {
        var directory: File? = codeSource(PDFBoxResourceLoader::class.java).let {
            if (it.isFile) it.parentFile else it
        }
        var depth = 0
        while (directory != null && depth < 5) {
            val candidate = File(directory, "assets")
            if (File(candidate, GLYPH_LIST).isFile) return candidate
            directory = directory.parentFile
            depth++
        }

        // Gradle 8 puts the transformed AAR under a sibling hash directory, not next to the runtime jar.
        val transforms = generateSequence(codeSource(PDFBoxResourceLoader::class.java)) { it.parentFile }
            .firstOrNull { it.name == "transforms" }
            ?: return null
        transforms.listFiles().orEmpty().forEach { hashDirectory ->
            File(hashDirectory, "transformed").listFiles().orEmpty().forEach { candidate ->
                if (candidate.isDirectory && candidate.name.startsWith("pdfbox-android")) {
                    val assets = File(candidate, "assets")
                    if (File(assets, GLYPH_LIST).isFile) return assets
                }
            }
        }
        return null
    }

    /** Last resort: the original AAR in Gradle's dependency cache contains the same assets as entries. */
    private fun findAar(): File? {
        val gradleHome = System.getenv("GRADLE_USER_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".gradle")
        val artifactDirectory = File(
            gradleHome,
            "caches/modules-2/files-2.1/com.tom-roush/pdfbox-android",
        )
        if (!artifactDirectory.isDirectory) return null
        return artifactDirectory.walkTopDown()
            .maxDepth(4)
            .firstOrNull { it.isFile && it.extension == "aar" }
    }

    private fun extractAssetsFromAar(aar: File, targetRoot: File) {
        val prefix = "assets/$RESOURCE_ROOT/"
        var copied = 0
        ZipFile(aar).use { archive ->
            archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) }
                .forEach { entry ->
                    val target = File(targetRoot, entry.name.removePrefix("assets/"))
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied++
                }
        }
        check(copied > 0) { "No PDFBox assets found inside " + aar }
    }

    private fun codeSource(type: Class<*>): File {
        val location = type.protectionDomain?.codeSource?.location
            ?: error("No code source for " + type.name)
        return File(location.toURI())
    }
}

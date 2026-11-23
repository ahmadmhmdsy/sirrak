package app.sirrak.core.rag

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-JVM extractors for the formats that are just text or a zip of XML: TXT, Markdown, DOCX and EPUB.
 * PDF needs an Android Context for font resources, so it lives in :app and is plugged into the same
 * [DocumentExtractor] registry. Adding a format here means one class plus one registry entry.
 *
 * All archive readers are bounded: a renamed zip bomb is refused as TOO_LARGE instead of exhausting the
 * process, and a damaged container surfaces as CORRUPT, never as a crash.
 */

/** Reads a stream to bytes with a hard ceiling; the import layer uses the same limit before this runs. */
internal object BoundedIo {
    fun readAll(stream: InputStream, ceiling: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        stream.use { input ->
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
}

/** Shared secure XML parsing and archive access for the zip-based formats. */
internal object XmlExtraction {
    private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024

    fun documentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        return builder
    }

    fun parse(bytes: ByteArray): Document = documentBuilder().parse(ByteArrayInputStream(bytes))

    /** Unzips all non-directory entries under hard per-entry and total byte ceilings. */
    fun unzip(bytes: ByteArray, limits: RagLimits): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.removePrefix("./")
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var entryBytes = 0L
                while (true) {
                    val read = zip.read(chunk)
                    if (read < 0) break
                    entryBytes += read
                    total += read
                    if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw ExtractionFailure(RagImportRejection.TOO_LARGE)
                    }
                    buffer.write(chunk, 0, read)
                }
                entries[name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }
        return entries
    }

    fun textOf(element: Element): String = element.textContent.orEmpty()
}

/** Text formats (TXT and Markdown): UTF-8, BOM stripped, one page, no syntax rewriting. */
class PlainTextExtractor(override val formatId: String) : DocumentExtractor {

    override fun extract(source: ExtractionSource, limits: RagLimits): ExtractedDocument {
        val bytes = BoundedIo.readAll(source.openStream(), limits.maxDocumentBytes)
        if (bytes.isEmpty()) throw ExtractionFailure(RagImportRejection.EMPTY)
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        if (text.isBlank()) throw ExtractionFailure(RagImportRejection.NO_TEXT)
        return ExtractedDocument(formatId, listOf(ExtractedPage(1, text)))
    }

    companion object {
        fun txt(): PlainTextExtractor = PlainTextExtractor(DocumentFormats.TXT.id)
        fun markdown(): PlainTextExtractor = PlainTextExtractor(DocumentFormats.MARKDOWN.id)
    }
}

/**
 * DOCX: word/document.xml only. Paragraphs become lines, tabs and explicit breaks are preserved, and a
 * heading-styled paragraph is emitted as a short line so the chunker can label citations.
 */
class DocxExtractor : DocumentExtractor {

    override val formatId: String = DocumentFormats.DOCX.id

    override fun extract(source: ExtractionSource, limits: RagLimits): ExtractedDocument {
        val bytes = BoundedIo.readAll(source.openStream(), limits.maxDocumentBytes)
        if (isOleContainer(bytes)) throw ExtractionFailure(RagImportRejection.ENCRYPTED)
        val entries = XmlExtraction.unzip(bytes, limits)
        val documentXml = entries["word/document.xml"]
            ?: throw ExtractionFailure(RagImportRejection.CORRUPT)
        val document = try {
            XmlExtraction.parse(documentXml)
        } catch (t: Throwable) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, t)
        }
        val paragraphs = document.getElementsByTagNameNS(WORD_NS, "p")
        val lines = ArrayList<String>(paragraphs.length)
        for (index in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(index) as? Element ?: continue
            val text = paragraphText(paragraph)
            if (text.isNotBlank()) lines += text
        }
        if (lines.isEmpty()) throw ExtractionFailure(RagImportRejection.NO_TEXT)
        return ExtractedDocument(formatId, listOf(ExtractedPage(1, lines.joinToString("\n"))))
    }

    private fun paragraphText(paragraph: Element): String {
        val out = StringBuilder()
        appendInlineText(paragraph, out)
        return out.toString().trim()
    }

    private fun appendInlineText(node: Node, out: StringBuilder) {
        when (node.nodeType) {
            Node.TEXT_NODE -> out.append(node.nodeValue)
            Node.ELEMENT_NODE -> {
                val element = node as Element
                if (element.namespaceURI == WORD_NS) {
                    when (element.localName) {
                        "t" -> out.append(element.textContent.orEmpty())
                        "tab" -> out.append('\t')
                        "br", "cr" -> out.append('\n')
                    }
                }
                var child = node.firstChild
                while (child != null) {
                    appendInlineText(child, out)
                    child = child.nextSibling
                }
            }
        }
    }

    private fun isOleContainer(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
    }

    private companion object {
        const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}

/**
 * EPUB: container.xml -> OPF manifest/spine -> XHTML body text, one [ExtractedPage] per spine item.
 * DRM-protected books are refused as ENCRYPTED instead of producing half text.
 */
class EpubExtractor : DocumentExtractor {

    override val formatId: String = DocumentFormats.EPUB.id

    override fun extract(source: ExtractionSource, limits: RagLimits): ExtractedDocument {
        val bytes = BoundedIo.readAll(source.openStream(), limits.maxDocumentBytes)
        val entries = XmlExtraction.unzip(bytes, limits)
        if (entries.containsKey("META-INF/encryption.xml")) {
            throw ExtractionFailure(RagImportRejection.ENCRYPTED)
        }
        val containerXml = entries["META-INF/container.xml"]
            ?: throw ExtractionFailure(RagImportRejection.CORRUPT)
        val container = try {
            XmlExtraction.parse(containerXml)
        } catch (t: Throwable) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, t)
        }
        val rootfile = container.getElementsByTagNameNS(CONTAINER_NS, "rootfile").item(0) as? Element
        val opfPath = rootfile?.getAttribute("full-path").orEmpty()
        if (opfPath.isEmpty()) throw ExtractionFailure(RagImportRejection.CORRUPT)
        val opfBytes = entries[opfPath] ?: throw ExtractionFailure(RagImportRejection.CORRUPT)
        val opf = try {
            XmlExtraction.parse(opfBytes)
        } catch (t: Throwable) {
            throw ExtractionFailure(RagImportRejection.CORRUPT, t)
        }
        val baseDir = opfPath.substringBeforeLast('/', "")
        val manifest = HashMap<String, String>()
        val items = opf.getElementsByTagNameNS(OPF_NS, "item")
        for (index in 0 until items.length) {
            val item = items.item(index) as? Element ?: continue
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = resolveHref(baseDir, href)
        }
        val spine = opf.getElementsByTagNameNS(OPF_NS, "itemref")
        val pages = ArrayList<ExtractedPage>()
        for (index in 0 until spine.length) {
            val itemref = spine.item(index) as? Element ?: continue
            val href = manifest[itemref.getAttribute("idref")] ?: continue
            val chapterBytes = entries[href] ?: continue
            val text = xhtmlText(chapterBytes)
            if (text.isBlank()) continue
            pages += ExtractedPage(pages.size + 1, text)
        }
        if (pages.isEmpty()) throw ExtractionFailure(RagImportRejection.NO_TEXT)
        return ExtractedDocument(formatId, pages)
    }

    private fun xhtmlText(bytes: ByteArray): String {
        val document = try {
            XmlExtraction.parse(bytes)
        } catch (t: Throwable) {
            // Some real-world EPUBs contain an invalid chapter; skip it rather than rejecting the book.
            return ""
        }
        val body = document.getElementsByTagNameNS(XHTML_NS, "body").item(0) as? Element
            ?: document.documentElement ?: return ""
        val out = StringBuilder()
        appendBlockText(body, out)
        return out.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun appendBlockText(node: Node, out: StringBuilder) {
        when (node.nodeType) {
            Node.TEXT_NODE -> out.append(node.nodeValue)
            Node.ELEMENT_NODE -> {
                val element = node as Element
                val name = element.localName
                if (name == "script" || name == "style" || name == "head") return
                val block = name in BLOCK_ELEMENTS
                var child = node.firstChild
                while (child != null) {
                    appendBlockText(child, out)
                    child = child.nextSibling
                }
                if (block) out.append('\n')
            }
        }
    }

    private fun resolveHref(baseDir: String, href: String): String {
        val decoded = try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (t: Throwable) {
            href
        }.removePrefix("./")
        if (decoded.startsWith("/")) return decoded.removePrefix("/")
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts += baseDir.split('/')
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private companion object {
        const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
        const val OPF_NS = "http://www.idpf.org/2007/opf"
        const val XHTML_NS = "http://www.w3.org/1999/xhtml"
        val BLOCK_ELEMENTS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "tr", "section", "article", "blockquote",
        )
    }
}

/** All extractors that are pure JVM; the app adds the Android PDF extractor to this list. */
fun defaultExtractors(): List<DocumentExtractor> = listOf(
    PlainTextExtractor.txt(),
    PlainTextExtractor.markdown(),
    DocxExtractor(),
    EpubExtractor(),
)

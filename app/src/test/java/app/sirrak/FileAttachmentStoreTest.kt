package app.sirrak

import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentImport
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.AttachmentLimits
import app.sirrak.core.runtime.AttachmentRejection
import app.sirrak.core.runtime.AttachmentSource
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M7 app-private attachment storage: bytes under attachments/<conversation>/<id>.<ext>, one JSON
 * sidecar per attachment, and a rolling byte cap that trusts the stream rather than the resolver.
 * Real files in a temp directory, fixed clock and id supplier, no Android runtime.
 */
class FileAttachmentStoreTest {

    private lateinit var root: File
    private var now = 1_000L
    private var ids = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sirrak-attachments").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(limits: AttachmentLimits = AttachmentLimits.Default): FileAttachmentStore =
        FileAttachmentStore(root = root, limits = limits, clock = { now }, nextId = { "a" + ids++ })

    private fun source(
        displayName: String,
        declaredMimeType: String?,
        bytes: ByteArray,
        declaredSize: Long = bytes.size.toLong(),
        opened: AtomicInteger? = null,
    ): AttachmentSource = AttachmentSource(displayName, declaredMimeType, declaredSize) {
        opened?.incrementAndGet()
        ByteArrayInputStream(bytes)
    }

    private fun rejectReason(result: AttachmentImport): AttachmentRejection =
        (result as AttachmentImport.Rejected).reason

    private fun attachmentDir(conversationId: String): File =
        File(root, "attachments/" + conversationId)

    private fun files(conversationId: String): List<File> =
        attachmentDir(conversationId).listFiles()?.sortedBy { it.name } ?: emptyList()

    private fun sidecar(conversationId: String, attachmentId: String): File =
        File(attachmentDir(conversationId), attachmentId + ".json")

    // ---- import ------------------------------------------------------------------------------

    @Test
    fun `a document import stores bytes metadata and a relative path`() {
        val store = store()
        val bytes = "تقرير قصير".toByteArray(Charsets.UTF_8)

        val stored = store.import("c1", source("Report.pdf", "application/pdf", bytes)) as AttachmentImport.Stored

        assertEquals(AttachmentKind.DOCUMENT, stored.attachment.kind)
        assertEquals("Report.pdf", stored.attachment.displayName)
        assertEquals("application/pdf", stored.attachment.mimeType)
        assertEquals(bytes.size.toLong(), stored.attachment.sizeBytes)
        assertEquals("attachments/c1/a0.pdf", stored.attachment.localPath)
        assertArrayEquals(bytes, File(root, "attachments/c1/a0.pdf").readBytes())

        val meta = Json.parseToJsonElement(sidecar("c1", "a0").readText()).jsonObject
        assertEquals(1, meta["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("a0", meta["id"]!!.jsonPrimitive.content)
        assertEquals("DOCUMENT", meta["kind"]!!.jsonPrimitive.content)
        assertEquals("Report.pdf", meta["displayName"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", meta["mimeType"]!!.jsonPrimitive.content)
        assertEquals(bytes.size.toString(), meta["sizeBytes"]!!.jsonPrimitive.content)
        assertEquals("attachments/c1/a0.pdf", meta["localPath"]!!.jsonPrimitive.content)
        assertEquals(1_000L, meta["createdAt"]!!.jsonPrimitive.content.toLong())

        assertEquals(stored.attachment, store.list("c1").single())
    }

    @Test
    fun `an oversized stream is rejected and leaves no orphan file`() {
        val store = store(AttachmentLimits(documentBytes = 8))

        val result = store.import("c1", source("big.txt", "text/plain", ByteArray(64), declaredSize = 4))

        assertEquals(AttachmentRejection.TOO_LARGE, rejectReason(result))
        assertTrue(files("c1").isEmpty())
        assertTrue(store.list("c1").isEmpty())
    }

    @Test
    fun `a declared oversize is rejected before the stream is opened`() {
        val opened = AtomicInteger()
        val store = store(AttachmentLimits(documentBytes = 8))

        val result = store.import(
            "c1",
            source("big.txt", "text/plain", ByteArray(9), declaredSize = 9, opened = opened),
        )

        assertEquals(AttachmentRejection.TOO_LARGE, rejectReason(result))
        assertEquals(0, opened.get())
        assertFalse(attachmentDir("c1").exists())
    }

    @Test
    fun `an unsupported type is rejected before the stream is opened`() {
        val opened = AtomicInteger()
        val store = store()

        val result = store.import("c1", source("archive.zip", null, ByteArray(4), opened = opened))

        assertEquals(AttachmentRejection.UNSUPPORTED_TYPE, rejectReason(result))
        assertEquals(0, opened.get())
        assertTrue(files("c1").isEmpty())
    }

    @Test
    fun `an empty stream is rejected but a stream that lies about its size is copied`() {
        val store = store()

        assertEquals(
            AttachmentRejection.EMPTY,
            rejectReason(store.import("c1", source("empty.txt", "text/plain", ByteArray(0)))),
        )
        assertTrue(files("c1").isEmpty())

        val stored = store.import(
            "c1",
            source("notes.txt", "text/plain", "abc".toByteArray(), declaredSize = 0),
        ) as AttachmentImport.Stored
        assertEquals(3L, stored.attachment.sizeBytes)
        assertEquals("attachments/c1/a0.txt", stored.attachment.localPath)
    }

    @Test
    fun `shared text stores its extracted text mime and name`() {
        val store = store()
        val text = "مرحبا بالعالم"

        val stored = store.saveSharedText("c1", text, "  ملاحظة  ") as AttachmentImport.Stored

        assertEquals(AttachmentKind.SHARED_TEXT, stored.attachment.kind)
        assertEquals("text/plain", stored.attachment.mimeType)
        assertEquals("ملاحظة", stored.attachment.displayName)
        assertEquals(text, stored.attachment.extractedText)
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), stored.attachment.sizeBytes)
        assertEquals("clipboard", stored.attachment.meta["source"])
        assertEquals("attachments/c1/a0.txt", stored.attachment.localPath)
        assertEquals(text, File(root, "attachments/c1/a0.txt").readText(Charsets.UTF_8))
        assertEquals(stored.attachment, store.list("c1").single())

        assertEquals(AttachmentRejection.EMPTY, rejectReason(store.saveSharedText("c1", "   ", "x")))
        val tiny = store(AttachmentLimits(sharedTextChars = 3))
        assertEquals(AttachmentRejection.TOO_LARGE, rejectReason(tiny.saveSharedText("c1", "four", "x")))
    }

    @Test
    fun `images resolve their extension from the mime type`() {
        val store = store()

        val photo = store.import("c1", source("photo.JPG", null, ByteArray(4))) as AttachmentImport.Stored
        assertEquals(AttachmentKind.IMAGE, photo.attachment.kind)
        assertEquals("image/jpeg", photo.attachment.mimeType)
        assertEquals("attachments/c1/a0.jpg", photo.attachment.localPath)

        val scan = store.import("c1", source("scan", "IMAGE/PNG", ByteArray(4))) as AttachmentImport.Stored
        assertEquals(AttachmentKind.IMAGE, scan.attachment.kind)
        assertEquals("attachments/c1/a1.png", scan.attachment.localPath)
    }

    @Test
    fun `a repeated id never overwrites an existing attachment`() {
        val store = FileAttachmentStore(root = root, clock = { now }, nextId = { "same" })

        val first = store.import("c1", source("one.txt", "text/plain", "1".toByteArray())) as AttachmentImport.Stored
        val second = store.import("c1", source("two.txt", "text/plain", "2".toByteArray())) as AttachmentImport.Stored

        assertEquals("same", first.attachment.id)
        assertFalse(first.attachment.id == second.attachment.id)
        assertEquals(2, store.list("c1").size)
        assertEquals(2, files("c1").count { it.extension == "txt" })
        assertTrue(File(root, first.attachment.localPath!!).isFile)
        assertTrue(File(root, second.attachment.localPath!!).isFile)
    }

    // ---- list / delete -----------------------------------------------------------------------

    @Test
    fun `list skips corrupt and unknown sidecars`() {
        val store = store()
        store.import("c1", source("one.txt", "text/plain", "1".toByteArray()))
        store.import("c1", source("two.txt", "text/plain", "2".toByteArray()))

        sidecar("c1", "a1").writeText("{not json at all")
        File(attachmentDir("c1"), "a2.txt").writeText("x")
        sidecar("c1", "a2").writeText(
            "{\"schemaVersion\":1,\"id\":\"a2\",\"kind\":\"ALIEN\",\"displayName\":\"x\"," +
                "\"mimeType\":\"text/plain\",\"sizeBytes\":1}",
        )

        val listed = store.list("c1")

        assertEquals(listOf("a0"), listed.map { it.id })
        assertEquals("one.txt", listed.single().displayName)
    }

    @Test
    fun `listing an unknown conversation is empty`() {
        val store = store()

        assertTrue(store.list("never").isEmpty())
        assertTrue(store.list("").isEmpty())
    }

    @Test
    fun `delete removes both the bytes and the sidecar`() {
        val store = store()
        val stored = store.import(
            "c1",
            source("notes.md", "text/markdown", "body".toByteArray()),
        ) as AttachmentImport.Stored
        val bytes = File(root, stored.attachment.localPath!!)
        assertTrue(bytes.isFile)

        assertTrue(store.delete("c1", stored.attachment.id))

        assertFalse(bytes.exists())
        assertFalse(sidecar("c1", stored.attachment.id).exists())
        assertTrue(store.list("c1").isEmpty())
        assertFalse(store.delete("c1", stored.attachment.id))
        assertFalse(store.delete("missing", stored.attachment.id))
    }

    @Test
    fun `deleteConversation removes its directory and never leaves the attachments root`() {
        val store = store()
        store.import("c1", source("one.txt", "text/plain", "1".toByteArray()))
        store.import("c2", source("two.txt", "text/plain", "2".toByteArray()))
        val sentinel = File(root, "sentinel.txt").apply { writeText("keep") }

        store.deleteConversation("..")

        assertTrue(sentinel.isFile)
        assertTrue(root.isDirectory)
        assertTrue(attachmentDir("c1").isDirectory)

        store.deleteConversation("c1")

        assertFalse(attachmentDir("c1").exists())
        assertTrue(attachmentDir("c2").isDirectory)
        store.deleteConversation("unknown")
        store.deleteConversation("")
    }

    // ---- names and ids -----------------------------------------------------------------------

    @Test
    fun `display names are stripped of separators control characters and length`() {
        val store = store()

        val stored = store.import(
            "c1",
            source("  ..\\secret/name\n.txt  ", "text/plain", "x".toByteArray()),
        ) as AttachmentImport.Stored
        val name = stored.attachment.displayName
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('\n'))
        assertEquals(name, name.trim())
        assertTrue(name.length <= 80)

        val long = store.import(
            "c1",
            source("b".repeat(200) + ".txt", "text/plain", "y".toByteArray()),
        ) as AttachmentImport.Stored
        assertEquals(80, long.attachment.displayName.length)

        val fallback = store.import("c1", source("   ", "text/plain", "z".toByteArray())) as AttachmentImport.Stored
        assertEquals("attachment.txt", fallback.attachment.displayName)

        val shared = store.saveSharedText("c1", "hello", "   ") as AttachmentImport.Stored
        assertEquals("Shared text", shared.attachment.displayName)
    }

    @Test
    fun `a blank or traversing conversation id never writes outside the root`() {
        val store = store()

        assertTrue(store.import("   ", source("one.txt", "text/plain", "1".toByteArray())) is AttachmentImport.Rejected)
        assertTrue(files("   ").isEmpty())
        assertTrue(store.list("").isEmpty())
        store.deleteConversation("")

        val stored = store.import(
            "../escape",
            source("two.txt", "text/plain", "2".toByteArray()),
        ) as AttachmentImport.Stored
        val relative = stored.attachment.localPath!!
        val segments = relative.split('/')
        assertTrue(relative.startsWith("attachments/"))
        assertFalse(segments.contains(".."))
        val file = File(root, relative)
        assertTrue(file.isFile)
        assertTrue(
            file.canonicalPath.startsWith(File(root, "attachments").canonicalPath + File.separator),
        )
    }

    @Test
    fun `the stored attachment fits the model for a fresh store instance`() {
        val stored = store().import(
            "c1",
            source("Report.pdf", "application/pdf", "abc".toByteArray()),
        ) as AttachmentImport.Stored
        assertEquals(AttachmentKind.DOCUMENT, stored.attachment.kind)

        val reopened = store()

        assertEquals(listOf(stored.attachment), reopened.list("c1"))
    }

    // ---- open (M9 vision) --------------------------------------------------------------------

    @Test
    fun `opening a stored image returns exactly the bytes that were imported`() {
        val store = store()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0x00)
        val stored = store.import(
            "c1",
            source("photo.jpg", "image/jpeg", bytes),
        ) as AttachmentImport.Stored
        assertEquals(AttachmentKind.IMAGE, stored.attachment.kind)

        val opened = store.open("c1", stored.attachment)?.use { it.readBytes() }

        assertArrayEquals(bytes, opened)
    }

    @Test
    fun `opening refuses another conversation, an unknown id and a blank path`() {
        val store = store()
        val stored = store.import(
            "c1",
            source("photo.jpg", "image/jpeg", "jpeg".toByteArray()),
        ) as AttachmentImport.Stored

        // Same file, wrong conversation: the reader must not cross the per-conversation boundary.
        assertEquals(null, store.open("c2", stored.attachment))
        assertEquals(null, store.open("c1", stored.attachment.copy(id = "missing")))
        assertEquals(null, store.open("c1", stored.attachment.copy(localPath = null)))
        // A hostile metadata edit that points outside the attachments root is refused.
        assertEquals(null, store.open("c1", stored.attachment.copy(localPath = "../../secret.txt")))
    }
}

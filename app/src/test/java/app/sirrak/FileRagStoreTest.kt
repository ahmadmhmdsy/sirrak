package app.sirrak

import app.sirrak.core.rag.DocumentFormats
import app.sirrak.core.rag.RagChunk
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagDocumentStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRagStoreTest {

    private val root: File = Files.createTempDirectory("sirrak-rag-store").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun document(
        id: String = "d-test",
        status: RagDocumentStatus = RagDocumentStatus.READY,
        name: String = "تقرير.pdf",
    ): RagDocument = RagDocument(
        id = id,
        displayName = name,
        formatId = DocumentFormats.PDF.id,
        sizeBytes = 1234L,
        importedAt = 1_000L,
        pageCount = 3,
        chunkCount = 2,
        status = status,
        indexedAt = if (status == RagDocumentStatus.READY) 2_000L else null,
        embeddingModelId = if (status == RagDocumentStatus.READY) "fake" else null,
    )

    private fun chunk(id: String, ordinal: Int, text: String): RagChunk = RagChunk(
        id = id,
        documentId = "d-test",
        ordinal = ordinal,
        text = text,
        page = ordinal + 1,
    )

    private fun vectors(): Map<String, FloatArray> = mapOf(
        "d-test#0" to floatArrayOf(1f, 0f, 0f),
        "d-test#1" to floatArrayOf(0f, 1f, 0.5f),
    )

    @Test
    fun writeIndexThenListRoundTripsMetadataChunksAndVectors() {
        val store = FileRagStore(root)
        val chunks = listOf(chunk("d-test#0", 0, "جملة أولى"), chunk("d-test#1", 1, "جملة ثانية"))
        store.writeIndex(document(), chunks, vectors(), 3)

        val loaded = store.list()
        assertEquals(1, loaded.size)
        assertEquals("تقرير.pdf", loaded.first().displayName)
        assertEquals(RagDocumentStatus.READY, loaded.first().status)
        assertEquals(2, loaded.first().chunkCount)
        assertEquals(chunks, store.chunks("d-test"))
        val stored = store.vectors("d-test")
        assertNotNull(stored)
        assertEquals(3, stored!!.dimensions)
        assertEquals(1f, stored.byChunkId.getValue("d-test#0")[0])
    }

    @Test
    fun originalFileRoundTripsAndReportsSize() {
        val store = FileRagStore(root)
        val size = store.writeOriginal("d-test", "pdf", { ByteArrayInputStream("PDFDATA".toByteArray()) }, 1024)
        assertEquals(7L, size)
        assertEquals(7L, store.originalSize("d-test"))
        assertEquals("PDFDATA", store.originalStream("d-test")!!.bufferedReader().use { it.readText() })
    }

    @Test
    fun writeOriginalEnforcesMaxBytesAndLeavesNoPartialFile() {
        val store = FileRagStore(root)
        assertThrows(RagTooLargeException::class.java) {
            store.writeOriginal("d-big", "txt", { ByteArrayInputStream(ByteArray(64)) }, 16)
        }
        assertEquals(0L, store.originalSize("d-big"))
        assertFalse(File(File(root, "rag/documents"), "d-big/original.txt").exists())
    }

    @Test
    fun writingExtractedIndexRemovesStaleVectors() {
        val store = FileRagStore(root)
        val chunks = listOf(chunk("d-test#0", 0, "نص"))
        store.writeIndex(document(), chunks, vectors(), 3)
        assertNotNull(store.vectors("d-test"))

        store.writeIndex(document(status = RagDocumentStatus.EXTRACTED), chunks, null, 0)
        assertNull(store.vectors("d-test"))
        assertEquals(RagDocumentStatus.EXTRACTED, store.metadata("d-test")!!.status)
    }

    @Test
    fun deleteRemovesDocumentDirectoryAndReturnsFalseForUnknown() {
        val store = FileRagStore(root)
        store.writeIndex(document(), listOf(chunk("d-test#0", 0, "نص")), vectors(), 3)
        assertTrue(store.delete("d-test"))
        assertNull(store.metadata("d-test"))
        assertTrue(store.chunks("d-test").isEmpty())
        assertFalse(store.delete("d-test"))
        assertFalse(store.delete("../escape"))
    }

    @Test
    fun storeIgnoresInterruptedImportDirectories() {
        val store = FileRagStore(root)
        File(File(File(root, "rag/documents"), "d-broken"), "original.pdf").mkdirs()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun storeIgnoresCorruptMetadata() {
        val store = FileRagStore(root)
        val dir = File(File(root, "rag/documents"), "d-corrupt")
        dir.mkdirs()
        File(dir, "metadata.json").writeText("{not json")
        assertTrue(store.list().isEmpty())
        assertNull(store.metadata("d-corrupt"))
    }

    @Test
    fun vectorsAreToleratedWhenTheChunkCountChanged() {
        val store = FileRagStore(root)
        store.writeIndex(document(), listOf(chunk("d-test#0", 0, "نص")), vectors(), 3)
        // A newer chunk file with different count must invalidate the old vectors, not misalign them.
        store.writeIndex(
            document(status = RagDocumentStatus.EXTRACTED),
            listOf(chunk("d-test#0", 0, "نص"), chunk("d-test#1", 1, "نص ثان")),
            null,
            0,
        )
        assertNull(store.vectors("d-test"))
    }

    @Test
    fun invalidDocumentIdsAreRejected() {
        val store = FileRagStore(root)
        assertThrows(RagStorageException::class.java) {
            store.writeMetadata(document(id = "../escape"))
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun newestImportComesFirst() {
        val store = FileRagStore(root)
        store.writeIndex(document(id = "d-old").copy(importedAt = 1L), emptyList(), null, 0)
        store.writeIndex(document(id = "d-new").copy(importedAt = 2L), emptyList(), null, 0)
        assertEquals(listOf("d-new", "d-old"), store.list().map { it.id })
    }
}

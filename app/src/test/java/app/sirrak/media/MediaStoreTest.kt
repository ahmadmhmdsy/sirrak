package app.sirrak.media

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

/**
 * The media store's persistence contract: what survives a restart, what a crash may leave behind, and
 * how a replace behaves. No Android and no Compose: a real temp directory is the whole environment.
 */
class MediaStoreTest {

    private val root: File = Files.createTempDirectory("sirrak-media-store").toFile()
    private val filesDir: File = File(root, "media/files")

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun store(): FileMediaStore = FileMediaStore(root)

    private fun importOne(
        store: FileMediaStore,
        id: String = "m-1",
        name: String = "تقرير.pdf",
        bytes: ByteArray = "hello".toByteArray(),
    ): MediaRecord {
        val stored = store.writeMedia(id, name) { ByteArrayInputStream(bytes) }
        return MediaRecord(
            id = id,
            kind = MediaKind.DOCUMENT,
            displayName = name,
            sizeBytes = stored.sizeBytes,
            retention = MediaRetention.KEEP,
            access = MediaAccess.GRANTED,
            createdAt = 10L,
            fileName = stored.fileName,
        )
    }

    @Test
    fun writeMediaCopiesBytesAndReportsTheirRealSize() {
        val store = store()
        val record = importOne(store, bytes = "1234567890".toByteArray())
        assertEquals(10L, record.sizeBytes)
        assertEquals(10L, store.fileOf(record)?.length())
        assertEquals(10L, store.storageBytes())
    }

    @Test
    fun snapshotRoundTripsPolicyAndRecordsAcrossInstances() {
        val first = store()
        val record = importOne(first)
        val policy = MediaPolicy(
            defaultRetention = MediaRetention.ONCE,
            defaultAccess = MediaAccess.DENIED,
        )
        first.write(MediaSnapshot(policy = policy, records = listOf(record)))

        val reopened = store().snapshot()
        assertEquals(policy, reopened.policy)
        assertEquals(1, reopened.records.size)
        assertEquals(record, reopened.records[0])
    }

    @Test
    fun replacingAnIdLeavesExactlyOneFile() {
        val store = store()
        store.writeMedia("m-1", "first.pdf") { ByteArrayInputStream("aa".toByteArray()) }
        val second = store.writeMedia("m-1", "second.txt") { ByteArrayInputStream("bbbb".toByteArray()) }
        val names = filesDir.listFiles()?.map { it.name }?.sorted().orEmpty()
        assertEquals(listOf(second.fileName), names)
    }

    @Test
    fun transcriptIsStoredNextToTheOriginal() {
        val store = store()
        val record = importOne(store)
        val transcript = store.writeTranscript(record.id, "هذا نص التفريغ")
        val persisted = record.copy(
            transcriptStatus = MediaTranscriptStatus.READY,
            transcriptFileName = transcript.fileName,
        )
        store.write(MediaSnapshot(records = listOf(persisted)))

        val reopened = store().snapshot().records.single()
        val file = store.transcriptOf(reopened)
        assertNotNull(file)
        assertEquals("هذا نص التفريغ", file?.readText(Charsets.UTF_8))
        assertTrue(reopened.transcriptStatus == MediaTranscriptStatus.READY)
    }

    @Test
    fun oversizedStreamIsRefusedWithoutLeavingAFile() {
        val store = store()
        val error = assertThrows(MediaTooLargeException::class.java) {
            store.writeMedia("m-1", "big.pdf", maxBytes = 4L) {
                ByteArrayInputStream(ByteArray(10) { 1 })
            }
        }
        assertEquals(4L, error.limitBytes)
        assertEquals(emptyList<String>(), filesDir.listFiles()?.map { it.name }.orEmpty())
    }

    @Test
    fun emptyStreamIsRefusedWithoutLeavingAFile() {
        val store = store()
        assertThrows(MediaStorageException::class.java) {
            store.writeMedia("m-1", "empty.pdf") { ByteArrayInputStream(ByteArray(0)) }
        }
        assertEquals(emptyList<String>(), filesDir.listFiles()?.map { it.name }.orEmpty())
    }

    @Test
    fun recordWithoutItsFileIsDroppedFromTheShelf() {
        val store = store()
        val ghost = MediaRecord(
            id = "ghost",
            kind = MediaKind.DOCUMENT,
            displayName = "ضائع.pdf",
            sizeBytes = 3L,
            retention = MediaRetention.KEEP,
            access = MediaAccess.GRANTED,
            createdAt = 1L,
            fileName = "ghost.pdf",
        )
        store.write(MediaSnapshot(records = listOf(ghost)))
        assertEquals(0, store.snapshot().records.size)
    }

    @Test
    fun unreferencedFileIsRemovedOnTheNextOpen() {
        val store = store()
        filesDir.mkdirs()
        val orphan = File(filesDir, "orphan.pdf")
        orphan.writeBytes("left behind".toByteArray())
        assertTrue(orphan.isFile)

        store.snapshot()
        assertFalse(orphan.exists())
    }

    @Test
    fun corruptIndexKeepsTheFilesOnDisk() {
        val store = store()
        val record = importOne(store)
        store.write(MediaSnapshot(records = listOf(record)))
        File(root, "media/index.json").writeText("{not json")

        val recovered = store().snapshot()
        assertEquals(0, recovered.records.size)
        assertTrue(store.fileOf(record)?.isFile == true)
        assertTrue(File(root, "media/index.json.corrupt").isFile)
    }

    @Test
    fun fileNamesThatEscapeTheStoreAreNeverResolved() {
        val store = store()
        val escaped = MediaRecord(
            id = "escape",
            kind = MediaKind.DOCUMENT,
            displayName = "خارج",
            sizeBytes = 1L,
            retention = MediaRetention.KEEP,
            access = MediaAccess.GRANTED,
            createdAt = 1L,
            fileName = "../../secret.txt",
        )
        assertNull(store.fileOf(escaped))
    }

    @Test
    fun deleteFilesRemovesMainAndTranscript() {
        val store = store()
        val record = importOne(store)
        val transcript = store.writeTranscript(record.id, "text")
        val full = record.copy(transcriptFileName = transcript.fileName)
        store.deleteFiles(full)
        assertEquals(emptyList<String>(), filesDir.listFiles()?.map { it.name }.orEmpty())
    }
}

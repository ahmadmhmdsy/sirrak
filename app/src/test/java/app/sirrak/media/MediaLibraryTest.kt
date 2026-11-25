package app.sirrak.media

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 acceptance, second half: the coordinator enforces kept-vs-once end to end with fakes for the two
 * Android seams. Every assertion here is a user-visible promise: ONCE never reaches the index, ONCE
 * does not come back after a restart, revoking access removes the item from the index, and an audio
 * item without a Whisper model is refused before anything is copied.
 */
class MediaLibraryTest {

    private val root: File = Files.createTempDirectory("sirrak-media-library").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private class FakeIndex : MediaIndexPort {
        val indexed = mutableListOf<MediaSource>()
        val removed = mutableListOf<String>()
        var result: String? = "rag-1"

        override suspend fun index(source: MediaSource): String? {
            indexed += source
            return result
        }

        override suspend fun remove(documentId: String) {
            removed += documentId
        }
    }

    private class FakeTranscriber : MediaTranscriptionPort {
        var availability: MediaTranscriptionAvailability = MediaTranscriptionAvailability.Available
        var result: MediaTranscriptionResult = MediaTranscriptionResult.Success("نص التفريغ", "ar")
        var calls = 0

        override fun availability(): MediaTranscriptionAvailability = availability

        override suspend fun transcribe(file: File, onPhase: (MediaPhase) -> Unit): MediaTranscriptionResult {
            calls += 1
            assertTrue("the audio source must be stored before transcription", file.isFile)
            onPhase(MediaPhase.TRANSCRIBING)
            return result
        }
    }

    private fun source(
        name: String = "تقرير.pdf",
        body: String = "hello media",
        mime: String? = "application/pdf",
    ): MediaSource {
        val bytes = body.toByteArray()
        return MediaSource(name, mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }

    private fun store(): FileMediaStore = FileMediaStore(root)

    private fun library(
        store: FileMediaStore = store(),
        index: MediaIndexPort? = FakeIndex(),
        transcriber: MediaTranscriptionPort? = null,
    ): FileMediaLibrary = FileMediaLibrary(store = store, index = index, transcriber = transcriber)

    @Test
    fun keptGrantedDocumentIsIndexedAndListed() = runBlocking {
        val index = FakeIndex()
        val library = library(index = index)
        val result = library.importDocument(source(), MediaRetention.KEEP)
        val imported = result as MediaImportResult.Imported

        assertNotNull(imported.record.indexedDocumentId)
        assertNull(imported.indexFailure)
        assertEquals(1, index.indexed.size)
        assertEquals(1, library.records.value.size)
        assertEquals(MediaTaskState.Idle, library.task.value)
    }

    @Test
    fun onceDocumentIsNeverIndexed() = runBlocking {
        val index = FakeIndex()
        val library = library(index = index)
        val result = library.importDocument(source(), MediaRetention.ONCE)
        val imported = result as MediaImportResult.Imported

        assertNull(imported.record.indexedDocumentId)
        assertEquals(0, index.indexed.size)
        assertTrue(imported.record.isOnce)
    }

    @Test
    fun imageIsStoredButNeverIndexed() = runBlocking {
        val index = FakeIndex()
        val library = library(index = index)
        val result = library.importImage(source(name = "صورة.jpg", mime = "image/jpeg"), MediaRetention.KEEP)
        val imported = result as MediaImportResult.Imported

        assertNull(imported.record.indexedDocumentId)
        assertEquals(0, index.indexed.size)
        assertEquals(MediaKind.IMAGE, imported.record.kind)
    }

    @Test
    fun onceItemAndItsFileAreGoneAfterReopen() = runBlocking {
        val store = store()
        val first = library(store = store)
        val imported = first.importDocument(source(), MediaRetention.ONCE) as MediaImportResult.Imported
        val file = store.fileOf(imported.record)
        assertNotNull(file)

        val reopened = library(store = store)
        assertTrue(reopened.records.value.isEmpty())
        assertFalse("the temporary file must not survive a restart", file!!.exists())
    }

    @Test
    fun keptItemSurvivesReopen() = runBlocking {
        val store = store()
        val first = library(store = store)
        first.importDocument(source(), MediaRetention.KEEP)

        val reopened = library(store = store)
        assertEquals(1, reopened.records.value.size)
        assertEquals(MediaRetention.KEEP, reopened.records.value[0].retention)
    }

    @Test
    fun revokingAccessRemovesTheIndexEntryButKeepsTheItem() = runBlocking {
        val index = FakeIndex()
        val library = library(index = index)
        val imported = library.importDocument(source()) as MediaImportResult.Imported
        val documentId = imported.record.indexedDocumentId

        library.setAccess(imported.record.id, MediaAccess.DENIED)

        assertEquals(listOf(documentId), index.removed)
        val updated = library.records.value.single()
        assertNull(updated.indexedDocumentId)
        assertEquals(MediaAccess.DENIED, updated.access)
        assertEquals(1, library.records.value.size)
    }

    @Test
    fun regrantingAccessIndexesTheItemAgain() = runBlocking {
        val index = FakeIndex()
        val library = library(index = index)
        val imported = library.importDocument(source()) as MediaImportResult.Imported
        library.setAccess(imported.record.id, MediaAccess.DENIED)
        index.result = "rag-2"

        library.setAccess(imported.record.id, MediaAccess.GRANTED)

        assertEquals("rag-2", library.records.value.single().indexedDocumentId)
        assertEquals(2, index.indexed.size)
    }

    @Test
    fun audioWithoutAModelIsRefusedBeforeAnythingIsCopied() = runBlocking {
        val transcriber = FakeTranscriber().apply {
            availability = MediaTranscriptionAvailability.Missing(MediaReasons.NO_SPEECH_MODEL)
        }
        val library = library(transcriber = transcriber)
        val result = library.importAudio(source(name = "مذكرة.m4a", mime = "audio/mp4"))

        assertEquals(MediaImportResult.Rejected(MediaReasons.NO_SPEECH_MODEL), result)
        assertTrue(library.records.value.isEmpty())
        assertEquals(
            MediaTaskState.Failed(MediaKind.AUDIO, "مذكرة.m4a", MediaReasons.NO_SPEECH_MODEL),
            library.task.value,
        )
        assertEquals(0, transcriber.calls)
    }

    @Test
    fun audioTranscriptIsStoredAndIndexedAsText() = runBlocking {
        val index = FakeIndex()
        val transcriber = FakeTranscriber()
        val library = library(index = index, transcriber = transcriber)
        val result = library.importAudio(source(name = "مقابلة.wav", mime = "audio/wav"), MediaRetention.KEEP)
        val imported = result as MediaImportResult.Imported

        assertEquals(MediaTranscriptStatus.READY, imported.record.transcriptStatus)
        assertNotNull(imported.record.indexedDocumentId)
        assertEquals("نص التفريغ", library.transcriptText(imported.record.id))
        assertEquals(1, index.indexed.size)
        assertTrue(index.indexed.single().displayName.endsWith("transcript.txt"))
        assertEquals("text/plain", index.indexed.single().mimeType)
    }

    @Test
    fun onceAudioIsTranscribedButNeverIndexedAndExpires() = runBlocking {
        val index = FakeIndex()
        val store = store()
        val library = library(store = store, index = index, transcriber = FakeTranscriber())
        val imported = library.importAudio(source(name = "مذكرة.ogg", mime = "audio/ogg"), MediaRetention.ONCE)
            as MediaImportResult.Imported

        assertEquals(MediaTranscriptStatus.READY, imported.record.transcriptStatus)
        assertNull(imported.record.indexedDocumentId)
        assertEquals(0, index.indexed.size)
        assertEquals("نص التفريغ", library.transcriptText(imported.record.id))

        val reopened = library(store = store, index = index, transcriber = FakeTranscriber())
        assertTrue(reopened.records.value.isEmpty())
        assertNull(reopened.transcriptText(imported.record.id))
    }

    @Test
    fun failedTranscriptionKeepsTheItemAndRetrySucceeds() = runBlocking {
        val transcriber = FakeTranscriber().apply {
            result = MediaTranscriptionResult.Failure(MediaReasons.DECODE_FAILED)
        }
        val library = library(transcriber = transcriber)
        val imported = library.importAudio(source(name = "صوت.amr", mime = "audio/amr")) as MediaImportResult.Imported

        assertEquals(MediaTranscriptStatus.FAILED, imported.record.transcriptStatus)
        assertEquals(MediaReasons.DECODE_FAILED, imported.record.transcriptFailure)
        assertEquals(
            MediaTaskState.Failed(MediaKind.AUDIO, "صوت.amr", MediaReasons.DECODE_FAILED),
            library.task.value,
        )
        assertEquals(1, library.records.value.size)

        transcriber.result = MediaTranscriptionResult.Success("نص بعد المحاولة", "ar")
        val retried = library.retryTranscription(imported.record.id) as MediaImportResult.Imported
        assertEquals(MediaTranscriptStatus.READY, retried.record.transcriptStatus)
        assertEquals("نص بعد المحاولة", library.transcriptText(imported.record.id))
    }

    @Test
    fun deleteRemovesTheFilesAndTheIndexEntry() = runBlocking {
        val index = FakeIndex()
        val store = store()
        val library = library(store = store, index = index)
        val imported = library.importDocument(source()) as MediaImportResult.Imported
        val file = store.fileOf(imported.record)

        library.delete(imported.record.id)

        assertTrue(library.records.value.isEmpty())
        assertFalse(file!!.exists())
        assertEquals(listOf(imported.record.indexedDocumentId), index.removed)
    }

    @Test
    fun consumingAnItemDeletesItOnlyWhenItWasTemporary() = runBlocking {
        val library = library(index = FakeIndex())
        val once = library.importDocument(source(name = "مؤقت.txt", mime = "text/plain"), MediaRetention.ONCE)
            as MediaImportResult.Imported
        val kept = library.importDocument(source(name = "دائم.txt", mime = "text/plain"), MediaRetention.KEEP)
            as MediaImportResult.Imported

        library.consume(once.record.id)
        library.consume(kept.record.id)

        assertEquals(listOf(kept.record.id), library.records.value.map { it.id })
    }

    @Test
    fun defaultPolicyIsAppliedWhenTheImportDoesNotDecide() = runBlocking {
        val index = FakeIndex()
        val library = library(index = index)
        library.setPolicy(MediaPolicy(defaultRetention = MediaRetention.ONCE, defaultAccess = MediaAccess.DENIED))

        val imported = library.importDocument(source()) as MediaImportResult.Imported

        assertEquals(MediaRetention.ONCE, imported.record.retention)
        assertEquals(MediaAccess.DENIED, imported.record.access)
        assertEquals(0, index.indexed.size)
    }

    @Test
    fun deniedDefaultAccessStoresTheItemWithoutIndexingIt() = runBlocking {
        val index = FakeIndex()
        val library = library(index = index)
        library.setPolicy(MediaPolicy(defaultRetention = MediaRetention.KEEP, defaultAccess = MediaAccess.DENIED))

        val imported = library.importDocument(source()) as MediaImportResult.Imported

        assertNull(imported.record.indexedDocumentId)
        assertEquals(0, index.indexed.size)
        assertEquals(1, library.records.value.size)
    }

    @Test
    fun indexFailureKeepsTheItemOnTheShelfAndReportsTheCode() = runBlocking {
        val index = FakeIndex().apply { result = null }
        val library = library(index = index)
        val result = library.importDocument(source()) as MediaImportResult.Imported

        assertEquals(MediaReasons.INDEX_FAILED, result.indexFailure)
        assertNull(result.record.indexedDocumentId)
        assertEquals(1, library.records.value.size)
        assertEquals(
            MediaTaskState.Failed(MediaKind.DOCUMENT, "تقرير.pdf", MediaReasons.INDEX_FAILED),
            library.task.value,
        )
    }

    @Test
    fun tooLargeAndEmptyImportsAreRefusedWithTypedReasons() = runBlocking {
        val library = library()
        val tooLarge = library.importDocument(
            MediaSource("كبير.pdf", "application/pdf", MediaLimits.MAX_IMPORT_BYTES + 1L) {
                ByteArrayInputStream(ByteArray((MediaLimits.MAX_IMPORT_BYTES + 1L).toInt()))
            },
        )
        assertEquals(MediaImportResult.Rejected(MediaReasons.TOO_LARGE), tooLarge)

        val empty = library.importDocument(MediaSource("فارغ.pdf", "application/pdf", 0L) {
            ByteArrayInputStream(ByteArray(0))
        })
        assertEquals(MediaImportResult.Rejected(MediaReasons.STORAGE), empty)
        assertTrue(library.records.value.isEmpty())
    }

    @Test
    fun storageBytesCountsWhatTheShelfHolds() = runBlocking {
        val library = library(index = FakeIndex())
        library.importDocument(source(body = "1234567890"))
        assertEquals(10L, library.storageBytes())
    }
}

package app.sirrak.media

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one RAG seam the media shelf talks to. Implementations wrap [app.sirrak.core.rag.RagService] (the
 * app adapter) or a fake (tests). Keeping it an interface here is what lets the kept-vs-once rules be
 * proven without Android or a real index: the library records an id only when this port returned one.
 */
interface MediaIndexPort {
    /** Indexes one text-bearing original; returns the RAG document id, or null on refusal/failure. */
    suspend fun index(source: MediaSource): String?

    /** Removes one indexed document; used when access is revoked or the item is deleted. */
    suspend fun remove(documentId: String)
}

/**
 * The on-device audio seam. The Android implementation decodes with MediaExtractor/MediaCodec and calls
 * the existing Whisper [app.sirrak.core.speech.TranscriptionEngine]; tests inject a fake. There is no
 * cloud path: a file that cannot be decoded or transcribed produces a typed failure, never a fallback.
 */
interface MediaTranscriptionPort {
    /** What this device can do right now, asked before the picker so "no model" is explained up front. */
    fun availability(): MediaTranscriptionAvailability

    /** Transcribes one stored audio file, reporting progress phases; never throws at the caller. */
    suspend fun transcribe(file: File, onPhase: (MediaPhase) -> Unit): MediaTranscriptionResult
}

/**
 * The media shelf seam the UI depends on. One implementation ([FileMediaLibrary]) owns policy, store
 * and the import/consume lifecycle; the Compose section only renders state and calls these methods.
 */
interface MediaLibrary {
    val records: StateFlow<List<MediaRecord>>
    val policy: StateFlow<MediaPolicy>
    val task: StateFlow<MediaTaskState>

    /** Bytes the shelf occupies on disk, for an honest storage line. */
    fun storageBytes(): Long

    /** The stored original of an item (the image the shelf previews), never a picker URI. */
    fun file(record: MediaRecord): File?

    /** Whether audio import can run now (Whisper engine + imported model). */
    fun transcriptionAvailability(): MediaTranscriptionAvailability

    suspend fun importDocument(source: MediaSource, retention: MediaRetention? = null): MediaImportResult

    suspend fun importImage(source: MediaSource, retention: MediaRetention? = null): MediaImportResult

    suspend fun importAudio(source: MediaSource, retention: MediaRetention? = null): MediaImportResult

    /** Re-runs the on-device transcription for a stored audio item whose transcript failed. */
    suspend fun retryTranscription(id: String): MediaImportResult

    /** The stored transcript text of a READY audio item, or null when there is none. */
    suspend fun transcriptText(id: String): String?

    /** Opens/closes assistant access for one item; revoking removes it from the RAG index. */
    suspend fun setAccess(id: String, access: MediaAccess)

    /** Remembers the default decisions applied to imports made without an explicit choice. */
    suspend fun setPolicy(policy: MediaPolicy)

    /** Deletes one item, its files and its index entry. */
    suspend fun delete(id: String)

    /** "Use once": an ONCE item is deleted the moment it has served its purpose. */
    suspend fun consume(id: String)

    /** Drops a failure banner without touching any record. */
    fun clearTask()

    /** Deletes every ONCE item; safe to call twice, and called on graph teardown. */
    fun purgeOnce()
}

/**
 * The P5 media coordinator. Pure JVM (files + coroutines only): the Android facts — ContentResolver,
 * MediaCodec, Whisper — arrive through [MediaIndexPort] and [MediaTranscriptionPort], so the policy
 * behaviour ("ONCE is never indexed and does not survive", "revoking access de-indexes") is testable
 * with fakes and a temp directory.
 *
 * State rules:
 *  - one import runs at a time ([mutex]); a queued second import waits instead of racing for memory;
 *  - a record is persisted immediately after its bytes are stored, so a crash leaves either nothing or
 *    a complete item, never a row pointing at absent bytes;
 *  - ONCE items are purged on construction as well as on [purgeOnce], so a process death cannot turn a
 *    temporary item into a durable one.
 */
class FileMediaLibrary(
    private val store: FileMediaStore,
    private val index: MediaIndexPort? = null,
    private val transcriber: MediaTranscriptionPort? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : MediaLibrary {

    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<MediaRecord>>(emptyList())
    private val _policy = MutableStateFlow(MediaPolicy.Default)
    private val _task = MutableStateFlow<MediaTaskState>(MediaTaskState.Idle)

    override val records: StateFlow<List<MediaRecord>> = _records.asStateFlow()
    override val policy: StateFlow<MediaPolicy> = _policy.asStateFlow()
    override val task: StateFlow<MediaTaskState> = _task.asStateFlow()

    init {
        // Open: load, then enforce "ONCE never survives" before anything can read the shelf.
        val snapshot = store.snapshot()
        val expiry = MediaOnce.expireSession(snapshot.records)
        if (expiry.changed) {
            snapshot.records.filter { it.id in expiry.deletedIds }.forEach { store.deleteFiles(it) }
            store.write(MediaSnapshot(policy = snapshot.policy, records = expiry.retained))
        }
        _policy.value = snapshot.policy
        _records.value = expiry.retained.sortedByDescending { it.createdAt }
    }

    override fun storageBytes(): Long = store.storageBytes()

    override fun file(record: MediaRecord): File? = store.fileOf(record)

    override fun transcriptionAvailability(): MediaTranscriptionAvailability {
        val port = transcriber ?: return MediaTranscriptionAvailability.Missing(MediaReasons.NO_TRANSCRIBER)
        return port.availability()
    }

    override suspend fun importDocument(source: MediaSource, retention: MediaRetention?): MediaImportResult =
        importFile(source, MediaKind.DOCUMENT, retention)

    override suspend fun importImage(source: MediaSource, retention: MediaRetention?): MediaImportResult =
        importFile(source, MediaKind.IMAGE, retention)

    /**
     * Audio is transcribed before it exists as a usable item, so the device is asked up front whether it
     * can transcribe at all: a missing Whisper model is reported as a typed answer, not as a dead file.
     */
    override suspend fun importAudio(source: MediaSource, retention: MediaRetention?): MediaImportResult {
        val availability = transcriptionAvailability()
        if (availability is MediaTranscriptionAvailability.Missing) {
            _task.value = MediaTaskState.Failed(MediaKind.AUDIO, source.displayName, availability.reason)
            return MediaImportResult.Rejected(availability.reason)
        }
        return importFile(source, MediaKind.AUDIO, retention)
    }

    override suspend fun retryTranscription(id: String): MediaImportResult = mutex.withLock {
        withContext(io) {
            val record = _records.value.firstOrNull { it.id == id }
                ?: return@withContext MediaImportResult.Rejected(MediaReasons.NOT_TRANSCRIBED)
            if (record.kind != MediaKind.AUDIO) {
                return@withContext MediaImportResult.Rejected(MediaReasons.NOT_TRANSCRIBED)
            }
            val availability = transcriptionAvailability()
            if (availability is MediaTranscriptionAvailability.Missing) {
                _task.value = MediaTaskState.Failed(MediaKind.AUDIO, record.displayName, availability.reason)
                return@withContext MediaImportResult.Rejected(availability.reason)
            }
            transcribeStored(record.copy(transcriptStatus = MediaTranscriptStatus.PENDING, transcriptFailure = null))
        }
    }

    override suspend fun transcriptText(id: String): String? = withContext(io) {
        val record = _records.value.firstOrNull { it.id == id } ?: return@withContext null
        if (record.transcriptStatus != MediaTranscriptStatus.READY) return@withContext null
        store.transcriptOf(record)?.let { file -> runCatching { file.readText(Charsets.UTF_8) }.getOrNull() }
    }

    override suspend fun setAccess(id: String, access: MediaAccess): Unit = mutex.withLock {
        withContext(io) {
            val record = _records.value.firstOrNull { it.id == id } ?: return@withContext
            if (access == MediaAccess.DENIED) {
                val documentId = record.indexedDocumentId
                if (documentId != null) {
                    runCatching { index?.remove(documentId) }
                }
                persist(record.copy(access = access, indexedDocumentId = null))
            } else {
                val granted = persist(record.copy(access = access))
                val (_, failure) = indexIfAllowed(granted)
                if (failure != null) {
                    _task.value = MediaTaskState.Failed(record.kind, record.displayName, failure)
                }
            }
        }
    }

    override suspend fun setPolicy(policy: MediaPolicy): Unit = mutex.withLock {
        withContext(io) {
            _policy.value = policy
            store.write(MediaSnapshot(policy = policy, records = _records.value))
        }
    }

    override suspend fun delete(id: String): Unit = mutex.withLock {
        withContext(io) {
            val record = _records.value.firstOrNull { it.id == id } ?: return@withContext
            record.indexedDocumentId?.let { documentId -> runCatching { index?.remove(documentId) } }
            store.deleteFiles(record)
            val remaining = _records.value.filterNot { it.id == id }
            store.write(MediaSnapshot(policy = _policy.value, records = remaining))
            _records.value = remaining
        }
    }

    override suspend fun consume(id: String) {
        val record = _records.value.firstOrNull { it.id == id } ?: return
        if (record.retention == MediaRetention.ONCE) delete(id)
    }

    override fun clearTask() {
        _task.value = MediaTaskState.Idle
    }

    /**
     * Removes every ONCE item and its files. Called on graph teardown and at construction: the temp
     * semantics must be true even when the process was killed instead of closed politely.
     */
    override fun purgeOnce() {
        val snapshot = store.snapshot()
        val expiry = MediaOnce.expireSession(snapshot.records)
        if (!expiry.changed) return
        snapshot.records.filter { it.id in expiry.deletedIds }.forEach { store.deleteFiles(it) }
        store.write(MediaSnapshot(policy = snapshot.policy, records = expiry.retained))
        _records.value = expiry.retained.sortedByDescending { it.createdAt }
        _policy.value = snapshot.policy
    }

    // --- import path -----------------------------------------------------------------------------

    private suspend fun importFile(
        source: MediaSource,
        kind: MediaKind,
        retentionOverride: MediaRetention?,
    ): MediaImportResult = mutex.withLock {
        withContext(io) {
            if (source.displayName.isBlank()) {
                return@withContext reject(kind, source.displayName, MediaReasons.EMPTY)
            }
            val decision = MediaRules.decide(_policy.value, retentionOverride, null)
            _task.value = MediaTaskState.Working(kind, source.displayName, MediaPhase.COPYING)
            val id = idFactory()
            val stored = try {
                store.writeMedia(id, source.displayName, open = source.open)
            } catch (error: MediaTooLargeException) {
                return@withContext reject(kind, source.displayName, MediaReasons.TOO_LARGE)
            } catch (error: MediaStorageException) {
                return@withContext reject(kind, source.displayName, MediaReasons.STORAGE)
            } catch (error: Throwable) {
                return@withContext reject(kind, source.displayName, MediaReasons.IMPORT_FAILED)
            }
            var record = MediaRecord(
                id = id,
                kind = kind,
                displayName = source.displayName,
                sizeBytes = stored.sizeBytes,
                retention = decision.retention,
                access = decision.access,
                createdAt = clock(),
                fileName = stored.fileName,
                mimeType = source.mimeType,
                transcriptStatus = if (kind == MediaKind.AUDIO) {
                    MediaTranscriptStatus.PENDING
                } else {
                    MediaTranscriptStatus.NOT_APPLICABLE
                },
            )
            record = persist(record)
            when (kind) {
                MediaKind.AUDIO -> transcribeStored(record)
                else -> {
                    val (indexed, failure) = indexIfAllowed(record)
                    _task.value = if (failure == null) {
                        MediaTaskState.Idle
                    } else {
                        MediaTaskState.Failed(kind, indexed.displayName, failure)
                    }
                    MediaImportResult.Imported(indexed, failure)
                }
            }
        }
    }

    /** Runs the on-device transcription for an already stored audio item, then indexes its transcript. */
    private suspend fun transcribeStored(record: MediaRecord): MediaImportResult {
        val port = transcriber
            ?: return failItem(record, MediaReasons.NO_TRANSCRIBER)
        val file = store.fileOf(record)
            ?: return failItem(record, MediaReasons.STORAGE)
        _task.value = MediaTaskState.Working(MediaKind.AUDIO, record.displayName, MediaPhase.TRANSCRIBING)
        val result = runCatching {
            port.transcribe(file) { phase ->
                _task.value = MediaTaskState.Working(MediaKind.AUDIO, record.displayName, phase)
            }
        }.getOrElse { MediaTranscriptionResult.Failure(MediaReasons.TRANSCRIPTION_FAILED) }
        return when (result) {
            is MediaTranscriptionResult.Failure ->
                failItem(
                    record.copy(
                        transcriptStatus = MediaTranscriptStatus.FAILED,
                        transcriptFailure = result.reason,
                    ),
                    result.reason,
                )
            is MediaTranscriptionResult.Success -> {
                val text = result.text.trim()
                if (text.isEmpty()) {
                    failItem(
                        record.copy(
                            transcriptStatus = MediaTranscriptStatus.FAILED,
                            transcriptFailure = MediaReasons.TRANSCRIPT_EMPTY,
                        ),
                        MediaReasons.TRANSCRIPT_EMPTY,
                    )
                } else {
                    val transcript = store.writeTranscript(record.id, text)
                    var ready = persist(
                        record.copy(
                            transcriptStatus = MediaTranscriptStatus.READY,
                            transcriptFailure = null,
                            transcriptFileName = transcript.fileName,
                        ),
                    )
                    val (indexed, failure) = indexIfAllowed(ready)
                    ready = indexed
                    _task.value = if (failure == null) {
                        MediaTaskState.Idle
                    } else {
                        MediaTaskState.Failed(MediaKind.AUDIO, ready.displayName, failure)
                    }
                    MediaImportResult.Imported(ready, failure)
                }
            }
        }
    }

    /** A stored item whose derivative step failed stays on the shelf; only the task reports the failure. */
    private fun failItem(record: MediaRecord, reason: String): MediaImportResult {
        val updated = persist(record)
        _task.value = MediaTaskState.Failed(record.kind, record.displayName, reason)
        return MediaImportResult.Imported(updated)
    }

    /**
     * The only place a record becomes readable by the assistant. [MediaRules.shouldIndex] decides; this
     * method cannot be talked into indexing an ONCE or DENIED item by a caller.
     */
    private suspend fun indexIfAllowed(record: MediaRecord): Pair<MediaRecord, String?> {
        if (!MediaRules.shouldIndex(record)) return record to null
        val port = index ?: return record to MediaReasons.INDEX_UNAVAILABLE
        val source = indexSource(record) ?: return record to MediaReasons.INDEX_FAILED
        val documentId = runCatching { port.index(source) }.getOrNull()
            ?: return record to MediaReasons.INDEX_FAILED
        return persist(record.copy(indexedDocumentId = documentId)) to null
    }

    /** What the RAG library reads: the stored original for documents, the transcript for audio. */
    private fun indexSource(record: MediaRecord): MediaSource? {
        return when (record.kind) {
            MediaKind.IMAGE -> null
            MediaKind.DOCUMENT -> {
                val file = store.fileOf(record) ?: return null
                MediaSource(
                    displayName = record.displayName,
                    mimeType = record.mimeType,
                    sizeBytes = record.sizeBytes,
                    open = { file.inputStream() },
                )
            }
            MediaKind.AUDIO -> {
                if (record.transcriptStatus != MediaTranscriptStatus.READY) return null
                val transcript = store.transcriptOf(record) ?: return null
                MediaSource(
                    displayName = record.id + TRANSCRIPT_NAME_SUFFIX,
                    mimeType = "text/plain",
                    sizeBytes = transcript.length(),
                    open = { transcript.inputStream() },
                )
            }
        }
    }

    private fun reject(kind: MediaKind, displayName: String, reason: String): MediaImportResult {
        _task.value = MediaTaskState.Failed(kind, displayName, reason)
        return MediaImportResult.Rejected(reason)
    }

    /** Writes the record through the store and mirrors it in state; caller holds the import mutex. */
    private fun persist(record: MediaRecord): MediaRecord {
        val others = _records.value.filterNot { it.id == record.id }
        val next = (others + record).sortedByDescending { it.createdAt }
        store.write(MediaSnapshot(policy = _policy.value, records = next))
        _records.value = next
        return record
    }

    private companion object {
        /** The indexed transcript is a .txt document, so the RAG extractor's extension rule applies. */
        const val TRANSCRIPT_NAME_SUFFIX = " transcript.txt"
    }
}

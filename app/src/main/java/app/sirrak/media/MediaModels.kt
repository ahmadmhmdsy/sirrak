package app.sirrak.media

import java.io.InputStream

/**
 * The P5 media shelf contract. Everything here is Android-free and Compose-free: the retention policy,
 * the store and the import coordinator are plain JVM so the "kept vs once" behaviour is provable in a
 * unit test (docs/31 §6.4 P5 acceptance: "kept vs once behaviour verified by test").
 *
 * The model is deliberately small. A media item is one file the user picked plus the two decisions that
 * give it meaning: who may read it ([MediaAccess]) and how long it lives ([MediaRetention]). The shelf
 * never owns a conversation; an item may carry a [MediaRecord.conversationId] so a future chat wiring
 * can expire it with that conversation, which is the ONCE semantic the council specified.
 */

/** What a shelf item is. The kind selects the picker, the shelf and whether the RAG index may read it. */
enum class MediaKind {
    DOCUMENT,
    IMAGE,
    AUDIO,
}

/**
 * The retention decision. KEEP is durable and may be indexed; ONCE is a borrow: never indexed, deleted
 * when its window ends (its conversation, or this app process — see [MediaOnce]).
 */
enum class MediaRetention {
    KEEP,
    ONCE,
}

/** Whether the assistant may read this item. Only an indexed item can ever reach an answer. */
enum class MediaAccess {
    GRANTED,
    DENIED,
}

/**
 * Transcript lifecycle for an audio import. [PENDING] is a live state and is never persisted as a fact
 * the user can rely on; only the terminal states survive a crash.
 */
enum class MediaTranscriptStatus {
    NOT_APPLICABLE,
    PENDING,
    READY,
    FAILED,
}

/**
 * Stable failure codes. They are data, not user-facing English: the library reports a code and the UI
 * maps it to an Arabic/English sentence, so rewording copy never touches the pipeline.
 */
object MediaReasons {
    // Import/storage.
    const val EMPTY = "media-empty"
    const val TOO_LARGE = "media-too-large"
    const val UNSUPPORTED_TYPE = "media-unsupported-type"
    const val UNREADABLE = "media-unreadable"
    const val STORAGE = "media-storage"
    const val IMPORT_FAILED = "media-import-failed"

    // Index (the RAG library is a derivative, never the owner of the bytes).
    const val INDEX_FAILED = "media-index-failed"
    const val INDEX_UNAVAILABLE = "media-index-unavailable"

    // Audio -> transcript, on device only.
    const val NO_TRANSCRIBER = "media-no-transcriber"
    const val NO_SPEECH_MODEL = "media-no-speech-model"
    const val DECODE_FAILED = "media-decode-failed"
    const val AUDIO_TOO_LONG = "media-audio-too-long"
    const val TRANSCRIPTION_FAILED = "media-transcription-failed"
    const val NO_SPEECH = "media-no-speech"
    const val TRANSCRIPT_EMPTY = "media-transcript-empty"
    const val NOT_TRANSCRIBED = "media-not-transcribed"
}

/**
 * One item on the shelf. [fileName] is relative to the store's file directory, never an absolute path,
 * so a moved app directory cannot break a saved record. Sizes are bytes as copied, not as declared by
 * the picker. [indexedDocumentId] is the RAG library's id when the item is indexed, and it is the only
 * proof the assistant can see this item.
 */
data class MediaRecord(
    val id: String,
    val kind: MediaKind,
    val displayName: String,
    val sizeBytes: Long,
    val retention: MediaRetention,
    val access: MediaAccess,
    val createdAt: Long,
    val fileName: String,
    val mimeType: String? = null,
    val transcriptStatus: MediaTranscriptStatus = MediaTranscriptStatus.NOT_APPLICABLE,
    val transcriptFileName: String? = null,
    val transcriptFailure: String? = null,
    val indexedDocumentId: String? = null,
    val conversationId: String? = null,
    val consumedAt: Long? = null,
) {
    /** True when the RAG library has a document for this item, i.e. the assistant may retrieve it. */
    val isIndexed: Boolean get() = indexedDocumentId != null

    /** ONCE items are the temporary ones; the UI badges them so "temporary" is never a surprise. */
    val isOnce: Boolean get() = retention == MediaRetention.ONCE
}

/**
 * A picked file offered to the shelf. The stream opens lazily and re-openably: the store copies it
 * once, and the index port re-opens the stored copy, so a picker grant expiring mid-import cannot
 * corrupt an item.
 */
data class MediaSource(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val open: () -> InputStream,
)

/** The decisions the policy actually applies when an import arrives without an explicit answer. */
data class MediaDecision(
    val retention: MediaRetention,
    val access: MediaAccess,
)

/** Outcome of one import. [indexFailure] separates "stored but not readable by the assistant" from a
 * failed import: the item exists, so the UI must show it and offer a retry rather than a dead end. */
sealed interface MediaImportResult {
    data class Imported(
        val record: MediaRecord,
        val indexFailure: String? = null,
    ) : MediaImportResult

    data class Rejected(val reason: String) : MediaImportResult
}

/** Progress phases reported while one import runs; the UI renders them one at a time. */
enum class MediaPhase {
    COPYING,
    DECODING,
    TRANSCRIBING,
    INDEXING,
}

/** Observable state of the one import the shelf runs at a time (imports never overlap: one file at a
 * time keeps progress honest and avoids two decoders fighting for memory). */
sealed interface MediaTaskState {
    data object Idle : MediaTaskState

    data class Working(
        val kind: MediaKind,
        val displayName: String,
        val phase: MediaPhase,
    ) : MediaTaskState

    data class Failed(
        val kind: MediaKind?,
        val displayName: String,
        val reason: String,
    ) : MediaTaskState
}

/** What the audio pipeline can do right now, asked before the picker so "no Whisper" is explained
 * up front instead of failing after the user chose a file. */
sealed interface MediaTranscriptionAvailability {
    data object Available : MediaTranscriptionAvailability
    data class Missing(val reason: String) : MediaTranscriptionAvailability
}

/** Outcome of one on-device transcription. */
sealed interface MediaTranscriptionResult {
    data class Success(val text: String, val languageId: String?) : MediaTranscriptionResult
    data class Failure(val reason: String) : MediaTranscriptionResult
}

/** Limits as data: a shelf item is user content, not a database. */
object MediaLimits {
    /** One import may not exceed this; the store enforces it while streaming, never after the fact. */
    const val MAX_IMPORT_BYTES: Long = 50L * 1024L * 1024L

    /** Ten minutes of speech per file: 16 kHz mono PCM16 is 32 kB/s, so this bounds decode memory. */
    const val MAX_AUDIO_MS: Long = 10L * 60L * 1000L

    /** The rate the Whisper engine expects; the decoder resamples to exactly this. */
    const val TARGET_SAMPLE_RATE: Int = 16_000
}

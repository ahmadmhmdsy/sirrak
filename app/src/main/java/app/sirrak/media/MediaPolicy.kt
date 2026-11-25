package app.sirrak.media

/**
 * The P5 retention/access policy, as data plus pure rules.
 *
 * The council's rule (docs/31 Q7, decision 6) is that the user decides retention at the moment they
 * know the intent, and the app remembers the default instead of asking three ways. So this file owns
 * three things and nothing else:
 *
 *  1. [MediaPolicy]: the default retention/access an import inherits when the sheet cannot ask.
 *  2. [MediaConsequences]: what each decision *means*, one object that the sheet copy, the row badge
 *     and the tests all read, so the copy and the behaviour cannot drift apart.
 *  3. [MediaOnce]: the explicit ONCE state machine. An ONCE item is never indexed and is deleted when
 *     its window ends. The window is "its conversation" once chat wiring exists; in V1 (no media ->
 *     conversation link yet) it is the app process, and the store ALSO refuses to resurrect an ONCE
 *     item on restart. Both are modelled here, not in the UI.
 */

/**
 * The remembered defaults. Retention is the one decision the council kept as a persisted preference;
 * access starts open because the user imported the file for the assistant, and every item can be
 * closed individually afterwards.
 */
data class MediaPolicy(
    val defaultRetention: MediaRetention = MediaRetention.KEEP,
    val defaultAccess: MediaAccess = MediaAccess.GRANTED,
) {
    /** Applies a per-import override over the remembered default. */
    fun retention(override: MediaRetention?): MediaRetention = override ?: defaultRetention

    /** Applies a per-item override over the remembered default. */
    fun access(override: MediaAccess?): MediaAccess = override ?: defaultAccess

    companion object {
        val Default = MediaPolicy()
    }
}

/**
 * What one decision means. The decision sheet renders [retention]/[access] consequences from here, so
 * "لا يُفهرس" on the button is the same boolean the index path checks.
 */
data class MediaConsequences(
    val retention: MediaRetention,
    val access: MediaAccess,
    val kind: MediaKind,
    /** May the RAG index ever hold this item (given its retention and access)? */
    val indexable: Boolean,
    /** Survives an app restart / process death. */
    val survivesRestart: Boolean,
    /** Deleted automatically when the conversation it was imported for ends. */
    val deletedWithConversation: Boolean,
    /** Deleted after being used once (consume semantics). */
    val deletedAfterUse: Boolean,
    /** The assistant may retrieve it, which requires an actual index entry. */
    val agentReadable: Boolean,
)

/** The rules every surface reads: one home for "may this be indexed?" and "what happens on ONCE?". */
object MediaRules {

    /** Only text-bearing kinds can enter the RAG index; images are stored but never indexed in V1. */
    fun kindCanBeIndexed(kind: MediaKind): Boolean =
        kind == MediaKind.DOCUMENT || kind == MediaKind.AUDIO

    /**
     * The one predicate the index path checks before it calls the RAG library. It combines all three
     * reasons an item must stay out of the index:
     *  - ONCE never becomes durable knowledge;
     *  - DENIED means the user withheld assistant access;
     *  - an audio item without a finished transcript has no text to index.
     */
    fun shouldIndex(record: MediaRecord): Boolean = when {
        record.retention != MediaRetention.KEEP -> false
        record.access != MediaAccess.GRANTED -> false
        !kindCanBeIndexed(record.kind) -> false
        record.kind == MediaKind.AUDIO -> record.transcriptStatus == MediaTranscriptStatus.READY
        else -> true
    }

    /** The agent-access switch is meaningless on a temporary item: it is never readable. */
    fun canGrantAgentAccess(retention: MediaRetention, kind: MediaKind): Boolean =
        retention == MediaRetention.KEEP && kindCanBeIndexed(kind)

    /** The decision an import inherits when the sheet supplied no explicit retention/access. */
    fun decide(policy: MediaPolicy, retentionOverride: MediaRetention?, accessOverride: MediaAccess?): MediaDecision =
        MediaDecision(
            retention = policy.retention(retentionOverride),
            access = policy.access(accessOverride),
        )

    /** The consequences of a decision, for the sheet, the row badge and the tests. */
    fun consequences(kind: MediaKind, retention: MediaRetention, access: MediaAccess): MediaConsequences {
        val once = retention == MediaRetention.ONCE
        val indexable = !once && access == MediaAccess.GRANTED && kindCanBeIndexed(kind)
        return MediaConsequences(
            retention = retention,
            access = access,
            kind = kind,
            indexable = indexable,
            survivesRestart = !once,
            deletedWithConversation = once,
            deletedAfterUse = once,
            agentReadable = indexable,
        )
    }

    fun consequences(record: MediaRecord): MediaConsequences =
        consequences(record.kind, record.retention, record.access)
}

/** One expiry decision: what stays on the shelf and which ids must be deleted (files and all). */
data class MediaExpiry(
    val retained: List<MediaRecord>,
    val deletedIds: List<String>,
) {
    val deletedCount: Int get() = deletedIds.size
    val changed: Boolean get() = deletedIds.isNotEmpty()
}

/**
 * The ONCE state machine.
 *
 * An ONCE item has exactly two live states — alive, then consumed — and every path that ends its
 * window produces a [MediaExpiry] the store applies. This is the explicit model the task demands:
 * "ONCE items must never be indexed and must be deleted after use/with their conversation" is a
 * property of these functions, tested without a UI or a filesystem.
 */
object MediaOnce {

    /** The state of a row's once-ness; null for KEEP items (the machine does not apply to them). */
    enum class State {
        /** Alive for the current window; never indexed. */
        ALIVE,

        /** Used once; must be deleted now. */
        CONSUMED,
    }

    fun state(record: MediaRecord): State? = when {
        record.retention != MediaRetention.ONCE -> null
        record.consumedAt != null -> State.CONSUMED
        else -> State.ALIVE
    }

    /** Marks an ONCE item used; the caller deletes it. KEEP items are unchanged (no consumption). */
    fun consume(record: MediaRecord, at: Long): MediaRecord = when (record.retention) {
        MediaRetention.KEEP -> record
        MediaRetention.ONCE -> record.copy(consumedAt = at)
    }

    /** True when [record] must be deleted because its conversation is over. */
    fun expiresWith(record: MediaRecord, conversationId: String): Boolean =
        record.retention == MediaRetention.ONCE && record.conversationId == conversationId

    /**
     * The session-end expiry: an ONCE item never survives the window it was created for. Called on
     * store open and on graph teardown, so a crash cannot leave a "temporary" file on disk forever.
     */
    fun expireSession(records: List<MediaRecord>): MediaExpiry =
        expire(records) { it.retention == MediaRetention.ONCE }

    /**
     * The conversation-end expiry: only ONCE items imported for [conversationId] (plus any already
     * consumed) are deleted; KEEP items and other conversations' items are untouched.
     */
    fun expireConversation(records: List<MediaRecord>, conversationId: String): MediaExpiry =
        expire(records) { record ->
            record.retention == MediaRetention.ONCE &&
                (record.conversationId == conversationId || record.consumedAt != null)
        }

    /** The already-consumed expiry: used items must not linger even before their conversation ends. */
    fun expireConsumed(records: List<MediaRecord>): MediaExpiry =
        expire(records) { it.retention == MediaRetention.ONCE && it.consumedAt != null }

    private inline fun expire(
        records: List<MediaRecord>,
        shouldDelete: (MediaRecord) -> Boolean,
    ): MediaExpiry {
        val kept = records.filterNot(shouldDelete)
        val deleted = records.filter(shouldDelete).map { it.id }
        return MediaExpiry(retained = kept, deletedIds = deleted)
    }
}

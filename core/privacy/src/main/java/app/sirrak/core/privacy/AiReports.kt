package app.sirrak.core.privacy

import java.time.Instant

/**
 * PRV-6 / Play AI policy: the user can report an offensive AI answer. The report is stored locally
 * first (works offline, queues), and sharing is an explicit second step so nothing leaves the device
 * without the user's action. Only an excerpt of the answer is kept, never the whole conversation.
 */
enum class AiReportReason(val id: String) {
    HARMFUL("harmful"),
    HATE("hate"),
    SEXUAL("sexual"),
    VIOLENCE("violence"),
    PRIVACY("privacy"),
    OTHER("other"),
    ;

    companion object {
        fun fromId(id: String): AiReportReason? = entries.firstOrNull { it.id == id }
    }
}

data class AiReport(
    val id: String,
    val createdAtMs: Long,
    val reason: AiReportReason,
    val answerExcerpt: String,
    val note: String = "",
    val modelLabel: String = "",
)

/** Persistence seam; the app stores a bounded JSON list in SharedPreferences. */
interface AiReportStore {
    fun reports(): List<AiReport>
    fun save(reports: List<AiReport>)
}

sealed interface AiReportSubmit {
    data class Accepted(val report: AiReport) : AiReportSubmit

    enum class Rejection {
        EMPTY_ANSWER,
        QUEUE_FULL,
    }

    data class Rejected(val reason: Rejection) : AiReportSubmit
}

object AiReportRules {
    /** Bounded so a report never becomes a copy of the conversation. */
    const val MAX_EXCERPT_CHARS = 2_000
    const val MAX_NOTE_CHARS = 500
    const val MAX_REPORTS = 50

    fun excerpt(answer: String): String {
        val trimmed = answer.trim()
        return if (trimmed.length <= MAX_EXCERPT_CHARS) {
            trimmed
        } else {
            trimmed.take(MAX_EXCERPT_CHARS - 1) + "…"
        }
    }

    fun note(note: String): String {
        val trimmed = note.trim()
        return if (trimmed.length <= MAX_NOTE_CHARS) trimmed else trimmed.take(MAX_NOTE_CHARS)
    }

    /** Stable, collision-free id without a UUID dependency (deterministic in tests). */
    fun newId(nowMs: Long, existing: List<AiReport>): String {
        val taken = existing.mapTo(HashSet()) { it.id }
        var candidate = "report-$nowMs"
        var suffix = 1
        while (candidate in taken) {
            suffix++
            candidate = "report-$nowMs-$suffix"
        }
        return candidate
    }

    /** The shareable form of a report: readable, self-contained, no hidden app data. */
    fun shareText(report: AiReport, appLabel: String): String = buildString {
        appendLine("$appLabel — AI output report")
        appendLine("Report id: " + report.id)
        appendLine("Created: " + Instant.ofEpochMilli(report.createdAtMs).toString())
        appendLine("Reason: " + report.reason.id)
        if (report.modelLabel.isNotBlank()) appendLine("Model: " + report.modelLabel)
        if (report.note.isNotBlank()) appendLine("Note: " + report.note)
        appendLine()
        appendLine("Answer excerpt:")
        append(report.answerExcerpt)
    }
}

/**
 * The local report queue: validates, bounds, persists. The UI calls [submit] and then offers
 * "share" through an Android intent; the queue itself never talks to the network.
 */
class AiReportQueue(
    private val store: AiReportStore,
    private val capacity: Int = AiReportRules.MAX_REPORTS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private var cached: List<AiReport> = store.reports()

    @Synchronized
    fun reports(): List<AiReport> = cached.toList()

    @Synchronized
    fun submit(
        reason: AiReportReason,
        answer: String,
        note: String = "",
        modelLabel: String = "",
    ): AiReportSubmit {
        val excerpt = AiReportRules.excerpt(answer)
        if (excerpt.isEmpty()) return AiReportSubmit.Rejected(AiReportSubmit.Rejection.EMPTY_ANSWER)
        if (cached.size >= capacity) return AiReportSubmit.Rejected(AiReportSubmit.Rejection.QUEUE_FULL)
        val now = clock()
        val report = AiReport(
            id = AiReportRules.newId(now, cached),
            createdAtMs = now,
            reason = reason,
            answerExcerpt = excerpt,
            note = AiReportRules.note(note),
            modelLabel = modelLabel.trim(),
        )
        cached = listOf(report) + cached
        store.save(cached)
        return AiReportSubmit.Accepted(report)
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val next = cached.filterNot { it.id == id }
        if (next.size == cached.size) return false
        cached = next
        store.save(cached)
        return true
    }

    @Synchronized
    fun clear() {
        cached = emptyList()
        store.save(cached)
    }
}

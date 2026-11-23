package app.sirrak

import android.content.Context
import app.sirrak.core.privacy.AiReport
import app.sirrak.core.privacy.AiReportReason
import app.sirrak.core.privacy.AiReportStore
import app.sirrak.core.privacy.NetworkAuditStore
import app.sirrak.core.privacy.NetworkCall
import app.sirrak.core.privacy.NetworkOutcome
import app.sirrak.core.privacy.OfflineModeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SharedPreferences implementations of the privacy seams. The audit log and the report queue are
 * bounded by their core classes before they reach here, so the stored JSON can never grow without
 * limit. Versioned keys let a future migration keep old data instead of guessing.
 */
private const val PRIVACY_PREFS = "sirrak_privacy"
private const val KEY_OFFLINE = "offline_only.v1"
private const val KEY_AUDIT = "network_audit.v1"
private const val KEY_REPORTS = "ai_reports.v1"

class SharedPrefsOfflineModeStore(
    context: Context,
    private val default: Boolean = false,
) : OfflineModeStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)

    override fun isOfflineOnly(): Boolean = prefs.getBoolean(KEY_OFFLINE, default)

    override fun setOfflineOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE, enabled).apply()
    }
}

class SharedPrefsNetworkAuditStore(context: Context) : NetworkAuditStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun events(): List<NetworkCall> {
        val raw = prefs.getString(KEY_AUDIT, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<NetworkCallDto>>(raw).mapNotNull { it.toModel() }
        }.getOrDefault(emptyList())
    }

    override fun save(events: List<NetworkCall>) {
        val encoded = json.encodeToString(events.map { NetworkCallDto.from(it) })
        prefs.edit().putString(KEY_AUDIT, encoded).apply()
    }
}

class SharedPrefsAiReportStore(context: Context) : AiReportStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun reports(): List<AiReport> {
        val raw = prefs.getString(KEY_REPORTS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<AiReportDto>>(raw).mapNotNull { it.toModel() }
        }.getOrDefault(emptyList())
    }

    override fun save(reports: List<AiReport>) {
        val encoded = json.encodeToString(reports.map { AiReportDto.from(it) })
        prefs.edit().putString(KEY_REPORTS, encoded).apply()
    }
}

@Serializable
private data class NetworkCallDto(
    val endpointId: String,
    val host: String,
    val startedAtMs: Long,
    val bytes: Long = 0,
    val outcome: String = NetworkOutcome.STARTED.name,
) {
    fun toModel(): NetworkCall? {
        if (endpointId.isBlank() || host.isBlank()) return null
        return NetworkCall(
            endpointId = endpointId,
            host = host,
            startedAtMs = startedAtMs,
            bytes = bytes,
            outcome = runCatching { NetworkOutcome.valueOf(outcome) }.getOrDefault(NetworkOutcome.STARTED),
        )
    }

    companion object {
        fun from(call: NetworkCall): NetworkCallDto = NetworkCallDto(
            endpointId = call.endpointId,
            host = call.host,
            startedAtMs = call.startedAtMs,
            bytes = call.bytes,
            outcome = call.outcome.name,
        )
    }
}

@Serializable
private data class AiReportDto(
    val id: String,
    val createdAtMs: Long,
    val reason: String,
    val answerExcerpt: String,
    val note: String = "",
    val modelLabel: String = "",
) {
    fun toModel(): AiReport? {
        val parsedReason = AiReportReason.fromId(reason) ?: return null
        if (id.isBlank() || answerExcerpt.isBlank()) return null
        return AiReport(
            id = id,
            createdAtMs = createdAtMs,
            reason = parsedReason,
            answerExcerpt = answerExcerpt,
            note = note,
            modelLabel = modelLabel,
        )
    }

    companion object {
        fun from(report: AiReport): AiReportDto = AiReportDto(
            id = report.id,
            createdAtMs = report.createdAtMs,
            reason = report.reason.id,
            answerExcerpt = report.answerExcerpt,
            note = report.note,
            modelLabel = report.modelLabel,
        )
    }
}

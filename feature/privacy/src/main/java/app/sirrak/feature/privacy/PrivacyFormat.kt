package app.sirrak.feature.privacy

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sirrak.core.privacy.AiReportReason
import app.sirrak.core.runtime.NetworkEndpoint
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Presentation helpers for the privacy surfaces. Endpoint purposes are localised through a stable
 * key, with the declared English text as the honest fallback, so a new endpoint ships in app.json
 * without a code change and still reads correctly before its translation lands.
 */
@Composable
internal fun endpointPurpose(endpoint: NetworkEndpoint): String = when (endpoint.purposeKey) {
    "model_download" -> stringResource(R.string.privacy_endpoint_model_download_purpose)
    else -> endpoint.purpose.ifBlank { stringResource(R.string.privacy_endpoint_unknown_purpose) }
}

@Composable
internal fun endpointWhenUsed(endpoint: NetworkEndpoint): String = when (endpoint.purposeKey) {
    "model_download" -> stringResource(R.string.privacy_endpoint_model_download_when)
    else -> endpoint.whenUsed.ifBlank { stringResource(R.string.privacy_endpoint_unknown_when) }
}

internal fun reasonLabelRes(reason: AiReportReason): Int = when (reason) {
    AiReportReason.HARMFUL -> R.string.privacy_report_reason_harmful
    AiReportReason.HATE -> R.string.privacy_report_reason_hate
    AiReportReason.SEXUAL -> R.string.privacy_report_reason_sexual
    AiReportReason.VIOLENCE -> R.string.privacy_report_reason_violence
    AiReportReason.PRIVACY -> R.string.privacy_report_reason_privacy
    AiReportReason.OTHER -> R.string.privacy_report_reason_other
}

/** Bytes as a short human value; the log stores raw bytes, the UI decides the unit. */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> String.format(Locale.US, "%d B", bytes)
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
}

internal fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

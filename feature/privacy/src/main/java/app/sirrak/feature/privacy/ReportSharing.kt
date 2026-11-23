package app.sirrak.feature.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.sirrak.core.privacy.AiReport
import app.sirrak.core.privacy.AiReportRules
import app.sirrak.core.runtime.PrivacyConfig

/** What actually happened when the user shared a report; the UI reports it honestly. */
internal enum class ShareOutcome { SHARED, COPIED, FAILED }

/**
 * PRV-6 share: nothing leaves the device on its own. A configured report address gets a mail draft,
 * otherwise the system share sheet appears, and if neither resolves the text is copied so the user
 * can paste it wherever they trust. Returns what happened instead of assuming success.
 */
internal fun shareReport(
    context: Context,
    report: AiReport,
    config: PrivacyConfig,
    appLabel: String,
): ShareOutcome {
    val text = AiReportRules.shareText(report, appLabel)
    val subject = appLabel + " AI report " + report.id
    val email = config.reportEmail
    if (email.isNotBlank()) {
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email)).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        if (runCatching { context.startActivity(mail) }.isSuccess) return ShareOutcome.SHARED
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(chooser) }.isSuccess) return ShareOutcome.SHARED
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return ShareOutcome.FAILED
    return runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText(subject, text))
        ShareOutcome.COPIED
    }.getOrDefault(ShareOutcome.FAILED)
}

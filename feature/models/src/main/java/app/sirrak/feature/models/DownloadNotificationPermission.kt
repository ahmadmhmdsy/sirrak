package app.sirrak.feature.models

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.sirrak.core.runtime.findActivity

/**
 * P3.4: Android 13+ hides notifications until POST_NOTIFICATIONS is granted. The foreground download
 * notification is the only signal a user has while the app is away, so ask right when a download
 * starts (contextual) instead of at launch. A denial is non-fatal: [app.sirrak.core.models.ModelDownloader]
 * and the foreground service still finish the transfer; only the progress notification is hidden, and
 * Android shows the service in the task manager instead.
 *
 * No-op below API 33, where notifications need no runtime grant, and in previews/tests, where there is
 * no Activity to attach the system dialog to.
 */
internal fun requestDownloadNotificationPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) return
    val activity = context.findActivity() ?: return
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        DOWNLOAD_NOTIFICATION_REQUEST,
    )
}

/** Stable request code; the app does not override onRequestPermissionsResult, so nothing else reads it. */
internal const val DOWNLOAD_NOTIFICATION_REQUEST = 4102

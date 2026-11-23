package app.sirrak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.sirrak.core.models.DownloadPhase
import app.sirrak.core.models.DownloadProgress
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * P3.4: keeps the process alive for the duration of a model download, so leaving the app no longer
 * pauses it. The downloader stays pure Kotlin: it reports activity through its `onActiveChanged`
 * seam and this host-owned service owns the foreground promotion, the progress notification and the
 * cancel action. It stops itself as soon as the download reaches any terminal state, so it can never
 * outlive the transfer it was started for.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastKey: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            runCatching { downloader()?.cancel() }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote immediately: the notification must exist within the startForegroundService window
        // even if the first progress snapshot has not arrived yet.
        promote(buildNotification(progress = null))
        observe()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        requested.set(false)
        super.onDestroy()
    }

    private fun downloader(): ModelDownloader? = runCatching {
        (application as SirrakApplication).container.service(ModelDownloader::class.java)
    }.getOrNull()

    private fun observe() {
        if (observing) return
        val downloader = downloader() ?: run {
            stopSelf()
            return
        }
        observing = true
        scope.launch {
            downloader.progress.collect { progress ->
                if (progress == null || !progress.isActive()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    show(progress)
                }
            }
        }
    }

    private fun show(progress: DownloadProgress) {
        val key = "${progress.entryId}:${progress.phase}:${progress.percent}"
        if (key == lastKey) return
        lastKey = key
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun promote(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(progress: DownloadProgress?): Notification {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = getString(R.string.download_notification_title)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(notificationText(progress))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.download_notification_cancel), cancel)
        if (progress == null || progress.totalBytes <= 0L) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(1000, (progress.fraction * 1000f).toInt().coerceIn(0, 1000), false)
        }
        return builder.build()
    }

    private fun notificationText(progress: DownloadProgress?): String {
        if (progress == null) return getString(R.string.download_notification_preparing)
        val name = runCatching {
            val catalogue = (application as SirrakApplication)
                .container
                .service(ModelCatalogueProvider::class.java)
                .catalogue()
            catalogue.models.firstOrNull { it.id == progress.entryId }?.displayName
        }.getOrNull().orEmpty().ifBlank { progress.entryId }
        return when (progress.phase) {
            DownloadPhase.VERIFYING -> getString(R.string.download_notification_verifying, name)
            else -> getString(R.string.download_notification_text, name, progress.percent)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notification_channel),
            // Low importance: progress belongs in the shade, never as a sound or heads-up alert.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun DownloadProgress.isActive(): Boolean =
        phase == DownloadPhase.QUEUED ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.VERIFYING

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_CANCEL = "app.sirrak.action.CANCEL_DOWNLOAD"

        // The downloader reports "active" on every progress snapshot, so the host must start the
        // service at most once per transfer. Android 12+ also refuses a second foreground-service
        // start from the background; deduplicating here keeps that request out of the log entirely.
        private val requested = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Asks the host to keep the process alive while a download runs. Safe to call repeatedly; the
         * service stops itself on any terminal download state and clears the flag in [onDestroy].
         */
        fun start(context: Context) {
            if (!requested.compareAndSet(false, true)) return
            val intent = Intent(context, DownloadService::class.java)
            val started = runCatching { context.startForegroundService(intent) }.isSuccess
            if (!started) requested.set(false)
        }
    }
}

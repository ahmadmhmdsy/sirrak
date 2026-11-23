package app.sirrak

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import app.sirrak.core.models.DeviceProfile
import app.sirrak.core.models.DeviceProfileProvider
import app.sirrak.core.models.NetworkPolicy
import app.sirrak.core.models.StorageProbe
import app.sirrak.core.privacy.OfflineModeStore
import java.io.File

/**
 * Wi-Fi-only default (ONB-3). Unmetered means the active network reports NOT_METERED, or is Wi-Fi /
 * Ethernet; a metered connection is refused by the downloader until the user turns the preference off.
 * PRV-2 adds a hard block: the privacy screen's offline-only switch wins over every metered rule.
 */
class AndroidNetworkPolicy(
    context: Context,
    private val offlineMode: OfflineModeStore? = null,
) : NetworkPolicy {

    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun blockedReason(): String? =
        if (offlineMode?.isOfflineOnly() == true) {
            app.sirrak.core.models.DownloadReasons.OFFLINE_MODE
        } else {
            null
        }

    override fun isUnmetered(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

/** Free space on the volume that will hold the download; the downloader does the policy. */
class AndroidStorageProbe(private val root: File) : StorageProbe {
    override fun freeBytes(): Long = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L)
}

/**
 * Device capability report (ONB-2): RAM, free storage, API level, ABIs and a best-effort SoC label.
 * The GPU/NPU hint is deliberately absent - this build runs on CPU, so claiming a GPU would be a lie.
 */
class AndroidDeviceProfiler(context: Context) : DeviceProfileProvider {

    private val appContext = context.applicationContext

    override fun profile(): DeviceProfile {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val totalRamMb = (memory.totalMem / MEBIBYTE).toInt()
        val freeBytes = AndroidStorageProbe(appContext.filesDir).freeBytes()
        return DeviceProfile(
            totalRamMb = totalRamMb,
            availableStorageBytes = freeBytes,
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            socLabel = socLabel(),
            gpuLabel = null,
        )
    }

    private fun socLabel(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: Build.SOC_MANUFACTURER?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
    } else {
        Build.MANUFACTURER?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
    }
}

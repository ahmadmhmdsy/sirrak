package app.sirrak.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/**
 * Reads the memory and disk facts the fit engine needs. This is the only place in the model module
 * that touches Android APIs, so everything above it (fit arithmetic, download gating, UI verdicts) is
 * pure Kotlin and testable on the JVM.
 *
 * Honest limits: [DeviceCapacity.availableRamMb] is a snapshot — another app can take memory a second
 * later, which is exactly why a TIGHT verdict is a warning and not a promise. Disk is measured with
 * `StatFs.getAvailableBytes()` (what this app and its user can actually write), never getFreeBytes().
 */
class AndroidDeviceProbe(private val context: Context) {

    fun capacity(directory: File = context.filesDir): DeviceCapacity {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activity?.getMemoryInfo(memory)
        val totalBytes = if (memory.totalMem > 0) memory.totalMem else readMemTotal()
        val availableBytes = if (memory.availMem > 0) memory.availMem else readMemAvailable()
        return DeviceCapacity(
            totalRamMb = (totalBytes / BYTES_PER_MB).toInt(),
            availableRamMb = (availableBytes / BYTES_PER_MB).toInt(),
            memoryThresholdMb = (memory.threshold / BYTES_PER_MB).toInt(),
            isLowRamDevice = activity?.isLowRamDevice ?: false,
            availableStorageBytes = usableStorage(directory),
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
    }

    private fun usableStorage(directory: File): Long = runCatching {
        val stat = StatFs(directory.absolutePath)
        stat.availableBytes
    }.getOrDefault(0L)

    /** Fallbacks for a report the framework did not fill (seen on some API 28 devices). */
    private fun readMemTotal(): Long = readMemInfoLine("MemTotal:")

    private fun readMemAvailable(): Long =
        readMemInfoLine("MemAvailable:").takeIf { it > 0 } ?: readMemInfoLine("MemFree:")

    private fun readMemInfoLine(prefix: String): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith(prefix) }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024L)
                ?: 0L
        }
    }.getOrDefault(0L)

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}

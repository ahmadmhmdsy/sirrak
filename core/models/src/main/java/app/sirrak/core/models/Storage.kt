package app.sirrak.core.models

import java.io.File

/** What the storage manager shows: installed bytes, free bytes and orphan file names. */
data class StorageUsage(
    val modelsBytes: Long,
    val freeBytes: Long,
    val orphanFiles: List<String>,
)

/** Pure storage arithmetic shared by the recommender, the downloader and the storage manager UI. */
object StorageMath {

    /** MOD-5: a model is "tight" when free space is below [factor] times its size. */
    fun storageTight(sizeBytes: Long, freeBytes: Long, factor: Double = 3.0): Boolean =
        sizeBytes > 0 && freeBytes < (sizeBytes * factor).toLong()

    fun freeAfter(sizeBytes: Long, freeBytes: Long): Long = freeBytes - sizeBytes

    fun totalBytes(files: List<File>): Long = files.sumOf { file ->
        if (file.isFile) file.length() else 0L
    }
}

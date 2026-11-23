package app.sirrak

import android.util.Log
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelFingerprint
import app.sirrak.core.runtime.ModelIdentity
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Model identity for settings: a SHA-256 fingerprint over size + first/last 64 KiB, cached by
 * (path, size, mtime) so a cold app start does not re-read 2 GB for every installed model. A rewritten
 * file invalidates its own cache entry; a corrupt cache is simply ignored and rebuilt.
 */
class FingerprintModelIdentity(
    private val cacheFile: File,
    private val hasher: (File) -> String = ModelFingerprint::of,
) : ModelIdentity {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cache: MutableMap<String, CacheEntry> = loadCache().toMutableMap()

    override fun keyFor(model: InstalledModel): String {
        val file = File(model.path)
        val cached = cache[model.path]
        if (file.isFile) {
            if (cached != null && cached.size == file.length() && cached.modified == file.lastModified()) {
                return cached.fingerprint
            }
            val fingerprint = hasher(file)
            store(model.path, CacheEntry(path = model.path, fingerprint = fingerprint, size = file.length(), modified = file.lastModified()))
            return fingerprint
        }
        // A model that vanished still needs a stable identity for its orphaned settings.
        cached?.let { return it.fingerprint }
        val fingerprint = ModelFingerprint.sha256Hex("missing:" + model.path)
        store(model.path, CacheEntry(path = model.path, fingerprint = fingerprint, size = -1L, modified = -1L))
        return fingerprint
    }

    private fun store(path: String, entry: CacheEntry) {
        cache[path] = entry
        persistCache()
    }

    private fun loadCache(): Map<String, CacheEntry> {
        val raw = runCatching { if (cacheFile.isFile) cacheFile.readText() else null }.getOrNull()
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(CacheDto.serializer(), raw) }
            .map { dto -> dto.entries.associateBy { it.path } }
            .getOrElse { error ->
                Log.w(TAG, "model identity cache is unreadable; rebuilding", error)
                emptyMap()
            }
    }

    private fun persistCache() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(CacheDto.serializer(), CacheDto(entries = cache.values.toList())))
            if (cacheFile.exists() && !cacheFile.delete()) {
                cacheFile.writeText(tmp.readText())
                tmp.delete()
                return@runCatching
            }
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { error -> Log.w(TAG, "could not save the model identity cache", error) }
    }

    @Serializable
    private data class CacheDto(
        val schemaVersion: Int = 1,
        val entries: List<CacheEntry> = emptyList(),
    )

    @Serializable
    private data class CacheEntry(
        val path: String,
        val fingerprint: String,
        val size: Long,
        val modified: Long,
    )

    companion object {
        private const val TAG = "SirrakModelIdentity"
    }
}
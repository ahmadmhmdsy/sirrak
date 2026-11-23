package app.sirrak

import android.util.Log
import app.sirrak.core.runtime.ModelSettings
import app.sirrak.core.runtime.ModelSettingsStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable per-model settings: one small JSON document in private storage, keyed by model fingerprint.
 *
 * Writes are atomic (temp file + rename) and reads are tolerant: a missing or corrupt file starts
 * empty, and the bad bytes are copied to `model_settings.json.corrupt` before being overwritten so a
 * report can still be diagnosed. The constructor takes a directory (not a Context) so JVM tests drive
 * the real file IO.
 */
class FileModelSettingsStore(private val dir: File) : ModelSettingsStore {

    private val file = File(dir, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _settings = MutableStateFlow(load())

    override val settings: StateFlow<Map<String, ModelSettings>> = _settings.asStateFlow()

    override fun get(modelKey: String): ModelSettings? = _settings.value[modelKey]

    override fun put(settings: ModelSettings) {
        _settings.update { current -> current + (settings.modelKey to settings) }
        persist()
    }

    override fun reset(modelKey: String) {
        _settings.update { current -> current - modelKey }
        persist()
    }

    private fun load(): Map<String, ModelSettings> {
        val raw = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = runCatching { json.decodeFromString(Dto.serializer(), raw) }.getOrElse { error ->
            Log.w(TAG, "model settings file is unreadable; starting empty", error)
            backupCorruptFile()
            return emptyMap()
        }
        return parsed.settings.associateBy { it.modelKey }
    }

    private fun backupCorruptFile() {
        runCatching {
            if (file.isFile) {
                File(dir, CORRUPT_FILE_NAME).writeText(file.readText())
                file.delete()
            }
        }.onFailure { error -> Log.w(TAG, "could not back up the corrupt model settings file", error) }
    }

    private fun persist() {
        val snapshot = Dto(settings = _settings.value.values.sortedBy { it.modelKey })
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, TEMP_FILE_NAME)
            tmp.writeText(json.encodeToString(Dto.serializer(), snapshot))
            if (file.exists() && !file.delete()) {
                file.writeText(tmp.readText())
                tmp.delete()
                return@runCatching
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { error -> Log.w(TAG, "could not save model settings", error) }
    }

    @Serializable
    private data class Dto(
        val schemaVersion: Int = SCHEMA_VERSION,
        val settings: List<ModelSettings> = emptyList(),
    )

    companion object {
        internal const val FILE_NAME = "model_settings.json"
        internal const val TEMP_FILE_NAME = FILE_NAME + ".tmp"
        internal const val CORRUPT_FILE_NAME = FILE_NAME + ".corrupt"
        private const val SCHEMA_VERSION = 1
        private const val TAG = "SirrakModelSettings"
    }
}
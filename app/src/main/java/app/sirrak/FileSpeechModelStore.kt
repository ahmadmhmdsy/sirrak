package app.sirrak

import android.content.Context
import app.sirrak.core.speech.SpeechImportException
import app.sirrak.core.speech.SpeechModel
import app.sirrak.core.speech.SpeechModelFile
import app.sirrak.core.speech.SpeechModelStore
import app.sirrak.core.speech.SpeechReasons
import app.sirrak.core.speech.WhisperLayoutResult
import app.sirrak.core.speech.WhisperModelLayout
import app.sirrak.engine.whisper.SherpaWhisperEngine
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The host speech-model registry: a Whisper int8 trio copied into one app-private directory per model,
 * plus the selected model. The import is a directory rename over a .part directory, so a cancelled or
 * incomplete selection can never appear in the list; the selection is a directory id, so it survives
 * restarts without a second index that could disagree with the filesystem.
 *
 * The store owns bytes and persistence only. Layout rules live in [WhisperModelLayout] (pure JVM, so
 * they are unit-tested without Android) and the engine only ever sees a [SpeechModel].
 */
class FileSpeechModelStore(context: Context) : SpeechModelStore {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, MODELS_DIR).apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _models = MutableStateFlow(scan())
    override val models: StateFlow<List<SpeechModel>> = _models.asStateFlow()

    private val _selected = MutableStateFlow(restoreSelection())
    override val selected: StateFlow<SpeechModel?> = _selected.asStateFlow()

    override suspend fun import(files: List<SpeechModelFile>): Result<SpeechModel> =
        withContext(Dispatchers.IO) {
            runCatching {
                val layout = when (val matched = WhisperModelLayout.match(files)) {
                    is WhisperLayoutResult.Rejected -> throw SpeechImportException(matched.reason)
                    is WhisperLayoutResult.Matched -> matched
                }
                val targetDir = uniqueTargetDir(layout.baseName)
                val partDir = File(modelsDir, targetDir.name + PART_SUFFIX)
                partDir.deleteRecursively()
                if (!partDir.mkdirs()) throw IOException("cannot create " + partDir)
                try {
                    layout.files.forEach { role ->
                        val source = files.firstOrNull { it.name == role.name }
                            ?: throw SpeechImportException(SpeechReasons.IMPORT_FAILED)
                        val target = File(partDir, role.name)
                        source.open().use { input ->
                            target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                        }
                        if (target.length() == 0L) throw IOException("empty speech file: " + role.name)
                    }
                    if (!partDir.renameTo(targetDir)) throw IOException("could not finalise the import")
                    val installed = readModel(targetDir)
                        ?: throw IOException("imported speech model is not readable")
                    refresh()
                    select(installed.id)
                    installed
                } finally {
                    if (partDir.exists()) partDir.deleteRecursively()
                }
            }.recoverCatching { error ->
                throw if (error is SpeechImportException) error
                else SpeechImportException(SpeechReasons.IMPORT_FAILED)
            }
        }

    override fun select(modelId: String?) {
        val model = modelId?.let { id -> _models.value.firstOrNull { it.id == id } }
        if (model == null) {
            prefs.edit().remove(KEY_SELECTED_ID).apply()
            _selected.value = null
        } else {
            prefs.edit().putString(KEY_SELECTED_ID, model.id).apply()
            _selected.value = model
        }
    }

    override fun delete(model: SpeechModel) {
        File(model.encoderPath).parentFile?.deleteRecursively()
        refresh()
        if (_selected.value?.id == model.id) {
            prefs.edit().remove(KEY_SELECTED_ID).apply()
            _selected.value = null
        }
    }

    private fun refresh() {
        val scanned = scan()
        _models.value = scanned
        // Keep the selection consistent with the filesystem: a deleted directory cannot stay selected.
        val selectedId = _selected.value?.id ?: prefs.getString(KEY_SELECTED_ID, null)
        val resolved = scanned.firstOrNull { it.id == selectedId }
        _selected.value = resolved
        if (selectedId != null && resolved == null) prefs.edit().remove(KEY_SELECTED_ID).apply()
    }

    private fun scan(): List<SpeechModel> = modelsDir.listFiles()
        ?.filter { it.isDirectory && !it.name.endsWith(PART_SUFFIX) }
        ?.mapNotNull { readModel(it) }
        ?.sortedBy { it.displayName.lowercase() }
        ?: emptyList()

    private fun restoreSelection(): SpeechModel? {
        val id = prefs.getString(KEY_SELECTED_ID, null) ?: return null
        val model = scan().firstOrNull { it.id == id }
        if (model == null) prefs.edit().remove(KEY_SELECTED_ID).apply()
        return model
    }

    /** Rebuilds one model from its directory; null when the directory no longer holds a valid trio. */
    private fun readModel(dir: File): SpeechModel? {
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return null
        val offered = files.map { file ->
            SpeechModelFile(name = file.name, sizeBytes = file.length()) { file.inputStream() }
        }
        val matched = WhisperModelLayout.match(offered) as? WhisperLayoutResult.Matched ?: return null
        fun pathFor(role: app.sirrak.core.speech.SpeechFileRole): String =
            File(dir, matched.nameFor(role)!!).absolutePath
        return SpeechModel(
            id = dir.name,
            displayName = displayNameFor(matched.baseName),
            engineId = SherpaWhisperEngine.ID,
            encoderPath = pathFor(app.sirrak.core.speech.SpeechFileRole.ENCODER),
            decoderPath = pathFor(app.sirrak.core.speech.SpeechFileRole.DECODER),
            tokensPath = pathFor(app.sirrak.core.speech.SpeechFileRole.TOKENS),
            sizeBytes = files.sumOf { it.length() },
        )
    }

    private fun uniqueTargetDir(baseName: String): File {
        val safe = baseName.lowercase().replace(Regex("[^a-z0-9._-]"), "-").ifBlank { "whisper" }
        var candidate = File(modelsDir, safe)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(modelsDir, safe + "-" + suffix)
            suffix++
        }
        return candidate
    }

    /** One display rule for every row: "small" becomes "Small", a hyphenated name stays readable. */
    private fun displayNameFor(baseName: String): String =
        baseName.replace('-', ' ').replace('_', ' ').trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "Whisper" }

    companion object {
        const val PREFS_NAME = "sirrak_speech"
        const val KEY_SELECTED_ID = "selected_speech_model_id"
        const val MODELS_DIR = "speech_models"
        const val PART_SUFFIX = ".part"
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

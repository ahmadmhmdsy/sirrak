package app.sirrak

import android.content.Context
import android.util.Log
import app.sirrak.core.runtime.GgufFile
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.VisionProjector
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The host model registry: GGUF files copied into app-private storage, plus the selected model.
 * Importing is a stream copy with a .part file and a GGUF magic check, so a cancelled or invalid
 * import can never appear in the list. The engine only ever sees an [InstalledModel].
 */
class FileModelStore(context: Context) : ModelStore {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _models = MutableStateFlow(scan())
    override val models: StateFlow<List<InstalledModel>> = _models.asStateFlow()

    private val _selected = MutableStateFlow(restoreSelection())
    override val selected: StateFlow<InstalledModel?> = _selected.asStateFlow()

    override suspend fun import(displayName: String, open: () -> InputStream): Result<InstalledModel> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = uniqueTarget(displayName)
                val part = File(modelsDir, target.name + ".part")
                try {
                    open().use { input ->
                        part.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                    }
                    val header = GgufFile.readHeader(part)
                        ?: throw IOException("not a GGUF file")
                    if (!part.renameTo(target)) throw IOException("could not finalise the import")
                    val installed = GgufFile.toInstalledModel(target)
                        ?: throw IOException("imported file is not readable")
                    refresh()
                    select(installed)
                    installed
                } finally {
                    if (part.exists()) part.delete()
                }
            }
        }

    override fun select(model: InstalledModel) {
        prefs.edit().putString(KEY_SELECTED_PATH, model.path).apply()
        _selected.value = model
    }

    override fun delete(model: InstalledModel) {
        // The projector belongs to the model: deleting the text model must not leave a ~100 MB
        // orphan that no list row can reach (and that would silently attach to a future import).
        model.visionProjectorPath?.let { File(it).delete() }
        File(model.path).delete()
        refresh()
        if (_selected.value?.path == model.path) {
            prefs.edit().remove(KEY_SELECTED_PATH).apply()
            _selected.value = null
        }
    }

    /**
     * M9: copies a multimodal projector next to [model] as <model base>.mmproj.gguf. The pairing is a
     * file-name convention, so the association survives restarts and needs no extra index to corrupt;
     * [scan] derives [InstalledModel.visionProjectorPath] from it on every refresh.
     */
    override suspend fun attachVisionProjector(
        model: InstalledModel,
        open: () -> InputStream,
    ): Result<InstalledModel> = withContext(Dispatchers.IO) {
        runCatching {
            val modelFile = File(model.path)
            if (!modelFile.isFile) throw IOException("model file is missing")
            val target = File(modelsDir, VisionProjector.fileNameFor(modelFile.name))
            val part = File(modelsDir, target.name + ".part")
            try {
                open().use { input ->
                    part.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
                if (GgufFile.readHeader(part) == null) throw IOException("not a GGUF projector file")
                if (!part.renameTo(target)) throw IOException("could not finalise the projector import")
            } finally {
                if (part.exists()) part.delete()
            }
            refresh()
            val updated = _models.value.firstOrNull { it.path == model.path }
                ?: throw IOException("model disappeared during the projector import")
            // A loaded/selected model must see the new projector; selecting again reloads it.
            if (_selected.value?.path == updated.path) select(updated)
            updated
        }
    }

    override fun detachVisionProjector(model: InstalledModel): Result<InstalledModel> = runCatching {
        model.visionProjectorPath?.let { File(it).delete() }
        refresh()
        val updated = _models.value.firstOrNull { it.path == model.path }
            ?: throw IOException("model disappeared during the projector removal")
        if (_selected.value?.path == updated.path) select(updated)
        updated
    }

    /**
     * M11: adopts a downloaded GGUF. The downloader already verified the SHA-256 and the header, but
     * the store validates again so the invariant "only a readable GGUF is visible" never depends on
     * the caller. The source is moved into place (same filesystem, atomic rename); a copy fallback
     * covers a source on another volume.
     */
    override suspend fun importFile(
        displayName: String,
        source: File,
        expectedSha256: String?,
    ): Result<InstalledModel> = withContext(Dispatchers.IO) {
        runCatching {
            if (!source.isFile) throw IOException("downloaded file is missing")
            if (GgufFile.readHeader(source) == null) throw IOException("downloaded file is not a GGUF model")
            val target = uniqueTarget(displayName)
            if (!source.renameTo(target)) {
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
                if (!source.delete()) Log.w(TAG, "could not delete the downloaded staging file: ${source.name}")
            }
            val installed = GgufFile.toInstalledModel(target)
                ?: throw IOException("imported file is not readable")
            refresh()
            select(installed)
            installed
        }
    }

    /** M11 storage manager: the model files plus their projectors, and nothing else. */
    override fun ownedFiles(): List<File> = _models.value.flatMap { model ->
        listOfNotNull(File(model.path), model.visionProjectorPath?.let { File(it) })
    }

    /**
     * M11 storage manager: removes GGUF files (and stray .part files) in the models directory that no
     * installed model owns. A model row is never deleted implicitly: the caller confirms the count.
     */
    override fun deleteOrphanFiles(): Int {
        val owned = ownedFiles().map { it.absolutePath }.toSet()
        val orphans = modelsDir.listFiles().orEmpty().filter { file ->
            file.isFile && (file.extension.equals("gguf", ignoreCase = true) || file.extension == "part") &&
                file.absolutePath !in owned
        }
        val deleted = orphans.count { it.delete() }
        refresh()
        return deleted
    }

    private fun refresh() {
        _models.value = scan()
    }

    private fun scan(): List<InstalledModel> = modelsDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) && !isProjectorFile(it) }
        .mapNotNull { file ->
            GgufFile.toInstalledModel(file)?.copy(visionProjectorPath = projectorFor(file)?.path)
        }
        .sortedBy { it.displayName.lowercase() }

    /** Projectors are GGUF too; the shared suffix keeps them out of the model list. */
    private fun isProjectorFile(file: File): Boolean = VisionProjector.isProjector(file.name)

    /** The projector paired with [modelFile] by the naming convention, when it exists. */
    private fun projectorFor(modelFile: File): File? =
        File(modelsDir, VisionProjector.fileNameFor(modelFile.name)).takeIf { it.isFile }

    private fun restoreSelection(): InstalledModel? {
        val path = prefs.getString(KEY_SELECTED_PATH, null) ?: return null
        _models.value.firstOrNull { it.path == path }?.let { return it }
        val file = File(path)
        val model = GgufFile.toInstalledModel(file)
        if (model == null) prefs.edit().remove(KEY_SELECTED_PATH).apply()
        return model
    }

    /** Keeps user file names tidy and unique inside the private models directory. */
    private fun uniqueTarget(displayName: String): File {
        val raw = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
        val base = raw.ifBlank { "model.gguf" }.let { if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf" }
        var candidate = File(modelsDir, base)
        var counter = 2
        while (candidate.exists()) {
            candidate = File(modelsDir, base.removeSuffix(".gguf") + "-" + counter + ".gguf")
            counter++
        }
        return candidate
    }

    companion object {
        const val PREFS_NAME = "sirrak_models"
        const val KEY_SELECTED_PATH = "selected_model_path"
        private const val TAG = "SirrakModelStore"
        private const val COPY_BUFFER_BYTES = 1 shl 16
    }
}

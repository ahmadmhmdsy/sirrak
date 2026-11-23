package app.sirrak.core.runtime

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * A GGUF file that lives in the app's private storage. This is everything the UI needs to show a
 * model without loading it: the native engine (or any future engine) consumes [path] only.
 */
data class InstalledModel(
    val id: String,
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val quantization: String?,
    /**
     * M9: app-private path of this model's multimodal projector (mmproj GGUF), or null for a
     * text-only model. The engine loads it next to the model and advertises vision only when the
     * projector itself reports vision support, so the capability stays data-driven.
     */
    val visionProjectorPath: String? = null,
) {
    val sizeLabel: String get() = GgufFile.formatSize(sizeBytes)

    /** True when a projector file is attached; the engine still verifies real vision support. */
    val hasVisionProjector: Boolean get() = !visionProjectorPath.isNullOrBlank()
}

/**
 * The observable lifecycle of whatever engine is composed. The chat surface listens to this and the
 * engine plugin renders it; a "no model" state is explicit instead of an empty reply.
 */
sealed interface EngineStatus {
    data object Unloaded : EngineStatus
    data class Loading(val label: String? = null) : EngineStatus
    data class Ready(val label: String) : EngineStatus
    data class Failed(val reason: String) : EngineStatus
}

/**
 * A model registry provided by the host. Engines never touch storage or URIs: they receive an
 * [InstalledModel] and read its path. Import is stream-based so the registry stays testable and the
 * UI owns the Android storage-access framework.
 */
interface ModelStore {
    val models: StateFlow<List<InstalledModel>>
    val selected: StateFlow<InstalledModel?>

    /**
     * Copies a model into private storage. [open] is invoked once, on an IO dispatcher, and its
     * stream is closed afterwards. The copy is validated as GGUF before it becomes visible.
     */
    suspend fun import(displayName: String, open: () -> InputStream): Result<InstalledModel>

    fun select(model: InstalledModel)

    fun delete(model: InstalledModel)

    /**
     * M9: copies a multimodal projector (mmproj GGUF) into private storage and attaches it to [model].
     * The default keeps stores that predate vision (and test fakes) source-compatible; they simply
     * report that projectors are unsupported instead of pretending to import one.
     */
    suspend fun attachVisionProjector(model: InstalledModel, open: () -> InputStream): Result<InstalledModel> =
        Result.failure(UnsupportedOperationException("vision projectors are not supported by this store"))

    /** M9: removes the projector from [model]; the text model and its selection stay untouched. */
    fun detachVisionProjector(model: InstalledModel): Result<InstalledModel> =
        Result.failure(UnsupportedOperationException("vision projectors are not supported by this store"))

    /**
     * M11: adopts a fully downloaded, already-verified GGUF file into private storage. The file is
     * moved (not copied) so a 1 GB download is never duplicated on disk; the store still re-validates
     * the GGUF header before the model becomes visible. [expectedSha256] is advisory metadata only.
     *
     * The default keeps stores that predate the model manager (and test fakes) source-compatible:
     * they report that file adoption is unsupported instead of pretending to import a file.
     */
    suspend fun importFile(
        displayName: String,
        source: File,
        expectedSha256: String? = null,
    ): Result<InstalledModel> =
        Result.failure(UnsupportedOperationException("file import is not supported by this store"))

    /** M11: every file this store owns (models and their projectors), for the storage manager. */
    fun ownedFiles(): List<File> = emptyList()

    /** M11: deletes files in the store directory that belong to no model; returns how many. */
    fun deleteOrphanFiles(): Int = 0
}

/** Stable failure codes shared by engines, the chat store and the UI (never user-visible strings). */
object EngineReasons {
    const val NO_MODEL = "no-model"
    const val NO_OUTPUT = "no-output"
    const val LOAD_FAILED = "load-failed"
    const val GENERATION_FAILED = "generation-failed"

    /** The user pressed stop; this is not an engine error. */
    const val STOPPED = "stopped"

    /** M9: the attached mmproj could not be initialised against the loaded text model. */
    const val VISION_PROJECTOR_FAILED = "vision-projector-failed"

    /** M9: the projector is a valid GGUF but carries no vision capability (e.g. audio-only). */
    const val VISION_PROJECTOR_NO_VISION = "vision-projector-no-vision"

    /** M9: an image was sent while the loaded model cannot see images. */
    const val VISION_UNAVAILABLE = "vision-unavailable"

    /** M9: the attached image could not be decoded/prepared for the engine. */
    const val VISION_IMAGE_FAILED = "vision-image-failed"

    /** Grounding was requested but retrieval found no source in the selected document scope. */
    const val RAG_NO_SOURCES = "rag-no-sources"

    /**
     * The isolated inference process (ADR-4) died - e.g. a native abort in llama.cpp - while the UI
     * process survived and reconnected. A retry reloads the model in a fresh process.
     */
    const val PROCESS_LOST = "engine-process-lost"
}

/** Pure GGUF inspection: no native code, no Android, fully unit-tested. */
object GgufFile {

    const val MAGIC = "GGUF"
    const val EXTENSION = ".gguf"

    data class Header(val version: Int, val sizeBytes: Long)

    fun readHeader(file: File): Header? = runCatching {
        if (!file.isFile || file.length() < 8) return null
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) return null
            val versionBytes = ByteArray(4)
            raf.readFully(versionBytes)
            val version = (versionBytes[0].toInt() and 0xFF) or
                ((versionBytes[1].toInt() and 0xFF) shl 8) or
                ((versionBytes[2].toInt() and 0xFF) shl 16) or
                ((versionBytes[3].toInt() and 0xFF) shl 24)
            Header(version, file.length())
        }
    }.getOrNull()

    fun isValid(file: File): Boolean = readHeader(file) != null

    /** "qwen2.5-1.5b-instruct-q4_k_m.gguf" -> "qwen2.5 1.5b instruct", keeping the quant separate. */
    fun displayName(fileName: String): String {
        val base = fileName.removeSuffix(EXTENSION)
        val quant = quantization(fileName)
        val stripped = if (quant != null) base.replace(Regex("(?i)[-_]" + Regex.escape(quant) + "$"), "") else base
        return stripped.replace('-', ' ').replace('_', ' ').trim().ifBlank { base }
    }

    /** The quantisation token from a GGUF file name, if it follows the usual naming convention. */
    fun quantization(fileName: String): String? =
        QUANT.findAll(fileName).lastOrNull()?.value?.uppercase(Locale.US)

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    }

    fun toInstalledModel(file: File): InstalledModel? {
        val header = readHeader(file) ?: return null
        return InstalledModel(
            id = file.name,
            displayName = displayName(file.name),
            path = file.absolutePath,
            sizeBytes = header.sizeBytes,
            quantization = quantization(file.name),
        )
    }

    private val QUANT = Regex("(?i)(q\\d+_k(?:_[ms])?|q\\d+_\\d+|q\\d+|f16|bf16|f32)")
}

package app.sirrak

import app.sirrak.core.rag.EmbeddingProvider
import app.sirrak.core.rag.EmbeddingProviderFactory
import app.sirrak.core.rag.EmbeddingStatus
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The embedding seam the RAG repository consumes: readiness plus a lazily opened provider. Keeping it an
 * interface lets repository tests use a deterministic fake and lets a future engine swap replace only
 * the factory.
 */
interface EmbeddingBackend {
    val status: StateFlow<EmbeddingStatus>

    /** Opens (once) and returns the provider, or null when no model is installed. */
    suspend fun provider(): EmbeddingProvider?
}

/** What the settings panel shows about the installed embedding file. */
data class EmbeddingModelInfo(val displayName: String, val sizeBytes: Long)

sealed interface EmbeddingModelImport {
    data class Imported(val model: EmbeddingModelInfo) : EmbeddingModelImport
    data class Rejected(val reason: String) : EmbeddingModelImport
}

/**
 * Owns the one embedding-model file (user-imported .litertlm, never bundled: the Gemma terms do not let
 * this build redistribute it) and the provider lifecycle.
 *
 * Import is a real probe, not a copy: the file is streamed to a temp file with a rolling cap, opened
 * once with the real backend to prove it loads, and only then renamed over the installed model. A
 * corrupt download therefore leaves the previous working model untouched.
 *
 * The provider is opened lazily on first indexing/retrieval and kept for the session (load is ~1.5 s on
 * the reference device); [close] releases native memory when the app is going away.
 */
class FileEmbeddingModelStore(
    rootDir: File,
    private val factory: EmbeddingProviderFactory,
    private val modelId: String = "embeddinggemma-2-270m",
    private val dimensions: Int = 768,
    private val modelFileName: String = "embedding.litertlm",
    private val maxModelBytes: Long = 1_200_000_000L,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EmbeddingBackend {

    private val dir = File(File(rootDir, "rag"), "embedding")
    private val modelFile = File(dir, modelFileName)

    /**
     * A persisted "the last load attempt failed" flag, written by the process that observed the failure
     * and honoured by every later process. Native inference code can fail in ways Kotlin cannot catch
     * (a native abort kills the process), so the guard has to survive the rest of the process: without
     * this marker, a corrupt model would be retried on every retrieval and crash-loop the app. A new
     * import clears it; removing the model clears it; nothing else disables the guard (ADR-4).
     */
    private val failureMarker = File(dir, FAILURE_MARKER)
    private val loadMutex = Mutex()
    private val stateMutex = Any()
    private var provider: EmbeddingProvider? = null

    @Volatile
    private var loadFailed = false
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<EmbeddingStatus> = _status

    init {
        dir.mkdirs()
        loadFailed = failureMarker.isFile
        _status.value = initialStatus()
    }

    fun installedModel(): EmbeddingModelInfo? = if (modelFile.isFile) {
        EmbeddingModelInfo(modelFile.name, modelFile.length())
    } else {
        null
    }

    override suspend fun provider(): EmbeddingProvider? {
        if (!modelFile.isFile) {
            _status.value = EmbeddingStatus.Missing
            return null
        }
        if (loadFailed) {
            _status.value = EmbeddingStatus.Failed(LOAD_FAILED)
            return null
        }
        synchronized(stateMutex) { provider }?.let { return it }
        return loadMutex.withLock {
            synchronized(stateMutex) { provider }?.let { return@withLock it }
            _status.value = EmbeddingStatus.Loading(modelId)
            withContext(io) {
                // Written before the native load starts: if the process dies inside the native
                // library, this marker is the only evidence the next process gets (ADR-4).
                runCatching { failureMarker.createNewFile() }
                try {
                    val opened = factory.open(modelFile.absolutePath)
                    synchronized(stateMutex) { provider = opened }
                    loadFailed = false
                    failureMarker.delete()
                    _status.value = EmbeddingStatus.Ready(opened.modelId, opened.dimensions)
                    opened
                } catch (error: Exception) {
                    // A caught load failure (corrupt file, unsupported model) is not a crash risk:
                    // the marker is cleared so a later process start may retry, but this session
                    // stops hammering a model that already failed (utility over silent retries).
                    loadFailed = true
                    runCatching { failureMarker.delete() }
                    _status.value = EmbeddingStatus.Failed(LOAD_FAILED)
                    null
                }
            }
        }
    }

    /**
     * Imports an embedding model from a user-picked stream. Returns a rejected outcome with a stable
     * reason code; the settings panel localises the code and keeps the old model on every failure path.
     */
    suspend fun import(
        displayName: String,
        declaredSizeBytes: Long,
        openStream: () -> InputStream,
    ): EmbeddingModelImport = withContext(io) {
        if (declaredSizeBytes > maxModelBytes) {
            return@withContext EmbeddingModelImport.Rejected(REASON_TOO_LARGE)
        }
        dir.mkdirs()
        val temp = File(dir, modelFileName + TEMP_SUFFIX)
        var total = 0L
        try {
            BufferedInputStream(openStream()).use { input ->
                BufferedOutputStream(temp.outputStream()).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxModelBytes) {
                            temp.delete()
                            _status.value = EmbeddingStatus.Failed(REASON_TOO_LARGE)
                            return@withContext EmbeddingModelImport.Rejected(REASON_TOO_LARGE)
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total <= 0L) {
                temp.delete()
                return@withContext EmbeddingModelImport.Rejected(REASON_EMPTY)
            }
            closeProvider()
            _status.value = EmbeddingStatus.Loading(modelId)
            // Same crash-loop guard as provider(): the probe opens a native model. On a clean
            // rejection below the marker is cleared again because the old model is still installed.
            runCatching { failureMarker.createNewFile() }
            val probe = try {
                factory.open(temp.absolutePath)
            } catch (error: Exception) {
                temp.delete()
                clearLoadFailure()
                _status.value = restoreStatus()
                return@withContext EmbeddingModelImport.Rejected(REASON_LOAD_FAILED)
            }
            runCatching { probe.close() }
            if (!replace(temp, modelFile)) {
                temp.delete()
                clearLoadFailure()
                _status.value = EmbeddingStatus.Failed(REASON_STORAGE)
                return@withContext EmbeddingModelImport.Rejected(REASON_STORAGE)
            }
            loadFailed = false
            clearLoadFailure()
            _status.value = EmbeddingStatus.Ready(modelId, dimensions)
            EmbeddingModelImport.Imported(EmbeddingModelInfo(displayName, total))
        } catch (error: Exception) {
            temp.delete()
            _status.value = EmbeddingStatus.Failed(REASON_IMPORT)
            EmbeddingModelImport.Rejected(REASON_IMPORT)
        }
    }

    /** Removes the installed model and closes the provider; the library stays but is no longer queryable. */
    suspend fun remove(): Boolean = withContext(io) {
        closeProvider()
        loadFailed = false
        clearLoadFailure()
        val deleted = !modelFile.exists() || modelFile.delete()
        _status.value = if (modelFile.isFile) {
            EmbeddingStatus.Failed(REASON_STORAGE)
        } else {
            EmbeddingStatus.Missing
        }
        deleted
    }

    /** Releases native memory; the next [provider] call would reopen the model. */
    fun close() {
        closeProvider()
    }

    private fun closeProvider() {
        val current = synchronized(stateMutex) {
            val value = provider
            provider = null
            value
        }
        runCatching { current?.close() }
    }

    private fun initialStatus(): EmbeddingStatus = when {
        !modelFile.isFile -> EmbeddingStatus.Missing
        // A process died during a native load: report it instead of retrying on every query.
        failureMarker.isFile -> EmbeddingStatus.Failed(LOAD_FAILED)
        else -> EmbeddingStatus.Ready(modelId, dimensions)
    }

    /** Clears the persisted crash-loop marker after a known-safe resolution (remove/import). */
    private fun clearLoadFailure() {
        loadFailed = false
        runCatching { failureMarker.delete() }
    }

    /** Status to show after a rejected replacement: the previously installed model is still there. */
    private fun restoreStatus(): EmbeddingStatus = if (modelFile.isFile) {
        EmbeddingStatus.Ready(modelId, dimensions)
    } else {
        EmbeddingStatus.Missing
    }

    private fun replace(temp: File, target: File): Boolean {
        if (temp.renameTo(target)) return true
        target.delete()
        return temp.renameTo(target)
    }

    companion object {
        const val REASON_TOO_LARGE = "embedding_too_large"
        const val REASON_EMPTY = "embedding_empty"
        const val REASON_LOAD_FAILED = "embedding_load_failed"
        const val REASON_STORAGE = "embedding_storage_failed"
        const val REASON_IMPORT = "embedding_import_failed"
        private const val TEMP_SUFFIX = ".tmp"
        private const val FAILURE_MARKER = "load-failed.txt"
        private const val COPY_BUFFER = 64 * 1024
        private const val LOAD_FAILED = "embedding_load_failed"
    }
}

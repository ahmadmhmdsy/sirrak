package app.sirrak.core.models

import app.sirrak.core.runtime.GgufFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadPhase {
    IDLE,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLED,
    FAILED,
}

/**
 * Immutable snapshot of the single download the app runs at a time. [file] is set only when the phase
 * is [DownloadPhase.INSTALLED]: the verified GGUF in the staging directory, ready to be imported by
 * the model store.
 */
data class DownloadProgress(
    val entryId: String,
    val phase: DownloadPhase,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val reason: String? = null,
    val file: File? = null,
) {
    val fraction: Float
        get() = when {
            totalBytes <= 0L -> 0f
            else -> (bytesDownloaded.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val percent: Int get() = (fraction * 100f).toInt()
}

/** Stable failure reasons; the UI maps them to localised strings (never shows raw codes). */
object DownloadReasons {
    const val NOT_UNMETERED = "not_unmetered"
    const val STORAGE_LOW = "storage_low"
    const val NETWORK = "network"
    const val HTTP = "http"
    const val CHECKSUM = "checksum"
    const val IO = "io"
    const val CANCELLED = "cancelled"
    const val INVALID_MODEL = "invalid_model"
    const val RANGE_UNSUPPORTED = "range_unsupported"
    /** PRV-2: the user switched the app to offline-only; no download may start. */
    const val OFFLINE_MODE = "offline_mode"
}

/** Whether the current connection is acceptable for a large download (ONB-3 Wi-Fi-only default). */
interface NetworkPolicy {
    fun isUnmetered(): Boolean

    /**
     * A hard block that wins over the metered policy — the privacy screen's offline-only switch.
     * Null means no hard block. A default keeps every existing fake and adapter source-compatible.
     */
    fun blockedReason(): String? = null
}

/** Free space on the volume that will hold the download. */
interface StorageProbe {
    fun freeBytes(): Long
}

/** A readable response body plus what the server told us about range support and size. */
interface TransportStream {
    fun read(buffer: ByteArray): Int
    fun close()
}

data class TransportResponse(
    val body: TransportStream,
    val contentLength: Long?,
    val supportsRange: Boolean,
)

/**
 * The only network seam in the app. [authorization] carries a Hugging Face access token for gated
 * repositories (memory only, never persisted); it is null for the curated catalogue.
 */
interface DownloadTransport {
    suspend fun open(url: String, offset: Long, authorization: String? = null): TransportResponse?
}

/**
 * MOD-1/MOD-2 downloader: one download at a time, resumable through a .part file, SHA-256 verified
 * when the catalogue provides a digest, GGUF-validated before hand-off, gated by Wi-Fi policy and a
 * storage pre-check. Downloads land in [stagingDir]; importing them into the model store is the app's
 * job, so the downloader never touches installed models.
 *
 * Honest limits: no background service/notification (the download runs while the app is alive; leaving
 * the app pauses it and the .part file resumes next time), and a server that ignores Range restarts
 * from zero instead of corrupting the file.
 */
class ModelDownloader(
    private val transport: DownloadTransport,
    private val networkPolicy: NetworkPolicy,
    private val storage: StorageProbe,
    private val scope: CoroutineScope,
    private val stagingDir: File,
    private val wifiOnly: () -> Boolean = { true },
    private val storageMarginBytes: Long = ModelRecommender.STORAGE_MARGIN_BYTES,
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 400L,
    /**
     * P3.4 background seam: called from downloader threads when a run becomes active (queued,
     * downloading, verifying) or idle (paused, installed, failed, cancelled). The Android host turns
     * this into a foreground service so the process survives backgrounding; the downloader itself
     * stays pure Kotlin. Implementations must be cheap, non-blocking and must never call back into
     * the downloader.
     */
    private val onActiveChanged: (Boolean) -> Unit = {},
) {

    private data class Request(
        val entry: CatalogueEntry,
        val url: String,
        val authorization: String?,
        val projector: CatalogueProjector?,
        /** Set only after the user explicitly accepted a metered connection for this request. */
        val allowMetered: Boolean = false,
    )

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    private var job: Job? = null
    private var lastRequest: Request? = null

    // Every start/pause/resume/cancel bumps the run id. A cancelled coroutine can still be inside a
    // blocking read, so it must never write progress or a failure over the state of the next run.
    @Volatile
    private var runId: Int = 0

    @Synchronized
    fun start(
        entry: CatalogueEntry,
        url: String = entry.downloadUrl,
        authorization: String? = null,
        allowMetered: Boolean = false,
    ) {
        enqueue(Request(entry, url, authorization, projector = null, allowMetered = allowMetered))
    }

    @Synchronized
    fun startProjector(
        entry: CatalogueEntry,
        projector: CatalogueProjector,
        authorization: String? = null,
    ) {
        val repo = projector.repo ?: entry.repo
        enqueue(
            Request(
                entry,
                "https://huggingface.co/$repo/resolve/main/${projector.fileName}",
                authorization,
                projector,
            ),
        )
    }

    @Synchronized
    fun pause() {
        val current = _progress.value ?: return
        if (current.phase != DownloadPhase.DOWNLOADING && current.phase != DownloadPhase.QUEUED) return
        runId++
        // Keep the job reference: resume() joins it, so the next run cannot append to the .part file
        // while the cancelled run is still inside a blocking read.
        job?.cancel()
        _progress.value = current.copy(phase = DownloadPhase.PAUSED, bytesPerSecond = 0L)
        publishActivity()
    }

    /** Resumes the paused or retryable failed request from the .part file offset. */
    @Synchronized
    fun resume() {
        val request = lastRequest ?: return
        val current = _progress.value
        val resumable = current == null ||
            current.phase == DownloadPhase.PAUSED ||
            (current.phase == DownloadPhase.FAILED && current.reason in RETRYABLE_REASONS)
        if (!resumable) return
        enqueue(request)
    }

    @Synchronized
    fun cancel() {
        runId++
        job?.cancel()
        lastRequest?.let { request -> partFile(request).delete() }
        _progress.value = null
        publishActivity()
    }

    /** Clears the finished/installed snapshot without touching the verified file. */
    @Synchronized
    fun clear() {
        if (_progress.value?.phase == DownloadPhase.DOWNLOADING) return
        _progress.value = null
        publishActivity()
    }

    private fun enqueue(request: Request) {
        val current = _progress.value
        if (current?.phase == DownloadPhase.QUEUED ||
            current?.phase == DownloadPhase.DOWNLOADING ||
            current?.phase == DownloadPhase.VERIFYING
        ) {
            return
        }
        lastRequest = request
        val previous = job
        previous?.cancel()
        val id = ++runId
        // Report active before the first progress emit so the host can promote to a foreground
        // service while the download is still queued; idle is reported from every terminal path.
        runCatching { onActiveChanged(true) }
        job = scope.launch {
            try {
                // Wait for an invalidated run to leave its blocking read before appending again.
                previous?.join()
                runDownload(request, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(request, DownloadReasons.IO, id)
            }
        }
    }

    private suspend fun runDownload(request: Request, id: Int) = withContext(Dispatchers.IO) {
        val entry = request.entry
        val expectedSize = request.projector?.sizeBytes ?: entry.sizeBytes
        val expectedSha = request.projector?.sha256 ?: entry.sha256
        stagingDir.mkdirs()
        val part = partFile(request)
        val finalFile = finalFile(request)

        networkPolicy.blockedReason()?.let { blocked ->
            fail(request, blocked, id)
            return@withContext
        }
        // ONB-3: the default is Wi-Fi only. A metered run is allowed only when the screen asked the
        // user and they said yes; the request remembers that answer across pause/resume.
        if (!request.allowMetered && wifiOnly() && !networkPolicy.isUnmetered()) {
            fail(request, DownloadReasons.NOT_UNMETERED, id)
            return@withContext
        }
        val remaining = (expectedSize - part.length()).coerceAtLeast(0L)
        if (storage.freeBytes() < remaining + storageMarginBytes) {
            fail(request, DownloadReasons.STORAGE_LOW, id)
            return@withContext
        }

        var offset = part.length()
        emit(id, DownloadProgress(entry.id, DownloadPhase.QUEUED, offset, expectedSize))
        var attempt = 0
        while (isActive && runId == id) {
            attempt++
            try {
                val response = transport.open(request.url, offset, request.authorization)
                if (response == null) {
                    fail(request, DownloadReasons.HTTP, id)
                    return@withContext
                }
                if (offset > 0L && !response.supportsRange) {
                    // The server ignored Range: restart cleanly instead of appending a second copy.
                    part.delete()
                    offset = 0L
                    emit(id, DownloadProgress(entry.id, DownloadPhase.QUEUED, 0L, expectedSize))
                }
                val total = when {
                    response.contentLength != null && response.contentLength >= 0L -> offset + response.contentLength
                    expectedSize > 0L -> expectedSize
                    else -> 0L
                }
                var windowBytes = 0L
                var windowStartedAt = System.currentTimeMillis()
                var lastEmit = 0L
                FileOutputStream(part, true).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (isActive && runId == id) {
                        val read = response.body.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        offset += read
                        windowBytes += read
                        val now = System.currentTimeMillis()
                        val elapsed = now - windowStartedAt
                        if (elapsed >= 250L) {
                            emit(
                                id,
                                DownloadProgress(
                                    entryId = entry.id,
                                    phase = DownloadPhase.DOWNLOADING,
                                    bytesDownloaded = offset,
                                    totalBytes = total,
                                    bytesPerSecond = windowBytes * 1000L / elapsed.coerceAtLeast(1L),
                                ),
                            )
                            windowBytes = 0L
                            windowStartedAt = now
                            lastEmit = now
                        } else if (now - lastEmit >= 120L) {
                            emit(
                                id,
                                DownloadProgress(
                                    entryId = entry.id,
                                    phase = DownloadPhase.DOWNLOADING,
                                    bytesDownloaded = offset,
                                    totalBytes = total,
                                    bytesPerSecond = windowBytes * 1000L / elapsed.coerceAtLeast(1L),
                                ),
                            )
                            lastEmit = now
                        }
                    }
                    output.flush()
                }
                response.body.close()
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (attempt >= maxAttempts) {
                    fail(request, DownloadReasons.NETWORK, id)
                    return@withContext
                }
                offset = part.length()
                if (offset <= 0L) part.delete()
                delay(retryDelayMs)
            }
        }
        if (!isActive || runId != id) return@withContext

        emit(id, DownloadProgress(entry.id, DownloadPhase.VERIFYING, part.length(), expectedSize))
        if (expectedSize > 0L && part.length() != expectedSize) {
            part.delete()
            fail(request, DownloadReasons.NETWORK, id)
            return@withContext
        }
        if (expectedSha != null) {
            val actual = sha256(part)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                part.delete()
                fail(request, DownloadReasons.CHECKSUM, id)
                return@withContext
            }
        }
        if (!GgufFile.isValid(part)) {
            part.delete()
            fail(request, DownloadReasons.INVALID_MODEL, id)
            return@withContext
        }
        finalFile.delete()
        if (!part.renameTo(finalFile)) {
            fail(request, DownloadReasons.IO, id)
            return@withContext
        }
        emit(
            id,
            DownloadProgress(
                entryId = entry.id,
                phase = DownloadPhase.INSTALLED,
                bytesDownloaded = finalFile.length(),
                totalBytes = finalFile.length(),
                file = finalFile,
            ),
        )
    }

    /**
     * Writes progress only while [id] is still the current run, so a paused run cannot overwrite it.
     * Synchronized with pause()/cancel(): the id check and the write must be one atomic step, or a
     * cancelled download can slip a DOWNLOADING snapshot in just after the PAUSED state.
     */
    @Synchronized
    private fun emit(id: Int, progress: DownloadProgress) {
        if (runId == id) {
            _progress.value = progress
            publishActivity()
        }
    }

    @Synchronized
    private fun fail(request: Request, reason: String, id: Int) {
        if (runId != id) return
        val part = partFile(request)
        _progress.value = DownloadProgress(
            entryId = request.entry.id,
            phase = DownloadPhase.FAILED,
            bytesDownloaded = if (part.exists()) part.length() else 0L,
            totalBytes = request.projector?.sizeBytes ?: request.entry.sizeBytes,
            reason = reason,
        )
        publishActivity()
    }

    /** Mirrors [DownloadProgress] to the keep-alive seam; only phase changes matter to the host. */
    private fun publishActivity() {
        val phase = _progress.value?.phase
        val active = phase == DownloadPhase.QUEUED ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.VERIFYING
        runCatching { onActiveChanged(active) }
    }

    private fun partFile(request: Request): File {
        val suffix = if (request.projector != null) PROJECTOR_SUFFIX else MODEL_SUFFIX
        return File(stagingDir, request.entry.id + suffix + PART_SUFFIX)
    }

    private fun finalFile(request: Request): File {
        val suffix = if (request.projector != null) PROJECTOR_SUFFIX else MODEL_SUFFIX
        return File(stagingDir, request.entry.id + suffix)
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MODEL_SUFFIX = ".gguf"
        private const val PROJECTOR_SUFFIX = ".mmproj.gguf"
        private const val PART_SUFFIX = ".part"
        private val RETRYABLE_REASONS = setOf(DownloadReasons.NETWORK, DownloadReasons.HTTP)

        /** Lowercase hex SHA-256 of a file, streamed so a 1 GB model never sits in memory. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

/** Adapter so the app's HttpURLConnection transport and tests share the [TransportStream] shape. */
class StreamTransportBody(private val input: InputStream) : TransportStream {
    override fun read(buffer: ByteArray): Int = input.read(buffer)
    override fun close() = input.close()
}

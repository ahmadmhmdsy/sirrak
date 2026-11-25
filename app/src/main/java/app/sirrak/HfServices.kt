package app.sirrak

import app.sirrak.core.models.DownloadReasons
import app.sirrak.core.models.GgufProbe
import app.sirrak.core.models.GgufProbeResponse
import app.sirrak.core.models.GgufProbeTransport
import app.sirrak.core.models.HfAccessPolicy
import app.sirrak.core.models.HfCallKind
import app.sirrak.core.models.NetworkPolicy
import app.sirrak.core.privacy.OfflineModeStore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The Hugging Face token for gated repos, held in memory only.
 *
 * Persisting it would put a long-lived credential on disk for a feature the reader may use once, so a
 * process restart asks again. The value is trimmed once here; blank means "no token", never an empty
 * Authorization header. It never reaches a log line, the audit or a URL: [app.sirrak.core.models.HfClient]
 * and [app.sirrak.core.models.GgufProbe] only put it in a request header, and the probe drops it on the
 * redirect to the CDN.
 */
class HfTokenStore {

    private val mutable = MutableStateFlow<String?>(null)

    val token: StateFlow<String?> = mutable.asStateFlow()

    fun set(value: String?) {
        val trimmed = value?.trim().orEmpty()
        mutable.value = trimmed.ifEmpty { null }
    }
}

/**
 * The Compose side of the metered-consent question.
 *
 * [app.sirrak.core.models.HfAccessPolicy] is pure Kotlin, so it cannot show a dialog. This host owns
 * one pending question as snapshot state; the visible page renders it and answers. A caller suspends
 * until the reader decides, which is exactly the contract [HfAccessPolicy.confirmMetered] documents:
 * nothing byte-moving begins while the question is on screen. A dismissed dialog answers "no", so the
 * worst case is a queued row that waits for Wi-Fi, never a silent metered transfer.
 *
 * Two concurrent requests are answered by the same one dialog (first question wins the wording); this
 * cannot happen from the UI, but making the seam safe beats relying on that.
 */
class HfMeteredPromptHost {

    private val lock = Any()
    private val mutable = MutableStateFlow<HfCallKind?>(null)
    private val waiters = mutableListOf<CompletableDeferred<Boolean>>()

    /** The question the page should render, or null when nothing is pending. */
    val pending: StateFlow<HfCallKind?> = mutable.asStateFlow()

    suspend fun ask(kind: HfCallKind): Boolean {
        val answer = CompletableDeferred<Boolean>()
        synchronized(lock) {
            waiters += answer
            if (waiters.size == 1) mutable.value = kind
        }
        return try {
            answer.await()
        } finally {
            synchronized(lock) {
                waiters -= answer
                if (waiters.isEmpty()) mutable.value = null
            }
        }
    }

    /** The page's answer for the pending question; a no-op when none is waiting. */
    fun answer(approved: Boolean) {
        val current = synchronized(lock) { waiters.toList() }
        current.forEach { it.complete(approved) }
    }
}

/**
 * [HfAccessPolicy] over the app's real switches: the privacy screen's offline-only store and the same
 * [NetworkPolicy] the downloader enforces. The consent question is delegated to [HfMeteredPromptHost],
 * so this adapter stays a pure expression of policy and remains trivially testable.
 */
class HfHostAccessPolicy(
    private val offline: OfflineModeStore,
    private val network: NetworkPolicy,
    private val prompt: HfMeteredPromptHost,
) : HfAccessPolicy {

    override fun blockedReason(): String? =
        if (offline.isOfflineOnly()) DownloadReasons.OFFLINE_MODE else null

    override fun isUnmetered(): Boolean = network.isUnmetered()

    override suspend fun confirmMetered(kind: HfCallKind): Boolean = prompt.ask(kind)
}

/**
 * [GgufProbeTransport] over HttpURLConnection. It does not follow redirects: [GgufProbe] owns the hop
 * so the Range header is preserved and the token is dropped when the resolver sends it to the CDN.
 * The body is read up to [maxBytes] and then closed, because the probe only ever needs a header and a
 * server that ignores Range must not turn 12 MB into the whole model file.
 *
 * Honest limits: no proxy, no certificate pinning, no retry. A socket failure is thrown, as the seam
 * documents, and the screen decides whether to offer the retry.
 */
class UrlGgufProbeTransport(
    private val userAgent: String = "Sirrak",
    /** Hard cap on the bytes kept in memory; tests lower it, the app uses the probe's own budget. */
    private val maxBytes: Int = GgufProbe.MAX_BYTES,
) : GgufProbeTransport {

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    override suspend fun get(
        url: String,
        rangeHeader: String,
        authorization: String?,
    ): GgufProbeResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "*/*")
            // No transparent decompression: the probe parses raw GGUF bytes and a gzip layer would
            // change the byte count the screen reports.
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Range", rangeHeader)
            if (!authorization.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + authorization)
            }
            when (val status = connection.responseCode) {
                in REDIRECT_STATUSES -> GgufProbeResponse(
                    status = status,
                    location = connection.getHeaderField("Location"),
                )
                STATUS_OK, STATUS_PARTIAL_CONTENT -> GgufProbeResponse(
                    status = status,
                    bytes = readCapped(connection),
                )
                else -> GgufProbeResponse(status = status)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(connection: HttpURLConnection): ByteArray {
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val READ_BUFFER_BYTES = 64 * 1024
        const val STATUS_OK = 200
        const val STATUS_PARTIAL_CONTENT = 206
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

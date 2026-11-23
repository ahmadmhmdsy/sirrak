package app.sirrak

import app.sirrak.core.models.DownloadTransport
import app.sirrak.core.models.TransportResponse
import app.sirrak.core.models.TransportStream
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.NetworkOutcome
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real transport behind [app.sirrak.core.models.ModelDownloader]: HTTP(S) with range requests so a
 * paused or interrupted download resumes at the exact byte. Everything here is I/O only - retry,
 * checksum, storage and licence policy stay in the pure-JVM downloader, where they are unit-tested.
 *
 * Honest limits: no proxy configuration, no certificate pinning and no background scheduler; the
 * download runs while the app process is alive and resumes from the .part file on the next start.
 * Hosting is Hugging Face's public resolver (doc 17 F7): one GET per file, redirects followed, no
 * analytics. [authorization] is used only when the user typed a token for a gated repository and is
 * never persisted by this class.
 */
class HttpDownloadTransport(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val userAgent: String = "Sirrak/0.1 (Android; on-device model downloader)",
    /** PRV-2: every call through this transport is recorded on-device, never sent anywhere. */
    private val audit: NetworkAudit? = null,
    private val endpointId: String = "model_download",
) : DownloadTransport {

    override suspend fun open(
        url: String,
        offset: Long,
        authorization: String?,
    ): TransportResponse? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", userAgent)
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            if (!authorization.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authorization")
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "download HTTP $code for $url")
                record(url, bytes = 0L, outcome = NetworkOutcome.FAILED)
                connection.disconnect()
                return@withContext null
            }
            val supportsRange = offset > 0 && code == HttpURLConnection.HTTP_PARTIAL
            val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
            record(url, bytes = contentLength ?: 0L, outcome = NetworkOutcome.COMPLETED)
            val input = connection.inputStream
            TransportResponse(
                contentLength = contentLength,
                supportsRange = supportsRange,
                body = object : TransportStream {
                    override fun read(buffer: ByteArray): Int = input.read(buffer)

                    override fun close() {
                        runCatching { input.close() }
                        connection.disconnect()
                    }
                },
            )
        } catch (error: IOException) {
            record(url, bytes = 0L, outcome = NetworkOutcome.FAILED)
            connection.disconnect()
            throw error
        }
    }

    /** Host is parsed from the URL, so the audit log never depends on a hardcoded host list. */
    private fun record(url: String, bytes: Long, outcome: NetworkOutcome) {
        val recorder = audit ?: return
        val host = runCatching { URL(url).host }.getOrDefault(url)
        runCatching { recorder.record(endpointId, host, bytes = bytes, outcome = outcome) }
    }

    private companion object {
        const val TAG = "SirrakDownload"
    }
}

package app.sirrak.core.models

import java.net.URI
import kotlinx.coroutines.CancellationException

/**
 * One answer to a ranged GET. [location] is the redirect target when [status] is 3xx; the probe
 * follows it itself because the resolver redirects to a signed CDN URL and the Range header has to
 * survive the hop. [bytes] is whatever the transport managed to read (capped by the caller).
 */
data class GgufProbeResponse(
    val status: Int,
    val bytes: ByteArray = ByteArray(0),
    val location: String? = null,
)

/**
 * The socket seam for the capped GGUF probe. The host implements it with HttpURLConnection, the
 * User-Agent and the memory-only Authorization header; tests fake it. It must not follow redirects —
 * [GgufProbe] owns that, so the token and the Range header are decided in one place.
 *
 * A socket failure is thrown (and mapped to [GgufProbeCodes.NETWORK]); a cancellation must surface
 * as [kotlinx.coroutines.CancellationException] like any suspending call.
 */
interface GgufProbeTransport {
    suspend fun get(url: String, rangeHeader: String, authorization: String?): GgufProbeResponse
}

/** Stable result codes the UI maps to localized sentences; never shown to the reader raw. */
object GgufProbeCodes {
    /** The socket failed before an answer arrived; a retry can help. */
    const val NETWORK = "network"

    /** 416: the server refused the byte range. The file can still be downloaded from zero. */
    const val RANGE_UNSUPPORTED = "range_unsupported"

    /** The bytes were not a GGUF header, the redirect chain was unusable, or the URL was unsafe. */
    const val MALFORMED = "malformed"

    /** Every non-2xx answer is `http_<status>`, so the screen can tell a gate from a server fault. */
    const val HTTP_PREFIX = "http_"

    fun httpStatus(status: Int): String = HTTP_PREFIX + status
}

/**
 * The outcome of one capped probe. A [Success] may still carry an incomplete header: a truncated
 * prefix is a value ([GgufMetadata.complete] is false), because the page can fill a band of numbers
 * instead of claiming it verified the file. "Parsed" is not "runs" — the fit engine makes that call.
 */
sealed interface GgufProbeResult {
    data class Success(
        val metadata: GgufMetadata,
        val bytesRead: Int,
        val finalUrl: String,
        /** True when the server answered 206 (a real partial range) rather than ignoring it with 200. */
        val ranged: Boolean,
    ) : GgufProbeResult

    data class Failure(
        val code: String,
        val retryable: Boolean,
        val httpStatus: Int? = null,
    ) : GgufProbeResult
}

/**
 * The P3 partial GGUF probe: one ranged GET of at most [MAX_BYTES] (12 MB, the measured T1 envelope),
 * manual redirect follow from the Hugging Face resolver to the CDN with the Range header preserved,
 * and a tolerant parse through [GgufMetadataReader]. A 206 is the expected answer; a 200 (a server
 * or proxy that ignored Range) is accepted honestly and flagged [GgufProbeResult.Success.ranged] false.
 *
 * The token travels in a header only and is forwarded only to huggingface.co: a redirect to the CDN
 * (or to any other host) drops it, so a signed CDN URL never receives a bearer token. Inclusive of
 * that rule, an unsafe URL — plain http, credentials in the authority, no host — never opens a socket.
 *
 * Honest limits: no retry/backoff (the screen owns the retry), no Range restart on a 206 past zero,
 * and a probe byte budget that is a hard cap rather than a guarantee: a pathological header stops at
 * the cap and returns the fields it read.
 */
class GgufProbe(
    private val transport: GgufProbeTransport,
    /** Byte budget of the ranged GET; tests lower it, the app always uses [MAX_BYTES]. */
    private val maxBytes: Int = MAX_BYTES,
    /** Redirect hops allowed after the first request; a loop stops here instead of running forever. */
    private val maxRedirects: Int = MAX_REDIRECTS,
    /**
     * Called on the same coroutine just before every HTTP call (including CDN hops) so the host can
     * record the network audit. A failed local write must not fail the probe.
     */
    private val onRequest: (url: String) -> Unit = {},
) {

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxRedirects >= 0) { "maxRedirects must not be negative" }
    }

    private val rangeValue: String = "bytes=0-" + (maxBytes - 1)

    /** Probes [url] (a resolver link from [HfClient.probeUrl]); [authorization] is a token or null. */
    suspend fun probe(url: String, authorization: String? = null): GgufProbeResult {
        var current = safeHttpsUrlOrNull(url) ?: return failure(GgufProbeCodes.MALFORMED)
        var hop = 0
        while (true) {
            runCatching { onRequest(current) }
            val response = try {
                transport.get(current, rangeValue, tokenFor(current, authorization))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                return GgufProbeResult.Failure(code = GgufProbeCodes.NETWORK, retryable = true)
            }
            when {
                response.status in REDIRECT_STATUSES -> {
                    if (hop >= maxRedirects) return failure(GgufProbeCodes.MALFORMED)
                    current = resolveRedirect(current, response.location)
                        ?: return failure(GgufProbeCodes.MALFORMED)
                    hop++
                }
                response.status == STATUS_PARTIAL_CONTENT -> return parse(response.bytes, current, ranged = true)
                response.status == STATUS_OK -> return parse(response.bytes, current, ranged = false)
                response.status == STATUS_RANGE_NOT_SATISFIABLE -> return GgufProbeResult.Failure(
                    code = GgufProbeCodes.RANGE_UNSUPPORTED,
                    retryable = false,
                    httpStatus = STATUS_RANGE_NOT_SATISFIABLE,
                )
                else -> return GgufProbeResult.Failure(
                    code = GgufProbeCodes.httpStatus(response.status),
                    retryable = response.status >= 500 || response.status == STATUS_TOO_MANY_REQUESTS,
                    httpStatus = response.status,
                )
            }
        }
    }

    /** Caps the body, refuses a non-GGUF payload, and keeps whatever header bytes arrived. */
    private fun parse(bytes: ByteArray, finalUrl: String, ranged: Boolean): GgufProbeResult {
        if (bytes.isEmpty()) return failure(GgufProbeCodes.MALFORMED)
        val prefix = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
        val metadata = GgufMetadataReader.read(prefix)
        if (!metadata.magicOk) return failure(GgufProbeCodes.MALFORMED)
        return GgufProbeResult.Success(
            metadata = metadata,
            bytesRead = prefix.size,
            finalUrl = finalUrl,
            ranged = ranged,
        )
    }

    /**
     * The token goes to huggingface.co only, and only while the hop is still on it. A signed CDN URL
     * never needs the bearer token, and forwarding it would hand a credential to a third host.
     */
    private fun tokenFor(url: String, authorization: String?): String? {
        if (authorization.isNullOrBlank()) return null
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        return authorization.takeIf { API_HOST.equals(host, ignoreCase = true) }
    }

    /** A redirect target: absolute or relative, and never a plain-http or credential-bearing URL. */
    private fun resolveRedirect(current: String, location: String?): String? {
        val raw = location?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val resolved = runCatching { URI(current).resolve(raw) }.getOrNull() ?: return null
        return safeHttpsUrlOrNull(resolved.toString())
    }

    /** An absolute https URL with a host and no embedded credentials, or null. */
    private fun safeHttpsUrlOrNull(url: String): String? {
        val value = url.trim()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val safe = uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
        return if (safe) value else null
    }

    private fun failure(code: String): GgufProbeResult.Failure =
        GgufProbeResult.Failure(code = code, retryable = false)

    companion object {
        /** The measured T1 envelope: every real GGUF header parsed within 7.82 MB, so 12 MB is a cap. */
        const val MAX_BYTES = 12 * 1024 * 1024

        /** Enough hops for resolver -> CDN -> signed edge without trusting an endless chain. */
        const val MAX_REDIRECTS = 5

        /** The exact Range value every hop sends: the first [MAX_BYTES] of the file, end-inclusive. */
        fun rangeHeader(): String = "bytes=0-" + (MAX_BYTES - 1)

        private const val API_HOST = "huggingface.co"
        private const val STATUS_OK = 200
        private const val STATUS_PARTIAL_CONTENT = 206
        private const val STATUS_RANGE_NOT_SATISFIABLE = 416
        private const val STATUS_TOO_MANY_REQUESTS = 429
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
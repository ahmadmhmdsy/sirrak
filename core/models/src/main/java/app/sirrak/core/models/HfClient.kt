package app.sirrak.core.models

import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Which Hugging Face call is about to run. The kind is the access policy's unit of consent (a
 * search is cheap, a probe can move up to 12 MB) and the label the network audit records, so both
 * seams agree on one name.
 */
enum class HfCallKind {
    /** Search queries; the page debounces typing so one keystroke never becomes one API call. */
    SEARCH,

    /** Repo card and file tree: cheap metadata calls that download no model bytes. */
    METADATA,

    /** The capped Range probe that reads a GGUF header to learn context size and chat template. */
    PROBE,

    /** A model file download; the largest call, and the one the Wi-Fi-only rule exists for. */
    DOWNLOAD;

    /** True only for the calls that move real model bytes; on a metered link those need a yes. */
    val needsMeteredConfirmation: Boolean
        get() = this == PROBE || this == DOWNLOAD
}

/**
 * What the app allows right now, answered by the Android host from the privacy switch and the
 * connectivity monitor. Keeping it an interface keeps this module pure Kotlin: tests drive every
 * branch, and the host owns the dialog that [confirmMetered] shows.
 */
interface HfAccessPolicy {
    /** Null when a call may proceed; otherwise a stable reason such as `offline_mode`. */
    fun blockedReason(): String?

    /** False on mobile data; the byte-moving calls then have to ask before they run. */
    fun isUnmetered(): Boolean

    /**
     * Asks the user whether one metered [kind] may run and suspends until they answer. The
     * implementation must keep a cancel path visible: the whole acquisition waits on this.
     */
    suspend fun confirmMetered(kind: HfCallKind): Boolean
}

/**
 * One API answer, body left as text. The Hub's JSON differs per endpoint, so a typed serializer per
 * endpoint would buy nothing; the client validates the shape it needs instead. Header names keep
 * the server's casing; [header] looks them up without caring.
 */
data class HfHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    /** Case-insensitive lookup: header names are case-insensitive and the Hub's casing varies. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * The socket seam. [authorization] is a Hugging Face token supplied for gated repos; it travels in
 * a header only, never in the URL, so no log line or audit entry can leak it.
 */
interface HfTransport {
    /** Performs one GET; a socket error is thrown and mapped to [HfCodes.NETWORK] by the client. */
    suspend fun get(url: String, authorization: String? = null): HfHttpResponse
}

/**
 * [HfTransport] over HttpURLConnection on the IO dispatcher. The token goes into the Authorization
 * header only — never in a URL, never in a log line — and the connection and stream are closed on
 * every path.
 *
 * Redirects are not followed: the JSON endpoints answer directly, and a silent hop off
 * huggingface.co would leave the host the privacy screen promises. The resolver URL, which does
 * redirect to the CDN, is not fetched here; the downloader owns that.
 *
 * Honest limits: no proxy, no certificate pinning, no retry/backoff and no response-size cap. The
 * caller owns the 429 wait, and the API answers are small by contract.
 */
class UrlHfTransport(
    private val userAgent: String = "Sirrak",
) : HfTransport {

    override suspend fun get(url: String, authorization: String?): HfHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", userAgent)
                if (!authorization.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $authorization")
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                HfHttpResponse(status = status, body = body, headers = headersOf(connection))
            } finally {
                connection.disconnect()
            }
        }

    private fun headersOf(connection: HttpURLConnection): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        connection.headerFields?.forEach { (name, values) ->
            if (name != null) headers[name] = values.filterNotNull().joinToString(", ")
        }
        return headers
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

/**
 * Every call returns one of these instead of throwing. The search page renders a blocked or failed
 * state without try/catch, and the codes stay stable so both locales can translate them.
 */
sealed interface HfResult<out T> {
    /** The call succeeded; [value] is the parsed body. */
    data class Success<T>(val value: T) : HfResult<T>

    /** The policy said no before a socket opened, or the user declined the metered prompt. */
    data class Blocked(val reason: String) : HfResult<Nothing> {
        /** Stable `blocked:<reason>` code; the UI maps each known reason to a localized sentence. */
        val code: String get() = "blocked:$reason"
    }

    /**
     * The call was attempted but produced no usable answer. [retryable] is known at the failure
     * site; [retryAfterSeconds] is filled from Retry-After on [HfCodes.RATE_LIMITED].
     */
    data class Failure(
        val code: String,
        val retryable: Boolean,
        val retryAfterSeconds: Long? = null,
    ) : HfResult<Nothing>
}

/**
 * Stable codes for every state the UI must explain. The UI translates them and never shows a code
 * raw; renaming one is a copy change, not a cleanup.
 */
object HfCodes {
    /** The Hub answered outside 2xx; [HfResult.Failure.retryable] says whether it was a 5xx. */
    const val HTTP_ERROR = "http_error"

    /** 429: the anonymous API budget is spent; wait [HfResult.Failure.retryAfterSeconds]. */
    const val RATE_LIMITED = "rate_limited"

    /** The body was not the JSON this client can read; the same bytes would fail again. */
    const val MALFORMED = "malformed"

    /** The socket failed before an answer arrived; a retry can help. */
    const val NETWORK = "network"

    /** Reserved: a cancelled coroutine is rethrown and the host maps CancellationException here. */
    const val CANCELLED = "cancelled"

    /** 401/403: the repo needs a Hugging Face token (gated or private); retrying changes nothing. */
    const val GATED = "gated"

    /** The query was blank after trimming, so no call was made. */
    const val BLANK_QUERY = "blank_query"

    /** The repo id is not a trimmed `owner/name`, so no call was made. */
    const val INVALID_REPO = "invalid_repo"

    /** Block reason: the user declined a PROBE or DOWNLOAD on a metered link. */
    const val METERED_DECLINED = "metered_declined"
}

/**
 * One search row, in the order the Hub returned it (downloads first). A missing count reads as 0
 * rather than hiding the row, because a downloadable model is worth showing even when the Hub's
 * counters are absent.
 */
data class HfModelSummary(
    /** `owner/name`, the id every later call takes. */
    val id: String,
    /** Lifetime download count; the sort key and the card's popularity line. */
    val downloads: Long,
    /** Lifetime like count; the card's second social signal. */
    val likes: Long,
    /** True when the repo needs a token and an accepted licence before the download link works. */
    val gated: Boolean,
    /** The Hub's ISO timestamp, kept raw for sorting and relative formatting in the UI. */
    val lastModified: String?,
)

/**
 * One downloadable file at the repo's main revision. [sizeBytes] is the Hub's real byte count
 * (LFS aware) and is what the progress bar and the free-space check trust; [sha256] exists only
 * for LFS files, where the LFS oid is the digest the downloader can verify.
 */
data class HfRepoFile(
    /** Path inside the repo, also the resolver URL's tail. */
    val path: String,
    /** Real size in bytes; 0 only when the Hub published none. */
    val sizeBytes: Long,
    /** SHA-256 for LFS files, null for files the Hub does not store in LFS. */
    val sha256: String?,
)

/**
 * A repo card as the Hub publishes it. [gated] resolves the Hub's `false | "auto" | "manual"`
 * answer into "needs a token"; [license] is the declared name, not legal advice; the `gguf` fields
 * are the uploader's metadata and save a probe when present.
 */
data class HfRepoInfo(
    /** `owner/name`, echoed back so the card knows which repo it describes. */
    val id: String,
    /** True when a token and an accepted licence are required. */
    val gated: Boolean,
    /** Declared licence id such as `apache-2.0`, or null when the uploader declared none. */
    val license: String?,
    /** Architecture from the Hub's GGUF metadata, e.g. `qwen2`. */
    val architecture: String?,
    /** Context length in tokens from the GGUF metadata; null when not published. */
    val contextTokens: Int?,
    /** The GGUF chat template; null means a probe has to read it from the file instead. */
    val chatTemplate: String?,
)

/**
 * One page of results. [nextCursorUrl] is the Hub's absolute next-page URL from the Link header;
 * null means the result is complete. Pass it to [HfClient.page] to fetch the next page.
 */
data class HfPage<T>(
    /** The page rows, in the order the Hub ranked them. */
    val items: List<T>,
    /** Absolute next-page URL, or null on the last page. */
    val nextCursorUrl: String? = null,
)

/**
 * Hugging Face Hub acquisition for the P1/P3 browser: search, file tree, repo card and the two
 * resolver URLs. Every call asks [HfAccessPolicy] first, reports itself through [onRequest] — on
 * the same coroutine, just before the transport call, so the audit write cannot race the request —
 * and returns an [HfResult] instead of throwing.
 *
 * [transport] performs the HTTP calls, [access] answers the policy, and [onRequest] feeds the
 * network audit. Keeping all three as seams is what lets this module stay pure JVM Kotlin.
 *
 * Honest limits, left out on purpose:
 * - No cache and no debounce. The page debounces typing and caches pages; one keystroke must never
 *   become one API call (anonymous budget: 500 API and 3000 resolve calls per five minutes).
 * - No retry loop. [HfResult.Failure.retryable] and [HfResult.Failure.retryAfterSeconds] go to the
 *   caller, which owns the 429 wait and the backoff.
 * - No pagination loop or prefetch. [page] fetches exactly the cursor URL it is handed, one page
 *   per call; the screen decides when the user asked for more.
 */
class HfClient(
    private val transport: HfTransport,
    private val access: HfAccessPolicy,
    private val onRequest: (url: String, kind: HfCallKind) -> Unit = { _, _ -> },
) {

    /**
     * One page of GGUF repos ordered by downloads. [query] is trimmed and UTF-8 percent-encoded, so
     * an Arabic search works; a blank query returns [HfCodes.BLANK_QUERY] without a call. [limit]
     * is clamped to 1..100 — the Hub's page range — so a caller cannot spend the anonymous budget
     * on an oversized page.
     */
    suspend fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        authorization: String? = null,
    ): HfResult<HfPage<HfModelSummary>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return HfResult.Failure(HfCodes.BLANK_QUERY, retryable = false)
        val pageSize = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val url = buildString {
            append(API_BASE)
            append("/api/models?search=")
            append(URLEncoder.encode(trimmed, Charsets.UTF_8.name()))
            append("&filter=gguf&sort=downloads&direction=-1&limit=")
            append(pageSize)
        }
        return execute(HfCallKind.SEARCH, url, authorization, ::parseSearch)
    }

    /**
     * Fetches one further result page from the Hub's opaque cursor URL — the value a previous call
     * left in [HfPage.nextCursorUrl]. This is the only safe way for the "more" button to page: the
     * URL must be an absolute https URL on huggingface.co before anything is sent, because
     * [authorization] would otherwise follow a foreign cursor off the Hub.
     */
    suspend fun page(cursorUrl: String, authorization: String? = null): HfResult<HfPage<HfModelSummary>> {
        val url = cursorPageUrlOrNull(cursorUrl)
            ?: return HfResult.Failure(HfCodes.MALFORMED, retryable = false)
        return execute(HfCallKind.SEARCH, url, authorization, ::parseSearch)
    }

    /**
     * The files of [repo] at main, flattened (`recursive=true`), with the real byte size and the LFS
     * SHA-256 where the Hub publishes it. Directories are dropped: the file page lists files. This
     * is what fills the downloader's expected size and checksum, so the page calls it once per repo
     * and caches the answer — never once per rendered row.
     */
    suspend fun files(repo: String, authorization: String? = null): HfResult<List<HfRepoFile>> {
        val id = repoIdOrNull(repo) ?: return HfResult.Failure(HfCodes.INVALID_REPO, retryable = false)
        return execute(
            kind = HfCallKind.METADATA,
            url = "$API_BASE/api/models/$id/tree/main?recursive=true",
            authorization = authorization,
            parse = ::parseFiles,
        )
    }

    /**
     * The repo card: the gate, the declared licence and the Hub's `gguf` block.
     * [HfRepoInfo.contextTokens] and [HfRepoInfo.chatTemplate] avoid a probe when the uploader
     * published them; when they are null the caller falls back to reading the GGUF header, which is
     * authoritative anyway.
     */
    suspend fun detail(repo: String, authorization: String? = null): HfResult<HfRepoInfo> {
        val id = repoIdOrNull(repo) ?: return HfResult.Failure(HfCodes.INVALID_REPO, retryable = false)
        return execute(HfCallKind.METADATA, "$API_BASE/api/models/$id", authorization) { response ->
            parseDetail(id, response)
        }
    }

    /**
     * Answers the access question for one call without touching the transport. The probe and the
     * download run through the downloader's Range transport rather than [search]/[files]/[detail],
     * so they ask here first; a null reason means the call may go ahead.
     */
    suspend fun gate(kind: HfCallKind): HfResult<Unit> {
        val reason = denialReason(kind) ?: return HfResult.Success(Unit)
        return HfResult.Blocked(reason)
    }

    /** Download URL for [file]; the resolver answers 302 and the downloader follows it. No call is made here. */
    fun fileUrl(repo: String, file: String): String =
        "$API_BASE/${repo.trim()}/resolve/main/${encodePath(file.trim())}"

    /**
     * The URL the GGUF header probe requests with a Range header capped at 12 MB. Same resolver as
     * [fileUrl]; the probe itself belongs to the downloader because it shares its transport.
     */
    fun probeUrl(repo: String, file: String): String = fileUrl(repo, file)

    private suspend fun <T> execute(
        kind: HfCallKind,
        url: String,
        authorization: String?,
        parse: (HfHttpResponse) -> HfResult<T>,
    ): HfResult<T> {
        denialReason(kind)?.let { return HfResult.Blocked(it) }
        // The audit hook is evidence, not a dependency: a failed local write must not fail a search.
        runCatching { onRequest(url, kind) }
        val response = try {
            transport.get(url, authorization)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            return HfResult.Failure(HfCodes.NETWORK, retryable = true)
        }
        responseFailure(response)?.let { return it }
        return parse(response)
    }

    /** Null when [kind] may run now; otherwise the stable reason behind `blocked:<reason>`. */
    private suspend fun denialReason(kind: HfCallKind): String? {
        access.blockedReason()?.let { return it }
        if (kind.needsMeteredConfirmation && !access.isUnmetered() && !access.confirmMetered(kind)) {
            return HfCodes.METERED_DECLINED
        }
        return null
    }

    /** Maps a non-2xx answer to a failure; null means the body can be parsed. */
    private fun responseFailure(response: HfHttpResponse): HfResult.Failure? = when {
        response.status == STATUS_TOO_MANY_REQUESTS -> HfResult.Failure(
            code = HfCodes.RATE_LIMITED,
            retryable = true,
            retryAfterSeconds = retryAfterSeconds(response),
        )

        // The Hub answers a gated or private repo with 401/403: the fix is a token, not a retry.
        response.status == STATUS_UNAUTHORIZED || response.status == STATUS_FORBIDDEN ->
            HfResult.Failure(code = HfCodes.GATED, retryable = false)

        response.status !in 200..299 ->
            HfResult.Failure(code = HfCodes.HTTP_ERROR, retryable = response.status >= 500)

        else -> null
    }

    /** Retry-After is whole seconds on the Hub; absent or nonsense answers fall back to a minute. */
    private fun retryAfterSeconds(response: HfHttpResponse): Long =
        response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
            ?: DEFAULT_RETRY_AFTER_SECONDS

    /**
     * Runs [block] on the parsed body, turning any parse error into [HfCodes.MALFORMED]: the Hub can
     * change its JSON shape without notice, and a crash there would take the whole page down.
     */
    private fun <T> parse(body: String, block: (JsonElement) -> HfResult<T>): HfResult<T> =
        try {
            block(Json.parseToJsonElement(body))
        } catch (broken: Exception) {
            HfResult.Failure(HfCodes.MALFORMED, retryable = false)
        }

    private fun parseSearch(response: HfHttpResponse): HfResult<HfPage<HfModelSummary>> =
        parse(response.body) { root ->
            val array = root as? JsonArray
                ?: return@parse HfResult.Failure(HfCodes.MALFORMED, retryable = false)
            HfResult.Success(
                HfPage(
                    items = array.map { element -> summary(element) },
                    nextCursorUrl = nextCursorUrl(response.header("Link")),
                ),
            )
        }

    private fun parseFiles(response: HfHttpResponse): HfResult<List<HfRepoFile>> =
        parse(response.body) { root ->
            val array = root as? JsonArray
                ?: return@parse HfResult.Failure(HfCodes.MALFORMED, retryable = false)
            HfResult.Success(array.mapNotNull { element -> file(element) })
        }

    private fun parseDetail(repo: String, response: HfHttpResponse): HfResult<HfRepoInfo> =
        parse(response.body) { root ->
            val obj = root as? JsonObject
                ?: return@parse HfResult.Failure(HfCodes.MALFORMED, retryable = false)
            val gguf = obj.objectOrNull("gguf")
            HfResult.Success(
                HfRepoInfo(
                    id = obj.string("id")?.takeIf { it.isNotBlank() } ?: repo,
                    gated = obj["gated"].isGated(),
                    license = obj.objectOrNull("cardData")?.string("license")?.takeIf { it.isNotBlank() },
                    architecture = gguf?.string("architecture")?.takeIf { it.isNotBlank() },
                    contextTokens = gguf?.long("context_length")
                        ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                        ?.toInt(),
                    chatTemplate = gguf?.string("chat_template")?.takeIf { it.isNotBlank() },
                ),
            )
        }

    private fun summary(element: JsonElement): HfModelSummary {
        val obj = element as? JsonObject ?: throw IllegalArgumentException("summary is not an object")
        // A row without an id cannot be opened; failing the page beats silently showing fewer models.
        val id = obj.string("id")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("summary has no id")
        return HfModelSummary(
            id = id,
            downloads = obj.long("downloads") ?: 0L,
            likes = obj.long("likes") ?: 0L,
            gated = obj["gated"].isGated(),
            lastModified = obj.string("lastModified")?.takeIf { it.isNotBlank() },
        )
    }

    private fun file(element: JsonElement): HfRepoFile? {
        val obj = element as? JsonObject ?: return null
        if (obj.string("type") != "file") return null
        val path = obj.string("path")?.takeIf { it.isNotBlank() } ?: return null
        val lfs = obj.objectOrNull("lfs")
        return HfRepoFile(
            path = path,
            // size is exact for LFS and plain files; lfs.size is only a fallback when the Hub omits it.
            sizeBytes = obj.long("size") ?: lfs?.long("size") ?: 0L,
            // The top-level oid is a git blob id; only lfs.oid is the SHA-256 the downloader verifies.
            sha256 = lfs?.string("oid")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The Hub's `gated` is `false` or a string (`"auto"`/`"manual"`). Any non-false answer means a
     * token and an accepted licence are needed, so it has to read as gated.
     */
    private fun JsonElement?.isGated(): Boolean {
        val primitive = this as? JsonPrimitive ?: return false
        if (primitive.isString) {
            return primitive.content.isNotBlank() &&
                !primitive.content.equals("false", ignoreCase = true)
        }
        return primitive.booleanOrNull == true
    }

    /** The Hub sends `Link: <next>; rel="next"` with an absolute cursor URL; absent means the last page. */
    private fun nextCursorUrl(link: String?): String? =
        link?.let { NEXT_LINK.find(it)?.groupValues?.get(1) }

    /** A string member, or null when it is absent, null or not a JSON string. */
    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A whole-number member; the Hub sends sizes, counts and lengths as JSON numbers. */
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    /** A nested object member; absent, null and wrong-typed values all read as null. */
    private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

    /**
     * The cursor URL when it is safe to fetch: an absolute https URL on huggingface.co, with no
     * embedded credentials and no alternate port. Anything else is [HfCodes.MALFORMED] and never
     * gets a request — a foreign or plain-http cursor would leak the token and pollute the audit.
     */
    private fun cursorPageUrlOrNull(cursorUrl: String): String? {
        val value = cursorUrl.trim()
        if (value.isEmpty()) return null
        val uri = try {
            URI(value)
        } catch (invalid: URISyntaxException) {
            return null
        }
        val safe = uri.isAbsolute &&
            uri.scheme == "https" &&
            API_HOST.equals(uri.host, ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1
        return if (safe) value else null
    }

    /** A repo id is `owner/name`: no whitespace and at least one slash, or nothing can be targeted. */
    private fun repoIdOrNull(repo: String): String? {
        val id = repo.trim()
        return id.takeIf { '/' in it && it.none { character -> character.isWhitespace() } }
    }

    /** Encodes one path segment; spaces become %20, not the + a query string would use. */
    private fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private companion object {
        const val API_HOST = "huggingface.co"
        const val API_BASE = "https://$API_HOST"
        const val DEFAULT_LIMIT = 20
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 100
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_FORBIDDEN = 403
        const val STATUS_TOO_MANY_REQUESTS = 429
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        val NEXT_LINK = Regex("<([^>]+)>\\s*;\\s*rel=\"?next\"?")
    }
}

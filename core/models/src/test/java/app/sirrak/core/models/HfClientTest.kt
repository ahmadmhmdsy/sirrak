package app.sirrak.core.models

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P3 acquisition seam is tested entirely through fakes: no test opens a socket, so the
 * anonymous API budget (500 calls per five minutes) is never spent and every policy branch runs
 * deterministically. The fake transport records the exact URL, token and audit kind it was handed.
 */
class HfClientTest {

    // ------------------------------------------------------------------ search

    @Test
    fun `search parses the summary fields and encodes the query`() = runBlocking {
        val transport = FakeTransport(
            ok(
                """
                [
                  {"id":"org/tiny-GGUF","downloads":1200,"likes":34,"gated":false,"lastModified":"2026-11-01T10:00:00.000Z"},
                  {"id":"org/gated-GGUF","downloads":5,"likes":0,"gated":"manual"}
                ]
                """.trimIndent(),
            ),
        )
        val requests = mutableListOf<Pair<String, HfCallKind>>()
        val client = client(transport, onRequest = { url, kind -> requests += url to kind })

        val page = client.search(arabicQuery).requireValue()

        assertEquals(2, page.items.size)
        val tiny = page.items[0]
        assertEquals("org/tiny-GGUF", tiny.id)
        assertEquals(1200L, tiny.downloads)
        assertEquals(34L, tiny.likes)
        assertFalse(tiny.gated)
        assertEquals("2026-11-01T10:00:00.000Z", tiny.lastModified)
        assertTrue(page.items[1].gated)
        assertNull(page.items[1].lastModified)
        assertNull(page.nextCursorUrl)

        val url = transport.urls.single()
        assertTrue("the Arabic query must be percent-encoded, got $url", url.contains("search=$encodedArabic"))
        assertTrue("the GGUF filter is part of the query, got $url", url.contains("filter=gguf"))
        assertTrue("the default page size is 20, got $url", url.contains("limit=20"))
        assertEquals(1, requests.size)
        assertEquals(HfCallKind.SEARCH, requests.single().second)
    }

    @Test
    fun `search keeps the next cursor from the Link header`() = runBlocking {
        val next = "https://huggingface.co/api/models?search=qwen&limit=20&cursor=abc123"
        val link = "<$next>; rel=\"next\", <https://huggingface.co/api/models?search=qwen&limit=20>; rel=\"prev\""
        val transport = FakeTransport(ok("[]", mapOf("Link" to link)))

        val page = client(transport).search("qwen").requireValue()

        assertEquals(next, page.nextCursorUrl)
    }

    @Test
    fun `search clamps the page size to the hub range`() = runBlocking {
        val transport = FakeTransport(ok("[]"))

        client(transport).search("qwen", limit = 500)
        assertTrue(transport.urls.single().endsWith("limit=100"))

        client(transport).search("qwen", limit = 0)
        assertTrue(transport.urls.last().endsWith("limit=1"))
    }

    @Test
    fun `a blank query fails without a call`() = runBlocking {
        val transport = FakeTransport(ok("[]"))
        val requests = mutableListOf<Pair<String, HfCallKind>>()

        val failure = client(transport, onRequest = { url, kind -> requests += url to kind })
            .search("   ")
            .requireFailure()

        assertEquals(HfCodes.BLANK_QUERY, failure.code)
        assertFalse(failure.retryable)
        assertTrue("a blank query must not open the transport", transport.urls.isEmpty())
        assertTrue("a blank query must not reach the audit hook", requests.isEmpty())
    }

    // ------------------------------------------------------------------ cursor pagination

    @Test
    fun `page fetches the cursor url and keeps the following cursor`() = runBlocking {
        val cursor = "https://huggingface.co/api/models?search=qwen&limit=20&cursor=abc123"
        val next = "https://huggingface.co/api/models?search=qwen&limit=20&cursor=def456"
        val transport = FakeTransport(
            ok(
                """[{"id":"org/second-GGUF","downloads":9,"likes":1,"gated":false}]""",
                mapOf("Link" to "<$next>; rel=\"next\""),
            ),
        )
        val requests = mutableListOf<Pair<String, HfCallKind>>()

        val page = client(transport, onRequest = { url, kind -> requests += url to kind })
            .page(cursor, authorization = "hf_test_token")
            .requireValue()

        assertEquals("org/second-GGUF", page.items.single().id)
        assertEquals(next, page.nextCursorUrl)
        assertEquals("the cursor url is used verbatim", cursor, transport.urls.single())
        assertEquals("hf_test_token", transport.authorizations.single())
        assertEquals(listOf(cursor to HfCallKind.SEARCH), requests)
    }

    @Test
    fun `page rejects a cursor that is not a huggingface https url`() = runBlocking {
        val transport = FakeTransport(ok("[]"))
        val requests = mutableListOf<Pair<String, HfCallKind>>()
        val client = client(transport, onRequest = { url, kind -> requests += url to kind })

        val rejected = listOf(
            "http://huggingface.co/api/models?search=qwen",
            "https://evil.example/api/models?search=qwen",
            "https://huggingface.co.evil.example/api/models?search=qwen",
            "https://hf_token@huggingface.co/api/models?search=qwen",
            "https://huggingface.co:8443/api/models?search=qwen",
            "/api/models?search=qwen",
        )
        for (cursor in rejected) {
            val failure = client.page(cursor).requireFailure()
            assertEquals("cursor must be rejected: $cursor", HfCodes.MALFORMED, failure.code)
            assertFalse(failure.retryable)
        }

        assertTrue("no rejected cursor may reach the transport", transport.urls.isEmpty())
        assertTrue("no rejected cursor may reach the audit hook", requests.isEmpty())
    }

    @Test
    fun `page rejects a blank cursor without a call`() = runBlocking {
        val transport = FakeTransport(ok("[]"))
        val requests = mutableListOf<Pair<String, HfCallKind>>()

        val failure = client(transport, onRequest = { url, kind -> requests += url to kind })
            .page("   ")
            .requireFailure()

        assertEquals(HfCodes.MALFORMED, failure.code)
        assertFalse(failure.retryable)
        assertTrue(transport.urls.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `page never prompts on a metered link`() = runBlocking {
        val access = FakeAccess(unmetered = false, meteredAnswer = false)

        val page = client(FakeTransport(ok("[]")), access)
            .page("https://huggingface.co/api/models?search=qwen&cursor=abc")
            .requireValue()

        assertTrue(page.items.isEmpty())
        assertTrue("paging is a search call and stays unprompted", access.confirmations.isEmpty())
    }

    @Test
    fun `page turns a malformed row into malformed`() = runBlocking {
        val transport = FakeTransport(ok("""[{"downloads":1,"likes":0}]"""))

        val failure = client(transport)
            .page("https://huggingface.co/api/models?search=qwen&cursor=abc")
            .requireFailure()

        assertEquals(HfCodes.MALFORMED, failure.code)
        assertEquals("the call was made, only the row was unusable", 1, transport.urls.size)
    }

    // ------------------------------------------------------------------ file tree

    @Test
    fun `files parse the exact size and the LFS sha256 and drop directories`() = runBlocking {
        val sha = "a3".repeat(32)
        val transport = FakeTransport(
            ok(
                """
                [
                  {"type":"file","path":"tiny-q4_k_m.gguf","size":491400032,"lfs":{"oid":"$sha","size":491400032}},
                  {"type":"directory","path":"sub"},
                  {"type":"file","path":"only-lfs.gguf","lfs":{"oid":"$sha","size":99}},
                  {"type":"file","path":"git-oid.gguf","oid":"0123456789abcdef","size":7},
                  {"type":"file","path":"tokenizer.json","size":1234}
                ]
                """.trimIndent(),
            ),
        )

        val files = client(transport).files("org/tiny-GGUF").requireValue()

        assertEquals(4, files.size)
        assertEquals("tiny-q4_k_m.gguf", files[0].path)
        assertEquals(491400032L, files[0].sizeBytes)
        assertEquals(sha, files[0].sha256)
        assertEquals(99L, files[1].sizeBytes)
        assertEquals("an lfs oid is the digest", sha, files[1].sha256)
        assertEquals(7L, files[2].sizeBytes)
        assertNull("the top-level oid is a git blob id, not a sha256", files[2].sha256)
        assertEquals(1234L, files[3].sizeBytes)
        assertNull(files[3].sha256)
        assertEquals(
            "https://huggingface.co/api/models/org/tiny-GGUF/tree/main?recursive=true",
            transport.urls.single(),
        )
    }

    @Test
    fun `an invalid repo fails without a call`() = runBlocking {
        val transport = FakeTransport(ok("[]"))

        val failure = client(transport).files("not-a-repo").requireFailure()

        assertEquals(HfCodes.INVALID_REPO, failure.code)
        assertFalse(failure.retryable)
        assertTrue(transport.urls.isEmpty())
    }

    // ------------------------------------------------------------------ details

    @Test
    fun `detail parses a manual gate and the gguf block`() = runBlocking {
        val transport = FakeTransport(
            ok(
                """
                {
                  "id":"org/qwen-GGUF",
                  "gated":"manual",
                  "cardData":{"license":"apache-2.0"},
                  "gguf":{"architecture":"qwen2","context_length":32768,"chat_template":"<|im_start|>","total":494000000}
                }
                """.trimIndent(),
            ),
        )

        val info = client(transport).detail("org/qwen-GGUF", authorization = "hf_test_token").requireValue()

        assertEquals("org/qwen-GGUF", info.id)
        assertTrue(info.gated)
        assertEquals("apache-2.0", info.license)
        assertEquals("qwen2", info.architecture)
        assertEquals(32768, info.contextTokens)
        assertEquals("<|im_start|>", info.chatTemplate)
        assertEquals("hf_test_token", transport.authorizations.single())
    }

    @Test
    fun `detail leaves optional fields null when the repo does not publish them`() = runBlocking {
        val transport = FakeTransport(ok("""{"id":"org/plain-GGUF","gated":false}"""))

        val info = client(transport).detail("org/plain-GGUF").requireValue()

        assertEquals("org/plain-GGUF", info.id)
        assertFalse(info.gated)
        assertNull(info.license)
        assertNull(info.architecture)
        assertNull(info.contextTokens)
        assertNull(info.chatTemplate)
    }

    // ------------------------------------------------------------------ access policy

    @Test
    fun `an offline block returns before any transport call and records nothing`() = runBlocking {
        val transport = FakeTransport(ok("[]"))
        val requests = mutableListOf<Pair<String, HfCallKind>>()
        val client = client(
            transport,
            access = FakeAccess(blocked = "offline_mode"),
            onRequest = { url, kind -> requests += url to kind },
        )

        val blocked = client.search("qwen").requireBlocked()

        assertEquals("offline_mode", blocked.reason)
        assertEquals("blocked:offline_mode", blocked.code)
        assertTrue("the offline switch must beat the transport", transport.urls.isEmpty())
        assertTrue("a blocked call must not reach the audit hook", requests.isEmpty())
    }

    @Test
    fun `the offline block wins over the metered prompt`() = runBlocking {
        val access = FakeAccess(blocked = "offline_mode", unmetered = false, meteredAnswer = false)

        val blocked = client(FakeTransport(ok("{}")), access).gate(HfCallKind.PROBE).requireBlocked()

        assertEquals("offline_mode", blocked.reason)
        assertTrue("no prompt once the hard switch is on", access.confirmations.isEmpty())
    }

    @Test
    fun `a declined metered probe is blocked without a call`() = runBlocking {
        val transport = FakeTransport(ok("{}"))
        val access = FakeAccess(unmetered = false, meteredAnswer = false)

        val blocked = client(transport, access).gate(HfCallKind.PROBE).requireBlocked()

        assertEquals(HfCodes.METERED_DECLINED, blocked.reason)
        assertEquals("blocked:metered_declined", blocked.code)
        assertEquals(listOf(HfCallKind.PROBE), access.confirmations)
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun `a metered probe runs only after an explicit yes`() = runBlocking {
        val access = FakeAccess(unmetered = false, meteredAnswer = true)

        val decision = client(FakeTransport(ok("{}")), access).gate(HfCallKind.PROBE)

        assertTrue(decision is HfResult.Success)
        assertEquals(listOf(HfCallKind.PROBE), access.confirmations)
    }

    @Test
    fun `an unmetered probe never asks for confirmation`() = runBlocking {
        val access = FakeAccess(unmetered = true, meteredAnswer = false)

        val decision = client(FakeTransport(ok("{}")), access).gate(HfCallKind.PROBE)

        assertTrue(decision is HfResult.Success)
        assertTrue(access.confirmations.isEmpty())
    }

    @Test
    fun `a metered search runs without asking for confirmation`() = runBlocking {
        val transport = FakeTransport(ok("[]"))
        val access = FakeAccess(unmetered = false, meteredAnswer = false)

        client(transport, access).search("qwen").requireValue()

        assertTrue("search is cheap and never prompts", access.confirmations.isEmpty())
        assertEquals(1, transport.urls.size)
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a 429 is retryable and carries the Retry-After wait`() = runBlocking {
        val transport = FakeTransport(
            HfHttpResponse(status = 429, body = "", headers = mapOf("Retry-After" to "17")),
        )

        val failure = client(transport).search("qwen").requireFailure()

        assertEquals(HfCodes.RATE_LIMITED, failure.code)
        assertTrue(failure.retryable)
        assertEquals(17L, failure.retryAfterSeconds)
    }

    @Test
    fun `a 429 without Retry-After defaults to a minute`() = runBlocking {
        val transport = FakeTransport(HfHttpResponse(status = 429, body = ""))

        val failure = client(transport).search("qwen").requireFailure()

        assertEquals(HfCodes.RATE_LIMITED, failure.code)
        assertEquals(60L, failure.retryAfterSeconds)
    }

    @Test
    fun `a malformed body fails instead of throwing`() = runBlocking {
        val failure = client(FakeTransport(ok("{not json"))).search("qwen").requireFailure()

        assertEquals(HfCodes.MALFORMED, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun `a body of the wrong shape is malformed`() = runBlocking {
        val failure = client(FakeTransport(ok("""{"unexpected":true}"""))).search("qwen").requireFailure()

        assertEquals(HfCodes.MALFORMED, failure.code)
    }

    @Test
    fun `a non success status becomes an http error`() = runBlocking {
        val failure = client(FakeTransport(HfHttpResponse(status = 503, body = "busy")))
            .detail("org/repo-GGUF")
            .requireFailure()

        assertEquals(HfCodes.HTTP_ERROR, failure.code)
        assertTrue("a 5xx may succeed on retry", failure.retryable)
    }

    @Test
    fun `a 401 on a gated repo is not retryable and needs a token`() = runBlocking {
        val failure = client(FakeTransport(HfHttpResponse(status = 401, body = """{"error":"GatedRepo"}""")))
            .files("org/gated-GGUF")
            .requireFailure()

        assertEquals(HfCodes.GATED, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun `a transport failure becomes a retryable network failure`() = runBlocking {
        val failure = client(FakeTransport(error = IOException("offline"))).search("qwen").requireFailure()

        assertEquals(HfCodes.NETWORK, failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `cancellation is rethrown and not turned into a network failure`() = runBlocking {
        val thrown = runCatching {
            client(FakeTransport(error = CancellationException("stopped"))).search("qwen")
        }.exceptionOrNull()

        assertTrue("the cancellation must propagate, got $thrown", thrown is CancellationException)
    }

    // ------------------------------------------------------------------ resolver URLs

    @Test
    fun `file and probe urls use the public resolver without a call`() {
        val transport = FakeTransport(ok("[]"))
        val client = client(transport)

        assertEquals(
            "https://huggingface.co/org/repo-GGUF/resolve/main/model-q4_k_m.gguf",
            client.fileUrl("org/repo-GGUF", "model-q4_k_m.gguf"),
        )
        assertEquals(
            client.fileUrl("org/repo-GGUF", "model-q4_k_m.gguf"),
            client.probeUrl("org/repo-GGUF", "model-q4_k_m.gguf"),
        )
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun `a file name with Arabic letters is percent encoded in the resolver url`() {
        val url = client(FakeTransport(ok("[]"))).fileUrl("org/repo-GGUF", "نموذج.gguf")

        assertTrue("expected a percent-encoded path, got $url", url.endsWith("/%D9%86%D9%85%D9%88%D8%B0%D8%AC.gguf"))
    }

    // ------------------------------------------------------------------ fakes

    private class FakeTransport(
        private val response: HfHttpResponse = HfHttpResponse(status = 200, body = "[]"),
        private val error: Throwable? = null,
    ) : HfTransport {
        val urls = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()

        override suspend fun get(url: String, authorization: String?): HfHttpResponse {
            urls += url
            authorizations += authorization
            error?.let { throw it }
            return response
        }
    }

    private class FakeAccess(
        private val blocked: String? = null,
        private val unmetered: Boolean = true,
        private val meteredAnswer: Boolean = true,
    ) : HfAccessPolicy {
        val confirmations = mutableListOf<HfCallKind>()

        override fun blockedReason(): String? = blocked

        override fun isUnmetered(): Boolean = unmetered

        override suspend fun confirmMetered(kind: HfCallKind): Boolean {
            confirmations += kind
            return meteredAnswer
        }
    }

    private fun client(
        transport: HfTransport,
        access: HfAccessPolicy = FakeAccess(),
        onRequest: (url: String, kind: HfCallKind) -> Unit = { _, _ -> },
    ) = HfClient(transport, access, onRequest)

    private fun ok(body: String, headers: Map<String, String> = emptyMap()) =
        HfHttpResponse(status = 200, body = body, headers = headers)

    private fun <T> HfResult<T>.requireValue(): T = when (this) {
        is HfResult.Success -> value
        is HfResult.Blocked -> throw AssertionError("expected Success, got Blocked($reason)")
        is HfResult.Failure -> throw AssertionError("expected Success, got Failure($code)")
    }

    private fun <T> HfResult<T>.requireFailure(): HfResult.Failure = when (this) {
        is HfResult.Failure -> this
        is HfResult.Success -> throw AssertionError("expected Failure, got Success")
        is HfResult.Blocked -> throw AssertionError("expected Failure, got Blocked($reason)")
    }

    private fun <T> HfResult<T>.requireBlocked(): HfResult.Blocked = when (this) {
        is HfResult.Blocked -> this
        is HfResult.Success -> throw AssertionError("expected Blocked, got Success")
        is HfResult.Failure -> throw AssertionError("expected Blocked, got Failure($code)")
    }

    private companion object {
        val arabicQuery = "نماذج gguf"
        val encodedArabic = "%D9%86%D9%85%D8%A7%D8%B0%D8%AC+gguf"
    }
}

package app.sirrak.core.models

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The capped GGUF probe runs entirely against fakes: no test opens a socket, so the anonymous
 * Hugging Face budget is never spent and every branch (redirect, cap, status mapping, cancellation)
 * runs deterministically. The fake records the exact URL, Range header and token it was handed.
 */
class GgufProbeTest {

    private class Recorded(val url: String, val range: String, val authorization: String?)

    private class FakeTransport(
        private val answers: MutableList<(String) -> GgufProbeResponse> = mutableListOf(),
    ) : GgufProbeTransport {
        val calls = mutableListOf<Recorded>()
        private var index = 0

        fun then(answer: GgufProbeResponse) = apply { answers += { answer } }
        fun then(answer: (String) -> GgufProbeResponse) = apply { answers += answer }

        override suspend fun get(url: String, rangeHeader: String, authorization: String?): GgufProbeResponse {
            calls += Recorded(url, rangeHeader, authorization)
            val next = answers.getOrNull(index) ?: error("unexpected call #$index to $url")
            index++
            return next(url)
        }
    }

    private fun ok(bytes: ByteArray, status: Int = 206): GgufProbeResponse =
        GgufProbeResponse(status = status, bytes = bytes)

    private fun redirect(location: String?): GgufProbeResponse =
        GgufProbeResponse(status = 302, location = location)

    // ------------------------------------------------------------------ happy paths

    @Test
    fun `a 206 answer is parsed and reports the bytes it read`() = runBlocking {
        val bytes = ggufHeader(arch = "qwen2", layers = 24, heads = 14, kvHeads = 2, embedding = 896)
        val transport = FakeTransport().then(ok(bytes))

        val result = probe(transport).probe(probeUrl)

        val success = result.requireSuccess()
        assertEquals("qwen2", success.metadata.architecture?.name)
        assertEquals(24, success.metadata.architecture?.layerCount)
        assertEquals(bytes.size, success.bytesRead)
        assertTrue(success.ranged)
        assertEquals(probeUrl, success.finalUrl)
        assertEquals(GgufProbe.rangeHeader(), transport.calls.single().range)
        assertNull(transport.calls.single().authorization)
    }

    @Test
    fun `a 200 answer that ignored the range is accepted`() = runBlocking {
        val bytes = ggufHeader(arch = "llama", layers = 16)
        val transport = FakeTransport().then(ok(bytes, status = 200))

        val success = probe(transport).probe(probeUrl).requireSuccess()

        assertEquals("llama", success.metadata.architecture?.name)
        assertFalse("a 200 answer is not a ranged answer", success.ranged)
    }

    @Test
    fun `a truncated header still reports the fields it read`() = runBlocking {
        val full = ggufHeader(arch = "lfm2", layers = 6, extra = listOf("tokenizer.chat_template" to GgufValue.Text("x".repeat(4096))))
        val truncated = full.copyOf(full.size - 300)
        val transport = FakeTransport().then(ok(truncated))

        val success = probe(transport).probe(probeUrl).requireSuccess()

        assertFalse(success.metadata.complete)
        assertEquals("lfm2", success.metadata.architecture?.name)
        assertEquals(6, success.metadata.architecture?.layerCount)
    }

    // ------------------------------------------------------------------ redirects

    @Test
    fun `a resolver redirect is followed to the cdn with the same range header`() = runBlocking {
        val cdn = "https://cdn-lfs.huggingface.co/repos/ab/cd/file.gguf?Signature=abc"
        val bytes = ggufHeader(arch = "gemma3", layers = 26)
        val transport = FakeTransport()
            .then(redirect(cdn))
            .then(ok(bytes))

        val success = probe(transport).probe(probeUrl).requireSuccess()

        assertEquals(cdn, success.finalUrl)
        assertEquals(listOf(probeUrl, cdn), transport.calls.map { it.url })
        val range = GgufProbe.rangeHeader()
        assertEquals("every hop must request the same range", listOf(range, range), transport.calls.map { it.range })
        assertEquals("gemma3", success.metadata.architecture?.name)
    }

    @Test
    fun `a relative redirect resolves against the current url`() = runBlocking {
        val transport = FakeTransport()
            .then(redirect("/cdn/file.gguf?token=1"))
            .then(ok(ggufHeader(arch = "llama")))

        val success = probe(transport).probe(probeUrl).requireSuccess()

        assertEquals("https://huggingface.co/cdn/file.gguf?token=1", success.finalUrl)
    }

    @Test
    fun `the token is only sent to huggingface and never to the cdn`() = runBlocking {
        val cdn = "https://cdn-lfs.huggingface.co/repos/ab/cd/file.gguf"
        val transport = FakeTransport()
            .then(redirect(cdn))
            .then(ok(ggufHeader(arch = "llama")))

        probe(transport).probe(probeUrl, authorization = "hf_secret")

        assertEquals(listOf("hf_secret", null), transport.calls.map { it.authorization })
    }

    @Test
    fun `a redirect without a location is malformed`() = runBlocking {
        val transport = FakeTransport().then(redirect(null))

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals(GgufProbeCodes.MALFORMED, failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `a redirect loop stops after the hop budget`() = runBlocking {
        val transport = FakeTransport().apply {
            repeat(8) { then(redirect("https://huggingface.co/loop")) }
        }

        val failure = probe(transport, maxRedirects = 3).probe(probeUrl).requireFailure()

        assertEquals(GgufProbeCodes.MALFORMED, failure.code)
        assertEquals("1 initial call + 3 redirects", 4, transport.calls.size)
    }

    @Test
    fun `a redirect to plain http is refused without a second call`() = runBlocking {
        val transport = FakeTransport().then(redirect("http://cdn.example/file.gguf"))

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals(GgufProbeCodes.MALFORMED, failure.code)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `a plain http initial url never opens the transport`() = runBlocking {
        val transport = FakeTransport()

        val failure = probe(transport)
            .probe("http://huggingface.co/org/repo/resolve/main/file.gguf")
            .requireFailure()

        assertEquals(GgufProbeCodes.MALFORMED, failure.code)
        assertTrue(transport.calls.isEmpty())
    }

    // ------------------------------------------------------------------ status mapping

    @Test
    fun `a 416 answer is range_unsupported and not retryable`() = runBlocking {
        val transport = FakeTransport().then(GgufProbeResponse(status = 416))

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals(GgufProbeCodes.RANGE_UNSUPPORTED, failure.code)
        assertFalse(failure.retryable)
        assertEquals(416, failure.httpStatus)
    }

    @Test
    fun `a gated 403 maps to http_403 without a retry`() = runBlocking {
        val transport = FakeTransport().then(GgufProbeResponse(status = 403))

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals("http_403", failure.code)
        assertFalse(failure.retryable)
        assertEquals(403, failure.httpStatus)
    }

    @Test
    fun `a 500 maps to http_500 and is retryable`() = runBlocking {
        val transport = FakeTransport().then(GgufProbeResponse(status = 500))

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals("http_500", failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a socket failure is network and retryable`() = runBlocking {
        val transport = FakeTransport().then { throw IOException("offline") }

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals(GgufProbeCodes.NETWORK, failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a body that is not GGUF is malformed`() = runBlocking {
        val transport = FakeTransport().then(ok("this is not a model".toByteArray()))

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals(GgufProbeCodes.MALFORMED, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun `an empty body is malformed`() = runBlocking {
        val transport = FakeTransport().then(ok(ByteArray(0)))

        val failure = probe(transport).probe(probeUrl).requireFailure()

        assertEquals(GgufProbeCodes.MALFORMED, failure.code)
    }

    // ------------------------------------------------------------------ cap and audit

    @Test
    fun `the parsed body is capped at twelve megabytes`() = runBlocking {
        val header = ggufHeader(arch = "qwen2", layers = 28)
        val oversized = header + ByteArray(GgufProbe.MAX_BYTES + 1_000_000)
        val transport = FakeTransport().then(ok(oversized))

        val success = probe(transport).probe(probeUrl).requireSuccess()

        assertEquals(GgufProbe.MAX_BYTES, success.bytesRead)
        assertEquals("qwen2", success.metadata.architecture?.name)
    }

    @Test
    fun `the request asks for exactly the cap`() = runBlocking {
        val transport = FakeTransport().then(ok(ggufHeader(arch = "llama")))

        probe(transport).probe(probeUrl)

        assertEquals("bytes=0-" + (GgufProbe.MAX_BYTES - 1), transport.calls.single().range)
    }

    @Test
    fun `the audit hook sees every hop before it is fetched`() = runBlocking {
        val cdn = "https://cdn-lfs.huggingface.co/repos/ab/cd/file.gguf"
        val seen = mutableListOf<String>()
        val transport = FakeTransport()
            .then(redirect(cdn))
            .then(ok(ggufHeader(arch = "llama")))

        GgufProbe(transport = transport, onRequest = { url -> seen += url }).probe(probeUrl)

        assertEquals(listOf(probeUrl, cdn), seen)
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun `a cancelled coroutine is rethrown not mapped to a failure`() = runBlocking {
        val transport = FakeTransport().then { throw CancellationException("cancelled") }

        try {
            probe(transport).probe(probeUrl)
            fail("cancellation must not be swallowed")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled", cancelled.message)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun probe(transport: GgufProbeTransport, maxRedirects: Int = 5): GgufProbe =
        GgufProbe(transport = transport, maxRedirects = maxRedirects)

    private fun GgufProbeResult.requireSuccess(): GgufProbeResult.Success = when (this) {
        is GgufProbeResult.Success -> this
        is GgufProbeResult.Failure -> error("expected success, got failure ${code}")
    }

    private fun GgufProbeResult.requireFailure(): GgufProbeResult.Failure = when (this) {
        is GgufProbeResult.Failure -> this
        is GgufProbeResult.Success -> error("expected failure, got success")
    }

    private fun ggufHeader(
        arch: String,
        layers: Int = 16,
        heads: Int = 32,
        kvHeads: Int = 8,
        embedding: Int = 2048,
        extra: List<Pair<String, GgufValue>> = emptyList(),
    ): ByteArray = GgufBuilder.header(
        version = 3,
        kvs = listOf(
            "general.architecture" to GgufValue.Text(arch),
            "$arch.block_count" to GgufValue.UInt32(layers),
            "$arch.attention.head_count" to GgufValue.UInt32(heads),
            "$arch.attention.head_count_kv" to GgufValue.UInt32(kvHeads),
            "$arch.embedding_length" to GgufValue.UInt32(embedding),
            "$arch.context_length" to GgufValue.UInt32(4096),
        ) + extra,
    )

    private companion object {
        const val probeUrl = "https://huggingface.co/org/model-GGUF/resolve/main/model-q4_k_m.gguf"
    }

    /** Builds GGUF headers for tests: the real format is little-endian and length-prefixed. */
    private sealed interface GgufValue {
        data class UInt32(val value: Int) : GgufValue
        data class Text(val value: String) : GgufValue
    }

    private object GgufBuilder {
        fun header(version: Int = 3, kvs: List<Pair<String, GgufValue>>, tensorCount: Long = 100L): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(le32(0x46554747))
            out.write(le32(version))
            out.write(le64(tensorCount))
            out.write(le64(kvs.size.toLong()))
            kvs.forEach { (key, value) ->
                writeString(out, key)
                when (value) {
                    is GgufValue.UInt32 -> {
                        out.write(le32(4))
                        out.write(le32(value.value))
                    }
                    is GgufValue.Text -> {
                        out.write(le32(8))
                        writeString(out, value.value)
                    }
                }
            }
            return out.toByteArray()
        }

        private fun writeString(out: ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(le64(bytes.size.toLong()))
            out.write(bytes)
        }

        private fun le32(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        private fun le64(value: Long): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }
}

package app.sirrak.core.models

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufMetadataTest {

    @Test
    fun `reads a full v3 header including the chat template`() {
        val bytes = GgufBuilder.header(
            version = 3,
            kvs = listOf(
                "general.architecture" to GgufBuilder.string("llama"),
                "llama.block_count" to GgufBuilder.uint32(16),
                "llama.attention.head_count" to GgufBuilder.uint32(32),
                "llama.attention.head_count_kv" to GgufBuilder.uint32(8),
                "llama.embedding_length" to GgufBuilder.uint32(2048),
                "llama.context_length" to GgufBuilder.uint32(8192),
                "tokenizer.chat_template" to GgufBuilder.string("{{ messages }}"),
            ),
        )

        val parsed = GgufMetadataReader.read(bytes)

        assertTrue(parsed.magicOk)
        assertEquals(3, parsed.version)
        assertTrue(parsed.complete)
        assertNull(parsed.problem)
        assertNotNull(parsed.architecture)
        val arch = parsed.architecture!!
        assertEquals("llama", arch.name)
        assertEquals(16, arch.layerCount)
        assertEquals(32, arch.headCount)
        assertEquals(listOf(8), arch.kvHeadCounts)
        assertEquals(16L * 8, arch.kvLayerHeads)
        assertEquals(2048, arch.embeddingLength)
        assertEquals(8192, arch.contextTokens)
        assertEquals("{{ messages }}", arch.chatTemplate)
    }

    @Test
    fun `accepts a per-layer head_count_kv array`() {
        val kvHeads = List(6) { index -> if (index < 2) 4 else 8 }
        val bytes = GgufBuilder.header(
            kvs = listOf(
                "general.architecture" to GgufBuilder.string("lfm2"),
                "lfm2.block_count" to GgufBuilder.uint32(kvHeads.size),
                "lfm2.attention.head_count" to GgufBuilder.uint32(16),
                "lfm2.attention.head_count_kv" to GgufBuilder.uint32Array(kvHeads),
                "lfm2.embedding_length" to GgufBuilder.uint32(1024),
            ),
        )

        val arch = GgufMetadataReader.read(bytes).architecture!!

        assertEquals(kvHeads, arch.kvHeadCounts)
        assertNull(arch.uniformKvHeadCount)
        assertEquals(kvHeads.sumOf { it.toLong() }, arch.kvLayerHeads)
    }

    @Test
    fun `skips float and int64 scalars without losing header alignment`() {
        val bytes = GgufBuilder.header(
            kvs = listOf(
                "general.name" to GgufBuilder.float32(1.5f),
                "general.size_label" to GgufBuilder.uint64(7L),
                "general.architecture" to GgufBuilder.string("qwen2"),
                "qwen2.block_count" to GgufBuilder.uint32(24),
            ),
        )

        val parsed = GgufMetadataReader.read(bytes)

        assertTrue(parsed.complete)
        assertEquals("qwen2", parsed.architecture?.name)
        assertEquals(24, parsed.architecture?.layerCount)
    }

    @Test
    fun `a truncated prefix returns the fields it read instead of throwing`() {
        val full = GgufBuilder.header(
            kvs = listOf(
                "general.architecture" to GgufBuilder.string("llama"),
                "llama.block_count" to GgufBuilder.uint32(16),
                "tokenizer.chat_template" to GgufBuilder.string("x".repeat(4096)),
            ),
        )
        val truncated = full.copyOf(full.size - 200)

        val parsed = GgufMetadataReader.read(truncated)

        assertTrue(parsed.magicOk)
        assertFalse(parsed.complete)
        assertNotNull(parsed.architecture)
        assertEquals("llama", parsed.architecture?.name)
        assertEquals(16, parsed.architecture?.layerCount)
        assertEquals("truncated_header", parsed.problem)
    }

    @Test
    fun `rejects a file that is not GGUF`() {
        val parsed = GgufMetadataReader.read("not a model".toByteArray())

        assertFalse(parsed.magicOk)
        assertFalse(parsed.usable)
        assertEquals("bad_magic", parsed.problem)
    }

    @Test
    fun `an empty buffer is a value not an exception`() {
        val parsed = GgufMetadataReader.read(ByteArray(0))

        assertFalse(parsed.magicOk)
        assertEquals("too_short", parsed.problem)
    }

    @Test
    fun `accepts GGUF v2`() {
        val bytes = GgufBuilder.header(
            version = 2,
            kvs = listOf("general.architecture" to GgufBuilder.string("qwen2")),
        )

        val parsed = GgufMetadataReader.read(bytes)

        assertEquals(2, parsed.version)
        assertEquals("qwen2", parsed.architecture?.name)
    }

    /** Builds GGUF headers for tests: the real format is little-endian and length-prefixed. */
    private object GgufBuilder {
        sealed interface Value
        data class UInt32(val value: Int) : Value
        data class UInt64(val value: Long) : Value
        data class Float32(val value: Float) : Value
        data class Text(val value: String) : Value
        data class UInt32Array(val values: List<Int>) : Value

        fun uint32(value: Int): Value = UInt32(value)
        fun uint64(value: Long): Value = UInt64(value)
        fun float32(value: Float): Value = Float32(value)
        fun string(value: String): Value = Text(value)
        fun uint32Array(values: List<Int>): Value = UInt32Array(values)

        fun header(version: Int = 3, kvs: List<Pair<String, Value>>, tensorCount: Long = 100L): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46554747).array())
            out.write(le32(version))
            out.write(le64(tensorCount))
            out.write(le64(kvs.size.toLong()))
            kvs.forEach { (key, value) ->
                writeString(out, key)
                when (value) {
                    is UInt32 -> {
                        out.write(le32(4))
                        out.write(le32(value.value))
                    }
                    is UInt64 -> {
                        out.write(le32(10))
                        out.write(le64(value.value))
                    }
                    is Float32 -> {
                        out.write(le32(6))
                        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value.value).array())
                    }
                    is Text -> {
                        out.write(le32(8))
                        writeString(out, value.value)
                    }
                    is UInt32Array -> {
                        out.write(le32(9))
                        out.write(le32(4))
                        out.write(le64(value.values.size.toLong()))
                        value.values.forEach { out.write(le32(it)) }
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

package app.sirrak.engine.embeddings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the only native-free logic in the module. The LiteRT engine itself is never loaded here:
 * a real .litertlm file needs an Android device (or an emulator) and is exercised by instrumented tests,
 * not by :engine:embeddings:testDebugUnitTest.
 */
class EmbeddingBatchTest {

    @Test
    fun `empty input returns an empty list without touching the engine`() {
        var calls = 0
        val vectors = EmbeddingBatch.embed(emptyList(), 3) {
            calls++
            floatArrayOf(0f, 0f, 0f)
        }
        assertTrue(vectors.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `returns one vector per input in input order`() {
        val vectors = EmbeddingBatch.embed(listOf("first", "second", "third"), 2) { text ->
            when (text) {
                "first" -> floatArrayOf(1f, 0f)
                "second" -> floatArrayOf(0f, 2f)
                else -> floatArrayOf(3f, 3f)
            }
        }
        assertEquals(3, vectors.size)
        assertArrayEquals(floatArrayOf(1f, 0f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(0f, 2f), vectors[1], 0f)
        assertArrayEquals(floatArrayOf(3f, 3f), vectors[2], 0f)
    }

    @Test
    fun `copies vectors so callers never share engine-owned arrays`() {
        val engineOwned = floatArrayOf(0.5f, 0.5f)
        val vectors = EmbeddingBatch.embed(listOf("x"), 2) { engineOwned }
        assertNotSame(engineOwned, vectors[0])
        engineOwned[0] = 9f
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), vectors[0], 0f)
    }

    @Test
    fun `rejects a vector whose size differs from the declared dimensions`() {
        val failure = assertThrows(EmbeddingBackendException::class.java) {
            EmbeddingBatch.embed(listOf("x"), 768) { floatArrayOf(1f, 2f) }
        }
        assertTrue(failure.message.orEmpty().contains("768"))
    }

    @Test
    fun `passes blank text through unchanged`() {
        val seen = ArrayList<String>()
        EmbeddingBatch.embed(listOf("", "   "), 1) { text ->
            seen += text
            floatArrayOf(0f)
        }
        assertEquals(listOf("", "   "), seen)
    }

    @Test
    fun `backend exception keeps its cause for logging`() {
        val cause = IllegalStateException("native load failed")
        val failure = EmbeddingBackendException("failed to open", cause)
        assertEquals(cause, failure.cause)
        assertEquals("failed to open", failure.message)
    }
}

package app.sirrak.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelLayoutTest {

    private fun file(name: String, size: Long = 1_000) =
        SpeechModelFile(name = name, sizeBytes = size, open = { java.io.ByteArrayInputStream(ByteArray(0)) })

    @Test
    fun `match pairs an int8 whisper trio and infers the base name`() {
        val result = WhisperModelLayout.match(
            listOf(
                file("small-encoder.int8.onnx"),
                file("small-decoder.int8.onnx"),
                file("small-tokens.txt"),
            ),
        )

        val matched = result as WhisperLayoutResult.Matched
        assertEquals("small", matched.baseName)
        assertEquals(3, matched.files.size)
        assertEquals("small-encoder.int8.onnx", matched.nameFor(SpeechFileRole.ENCODER))
        assertEquals("small-decoder.int8.onnx", matched.nameFor(SpeechFileRole.DECODER))
        assertEquals("small-tokens.txt", matched.nameFor(SpeechFileRole.TOKENS))
    }

    @Test
    fun `match prefers the int8 encoder when both variants are offered`() {
        val result = WhisperModelLayout.match(
            listOf(
                file("small-encoder.onnx", 400),
                file("small-encoder.int8.onnx", 100),
                file("small-decoder.int8.onnx"),
                file("small-tokens.txt"),
            ),
        )

        val matched = result as WhisperLayoutResult.Matched
        assertEquals("small-encoder.int8.onnx", matched.nameFor(SpeechFileRole.ENCODER))
    }

    @Test
    fun `match rejects a missing tokens file`() {
        val result = WhisperModelLayout.match(
            listOf(file("small-encoder.int8.onnx"), file("small-decoder.int8.onnx")),
        )

        assertEquals(
            SpeechReasons.IMPORT_MISSING_TOKENS,
            (result as WhisperLayoutResult.Rejected).reason,
        )
    }

    @Test
    fun `match rejects a missing encoder`() {
        val result = WhisperModelLayout.match(
            listOf(file("small-decoder.int8.onnx"), file("small-tokens.txt")),
        )

        assertEquals(
            SpeechReasons.IMPORT_MISSING_ENCODER,
            (result as WhisperLayoutResult.Rejected).reason,
        )
    }

    @Test
    fun `match rejects a missing decoder`() {
        val result = WhisperModelLayout.match(
            listOf(file("small-encoder.int8.onnx"), file("small-tokens.txt")),
        )

        assertEquals(
            SpeechReasons.IMPORT_MISSING_DECODER,
            (result as WhisperLayoutResult.Rejected).reason,
        )
    }

    @Test
    fun `match rejects two competing encoders at the same preference`() {
        val result = WhisperModelLayout.match(
            listOf(
                file("one-encoder.int8.onnx"),
                file("two-encoder.int8.onnx"),
                file("one-decoder.int8.onnx"),
                file("one-tokens.txt"),
            ),
        )

        assertEquals(
            SpeechReasons.IMPORT_DUPLICATE_ROLE,
            (result as WhisperLayoutResult.Rejected).reason,
        )
    }

    @Test
    fun `match ignores unrelated files and rejects an empty selection`() {
        val withExtras = WhisperModelLayout.match(
            listOf(
                file("small-encoder.int8.onnx"),
                file("small-decoder.int8.onnx"),
                file("small-tokens.txt"),
                file("readme.md"),
                file("config.json"),
            ),
        )
        assertTrue(withExtras is WhisperLayoutResult.Matched)

        val empty = WhisperModelLayout.match(emptyList())
        assertEquals(
            SpeechReasons.IMPORT_EMPTY,
            (empty as WhisperLayoutResult.Rejected).reason,
        )
    }

    @Test
    fun `match rejects a selection whose files are too large for a speech model`() {
        val result = WhisperModelLayout.match(
            listOf(
                file("small-encoder.int8.onnx", SpeechLimits.MAX_MODEL_FILE_BYTES + 1),
                file("small-decoder.int8.onnx"),
                file("small-tokens.txt"),
            ),
        )

        assertEquals(
            SpeechReasons.IMPORT_TOO_LARGE,
            (result as WhisperLayoutResult.Rejected).reason,
        )
    }

    @Test
    fun `base name falls back to whisper when no role file carries a prefix`() {
        val result = WhisperModelLayout.match(
            listOf(file("encoder.onnx"), file("decoder.onnx"), file("tokens.txt")),
        )

        assertEquals("whisper", (result as WhisperLayoutResult.Matched).baseName)
    }
}

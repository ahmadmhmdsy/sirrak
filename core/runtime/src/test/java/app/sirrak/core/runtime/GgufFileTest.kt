package app.sirrak.core.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gguf(name: String, version: Int = 3): File {
        val file = tmp.newFile(name)
        val bytes = "GGUF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(
                (version and 0xFF).toByte(),
                ((version shr 8) and 0xFF).toByte(),
                ((version shr 16) and 0xFF).toByte(),
                ((version shr 24) and 0xFF).toByte(),
            ) + ByteArray(32)
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `reads the magic and little-endian version`() {
        val header = GgufFile.readHeader(gguf("model.gguf", version = 3))
        assertEquals(3, header?.version)
    }

    @Test
    fun `rejects a file whose magic is not GGUF`() {
        val file = tmp.newFile("fake.gguf")
        file.writeBytes("NOPE".toByteArray() + ByteArray(64))
        assertNull(GgufFile.readHeader(file))
        assertFalse(GgufFile.isValid(file))
    }

    @Test
    fun `rejects a truncated file`() {
        val file = tmp.newFile("short.gguf")
        file.writeBytes("GG".toByteArray())
        assertNull(GgufFile.readHeader(file))
    }

    @Test
    fun `rejects a directory`() {
        val dir = tmp.newFolder("folder.gguf")
        assertNull(GgufFile.readHeader(dir))
    }

    @Test
    fun `builds a friendly name and keeps the quant separate`() {
        assertEquals("qwen2.5 1.5b instruct", GgufFile.displayName("qwen2.5-1.5b-instruct-q4_k_m.gguf"))
        assertEquals("Q4_K_M", GgufFile.quantization("qwen2.5-1.5b-instruct-q4_k_m.gguf"))
        assertEquals("whisper small", GgufFile.displayName("whisper-small.gguf"))
        assertNull(GgufFile.quantization("plain-model.gguf"))
    }

    @Test
    fun `formats sizes for the model row`() {
        assertEquals("1.12 GB", GgufFile.formatSize(1_117_320_736L))
        assertEquals("500 MB", GgufFile.formatSize(500_000_000L))
        assertEquals("1.5 KB", GgufFile.formatSize(1_500L))
    }

    @Test
    fun `creates an installed model with size from the header`() {
        val file = gguf("qwen2.5-0.5b-instruct-q4_k_m.gguf")
        val model = GgufFile.toInstalledModel(file)
        assertEquals("qwen2.5 0.5b instruct", model?.displayName)
        assertEquals("Q4_K_M", model?.quantization)
        assertEquals(file.absolutePath, model?.path)
        assertEquals(file.length(), model?.sizeBytes)
    }

    @Test
    fun `returns null for a file that is not a model`() {
        val file = tmp.newFile("notes.gguf")
        file.writeText("hello")
        assertNull(GgufFile.toInstalledModel(file))
    }
}

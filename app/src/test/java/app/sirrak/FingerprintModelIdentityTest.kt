package app.sirrak

import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelFingerprint
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The model identity seam: settings are keyed by a cheap content fingerprint, cached by path+size+
 * mtime so selecting a 2 GB model does not re-hash it on every app start. Rewriting the file (same
 * size, different ends) must change the key, otherwise settings would leak between models.
 */
class FingerprintModelIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun model(file: File): InstalledModel =
        InstalledModel("id", "name", file.absolutePath, file.length(), "Q4_K_M")

    private fun contentFile(name: String, first: Byte, last: Byte): File {
        val file = File(tmp.root, name)
        val bytes = ByteArray(300_000)
        bytes[0] = first
        bytes[bytes.size - 1] = last
        file.writeBytes(bytes)
        file.setLastModified(1_700_000_000_000L)
        return file
    }

    @Test
    fun `same file gets the same key across instances`() {
        val file = contentFile("a.gguf", 1, 2)
        val cache = File(tmp.root, "keys.json")

        val first = FingerprintModelIdentity(cache).keyFor(model(file))
        val second = FingerprintModelIdentity(cache).keyFor(model(file))

        assertEquals(first, second)
        assertTrue(first.startsWith("sha256:"))
        assertTrue(cache.isFile)
    }

    @Test
    fun `rewriting the ends changes the key even at the same size`() {
        val file = contentFile("a.gguf", 1, 2)
        val identity = FingerprintModelIdentity(File(tmp.root, "keys.json"))
        val before = identity.keyFor(model(file))

        file.writeBytes(ByteArray(300_000).also { it[0] = 9; it[it.size - 1] = 9 })

        assertNotEquals(before, identity.keyFor(model(file)))
    }

    @Test
    fun `unchanged file is not re-hashed after the cache is warm`() {
        val file = contentFile("a.gguf", 1, 2)
        val cache = File(tmp.root, "keys.json")
        val calls = AtomicInteger()
        fun countingIdentity() = FingerprintModelIdentity(cache) { target ->
            calls.incrementAndGet()
            ModelFingerprint.of(target)
        }

        countingIdentity().keyFor(model(file))
        countingIdentity().keyFor(model(file))

        assertEquals("hash calls", 1, calls.get())
    }

    @Test
    fun `corrupt cache is ignored and rebuilt`() {
        val file = contentFile("a.gguf", 1, 2)
        val cache = File(tmp.root, "keys.json")
        cache.writeText("{broken")

        val key = FingerprintModelIdentity(cache).keyFor(model(file))

        assertTrue(key.startsWith("sha256:"))
        assertEquals(key, FingerprintModelIdentity(cache).keyFor(model(file)))
    }

    @Test
    fun `missing file gets a stable fallback key instead of crashing`() {
        val missing = File(tmp.root, "gone.gguf")
        val cache = File(tmp.root, "keys.json")
        val identity = FingerprintModelIdentity(cache)

        val first = identity.keyFor(model(missing))
        assertEquals(first, FingerprintModelIdentity(cache).keyFor(model(missing)))
        assertTrue(first.startsWith("sha256:"))
    }
}

package app.sirrak

import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.ModelSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M5 durable per-model settings: one small JSON file, atomic writes, tolerant reads, keyed by model
 * fingerprint. A corrupted file must never crash or block the app, and the bad bytes are kept for
 * diagnosis instead of silently destroyed.
 */
class FileModelSettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): FileModelSettingsStore = FileModelSettingsStore(tmp.root)

    private fun sample(key: String = "sha256:abc") = ModelSettings(
        modelKey = key,
        params = GenerationParams(temperature = 0.4f, topK = 20),
        touchedParams = listOf("temperature", "topK"),
        systemPrompt = "be brief",
        template = "qwen2",
        userModifiedAt = 1234L,
    )

    @Test
    fun `missing file loads as an empty map`() {
        assertTrue(store().settings.value.isEmpty())
        assertNull(store().get("sha256:abc"))
    }

    @Test
    fun `settings round-trip through a new instance`() {
        val saved = sample()

        store().put(saved)

        assertEquals(mapOf(saved.modelKey to saved), store().settings.value)
    }

    @Test
    fun `put and reset update the state flow and the file`() {
        val first = store()
        first.put(sample("sha256:one"))
        first.put(sample("sha256:two"))
        assertEquals(2, first.settings.value.size)

        first.reset("sha256:one")

        assertEquals(setOf("sha256:two"), first.settings.value.keys)
        assertEquals(setOf("sha256:two"), store().settings.value.keys)
    }

    @Test
    fun `corrupt file is backed up and loads empty`() {
        val file = File(tmp.root, FileModelSettingsStore.FILE_NAME)
        file.writeText("{not valid json")

        val loaded = store()

        assertTrue(loaded.settings.value.isEmpty())
        assertTrue("corrupt backup missing", File(tmp.root, FileModelSettingsStore.CORRUPT_FILE_NAME).isFile)
        assertEquals("{not valid json", File(tmp.root, FileModelSettingsStore.CORRUPT_FILE_NAME).readText())
    }

    @Test
    fun `file declares schemaVersion one`() {
        store().put(sample())

        val raw = File(tmp.root, FileModelSettingsStore.FILE_NAME).readText()
        assertTrue(raw.contains("\"schemaVersion\":1") || raw.contains("\"schemaVersion\": 1"))
    }

    @Test
    fun `temporary file never survives a successful write`() {
        store().put(sample())

        assertTrue(!File(tmp.root, FileModelSettingsStore.TEMP_FILE_NAME).exists())
    }
}

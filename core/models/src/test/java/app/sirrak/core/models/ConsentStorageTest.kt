package app.sirrak.core.models

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentStorageTest {

    @Test
    fun gatedModelsRequireConsentAndOpenOnesDoNot() {
        assertTrue(ConsentRules.requiresConsent(entry(gated = true)))
        assertFalse(ConsentRules.requiresConsent(entry(gated = false)))
    }

    @Test
    fun storageTightIsThreeTimesTheModelByDefault() {
        assertFalse(StorageMath.storageTight(100L, 300L))
        assertTrue(StorageMath.storageTight(100L, 299L))
        assertFalse(StorageMath.storageTight(0L, 0L))
    }

    @Test
    fun storageTightAcceptsACustomFactor() {
        assertTrue(StorageMath.storageTight(100L, 450L, factor = 5.0))
        assertFalse(StorageMath.storageTight(100L, 500L, factor = 5.0))
    }

    @Test
    fun freeAfterSubtractsTheModelSize() {
        assertEquals(500L, StorageMath.freeAfter(500L, 1000L))
        assertEquals(-500L, StorageMath.freeAfter(1500L, 1000L))
    }

    @Test
    fun totalBytesSumsOnlyFiles() {
        val root = Files.createTempDirectory("sirrak-storage").toFile()
        try {
            File(root, "a.gguf").writeBytes(ByteArray(100))
            File(root, "b.gguf").writeBytes(ByteArray(50))
            File(root, "sub").mkdirs()
            assertEquals(150L, StorageMath.totalBytes(listOf(File(root, "a.gguf"), File(root, "b.gguf"), File(root, "sub"))))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun entry(gated: Boolean) = CatalogueEntry(
        id = "gated-model",
        displayName = "Gated Model",
        repo = "org/gated",
        fileName = "gated.gguf",
        sizeBytes = 1024L,
        gated = gated,
        licence = "Llama 3.2",
    )
}

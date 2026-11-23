package app.sirrak

import app.sirrak.core.rag.EmbeddingProvider
import app.sirrak.core.rag.EmbeddingProviderFactory
import app.sirrak.core.rag.EmbeddingStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic embedding provider: the store only needs dimensions/modelId and a close hook. */
private class StoreFakeEmbeddingProvider(
    override val modelId: String = "fake-embedding",
    override val dimensions: Int = 8,
) : EmbeddingProvider {
    override fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(dimensions) }
    override fun close() {}
}

private class FakeEmbeddingFactory(
    var behavior: (String) -> EmbeddingProvider = { StoreFakeEmbeddingProvider() },
) : EmbeddingProviderFactory {
    var openCalls = 0
        private set

    override fun open(modelPath: String): EmbeddingProvider {
        openCalls++
        return behavior(modelPath)
    }
}

/**
 * Unit tests for [FileEmbeddingModelStore], including the M8.8 process-isolation decision (ADR-4):
 * a native model load can abort the whole process, so the store persists a marker before loading and
 * refuses to retry that model until it is replaced or removed. That is the substitute for running
 * embeddings in a separate process: it turns a reproducible crash loop into one failed attempt.
 */
class EmbeddingModelStoreTest {

    private val root: File = Files.createTempDirectory("sirrak-embedding-store").toFile()
    private val modelDir = File(File(root, "rag"), "embedding")
    private val marker = File(modelDir, "load-failed.txt")

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun importInstallsTheModelAndReportsReady() = runBlocking {
        val factory = FakeEmbeddingFactory()
        val store = FileEmbeddingModelStore(root, factory)

        val outcome = store.import(MODEL_NAME, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

        assertTrue("import rejected: " + outcome, outcome is EmbeddingModelImport.Imported)
        assertNotNull(store.installedModel())
        assertTrue(store.status.value is EmbeddingStatus.Ready)
        assertTrue(File(modelDir, "embedding.litertlm").isFile)
        assertTrue("provider must not be opened by import", factory.openCalls == 1)
    }

    @Test
    fun importRejectsEmptyAndTooLargeWithoutTouchingTheInstalledModel() = runBlocking {
        val factory = FakeEmbeddingFactory()
        val store = FileEmbeddingModelStore(root, factory)
        store.import(MODEL_NAME, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

        val empty = store.import("empty.litertlm", 0L) { ByteArrayInputStream(ByteArray(0)) }
        val tooLarge = store.import("huge.litertlm", Long.MAX_VALUE) { ByteArrayInputStream(bytes) }

        assertEquals(EmbeddingModelImport.Rejected(FileEmbeddingModelStore.REASON_EMPTY), empty)
        assertEquals(EmbeddingModelImport.Rejected(FileEmbeddingModelStore.REASON_TOO_LARGE), tooLarge)
        assertNotNull("the working model must survive a rejected import", store.installedModel())
        assertTrue(store.status.value is EmbeddingStatus.Ready)
    }

    @Test
    fun caughtProviderFailureIsNotRetriedInTheSameSession() = runBlocking {
        val factory = FakeEmbeddingFactory()
        val store = FileEmbeddingModelStore(root, factory)
        store.import(MODEL_NAME, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        assertEquals("import probe opened the model once", 1, factory.openCalls)

        factory.behavior = { error("native load failed after import") }
        assertNull(store.provider())
        assertTrue(store.status.value is EmbeddingStatus.Failed)
        assertEquals(2, factory.openCalls)

        // The same session must not hammer a model that already failed; a later process start may
        // retry because a caught exception is not a crash (the marker was cleared).
        assertNull(store.provider())
        assertEquals(2, factory.openCalls)
        assertTrue(!marker.exists())
    }

    @Test
    fun nativeCrashMarkerReportsFailureAndBlocksRetryUntilRemovedOrReplaced() = runBlocking {
        modelDir.mkdirs()
        File(modelDir, "embedding.litertlm").writeBytes(bytes)
        marker.writeText("process died while loading this model")

        val factory = FakeEmbeddingFactory()
        val store = FileEmbeddingModelStore(root, factory)

        assertTrue(store.status.value is EmbeddingStatus.Failed)
        assertNull(store.provider())
        assertEquals("a marked model must never be retried", 0, factory.openCalls)

        // Removing the model clears the guard so a fresh import starts clean.
        assertTrue(store.remove())
        assertTrue(store.status.value is EmbeddingStatus.Missing)
        assertTrue(!marker.exists())
        store.import(MODEL_NAME, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        assertTrue(store.status.value is EmbeddingStatus.Ready)
        assertEquals(1, factory.openCalls)
    }

    @Test
    fun successfulImportClearsAPreviousCrashMarker() = runBlocking {
        modelDir.mkdirs()
        File(modelDir, "embedding.litertlm").writeBytes(bytes)
        marker.writeText("stale marker")

        val factory = FakeEmbeddingFactory()
        val store = FileEmbeddingModelStore(root, factory)

        assertTrue(store.status.value is EmbeddingStatus.Failed)
        val outcome = store.import(MODEL_NAME, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

        assertTrue(outcome is EmbeddingModelImport.Imported)
        assertTrue(!marker.exists())
        assertTrue(store.status.value is EmbeddingStatus.Ready)
        assertNotNull(store.provider())
    }

    @Test
    fun removeClosesProviderAndReportsMissing() = runBlocking {
        val factory = FakeEmbeddingFactory()
        val store = FileEmbeddingModelStore(root, factory)
        store.import(MODEL_NAME, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        assertNotNull(store.provider())

        assertTrue(store.remove())

        assertNull(store.installedModel())
        assertTrue(store.status.value is EmbeddingStatus.Missing)
        assertNull(store.provider())
    }

    private companion object {
        const val MODEL_NAME = "embeddinggemma-2-text-270m.litertlm"
        val bytes = ByteArray(4096) { 7 }
    }
}
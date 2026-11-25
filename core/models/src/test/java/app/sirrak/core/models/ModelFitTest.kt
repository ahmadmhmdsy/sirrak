package app.sirrak.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFitTest {

    private val engine = ModelFitEngine()

    /** The review phone: 3.7 GB total, roughly 1.35 GB available, ~220 MB watermark, 15 GB free. */
    private val phone = DeviceCapacity(
        totalRamMb = 3737,
        availableRamMb = 1350,
        memoryThresholdMb = 220,
        isLowRamDevice = true,
        availableStorageBytes = 15L * 1024 * 1024 * 1024,
        sdkInt = 29,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
    )

    /** Qwen2.5-0.5B-Instruct Q4_K_M: 24 layers, 14 heads, 2 KV heads, 896 embedding, 32768 trained. */
    private val qwenHalfBillion = GgufArchitecture(
        name = "qwen2",
        layerCount = 24,
        headCount = 14,
        kvHeadCounts = listOf(2),
        embeddingLength = 896,
        contextTokens = 32768,
    )

    @Test
    fun `the review phone runs the half-billion model with headroom`() {
        val verdict = engine.evaluate(
            fileBytes = 491_400_032L,
            architecture = qwenHalfBillion,
            requestedContext = 2048,
            capacity = phone,
        )

        assertEquals(FitStatus.RUNS, verdict.status)
        assertFalse(verdict.blocked)
        assertEquals(910, verdict.usableRamMb)
        // 469 MB weights + 300 MB overhead + ~24 MB KV at 2048 context.
        assertEquals(792L, verdict.footprint.totalBytes / ModelFitEngine.MB)
    }

    @Test
    fun `a one and a half billion model is refused on the same phone`() {
        val verdict = engine.evaluate(
            fileBytes = 1_117_000_000L,
            architecture = qwenHalfBillion.copy(layerCount = 28, headCount = 12, kvHeadCounts = listOf(2), embeddingLength = 1536),
            requestedContext = 2048,
            capacity = phone,
        )

        assertEquals(FitStatus.WONT_RUN, verdict.status)
        assertTrue(verdict.reasons.contains(FitReasons.RAM_INSUFFICIENT))
    }

    @Test
    fun `a model between the tight line and the hard limit is TIGHT and asks first`() {
        val roomy = phone.copy(availableRamMb = 1100, memoryThresholdMb = 50) // usable 1000
        val verdict = engine.evaluate(
            fileBytes = 600L * 1024 * 1024,
            architecture = qwenHalfBillion,
            requestedContext = 2048,
            capacity = roomy,
        )

        assertEquals(1000, verdict.usableRamMb)
        assertEquals(FitStatus.TIGHT, verdict.status)
        assertTrue(verdict.needsConfirmation)
        assertFalse(verdict.blocked)
    }

    @Test
    fun `disk under 1_1 times the file size blocks even when RAM is fine`() {
        val verdict = engine.evaluate(
            fileBytes = 491_400_032L,
            architecture = qwenHalfBillion,
            requestedContext = 2048,
            capacity = phone.copy(availableStorageBytes = 480L * 1024 * 1024),
        )

        assertEquals(FitStatus.WONT_RUN, verdict.status)
        assertTrue(verdict.reasons.contains(FitReasons.DISK_INSUFFICIENT))
    }

    @Test
    fun `a low-RAM device cannot take a multi-gigabyte model`() {
        val verdict = engine.evaluate(
            fileBytes = 2L * 1024 * 1024 * 1024,
            architecture = qwenHalfBillion,
            requestedContext = 2048,
            capacity = phone.copy(availableRamMb = 1400),
        )

        assertEquals(FitStatus.WONT_RUN, verdict.status)
        assertTrue(verdict.reasons.contains(FitReasons.LOW_RAM_DEVICE))
    }

    @Test
    fun `an unknown architecture is never reported as running`() {
        val verdict = engine.evaluate(
            fileBytes = 491_400_032L,
            architecture = null,
            requestedContext = 2048,
            capacity = phone,
        )

        assertEquals(FitStatus.UNKNOWN, verdict.status)
        assertFalse(verdict.blocked)
        assertNull(verdict.suggestedContext)
        assertTrue(verdict.reasons.contains(FitReasons.ARCHITECTURE_UNKNOWN))
    }

    @Test
    fun `an architecture the build does not support blocks`() {
        val verdict = engine.evaluate(
            fileBytes = 200L * 1024 * 1024,
            architecture = qwenHalfBillion.copy(name = "mamba2"),
            requestedContext = 2048,
            capacity = phone,
        )

        assertEquals(FitStatus.WONT_RUN, verdict.status)
        assertTrue(verdict.reasons.contains(FitReasons.ARCHITECTURE_UNSUPPORTED))
    }

    @Test
    fun `the suggested context never exceeds the trained window`() {
        val twentyFiveMb = 25L * 1024 * 1024
        val trained = qwenHalfBillion.copy(contextTokens = 4096)
        val verdict = engine.evaluate(
            fileBytes = twentyFiveMb,
            architecture = trained,
            requestedContext = 2048,
            capacity = phone,
        )

        assertEquals(FitStatus.RUNS, verdict.status)
        assertEquals(4096, verdict.suggestedContext)
    }

    @Test
    fun `the KV formula matches the hand-computed cache for the review phone`() {
        // Qwen2.5-0.5B: 24 layers × 2 kv heads = 48 kv layer-heads; head dim = 896 / 14 = 64.
        // kv = 2 (K,V) × 2 B × 2048 × 48 × 64 = 25 165 824 B.
        assertEquals(25_165_824L, engine.kvBytes(qwenHalfBillion, 2048))
    }

    @Test
    fun `capacity never reports negative usable RAM`() {
        assertEquals(0, DeviceCapacity.unknown().usableRamMb)
        assertEquals(0, phone.copy(availableRamMb = 100, memoryThresholdMb = 200).usableRamMb)
    }
}

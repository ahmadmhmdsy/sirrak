package app.sirrak.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {

    private val lowRamProfile = profile(ramMb = 3072, freeBytes = 8L * 1024 * 1024 * 1024)
    private val midProfile = profile(ramMb = 6144, freeBytes = 16L * 1024 * 1024 * 1024)

    @Test
    fun recommendsTheFirstEntryThatFitsInCatalogueOrder() {
        val catalogue = catalogue(entry("a", size = 400, minRam = 2048), entry("b", size = 500, minRam = 2048))
        val recommendation = ModelRecommender.recommend(catalogue, midProfile)
        assertEquals("a", recommendation.entry?.id)
        assertEquals(RecommendationReason.RECOMMENDED, recommendation.reason)
        assertEquals("recommended_fits", recommendation.whyKey)
    }

    @Test
    fun skipsEntriesThatNeedMoreRamThanTheDeviceHas() {
        val catalogue = catalogue(entry("big", size = 400, minRam = 16384), entry("small", size = 400, minRam = 2048))
        val recommendation = ModelRecommender.recommend(catalogue, midProfile)
        assertEquals("small", recommendation.entry?.id)
    }

    @Test
    fun skipsEntriesThatDoNotFitStorage() {
        val catalogue = catalogue(entry("huge", size = 64L * 1024 * 1024 * 1024, minRam = 1024), entry("ok", size = 400, minRam = 1024))
        val recommendation = ModelRecommender.recommend(catalogue, midProfile)
        assertEquals("ok", recommendation.entry?.id)
    }

    @Test
    fun fallsBackToTheSmallestEntryThatFitsRamWhenStorageIsTooSmallForAny() {
        val tight = profile(ramMb = 6144, freeBytes = 1024L)
        val catalogue = catalogue(entry("large", size = 4000, minRam = 2048), entry("smaller", size = 1000, minRam = 2048))
        val recommendation = ModelRecommender.recommend(catalogue, tight)
        assertEquals("smaller", recommendation.entry?.id)
        assertEquals(RecommendationReason.SMALLEST_THAT_FITS, recommendation.reason)
        assertEquals("smallest_that_fits", recommendation.whyKey)
        assertTrue(recommendation.storageTight)
    }

    @Test
    fun reportsNothingFitsWhenRamIsBelowEveryEntry() {
        val catalogue = catalogue(entry("a", size = 100, minRam = 32768))
        val recommendation = ModelRecommender.recommend(catalogue, lowRamProfile)
        assertEquals(RecommendationReason.NOTHING_FITS, recommendation.reason)
        assertEquals("nothing_fits", recommendation.whyKey)
        assertTrue(recommendation.ramTight)
    }

    @Test
    fun emptyCatalogueRecommendsNothing() {
        val recommendation = ModelRecommender.recommend(ModelCatalogue(models = emptyList()), midProfile)
        assertNull(recommendation.entry)
        assertEquals(RecommendationReason.EMPTY_CATALOGUE, recommendation.reason)
    }

    @Test
    fun storageIsTightBelowThreeTimesTheModelSize() {
        val catalogue = catalogue(entry("a", size = 1000, minRam = 1024))
        val threeTimes = profile(ramMb = 8192, freeBytes = 3000)
        val lessThanThreeTimes = profile(ramMb = 8192, freeBytes = 2999)
        assertEquals(false, ModelRecommender.recommend(catalogue, threeTimes).storageTight)
        assertEquals(true, ModelRecommender.recommend(catalogue, lessThanThreeTimes).storageTight)
    }

    @Test
    fun deviceClassFollowsRamBoundaries() {
        assertEquals(DeviceClass.LOW, DeviceClass.of(profile(ramMb = 3071, freeBytes = 0)))
        assertEquals(DeviceClass.MID, DeviceClass.of(profile(ramMb = 4096, freeBytes = 0)))
        assertEquals(DeviceClass.HIGH, DeviceClass.of(profile(ramMb = 8192, freeBytes = 0)))
    }

    @Test
    fun speedEstimateScalesTheCatalogueHintAndHonoursMissingHints() {
        val entry = entry("a", size = 100, minRam = 1024).copy(tokensPerSecondHint = 10.0)
        val low = SpeedEstimator.estimatedTokensPerSecond(entry, profile(ramMb = 3072, freeBytes = 0))
        val high = SpeedEstimator.estimatedTokensPerSecond(entry, profile(ramMb = 16384, freeBytes = 0))
        assertEquals(5.5, low ?: 0.0, 0.0001)
        assertEquals(10.0, high ?: 0.0, 0.0001)
        assertNull(SpeedEstimator.estimatedTokensPerSecond(entry.copy(tokensPerSecondHint = null), midProfile))
    }

    private fun profile(ramMb: Int, freeBytes: Long) = DeviceProfile(
        totalRamMb = ramMb,
        availableStorageBytes = freeBytes,
        sdkInt = 34,
        abis = listOf("arm64-v8a"),
        socLabel = "test-soc",
    )

    private fun catalogue(vararg entries: CatalogueEntry) = ModelCatalogue(schemaVersion = 1, models = entries.toList())

    private fun entry(id: String, size: Long, minRam: Int) = CatalogueEntry(
        id = id,
        displayName = id,
        repo = "org/$id",
        fileName = "$id.gguf",
        sizeBytes = size,
        ramMb = 512,
        minRamMb = minRam,
    )
}

package app.sirrak.core.models

/**
 * What the device can tell us about itself (ONB-2). [gpuLabel] is nullable because this build runs
 * inference on the CPU; a null GPU hint is honest, an invented one is not.
 */
data class DeviceProfile(
    val totalRamMb: Int,
    val availableStorageBytes: Long,
    val sdkInt: Int,
    val abis: List<String> = emptyList(),
    val socLabel: String? = null,
    val gpuLabel: String? = null,
)

/** Coarse device class used only for a speed estimate and a default suggestion. */
enum class DeviceClass {
    LOW,
    MID,
    HIGH;

    companion object {
        fun of(profile: DeviceProfile): DeviceClass = when {
            profile.totalRamMb < 4_096 -> LOW
            profile.totalRamMb < 8_192 -> MID
            else -> HIGH
        }
    }
}

interface DeviceProfileProvider {
    fun profile(): DeviceProfile
}

enum class RecommendationReason {
    /** The first catalogue entry that fits RAM and storage. */
    RECOMMENDED,

    /** Nothing fits storage, so the smallest entry that fits RAM is suggested with a warning. */
    SMALLEST_THAT_FITS,

    /** Nothing fits the device; the smallest entry is still shown so the user understands why. */
    NOTHING_FITS,
    EMPTY_CATALOGUE,
}

/**
 * A recommendation with everything the UI needs to explain it: the entry, why it was chosen, and the
 * storage/RAM warnings. The [whyKey] is a stable code the UI localises.
 */
data class Recommendation(
    val entry: CatalogueEntry?,
    val reason: RecommendationReason,
    val whyKey: String,
    val storageTight: Boolean,
    val ramTight: Boolean,
)

/**
 * Picks a model from the catalogue for this device (MOD-6). Rules, in order: the first entry in
 * catalogue order whose RAM floor fits and whose size plus a 64 MiB install margin fits storage; else
 * the smallest entry that fits RAM; else the smallest entry overall (NOTHING_FITS). Priority in the
 * catalogue is a product decision, so adding a model earlier in the asset changes the recommendation
 * without a code change.
 */
object ModelRecommender {

    /** Extra space kept free beyond the model itself for the copy/import step. */
    const val STORAGE_MARGIN_BYTES = 64L * 1024 * 1024

    fun recommend(catalogue: ModelCatalogue, profile: DeviceProfile): Recommendation {
        val entries = catalogue.models
        if (entries.isEmpty()) {
            return Recommendation(null, RecommendationReason.EMPTY_CATALOGUE, "empty_catalogue", false, false)
        }
        val fits = entries.filter { entry ->
            entry.minRamMb <= profile.totalRamMb &&
                entry.sizeBytes + STORAGE_MARGIN_BYTES <= profile.availableStorageBytes
        }
        fits.firstOrNull()?.let { chosen ->
            return Recommendation(
                entry = chosen,
                reason = RecommendationReason.RECOMMENDED,
                whyKey = "recommended_fits",
                storageTight = storageTight(chosen, profile),
                ramTight = false,
            )
        }
        val ramFits = entries.filter { it.minRamMb <= profile.totalRamMb }
        val candidate = (ramFits.ifEmpty { entries }).minByOrNull { it.sizeBytes }
        val reason = if (candidate != null && candidate.minRamMb <= profile.totalRamMb) {
            RecommendationReason.SMALLEST_THAT_FITS
        } else {
            RecommendationReason.NOTHING_FITS
        }
        return Recommendation(
            entry = candidate,
            reason = reason,
            whyKey = if (reason == RecommendationReason.SMALLEST_THAT_FITS) "smallest_that_fits" else "nothing_fits",
            storageTight = candidate?.let { storageTight(it, profile) } ?: false,
            ramTight = candidate?.let { profile.totalRamMb < it.minRamMb } ?: false,
        )
    }

    private fun storageTight(entry: CatalogueEntry, profile: DeviceProfile): Boolean =
        profile.availableStorageBytes < entry.sizeBytes * 3
}

/**
 * A device-class-scaled estimate from the catalogue benchmark hint, never a promise: the UI labels it
 * as an estimate, and the M5/M10 measurement protocol remains the source of truth for real numbers.
 */
object SpeedEstimator {

    fun estimatedTokensPerSecond(entry: CatalogueEntry, profile: DeviceProfile): Double? {
        val base = entry.tokensPerSecondHint ?: return null
        val factor = when (DeviceClass.of(profile)) {
            DeviceClass.LOW -> 0.55
            DeviceClass.MID -> 0.85
            DeviceClass.HIGH -> 1.0
        }
        return base * factor
    }
}

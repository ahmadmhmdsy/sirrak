package app.sirrak

import android.content.res.AssetManager
import android.util.Log
import app.sirrak.core.runtime.LicenceRegistry
import app.sirrak.core.runtime.LicenceRules
import kotlinx.serialization.json.Json

/**
 * P3.2: loads the licence/SBOM registry from an asset (app.json -> licences.assetPath). Adding a
 * component is one JSON entry; the About screen, the NOTICE file and the store listing read the same
 * data. A malformed registry degrades to an empty list with a loud log instead of crashing, and the
 * unit test parses the same asset strictly.
 */
class AssetLicenceRegistry(
    private val assets: AssetManager,
    private val assetPath: String,
) {

    private val loaded: LicenceRegistry by lazy { load() }

    fun registry(): LicenceRegistry = loaded

    private fun load(): LicenceRegistry = runCatching {
        val raw = assets.open(assetPath).bufferedReader().use { it.readText() }
        val registry = json.decodeFromString<LicenceRegistry>(raw)
        val problems = LicenceRules.validate(registry)
        check(problems.isEmpty()) { "licence registry $assetPath is invalid: $problems" }
        registry
    }.getOrElse { error ->
        Log.e(TAG, "licence registry unavailable; About shows an honest empty state", error)
        LicenceRegistry()
    }

    private companion object {
        const val TAG = "SirrakLicences"
        val json = Json { ignoreUnknownKeys = false }
    }
}

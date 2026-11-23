package app.sirrak

import android.content.res.AssetManager
import android.util.Log
import app.sirrak.core.models.CatalogueJson
import app.sirrak.core.models.ModelCatalogue
import app.sirrak.core.models.ModelCatalogueProvider

/**
 * The curated catalogue is an asset (app.json -> content.catalog): adding, removing or re-prioritising
 * a recommended model is one JSON entry, no code change. A malformed catalogue degrades to "no
 * recommendations" with a loud log instead of crashing the app; the debug build fails fast in the
 * composition test that parses the same asset.
 */
class AssetModelCatalogue(
    private val assets: AssetManager,
    private val assetPath: String,
) : ModelCatalogueProvider {

    private val loaded: ModelCatalogue by lazy { load() }

    override fun catalogue(): ModelCatalogue = loaded

    private fun load(): ModelCatalogue = runCatching {
        val raw = assets.open(assetPath).bufferedReader().use { it.readText() }
        val catalogue = CatalogueJson.parse(raw)
            .getOrElse { error -> throw IllegalStateException("catalogue $assetPath is not valid JSON", error) }
        val problems = CatalogueJson.validationErrors(catalogue)
        check(problems.isEmpty()) { "catalogue $assetPath is invalid: $problems" }
        catalogue
    }.getOrElse { error ->
        Log.e(TAG, "model catalogue unavailable; recommendations are off", error)
        ModelCatalogue()
    }

    private companion object {
        const val TAG = "SirrakCatalogue"
    }
}

package app.sirrak.feature.models

import app.sirrak.core.models.DeviceProfileProvider
import app.sirrak.core.models.LicenceConsentStore
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelDownloader
import app.sirrak.core.models.OnboardingStore
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.SettingsService

/**
 * M11 feature plugin: it used to contribute the Settings model group. P4 moved model management into
 * the Library (models segment, downloads segment, model page), so this plugin now only declares the
 * services the composer and the host's onboarding renderer rely on — [ModelManagerSection] itself is
 * still composed by the first-run onboarding flow and by tests, just not as a Settings group.
 *
 * The plugin contributes no UI of its own; removing it would change install order for the onboarding
 * store, which is why it stays as an explicit dependency declaration.
 */
class ModelManagerPlugin : Plugin {

    override val id: String = "models"

    override val requires: List<Class<*>> = listOf(
        ModelStore::class.java,
        SettingsService::class.java,
        ModelCatalogueProvider::class.java,
        DeviceProfileProvider::class.java,
        ModelDownloader::class.java,
        OnboardingStore::class.java,
        LicenceConsentStore::class.java,
    )

    override fun install(scope: PluginScope) {
        // Intentionally empty: model surfaces live in the Library since P4.
    }
}

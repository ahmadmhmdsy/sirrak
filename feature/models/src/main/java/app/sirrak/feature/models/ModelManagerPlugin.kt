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
import app.sirrak.core.runtime.Slots

/**
 * M11 feature plugin: one Settings group (catalogue, downloader, consent, storage manager) and the
 * first-run onboarding screens rendered by the host before the shell exists. The plugin contributes
 * UI only - the host owns the catalogue asset, the device report and the single downloader, so this
 * module stays testable and the same components can be reused by a future standalone models screen.
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
        val catalogueProvider = scope.service(ModelCatalogueProvider::class.java)
        val profileProvider = scope.service(DeviceProfileProvider::class.java)
        val downloader = scope.service(ModelDownloader::class.java)
        val consentStore = scope.service(LicenceConsentStore::class.java)
        val modelStore = scope.service(ModelStore::class.java)
        scope.slotList(Slots.SettingsSections, order = 12) {
            ModelManagerSection(
                catalogueProvider = catalogueProvider,
                profileProvider = profileProvider,
                downloader = downloader,
                consentStore = consentStore,
                modelStore = modelStore,
            )
        }
    }
}

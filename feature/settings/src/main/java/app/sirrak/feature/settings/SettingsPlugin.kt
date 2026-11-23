package app.sirrak.feature.settings

import app.sirrak.core.runtime.Destination
import app.sirrak.core.runtime.NavIcon
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.Slots
import app.sirrak.core.runtime.SettingsService

/** Settings as a feature plugin: it owns one destination and contributes its own groups as slots. */
class SettingsPlugin : Plugin {
    override val id: String = "settings"

    override val requires: List<Class<*>> = listOf(SettingsService::class.java)

    override fun install(scope: PluginScope) {
        val settings = scope.service(SettingsService::class.java)
        scope.destination(
            Destination(
                id = "settings",
                labelRes = R.string.nav_settings,
                icon = NavIcon.SETTINGS,
                order = 20,
                content = { SettingsScreen(settings) },
            ),
        )
        scope.slotList(Slots.SettingsSections, order = 0) { AppearanceSection(settings) }
        scope.slotList(Slots.SettingsSections, order = 5) { LanguageSection(settings) }
    }
}

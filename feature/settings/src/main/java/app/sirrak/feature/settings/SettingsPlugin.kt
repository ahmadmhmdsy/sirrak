package app.sirrak.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.SettingsPageScaffold
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.Destination
import app.sirrak.core.runtime.NavIcon
import app.sirrak.core.runtime.PageContribution
import app.sirrak.core.runtime.PageIds
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.SettingsDoor
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.ThemeMode
import app.sirrak.core.design.FontScales

/**
 * Settings as a feature plugin: it owns one destination and contributes its own doors and page. The
 * appearance door is this module's; the language door expands in place. The other four doors come from
 * the features that own their state (privacy, voice, RAG/advanced, about), so nothing here hardcodes
 * another feature's section.
 */
class SettingsPlugin : Plugin {
    override val id: String = "settings"

    override val requires: List<Class<*>> = listOf(
        SettingsService::class.java,
        AppNavigator::class.java,
    )

    override fun install(scope: PluginScope) {
        val settings = scope.service(SettingsService::class.java)
        val navigator = scope.service(AppNavigator::class.java)

        scope.destination(
            Destination(
                id = "settings",
                labelRes = R.string.nav_settings,
                icon = NavIcon.SETTINGS,
                order = 20,
                content = { SettingsScreen(settings) },
            ),
        )

        scope.door(
            SettingsDoor(
                id = "appearance",
                labelRes = R.string.settings_appearance,
                order = 0,
                summary = {
                    val mode by settings.themeMode.collectAsStateWithLifecycle()
                    val fontScaleId by settings.fontScaleId.collectAsStateWithLifecycle()
                    val percent = FontScales.byId(fontScaleId).percent
                    stringResource(
                        R.string.settings_door_appearance_value,
                        themeModeLabel(mode),
                        percent,
                    )
                },
                onClick = { navigator.push(PageIds.SETTINGS_APPEARANCE) },
            ),
        )
        scope.page(
            PageContribution(PageIds.SETTINGS_APPEARANCE, labelRes = R.string.settings_appearance) {
                SettingsPageScaffold(
                    title = stringResource(R.string.settings_appearance),
                    onBack = { navigator.back() },
                ) {
                    AppearanceSection(settings)
                }
            },
        )

        scope.door(
            SettingsDoor(
                id = "language",
                labelRes = R.string.settings_language,
                order = 5,
                summary = {
                    val language by settings.language.collectAsStateWithLifecycle()
                    stringResource(
                        if (language == app.sirrak.core.runtime.AppLanguage.ARABIC) {
                            R.string.language_arabic
                        } else {
                            R.string.language_english
                        },
                    )
                },
                expandedContent = { LanguageChoices(settings) },
            ),
        )
    }
}

/** Localised name of the colour mode, shared by the door summary and any future surface. */
@androidx.compose.runtime.Composable
internal fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.SYSTEM -> R.string.theme_system
    },
)

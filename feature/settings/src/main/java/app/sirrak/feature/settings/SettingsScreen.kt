package app.sirrak.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.FontScaleOption
import app.sirrak.core.design.FontScales
import app.sirrak.core.design.ReadingColumn
import app.sirrak.core.design.ScreenHeader
import app.sirrak.core.design.SettingsChoiceRow
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.design.SlotList
import app.sirrak.core.design.ThemePackOption
import app.sirrak.core.design.ThemePacks
import app.sirrak.core.runtime.AppLanguage
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.Slots
import app.sirrak.core.runtime.ThemeMode
import app.sirrak.core.runtime.findActivity

/** Settings renders slots only. Every group, including the engine and about groups, is contributed. */
@Composable
fun SettingsScreen(settings: SettingsService, modifier: Modifier = Modifier) {
    ReadingColumn(modifier = modifier) {
        ScreenHeader(title = stringResource(R.string.settings_title))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp),
        ) {
            SlotList(Slots.SettingsSections)
            Spacer(Modifier.size(28.dp))
        }
    }
}

/** Appearance group, contributed by the settings feature itself. */
@Composable
fun AppearanceSection(settings: SettingsService) {
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val packId by settings.themePackId.collectAsStateWithLifecycle()
    val fontScaleId by settings.fontScaleId.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        // Three groups, one concept each: mode (light/dark/system), text size and pack (colour
        // identity). Both list choices recompose through StateFlows — no activity recreate.
        SettingsSection(title = stringResource(R.string.settings_appearance)) {
            SettingsChoiceRow(
                label = stringResource(R.string.settings_theme),
                options = listOf(
                    stringResource(R.string.theme_light),
                    stringResource(R.string.theme_dark),
                    stringResource(R.string.theme_system),
                ),
                selectedIndex = when (mode) {
                    ThemeMode.LIGHT -> 0
                    ThemeMode.DARK -> 1
                    ThemeMode.SYSTEM -> 2
                },
                onSelect = { index ->
                    settings.setThemeMode(
                        when (index) {
                            0 -> ThemeMode.LIGHT
                            1 -> ThemeMode.DARK
                            else -> ThemeMode.SYSTEM
                        },
                    )
                },
            )
        }
        SettingsSection(title = stringResource(R.string.settings_font_scale)) {
            // Data-driven: the registry order is the UI order, and a future preset needs no branch
            // here. Selecting one updates the StateFlow, so the whole app rescales in place.
            FontScales.all.forEach { scale ->
                FontScaleOption(
                    scale = scale,
                    selected = scale.id == fontScaleId,
                    onClick = { settings.setFontScale(scale.id) },
                )
            }
            SettingsNote(stringResource(R.string.settings_font_scale_note))
        }
        SettingsSection(title = stringResource(R.string.settings_theme_pack)) {
            ThemePacks.all.forEach { pack ->
                ThemePackOption(
                    pack = pack,
                    selected = pack.id == packId,
                    onClick = { settings.setThemePack(pack.id) },
                )
            }
            SettingsNote(stringResource(R.string.settings_theme_pack_note))
        }
    }
}

/** Language group: persists the choice, then recreates the activity so resources reload. */
@Composable
fun LanguageSection(settings: SettingsService) {
    val language by settings.language.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        SettingsSection(title = stringResource(R.string.settings_language)) {
            SettingsChoiceRow(
                label = stringResource(R.string.settings_language),
                options = listOf(
                    stringResource(R.string.language_arabic),
                    stringResource(R.string.language_english),
                ),
                selectedIndex = if (language == AppLanguage.ARABIC) 0 else 1,
                onSelect = { index ->
                    val target = if (index == 0) AppLanguage.ARABIC else AppLanguage.ENGLISH
                    if (target != language) {
                        settings.setLanguage(target)
                        context.findActivity()?.recreate()
                    }
                },
            )
            SettingsNote(stringResource(R.string.settings_language_note))
        }
    }
}

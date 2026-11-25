package app.sirrak.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.FontScaleOption
import app.sirrak.core.design.FontScales
import app.sirrak.core.design.LocalAppContext
import app.sirrak.core.design.ReadingColumn
import app.sirrak.core.design.ScreenHeader
import app.sirrak.core.design.SettingsChoiceRow
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.design.ThemePackOption
import app.sirrak.core.design.ThemePacks
import app.sirrak.core.runtime.AppLanguage
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.ThemeMode
import app.sirrak.core.runtime.findActivity

/**
 * Settings is a root of six doors, not one long scroll (P4). The root owns the card, the dividers and
 * the single expanded door; every door's label, live summary and destination come from the plugin
 * that owns the state, so this module never imports another feature. Tapping a door pushes its page
 * through [app.sirrak.core.runtime.AppNavigator]; the language door expands in place because switching
 * language recreates the activity, and a push would make that feel like a lost page.
 */
@Composable
fun SettingsScreen(settings: SettingsService, modifier: Modifier = Modifier) {
    val doors = LocalAppContext.current.settingsDoors.all()
    var expandedDoorId by rememberSaveable { mutableStateOf<String?>(null) }
    ReadingColumn(modifier = modifier) {
        ScreenHeader(title = stringResource(R.string.settings_title))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                SettingsSection(title = stringResource(R.string.settings_doors_section)) {
                    doors.forEachIndexed { index, door ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            label = stringResource(door.labelRes),
                            value = door.summary(),
                            onClick = {
                                if (door.expandedContent != null) {
                                    expandedDoorId =
                                        if (expandedDoorId == door.id) null else door.id
                                } else {
                                    door.onClick()
                                }
                            },
                        )
                        if (door.expandedContent != null && expandedDoorId == door.id) {
                            door.expandedContent?.invoke()
                        }
                    }
                }
            }
            Spacer(Modifier.size(28.dp))
        }
    }
}

/**
 * Appearance page body: mode, text size and pack — three groups, one concept each. Both list choices
 * recompose through StateFlows, so no activity recreate is needed.
 */
@Composable
fun AppearanceSection(settings: SettingsService) {
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val packId by settings.themePackId.collectAsStateWithLifecycle()
    val fontScaleId by settings.fontScaleId.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
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

/**
 * The language door's inline body. It is not a [SettingsSection]: the root renders it inside the
 * doors card under the language row, so it reads as one expanded row rather than a second card.
 */
@Composable
fun LanguageChoices(settings: SettingsService) {
    val language by settings.language.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column {
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

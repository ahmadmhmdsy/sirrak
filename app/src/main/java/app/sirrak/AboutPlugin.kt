package app.sirrak

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsPageScaffold
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.LicenceRegistry
import app.sirrak.core.runtime.PageContribution
import app.sirrak.core.runtime.PageIds
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.SettingsDoor

/**
 * About as its own tiny plugin: proves a settings group can live outside the settings module, and
 * P3.2 keeps it data-driven - every licence row comes from the injected [LicenceRegistry] asset,
 * never from a hardcoded string. Adding a component is a JSON edit.
 */
class AboutPlugin : Plugin {
    override val id: String = "about"

    override val requires: List<Class<*>> = listOf(
        LicenceRegistry::class.java,
        AppNavigator::class.java,
    )

    override fun install(scope: PluginScope) {
        val licences = scope.service(LicenceRegistry::class.java)
        val navigator = scope.service(AppNavigator::class.java)
        scope.door(
            SettingsDoor(
                id = "about",
                labelRes = R.string.settings_about,
                order = 40,
                summary = { BuildConfig.VERSION_NAME },
                onClick = { navigator.push(PageIds.SETTINGS_ABOUT) },
            ),
        )
        scope.page(
            PageContribution(PageIds.SETTINGS_ABOUT, labelRes = R.string.settings_about) {
                SettingsPageScaffold(
                    title = stringResource(R.string.settings_about),
                    onBack = { navigator.back() },
                ) {
                    AboutSection(licences)
                }
            },
        )
    }
}

@Composable
fun AboutSection(licences: LicenceRegistry) {
    var showLicences by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        SettingsSection(title = stringResource(R.string.settings_about)) {
            SettingsRow(
                label = stringResource(R.string.about_version),
                value = BuildConfig.VERSION_NAME,
            )
            // P3.5: shows which distribution channel this APK was built for ("libre" F-Droid or
            // "play"), straight from the flavour's BuildConfig field - no hardcoded build name.
            SettingsRow(
                label = stringResource(R.string.about_channel),
                value = BuildConfig.STORE,
            )
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.about_licenses),
                value = pluralStringResource(
                    R.plurals.about_licences_count,
                    licences.entries.size,
                    licences.entries.size,
                ),
                onClick = { showLicences = true },
            )
            SettingsNote(stringResource(R.string.licenses_note))
            SettingsDivider()
            SettingsRow(label = stringResource(R.string.about_privacy))
            SettingsNote(stringResource(R.string.privacy_statement))
        }
    }
    if (showLicences) {
        LicencesSheet(registry = licences, onDismiss = { showLicences = false })
    }
}

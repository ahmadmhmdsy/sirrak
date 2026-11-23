package app.sirrak

import android.content.Context
import android.content.res.Configuration
import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.AppConfigLoader
import app.sirrak.core.runtime.AppLanguage
import java.util.Locale

/** Applies the saved or configured language before resources load; no AppCompat needed. */
object LocaleSupport {

    fun wrap(base: Context): Context {
        val language = currentLanguage(base)
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    fun currentLanguage(base: Context): AppLanguage {
        val prefs = base.getSharedPreferences(
            SharedPrefsSettingsService.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val saved = prefs.getString(SharedPrefsSettingsService.KEY_LANGUAGE, null)
        if (saved != null) return AppLanguage.fromTag(saved)
        val configured = runCatching {
            val raw = base.assets.open("config/app.json").bufferedReader().use { it.readText() }
            AppConfigLoader.parse(raw).language.defaultLanguage
        }.getOrNull()
        return AppLanguage.fromTag(configured)
    }

    @Suppress("unused")
    private fun defaults(): AppConfig = AppConfig()
}

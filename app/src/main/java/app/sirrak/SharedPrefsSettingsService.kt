package app.sirrak

import android.content.Context
import android.content.SharedPreferences
import app.sirrak.core.design.FontScales
import app.sirrak.core.design.ThemePacks
import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.AppLanguage
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted, observable settings. Every change is a StateFlow update, so the UI never polls. */
class SharedPrefsSettingsService(
    private val prefs: SharedPreferences,
    config: AppConfig,
) : SettingsService {

    /** Production entry point: the app's private preference file. Tests inject an in-memory [prefs]. */
    constructor(context: Context, config: AppConfig) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        config,
    )

    private val _themeMode = MutableStateFlow(
        parseTheme(prefs.getString(KEY_THEME, null))
            ?: parseTheme(config.theme.defaultMode)
            ?: ThemeMode.SYSTEM,
    )
    // Resolution happens here, not in the UI: a saved/config id may refer to a removed or mistyped pack,
    // and the app must open styled with the default pack rather than crash or flash unstyled.
    private val _themePackId = MutableStateFlow(
        ThemePacks.byId(prefs.getString(KEY_THEME_PACK, null) ?: config.theme.defaultPack).id,
    )
    // Same resolution rule as packs: a removed/unknown/blank id opens at 100%, never crashes.
    private val _fontScaleId = MutableStateFlow(
        FontScales.byId(prefs.getString(KEY_FONT_SCALE, null)).id,
    )
    private val _language = MutableStateFlow(
        prefs.getString(KEY_LANGUAGE, null)?.let(AppLanguage::fromTag)
            ?: AppLanguage.fromTag(config.language.defaultLanguage),
    )

    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    override val themePackId: StateFlow<String> = _themePackId.asStateFlow()
    override val fontScaleId: StateFlow<String> = _fontScaleId.asStateFlow()
    override val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** Global system prompt override; null/blank means "use the app.json default / localised asset". */
    private val _systemPrompt = MutableStateFlow(
        prefs.getString(KEY_SYSTEM_PROMPT, null)?.takeIf { it.isNotBlank() },
    )
    override val systemPrompt: StateFlow<String?> = _systemPrompt.asStateFlow()

    private val _modelAdvanced = MutableStateFlow(prefs.getBoolean(KEY_MODEL_ADVANCED, false))
    override val modelAdvanced: StateFlow<Boolean> = _modelAdvanced.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name.lowercase()).apply()
        _themeMode.value = mode
    }

    override fun setThemePack(packId: String) {
        val resolved = ThemePacks.byId(packId).id
        prefs.edit().putString(KEY_THEME_PACK, resolved).apply()
        _themePackId.value = resolved
    }

    override fun setFontScale(scaleId: String) {
        val resolved = FontScales.byId(scaleId).id
        prefs.edit().putString(KEY_FONT_SCALE, resolved).apply()
        _fontScaleId.value = resolved
    }

    override fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.tag).apply()
        _language.value = language
    }

    override fun setModelAdvanced(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MODEL_ADVANCED, enabled).apply()
        _modelAdvanced.value = enabled
    }

    override fun setSystemPrompt(prompt: String?) {
        val normalized = prompt?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (normalized == null) remove(KEY_SYSTEM_PROMPT) else putString(KEY_SYSTEM_PROMPT, normalized)
        }.apply()
        _systemPrompt.value = normalized
    }

    private fun parseTheme(raw: String?): ThemeMode? = when (raw?.lowercase()) {
        "light" -> ThemeMode.LIGHT
        "dark" -> ThemeMode.DARK
        "system" -> ThemeMode.SYSTEM
        else -> null
    }

    companion object {
        const val PREFS_NAME = "sirrak_settings"
        const val KEY_THEME = "theme"
        const val KEY_THEME_PACK = "theme_pack"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_LANGUAGE = "language"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_MODEL_ADVANCED = "model_advanced"
    }
}
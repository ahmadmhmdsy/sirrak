package app.sirrak

import android.content.Context
import app.sirrak.core.speech.DictationLanguage
import app.sirrak.core.speech.DictationMode
import app.sirrak.core.speech.DictationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dictation preferences, stored beside the rest of the app's settings. Values are stored as stable ids
 * (not enum ordinals), so reordering or extending the enums later cannot silently change a user's
 * language or interaction mode.
 */
class SharedPrefsDictationSettings(context: Context) : DictationSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(
        DictationLanguage.fromId(prefs.getString(KEY_LANGUAGE, null)),
    )
    override val language: StateFlow<DictationLanguage> = _language.asStateFlow()

    private val _mode = MutableStateFlow(DictationMode.fromId(prefs.getString(KEY_MODE, null)))
    override val mode: StateFlow<DictationMode> = _mode.asStateFlow()

    override fun setLanguage(language: DictationLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.id).apply()
        _language.value = language
    }

    override fun setMode(mode: DictationMode) {
        prefs.edit().putString(KEY_MODE, mode.id).apply()
        _mode.value = mode
    }

    companion object {
        const val PREFS_NAME = "sirrak_dictation"
        const val KEY_LANGUAGE = "dictation_language"
        const val KEY_MODE = "dictation_mode"
    }
}

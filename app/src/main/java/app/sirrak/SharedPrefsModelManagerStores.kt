package app.sirrak

import android.content.Context
import app.sirrak.core.models.LicenceConsent
import app.sirrak.core.models.LicenceConsentStore
import app.sirrak.core.models.OnboardingState
import app.sirrak.core.models.OnboardingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Licence consent is never auto-accepted (ONB-4): each gated model records its licence id and the
 * moment the user accepted it. A re-download of the same model after deletion asks again because the
 * consent is cleared together with the model record - the store keeps one key per model id.
 */
class SharedPrefsLicenceConsentStore(context: Context) : LicenceConsentStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val consents = LinkedHashMap<String, LicenceConsent>()

    init {
        prefs.all.forEach { (modelId, value) ->
            val raw = value as? String ?: return@forEach
            val separator = raw.lastIndexOf(SEPARATOR)
            if (separator <= 0) return@forEach
            val licence = raw.substring(0, separator)
            val acceptedAt = raw.substring(separator + 1).toLongOrNull() ?: return@forEach
            consents[modelId] = LicenceConsent(modelId, licence, acceptedAt)
        }
    }

    override fun isAccepted(modelId: String): Boolean = consents.containsKey(modelId)

    override fun accept(modelId: String, licence: String, acceptedAtMs: Long) {
        val consent = LicenceConsent(modelId, licence, acceptedAtMs)
        consents[modelId] = consent
        prefs.edit().putString(modelId, licence + SEPARATOR + acceptedAtMs).apply()
    }

    override fun clear(modelId: String) {
        consents.remove(modelId)
        prefs.edit().remove(modelId).apply()
    }

    override fun accepted(): Map<String, LicenceConsent> = consents.toMap()

    private companion object {
        const val PREFS_NAME = "sirrak_model_consent"
        const val SEPARATOR = "\u0000"
    }
}

/**
 * First-run state (ONB-1/5). Completing with "skip" is a real choice: the user lands in chat on the
 * demo engine and can install a model later from Settings. Reset exists for tests and for a future
 * "run onboarding again" action; it is not exposed in the UI yet.
 */
class SharedPrefsOnboardingStore(context: Context) : OnboardingStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<OnboardingState> = _state.asStateFlow()

    override fun complete(skipped: Boolean) {
        val completedAt = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .putBoolean(KEY_SKIPPED, skipped)
            .putLong(KEY_COMPLETED_AT, completedAt)
            .apply()
        _state.value = OnboardingState(completed = true, skipped = skipped, completedAtMs = completedAt)
    }

    override fun reset() {
        prefs.edit().clear().apply()
        _state.value = OnboardingState()
    }

    private fun read(): OnboardingState = OnboardingState(
        completed = prefs.getBoolean(KEY_COMPLETED, false),
        skipped = prefs.getBoolean(KEY_SKIPPED, false),
        completedAtMs = prefs.getLong(KEY_COMPLETED_AT, 0L),
    )

    private companion object {
        const val PREFS_NAME = "sirrak_onboarding"
        const val KEY_COMPLETED = "completed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_COMPLETED_AT = "completed_at"
    }
}

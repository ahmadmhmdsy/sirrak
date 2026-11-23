package app.sirrak.core.models

/** One recorded acceptance of a model licence. */
data class LicenceConsent(
    val modelId: String,
    val licence: String,
    val acceptedAtMs: Long,
)

/**
 * ONB-4: a gated model is never downloaded without an explicit, stored acceptance. The store is a
 * seam so the policy (ConsentRules) and the persistence (app SharedPreferences) stay separate.
 */
interface LicenceConsentStore {
    fun isAccepted(modelId: String): Boolean
    fun accept(modelId: String, licence: String, acceptedAtMs: Long = System.currentTimeMillis())
    fun clear(modelId: String)
    fun accepted(): Map<String, LicenceConsent>
}

object ConsentRules {
    /** A gated model needs consent; an Apache-2.0 model only displays its licence. */
    fun requiresConsent(entry: CatalogueEntry): Boolean = entry.gated
}

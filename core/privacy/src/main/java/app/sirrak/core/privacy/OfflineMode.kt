package app.sirrak.core.privacy

/**
 * PRV-2: the user's global "offline only" switch. The app has exactly one network seam (the model
 * download transport), so the switch is enforced in two places: the downloader refuses to start
 * (typed reason) and the transport refuses to open a connection. Everything else works offline.
 *
 * The store is a seam so the policy stays pure JVM and the app owns SharedPreferences.
 */
interface OfflineModeStore {
    fun isOfflineOnly(): Boolean
    fun setOfflineOnly(enabled: Boolean)
}

/**
 * A tiny no-op store for previews and tests that need the default (online) state without Android.
 */
class InMemoryOfflineModeStore(initial: Boolean = false) : OfflineModeStore {
    @Volatile
    private var offline = initial

    override fun isOfflineOnly(): Boolean = offline

    override fun setOfflineOnly(enabled: Boolean) {
        offline = enabled
    }
}

package app.sirrak.core.privacy

/**
 * One network call the app actually made, kept only on this device. The audit log is the evidence
 * behind PRV-2: the privacy screen lists both the declared endpoints (from app.json) and the calls
 * that really happened, so "offline by design" is inspectable instead of a claim.
 */
data class NetworkCall(
    val endpointId: String,
    val host: String,
    val startedAtMs: Long,
    val bytes: Long = 0L,
    val outcome: NetworkOutcome = NetworkOutcome.STARTED,
)

enum class NetworkOutcome {
    STARTED,
    COMPLETED,
    FAILED,
}

/** Persistence seam: the app stores the bounded log in SharedPreferences, tests use a fake. */
interface NetworkAuditStore {
    fun events(): List<NetworkCall>
    fun save(events: List<NetworkCall>)
}

/**
 * The bounded, newest-first audit log. Every record is persisted immediately so the log survives a
 * process death; the oldest call is dropped once [capacity] is reached. Recording is synchronized
 * because the download transport records from an IO dispatcher while the UI reads.
 */
class NetworkAudit(
    private val store: NetworkAuditStore,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private var cached: List<NetworkCall> = store.events()

    @Synchronized
    fun record(
        endpointId: String,
        host: String,
        bytes: Long = 0L,
        outcome: NetworkOutcome = NetworkOutcome.STARTED,
    ): NetworkCall {
        require(endpointId.isNotBlank()) { "endpointId must not be blank" }
        require(host.isNotBlank()) { "host must not be blank" }
        val call = NetworkCall(
            endpointId = endpointId.trim(),
            host = host.trim(),
            startedAtMs = clock(),
            bytes = bytes.coerceAtLeast(0L),
            outcome = outcome,
        )
        cached = (listOf(call) + cached).take(capacity)
        store.save(cached)
        return call
    }

    @Synchronized
    fun events(): List<NetworkCall> = cached.toList()

    @Synchronized
    fun clear() {
        cached = emptyList()
        store.save(cached)
    }

    companion object {
        /** Enough history to show a few downloads without turning the log into a data store. */
        const val DEFAULT_CAPACITY = 50
    }
}

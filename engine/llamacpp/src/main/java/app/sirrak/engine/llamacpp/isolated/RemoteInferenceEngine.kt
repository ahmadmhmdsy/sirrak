package app.sirrak.engine.llamacpp.isolated

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineReasons
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.InstalledModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADR-4 spike, client half: the same [InferenceEngine] contract the in-process llama.cpp engine
 * implements, backed by [IsolatedEngineService] in `<applicationId>:engine`. Tokens stream back over a
 * ReplyTo [Messenger]; module death surfaces as [EngineStatus.Failed] with
 * [EngineReasons.PROCESS_LOST], and [connect]/[load] can be called again to recover in a fresh process.
 *
 * This class is deliberately not the production engine yet: it is the evidence that the UI can keep
 * the exact same seam while the native engine is crash-isolated. See ADR-4 in docs/22.
 */
class RemoteInferenceEngine(
    context: Context,
    override val displayName: String = "llama.cpp isolated",
) : InferenceEngine {

    private val appContext = context.applicationContext

    override val id: String = ID

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Unloaded)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            route(message)
            true
        },
    )

    @Volatile
    private var remote: Messenger? = null

    @Volatile
    private var connection: ServiceConnection? = null

    @Volatile
    private var bound = false

    @Volatile
    private var binding: CompletableDeferred<Unit>? = null

    @Volatile
    private var loadOutcome: CompletableDeferred<Boolean>? = null

    /** Null means "completed fine"; a non-null string is the failing [EngineChunk.Failed] reason. */
    @Volatile
    private var generationEnd: CompletableDeferred<String?>? = null

    @Volatile
    private var tokenSink: ((String) -> Unit)? = null

    /** PID of the bound engine process, or -1 before the first successful bind. */
    @Volatile
    var remotePid: Int = -1
        private set

    /** Name of the bound engine process (e.g. `app.sirrak:engine`), or null before the first bind. */
    @Volatile
    var remoteProcessName: String? = null
        private set

    val isConnected: Boolean get() = remote != null

    /** Binds the engine process, creating it on demand. Idempotent while connected. */
    suspend fun connect(timeoutMs: Long = CONNECT_TIMEOUT_MS) {
        if (remote != null) return
        if (bound) {
            val stale = connection
            bound = false
            connection = null
            if (stale != null) runCatching { appContext.unbindService(stale) }
        }
        val ready = CompletableDeferred<Unit>()
        binding = ready
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = Messenger(service)
                if (!ready.isCompleted) ready.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName?) = onRemoteLost()

            override fun onBindingDied(name: ComponentName?) = onRemoteLost()

            override fun onNullBinding(name: ComponentName?) {
                if (!ready.isCompleted) ready.cancel()
            }
        }
        connection = serviceConnection
        bound = appContext.bindService(
            Intent(appContext, IsolatedEngineService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) throw IllegalStateException("engine process service could not be bound")
        withTimeout(timeoutMs) { ready.await() }
    }

    /** Loads [model] in the engine process; true when it reported Ready. */
    suspend fun load(model: InstalledModel, timeoutMs: Long = LOAD_TIMEOUT_MS): Boolean {
        connect()
        val outcome = CompletableDeferred<Boolean>()
        loadOutcome = outcome
        _status.value = EngineStatus.Loading(model.displayName)
        send(EngineWire.MSG_LOAD, EngineWire.loadRequest(model.path, model.displayName), incoming)
        return withTimeoutOrNull(timeoutMs) { outcome.await() } ?: false
    }

    override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> =
        stream(prompt, history, GenerationOptions())

    override fun stream(
        prompt: String,
        history: List<ChatTurn>,
        options: GenerationOptions,
    ): Flow<EngineChunk> = channelFlow {
        if (remote == null) {
            trySend(EngineChunk.Failed(EngineReasons.NO_MODEL))
            close()
            return@channelFlow
        }
        val end = CompletableDeferred<String?>()
        generationEnd = end
        tokenSink = { piece -> trySend(EngineChunk.Delta(piece)) }
        send(
            EngineWire.MSG_GENERATE,
            EngineWire.generationRequest(prompt, history, options),
            incoming,
        )
        try {
            val failure = withTimeoutOrNull(GENERATION_TIMEOUT_MS) { end.await() }
            if (failure == null && end.isCompleted) {
                trySend(EngineChunk.Completed(emptyList()))
            } else {
                trySend(EngineChunk.Failed(failure ?: EngineReasons.GENERATION_FAILED))
            }
        } finally {
            tokenSink = null
            generationEnd = null
            // Best effort: a new request on the next turn clears the engine's KV anyway.
            send(EngineWire.MSG_STOP)
            close()
        }
    }

    override fun stop() {
        send(EngineWire.MSG_STOP)
    }

    /** Test hook for ADR-4: asks the (debuggable) engine process to die like a native abort would. */
    fun simulateCrash() {
        send(EngineWire.MSG_SIMULATE_CRASH)
    }

    /** Unbinds the engine process; the next [connect] starts a fresh one. */
    fun close() {
        val stale = connection
        connection = null
        bound = false
        remote = null
        tokenSink = null
        generationEnd = null
        loadOutcome = null
        if (stale != null) runCatching { appContext.unbindService(stale) }
        _status.value = EngineStatus.Unloaded
    }

    private fun route(message: Message) {
        when (message.what) {
            EngineWire.MSG_LOADED -> {
                remotePid = message.data.getInt(EngineWire.KEY_PID, -1)
                remoteProcessName = message.data.getString(EngineWire.KEY_PROCESS)
                _status.value = EngineStatus.Ready(displayName)
                loadOutcome?.complete(true)
            }

            EngineWire.MSG_ERROR -> {
                val reason = message.data.getString(EngineWire.KEY_ERROR)
                    ?: EngineReasons.GENERATION_FAILED
                _status.value = EngineStatus.Failed(reason)
                loadOutcome?.complete(false)
                generationEnd?.complete(reason)
            }

            EngineWire.MSG_TOKEN -> tokenSink?.invoke(
                message.data.getString(EngineWire.KEY_DELTA).orEmpty(),
            )

            EngineWire.MSG_DONE -> generationEnd?.complete(null)

            else -> Unit
        }
    }

    private fun onRemoteLost() {
        remote = null
        _status.value = EngineStatus.Failed(EngineReasons.PROCESS_LOST)
        loadOutcome?.complete(false)
        generationEnd?.complete(EngineReasons.PROCESS_LOST)
    }

    private fun send(what: Int, data: Bundle? = null, replyTo: Messenger? = null) {
        val target = remote ?: return
        val message = Message.obtain(null, what)
        message.data = data ?: Bundle()
        if (replyTo != null) message.replyTo = replyTo
        runCatching { target.send(message) }
    }

    companion object {
        const val ID = "llamacpp-isolated"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val LOAD_TIMEOUT_MS = 120_000L
        private const val GENERATION_TIMEOUT_MS = 300_000L
    }
}

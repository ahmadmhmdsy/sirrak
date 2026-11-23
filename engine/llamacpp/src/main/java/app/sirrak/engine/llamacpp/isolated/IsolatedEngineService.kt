package app.sirrak.engine.llamacpp.isolated

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineReasons
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.engine.llamacpp.LlamaCppEngine
import java.io.File
import kotlinx.coroutines.CancellationException as CancelCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ADR-4 spike, server half: llama.cpp lives in `<applicationId>:engine` (declared in this module's
 * manifest) behind a Messenger. A native abort in llama.cpp kills this process only; the UI process
 * stays alive, sees the binder die, shows [EngineReasons.PROCESS_LOST] and can retry into a fresh
 * engine process.
 *
 * The protocol is intentionally small (load, generate, stop, unload) and lives in [EngineWire]. This
 * service is not wired into the production composition yet - it is the feasibility half of ADR-4, and
 * the device test EngineIsolationDeviceTest in the app module is its evidence.
 */
class IsolatedEngineService : Service() {

    private val engine by lazy {
        LlamaCppEngine(
            engineDisplayName = DISPLAY_NAME,
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultContextTokens = DEFAULT_CONTEXT_TOKENS,
            defaultMaxTokens = DEFAULT_MAX_TOKENS,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var generation: Job? = null

    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            // The data is copied before the coroutine is launched: the Looper recycles the Message
            // (and clears its Bundle) as soon as this handler returns, so reading it later is a race.
            EngineWire.MSG_LOAD -> {
                val data = message.data
                val path = data.getString(EngineWire.KEY_MODEL_PATH).orEmpty()
                val name = data.getString(EngineWire.KEY_MODEL_NAME).orEmpty()
                val reply = message.replyTo
                scope.launch { loadModel(path, name, reply) }
            }

            EngineWire.MSG_GENERATE -> {
                val data = message.data
                val prompt = data.getString(EngineWire.KEY_PROMPT).orEmpty()
                val history = EngineWire.decodeHistory(data)
                val options = EngineWire.decodeOptions(data.getBundle(EngineWire.KEY_OPTIONS))
                val reply = message.replyTo
                scope.launch { generate(reply, prompt, history, options) }
            }
            EngineWire.MSG_STOP -> engine.stop()
            EngineWire.MSG_UNLOAD -> engine.unload()
            EngineWire.MSG_SIMULATE_CRASH -> simulateCrashIfDebuggable()
            else -> Log.w(TAG, "unknown message " + message.what)
        }
        true
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        engine.close()
        super.onDestroy()
    }

    private suspend fun loadModel(path: String, name: String, reply: Messenger?) {
        val model = InstalledModel(
            id = name,
            displayName = name,
            path = path,
            sizeBytes = File(path).length(),
            quantization = null,
        )
        engine.load(model)
        val ready = engine.status.value is EngineStatus.Ready
        send(reply, if (ready) EngineWire.MSG_LOADED else EngineWire.MSG_ERROR) {
            putInt(EngineWire.KEY_PID, Process.myPid())
            putString(EngineWire.KEY_PROCESS, processName())
            if (!ready) putString(EngineWire.KEY_ERROR, EngineReasons.LOAD_FAILED)
        }
    }

    private fun generate(
        reply: Messenger?,
        prompt: String,
        history: List<app.sirrak.core.runtime.ChatTurn>,
        options: app.sirrak.core.runtime.GenerationOptions,
    ) {
        if (reply == null) return
        generation?.cancel()
        generation = scope.launch {
            try {
                engine.stream(prompt, history, options).collect { chunk ->
                    when (chunk) {
                        is EngineChunk.Delta -> send(reply, EngineWire.MSG_TOKEN) {
                            putString(EngineWire.KEY_DELTA, chunk.text)
                        }
                        is EngineChunk.Completed -> send(reply, EngineWire.MSG_DONE)
                        is EngineChunk.Failed -> send(reply, EngineWire.MSG_ERROR) {
                            putString(EngineWire.KEY_ERROR, chunk.reason)
                        }
                    }
                }
            } catch (cancellation: CancelCancellation) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.e(TAG, "generation failed in the engine process", failure)
                send(reply, EngineWire.MSG_ERROR) {
                    putString(EngineWire.KEY_ERROR, EngineReasons.GENERATION_FAILED)
                }
            }
        }
    }

    /** Debug-only: a real native abort would SIGABRT this process; the test asks for the same death. */
    private fun simulateCrashIfDebuggable() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "crash simulation is ignored in a release build")
            return
        }
        Log.e(TAG, "simulating a native abort in " + processName() + " (pid=" + Process.myPid() + ")")
        Process.killProcess(Process.myPid())
    }

    private fun send(target: Messenger?, what: Int, fill: Bundle.() -> Unit = {}) {
        if (target == null) return
        val message = Message.obtain(null, what)
        message.data = Bundle().apply(fill)
        runCatching { target.send(message) }
            .onFailure { Log.w(TAG, "could not reply to client", it) }
    }

    private fun processName(): String =
        runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }
            .getOrDefault(applicationContext.packageName + EngineWire.PROCESS_SUFFIX)

    private companion object {
        const val TAG = "SirrakEngineProcess"
        const val DISPLAY_NAME = "llama.cpp isolated"
        const val DEFAULT_SYSTEM_PROMPT = "You are Sirrak, a concise on-device assistant."
        const val DEFAULT_CONTEXT_TOKENS = 2048
        const val DEFAULT_MAX_TOKENS = 96
    }
}

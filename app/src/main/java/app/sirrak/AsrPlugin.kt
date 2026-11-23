package app.sirrak

import android.app.Application
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.speech.AudioRecorder
import app.sirrak.core.speech.DictationController
import app.sirrak.core.speech.DictationSettings
import app.sirrak.core.speech.SpeechModelStore
import app.sirrak.core.speech.TranscriptionEngine
import app.sirrak.engine.whisper.SherpaWhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * M10 speech composition: the host picks the concrete engine, recorder and stores, and provides them
 * under their seams. No feature module names sherpa-onnx or AudioRecord.
 *
 * The controller gets its own scope so a transcription in flight survives a recomposition and is
 * cancelled only when the whole graph is disposed (reverse order, before the engine's native release).
 */
class AsrPlugin(private val application: Application) : Plugin {

    override val id: String = "asr"

    override fun install(scope: PluginScope) {
        val engine = SherpaWhisperEngine()
        val store = FileSpeechModelStore(application)
        val settings = SharedPrefsDictationSettings(application)
        val recorder = PcmAudioRecorder { AndroidPcmSource(application) }
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DictationController(
            recorder = recorder,
            engine = engine,
            models = store,
            settings = settings,
            scope = controllerScope,
        )

        scope.provide(TranscriptionEngine::class.java, engine)
        scope.provide(SpeechModelStore::class.java, store)
        scope.provide(DictationSettings::class.java, settings)
        scope.provide(AudioRecorder::class.java, recorder)
        scope.provide(DictationController::class.java, controller)

        // Reverse-order disposal: stop the coroutine scope first, then release the native recognizer.
        scope.effect {
            controllerScope.cancel()
            runCatching { engine.unload() }
        }
    }
}

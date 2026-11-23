package app.sirrak

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.runtime.ComposerTextRequests
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.Slots
import app.sirrak.core.speech.DictationController
import app.sirrak.core.speech.DictationSettings
import app.sirrak.core.speech.DictationState
import app.sirrak.core.speech.SpeechModelStore
import app.sirrak.core.speech.TranscriptionEngine
import app.sirrak.feature.voice.DictationMicButton
import app.sirrak.feature.voice.DictationStatusRow
import app.sirrak.feature.voice.VoiceSettingsSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * M10 voice feature composition: contributes the mic to the composer, the dictation status strip above
 * it, and the Settings card. It owns one small bridge — the controller's transcript stream is appended
 * to [ComposerTextRequests] — so the chat feature never imports the voice feature and the composer
 * stays the single owner of its text field.
 */
class VoicePlugin : Plugin {

    override val id: String = "voice"

    override val requires: List<Class<*>> = listOf(
        DictationController::class.java,
        TranscriptionEngine::class.java,
        SpeechModelStore::class.java,
        DictationSettings::class.java,
        ComposerTextRequests::class.java,
    )

    override fun install(scope: PluginScope) {
        val controller = scope.service(DictationController::class.java)
        val engine = scope.service(TranscriptionEngine::class.java)
        val models = scope.service(SpeechModelStore::class.java)
        val settings = scope.service(DictationSettings::class.java)
        val requests = scope.service(ComposerTextRequests::class.java)

        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        bridgeScope.launch {
            controller.transcript.collect { text -> requests.append(text) }
        }
        scope.effect { bridgeScope.cancel() }

        scope.slotList(Slots.ChatComposerTrailing, order = 20) {
            val mode by settings.mode.collectAsStateWithLifecycle()
            DictationMicButton(controller = controller, mode = mode)
        }
        scope.slotList(Slots.ChatComposerAbove, order = 10) {
            // An idle dictation has no row: the composer keeps its exact height until the mic is used.
            val state by controller.state.collectAsStateWithLifecycle()
            if (state !is DictationState.Idle) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    DictationStatusRow(controller = controller)
                }
            }
        }
        scope.slotList(Slots.SettingsSections, order = 30) {
            VoiceSettingsSection(engine = engine, models = models, settings = settings)
        }
    }
}

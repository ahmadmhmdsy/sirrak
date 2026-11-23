package app.sirrak

import android.content.Context
import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.GenerationSettingsProvider
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelIdentity
import app.sirrak.core.runtime.ModelSettingsStore
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.PresetRepository
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.Slots
import app.sirrak.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Real engine plugin: provides llama.cpp behind [InferenceEngine], follows the selected model, and
 * contributes the model chip and the engine/model management group in settings.
 *
 * Selecting a model loads it; deleting or deselecting unloads it. Loading is asynchronous, so the
 * chat surface can render "loading" instead of blocking the first message.
 */
class LlamaCppPlugin(
    private val context: Context,
    private val config: AppConfig,
) : Plugin {
    override val id: String = "engine"

    override val requires: List<Class<*>> = listOf(
        ModelStore::class.java,
        ContentRepository::class.java,
        ModelSettingsStore::class.java,
        ModelIdentity::class.java,
        PresetRepository::class.java,
        SettingsService::class.java,
    )

    override fun install(scope: PluginScope) {
        val store = scope.service(ModelStore::class.java)
        val content = scope.service(ContentRepository::class.java)
        val settingsService = scope.service(SettingsService::class.java)
        val fallbackSystemPrompt = context.getString(R.string.engine_system_prompt)
        val engine = LlamaCppEngine(
            engineDisplayName = context.getString(R.string.engine_llamacpp_name),
            defaultSystemPrompt = config.engine.systemPrompt.ifBlank { fallbackSystemPrompt },
            defaultContextTokens = config.engine.contextTokens,
            defaultMaxTokens = config.engine.maxTokens,
            defaultTemperature = config.engine.temperature,
            defaultTopP = config.engine.topP,
            defaultTopK = config.engine.topK,
        )
        scope.provide(InferenceEngine::class.java, engine)

        // Model control is host state, not chat state: the Settings > Model UI and the send path both
        // read the same resolved answer for params, template and system prompt.
        val modelControl = AppModelControlService(
            config = config,
            engine = engine,
            modelStore = store,
            settingsStore = scope.service(ModelSettingsStore::class.java),
            identity = scope.service(ModelIdentity::class.java),
            presets = scope.service(PresetRepository::class.java),
            settings = settingsService,
            fallbackPrompt = fallbackSystemPrompt,
        )
        scope.provide(GenerationSettingsProvider::class.java, modelControl)
        scope.provide(AppModelControlService::class.java, modelControl)

        // One collector owns the model lifecycle: the settings UI only mutates the selection.
        val scopeForModels = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val loadJob = scopeForModels.launch {
            store.selected.collect { model ->
                if (model == null) engine.unload() else engine.load(model)
            }
        }
        scope.effect {
            loadJob.cancel()
            engine.close()
            scopeForModels.cancel()
        }

        scope.slotList(Slots.ChatTopBarTrailing, order = 0) {
            EngineModelChip(engine = engine, models = content.models())
        }
        scope.slotList(Slots.SettingsSections, order = 10) {
            LlamaEngineSettingsSection(
                engine = engine,
                store = store,
                modelControl = modelControl,
                settings = settingsService,
            )
        }
    }
}
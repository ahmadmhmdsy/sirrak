package app.sirrak

import android.content.Context
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.GenerationSettingsProvider
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.Slots

/**
 * Demo engine plugin (config: engine.provider = "demo"): streams authored answers with no model file.
 * Useful for screenshots and for checking the UI without loading a 1 GB file; never the shipped default.
 */
class DemoEnginePlugin(private val context: Context) : Plugin {
    override val id: String = "engine"

    override val requires: List<Class<*>> = listOf(
        ContentRepository::class.java,
        ModelStore::class.java,
        AppNavigator::class.java,
    )

    override fun install(scope: PluginScope) {
        val content = scope.service(ContentRepository::class.java)
        val store = scope.service(ModelStore::class.java)
        val navigator = scope.service(AppNavigator::class.java)
        val engine = DemoInferenceEngine(content, context.getString(R.string.engine_demo_name))
        scope.provide(InferenceEngine::class.java, engine)
        // No real model control behind the demo engine: chat uses the engine's own defaults.
        scope.provide(GenerationSettingsProvider::class.java, GenerationSettingsProvider.Default)
        scope.slotList(Slots.ChatTopBarTrailing, order = 0) {
            EngineModelChip(engine = engine, store = store, navigator = navigator)
        }
        // P4: no Settings group for the demo engine; the chip is its only surface.
    }
}
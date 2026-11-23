package app.sirrak

import app.sirrak.core.design.FontScales
import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.AppLanguage
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.DefaultSource
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineConfig
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOverrides
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.LocalisedText
import app.sirrak.core.runtime.ModelFacts
import app.sirrak.core.runtime.ModelPreset
import app.sirrak.core.runtime.ModelPresetsFile
import app.sirrak.core.runtime.ModelSettings
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.PresetRepository
import app.sirrak.core.runtime.PromptPreset
import app.sirrak.core.runtime.PromptPresetsFile
import app.sirrak.core.runtime.PromptSource
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.TemplateChoice
import app.sirrak.core.runtime.TemplateSource
import app.sirrak.core.runtime.ThemeMode
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M5 orchestration: precedence, prompt/template resolution, persistence and reset. The pure resolver
 * is tested in :core:runtime; here the app-level service is driven with fakes so the wiring the
 * Settings > Model UI and the chat send path depend on is pinned end to end.
 */
class AppModelControlServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSettings : SettingsService {
        override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        override val themePackId = MutableStateFlow("girih_ink")
        override val fontScaleId = MutableStateFlow(FontScales.DEFAULT_ID)
        override val language = MutableStateFlow(AppLanguage.ARABIC)
        override val systemPrompt = MutableStateFlow<String?>(null)
        override val modelAdvanced = MutableStateFlow(false)
        override fun setThemeMode(mode: ThemeMode) { themeMode.value = mode }
        override fun setThemePack(packId: String) { themePackId.value = packId }
        override fun setFontScale(scaleId: String) { fontScaleId.value = scaleId }
        override fun setLanguage(language: AppLanguage) { this.language.value = language }
        override fun setSystemPrompt(prompt: String?) { systemPrompt.value = prompt }
        override fun setModelAdvanced(enabled: Boolean) { modelAdvanced.value = enabled }
    }

    private class FakeModelStore : ModelStore {
        override val models = MutableStateFlow<List<InstalledModel>>(emptyList())
        override val selected = MutableStateFlow<InstalledModel?>(null)
        override suspend fun import(displayName: String, open: () -> InputStream): Result<InstalledModel> =
            Result.failure(UnsupportedOperationException())
        override fun select(model: InstalledModel) { selected.value = model }
        override fun delete(model: InstalledModel) { models.value = models.value - model }
    }

    private class FakeEngine : InferenceEngine {
        override val id = "fake"
        override val displayName = "Fake"
        override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.Unloaded)
        override var modelFacts: ModelFacts? = null
        override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flowOf()
    }

    private class FakePresets(
        override val modelPresets: ModelPresetsFile,
        override val promptPresets: PromptPresetsFile,
    ) : PresetRepository

    // Lazily built: TemporaryFolder creates tmp.root only after the test instance is constructed.
    private val model: InstalledModel by lazy {
        InstalledModel("qwen.gguf", "Qwen", File(tmp.root, "qwen.gguf").absolutePath, 10, "Q4")
    }

    private val qwenPreset = ModelPreset(
        id = "qwen",
        families = listOf("qwen2"),
        params = app.sirrak.core.runtime.GenerationOverrides(temperature = 0.65f, topK = 30),
    )

    private val teacherPreset = PromptPreset(
        id = "teacher",
        name = LocalisedText(ar = "معلّم", en = "Teacher"),
        description = LocalisedText(ar = "شرح", en = "Explain"),
        systemPrompt = LocalisedText(ar = "اشرح بلطف", en = "Explain kindly"),
        tags = listOf("learning"),
    )

    private val presets = FakePresets(
        modelPresets = ModelPresetsFile(presets = listOf(qwenPreset)),
        promptPresets = PromptPresetsFile(presets = listOf(teacherPreset)),
    )

    private fun service(
        engine: FakeEngine = FakeEngine(),
        config: AppConfig = AppConfig(),
        settings: FakeSettings = FakeSettings(),
        store: ModelStore = FakeModelStore().also { it.select(model) },
        settingsStore: FileModelSettingsStore = FileModelSettingsStore(tmp.root),
        fallbackPrompt: String = "fallback prompt",
    ): AppModelControlService = AppModelControlService(
        config = config,
        engine = engine,
        modelStore = store,
        settingsStore = settingsStore,
        identity = object : app.sirrak.core.runtime.ModelIdentity {
            override fun keyFor(model: InstalledModel): String = "key:" + model.path
        },
        presets = presets,
        settings = settings,
        fallbackPrompt = fallbackPrompt,
    )

    @Test
    fun `code defaults win when nothing else declares a value`() {
        val service = service()

        assertEquals(GenerationParams.DEFAULT, service.effectiveParams())
        assertEquals(DefaultSource.CODE, service.defaultsSource())
        assertEquals(TemplateSource.FALLBACK, service.resolvedTemplate().source)
    }

    @Test
    fun `gguf metadata is the first base layer`() {
        val engine = FakeEngine().apply {
            modelFacts = ModelFacts(
                architecture = "qwen2",
                samplingMetadata = mapOf("general.sampling.temp" to "0.9", "general.sampling.top_k" to "10"),
            )
        }
        val service = service(engine = engine)

        assertEquals(0.9f, service.effectiveParams().temperature)
        assertEquals(10, service.effectiveParams().topK)
        assertEquals(DefaultSource.MODEL, service.defaultsSource())
    }

    @Test
    fun `family preset applies under gguf metadata`() {
        val engine = FakeEngine().apply { modelFacts = ModelFacts(architecture = "qwen2") }
        val service = service(engine = engine)

        assertEquals(DefaultSource.PRESET, service.defaultsSource())
        assertEquals(0.65f, service.effectiveParams().temperature)
        assertEquals(30, service.effectiveParams().topK)
    }

    @Test
    fun `user touches override only the touched fields`() {
        val engine = FakeEngine().apply {
            modelFacts = ModelFacts(
                architecture = "qwen2",
                samplingMetadata = mapOf("general.sampling.temp" to "0.9", "general.sampling.min_p" to "0.01"),
            )
        }
        val settingsStore = FileModelSettingsStore(tmp.root)
        settingsStore.put(
            ModelSettings(
                modelKey = "key:" + model.path,
                params = GenerationParams(temperature = 0.5f, minP = 0.9f),
                touchedParams = listOf("temperature"),
            ),
        )
        val service = service(engine = engine, settingsStore = settingsStore)

        assertEquals(0.5f, service.effectiveParams().temperature)
        assertEquals(0.01f, service.effectiveParams().minP)
        assertEquals(DefaultSource.USER, service.defaultsSource())
    }

    @Test
    fun `saved parameters persist across service instances`() {
        val service = service()
        service.saveParams(GenerationParams(temperature = 0.33f, topK = 11), setOf("temperature", "topK"))

        val reopened = service(settingsStore = FileModelSettingsStore(tmp.root))

        assertEquals(0.33f, reopened.effectiveParams().temperature)
        assertEquals(11, reopened.effectiveParams().topK)
        assertEquals(DefaultSource.USER, reopened.defaultsSource())
    }

    @Test
    fun `reset drops every override for the model`() {
        val engine = FakeEngine().apply {
            modelFacts = ModelFacts(
                samplingMetadata = mapOf("general.sampling.temp" to "0.8"),
                templateSource = TemplateSource.MODEL,
            )
        }
        val service = service(engine = engine)
        service.saveParams(GenerationParams(temperature = 0.2f), setOf("temperature"))
        service.setTemplate(TemplateChoice.Builtin("qwen2"))
        service.setModelSystemPrompt("per model")

        service.resetModelSettings()

        assertNull(service.modelSettingsFor("key:" + model.path))
        assertEquals(0.8f, service.effectiveParams().temperature)
        assertEquals(TemplateSource.MODEL, service.resolvedTemplate().source)
    }

    @Test
    fun `template resolves from forced builtin and from model metadata`() {
        val withTemplate = FakeEngine().apply {
            modelFacts = ModelFacts(templateSource = TemplateSource.MODEL, templateFirstLine = "<|im_start|>")
        }
        val modelService = service(engine = withTemplate)
        assertEquals(TemplateSource.MODEL, modelService.resolvedTemplate().source)
        assertNull(modelService.resolvedTemplate().templateName)

        modelService.setTemplate(TemplateChoice.Builtin("chatml"))
        val forced = modelService.resolvedTemplate()
        assertEquals(TemplateSource.BUILTIN, forced.source)
        assertEquals("chatml", forced.templateName)
    }

    @Test
    fun `system prompt follows conversation then model then global then app`() {
        val settings = FakeSettings()
        val config = AppConfig(engine = EngineConfig(systemPrompt = "app default"))
        val service = service(config = config, settings = settings)
        assertEquals("app default", service.resolvedPrompt().text)
        assertEquals(PromptSource.APP_DEFAULT, service.resolvedPrompt().source)

        service.setGlobalSystemPrompt("global")
        assertEquals("global", service.resolvedPrompt().text)
        assertEquals(PromptSource.GLOBAL, service.resolvedPrompt().source)

        service.setModelSystemPrompt("per model")
        assertEquals("per model", service.resolvedPrompt().text)
        assertEquals(PromptSource.MODEL, service.resolvedPrompt().source)
    }

    @Test
    fun `resource prompt is the last fallback`() {
        val service = service(fallbackPrompt = "resource prompt")

        assertEquals("resource prompt", service.resolvedPrompt().text)
        assertEquals(PromptSource.RESOURCE, service.resolvedPrompt().source)
    }

    @Test
    fun `prompt preset applies the localised prompt and rejects unknown ids`() {
        val settings = FakeSettings()
        val service = service(settings = settings)

        assertTrue(service.applyPromptPreset("teacher"))
        assertEquals("اشرح بلطف", settings.systemPrompt.value)

        settings.language.value = AppLanguage.ENGLISH
        assertTrue(service.applyPromptPreset("teacher"))
        assertEquals("Explain kindly", settings.systemPrompt.value)

        assertFalse(service.applyPromptPreset("ghost"))
    }

    @Test
    fun `resetParam drops one field and keeps the other touched fields`() {
        val engine = FakeEngine().apply { modelFacts = ModelFacts(architecture = "qwen2") }
        val service = service(engine = engine)
        service.saveParams(
            GenerationParams(temperature = 0.3f, topK = 7, seed = 42),
            setOf("temperature", "topK", "seed"),
        )
        assertEquals(0.3f, service.effectiveParams().temperature)
        assertEquals(7, service.effectiveParams().topK)

        service.resetParam("temperature")

        val settings = service.modelSettingsFor("key:" + model.path)!!
        assertEquals(setOf("topK", "seed"), settings.touchedParams.toSet())
        assertEquals(0.65f, service.effectiveParams().temperature) // family preset again
        assertEquals(7, service.effectiveParams().topK)
        assertEquals(42, service.effectiveParams().seed)
    }

    @Test
    fun `resetParam of the last override removes the per-model settings entirely`() {
        val service = service()
        service.saveParams(GenerationParams(seed = 5), setOf("seed"))

        service.resetParam("seed")

        // The record stays (so prompt/template choices survive) but no field is overridden any
        // more, and the lower layers are authoritative again.
        val settings = service.modelSettingsFor("key:" + model.path)!!
        assertEquals(emptyList<String>(), settings.touchedParams)
        assertNull(settings.params)
        assertEquals(DefaultSource.CODE, service.defaultsSource())
    }

    @Test
    fun `resetTemplate returns to the model template without dropping parameters`() {
        val engine = FakeEngine().apply {
            modelFacts = ModelFacts(templateSource = TemplateSource.MODEL)
        }
        val service = service(engine = engine)
        service.setTemplate(TemplateChoice.Builtin("chatml"))
        service.saveParams(GenerationParams(seed = 9), setOf("seed"))

        service.resetTemplate()

        assertEquals(TemplateSource.MODEL, service.resolvedTemplate().source)
        assertEquals(9, service.effectiveParams().seed)
        assertTrue(service.modelSettingsFor("key:" + model.path)!!.touchedParams.contains("seed"))
    }

    @Test
    fun `options carry the resolved system prompt for the send path`() {
        val settings = FakeSettings()
        val config = AppConfig(engine = EngineConfig(systemPrompt = "app default"))
        val service = service(config = config, settings = settings)

        assertEquals("app default", service.options().systemPrompt)

        service.setGlobalSystemPrompt("global prompt")

        assertEquals("global prompt", service.options().systemPrompt)
    }


    @Test
    fun `conversation overrides and prompt win over every base layer`() {
        val engine = FakeEngine().apply {
            modelFacts = ModelFacts(
                architecture = "qwen2",
                samplingMetadata = mapOf("general.sampling.temp" to "0.9"),
            )
        }
        val service = service(engine = engine)
        service.setModelSystemPrompt("per model")
        service.saveParams(GenerationParams(temperature = 0.4f), setOf("temperature"))

        val options = service.options(
            conversationOverrides = GenerationOverrides(temperature = 0.15f, stop = listOf("END")),
            conversationPrompt = "chat prompt",
        )

        assertEquals(0.15f, options.params.temperature)
        assertEquals(listOf("END"), options.params.stop)
        assertEquals("chat prompt", options.systemPrompt)
        // The per-model layer is untouched: the conversation layer is resolved, not written.
        assertEquals(0.4f, service.effectiveParams().temperature)
        assertEquals("per model", service.options().systemPrompt)
    }

    @Test
    fun `preview and builtin templates stay empty for engines without a chat template`() {
        val service = service()

        assertNull(service.previewPrompt("sample"))
        assertEquals(emptyList<String>(), service.builtinTemplates())
    }

    @Test
    fun `generation options carry the resolved params and template choice`() {
        val engine = FakeEngine().apply {
            modelFacts = ModelFacts(samplingMetadata = mapOf("general.sampling.temp" to "0.9"))
        }
        val service = service(engine = engine)

        val options = service.options()

        assertEquals(0.9f, options.params.temperature)
        assertEquals(TemplateChoice.ModelDefault, options.template)
    }
}
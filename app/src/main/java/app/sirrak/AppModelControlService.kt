package app.sirrak

import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.DefaultSource
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationOverrides
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.GenerationSettingsProvider
import app.sirrak.core.runtime.GgufSampling
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.LocalisedText
import app.sirrak.core.runtime.ModelControlResolver
import app.sirrak.core.runtime.ModelFacts
import app.sirrak.core.runtime.ModelIdentity
import app.sirrak.core.runtime.ModelPreset
import app.sirrak.core.runtime.ModelPresets
import app.sirrak.core.runtime.ModelSettings
import app.sirrak.core.runtime.ModelSettingsStore
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.PresetRepository
import app.sirrak.core.runtime.PromptPreset
import app.sirrak.core.runtime.PromptSource
import app.sirrak.core.runtime.ResolvedPrompt
import app.sirrak.core.runtime.ResolvedTemplate
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.TemplateChoice
import app.sirrak.core.runtime.generationOverrides
import app.sirrak.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.flow.StateFlow

/**
 * App-level model control: resolves the five generation layers, the chat template and the system
 * prompt for the selected model, and persists per-model edits by fingerprint.
 *
 * The pure precedence rules live in :core:runtime (unit tested there); this class only wires the
 * selected model, the engine's GGUF facts, the settings store and the shipped content presets. Both
 * the Settings > Model UI and the chat send path read through here, so there is exactly one answer to
 * "what will be sent next".
 */
class AppModelControlService(
    private val config: AppConfig,
    private val engine: InferenceEngine,
    private val modelStore: ModelStore,
    private val settingsStore: ModelSettingsStore,
    private val identity: ModelIdentity,
    private val presets: PresetRepository,
    private val settings: SettingsService,
    /** Localised resource prompt used when nothing above it declares a system prompt. */
    private val fallbackPrompt: String,
) : GenerationSettingsProvider {

    /** Selected model, observable: the Settings > Model screen follows imports and selection. */
    val selectedModel: StateFlow<InstalledModel?> get() = modelStore.selected

    /** Every per-model override, observable: edits made anywhere re-render the model screen. */
    val modelSettingsState: StateFlow<Map<String, ModelSettings>> get() = settingsStore.settings

    fun currentModel(): InstalledModel? = modelStore.selected.value

    fun currentKey(): String? = currentModel()?.let(identity::keyFor)

    /** Fingerprint key of any installed model, for pages that describe a model that is not selected. */
    fun keyFor(model: InstalledModel): String = identity.keyFor(model)

    fun modelFacts(): ModelFacts? = engine.modelFacts

    fun modelSettingsFor(modelKey: String? = currentKey()): ModelSettings? =
        modelKey?.let(settingsStore::get)

    fun modelPresets(): app.sirrak.core.runtime.ModelPresetsFile = presets.modelPresets

    fun promptPresets(): app.sirrak.core.runtime.PromptPresetsFile = presets.promptPresets

    /**
     * Family preset for [modelKey]. The family is inferred from the loaded GGUF's architecture, so it
     * only applies to the model that is actually loaded; asking about another key returns null rather
     * than silently answering for the wrong model.
     */
    fun familyPreset(modelKey: String? = currentKey()): ModelPreset? {
        if (modelKey == null || modelKey != currentKey()) return null
        return ModelPresets.forArchitecture(presets.modelPresets, modelFacts()?.architecture)
    }

    /** The five layers that feed parameter resolution, gathered once so every reader agrees. */
    private fun layers(modelKey: String?): Layers {
        val user = modelSettingsFor(modelKey)
        return Layers(
            appConfig = config.engine.generationOverrides(),
            familyPreset = familyPreset(modelKey)?.params,
            modelMetadata = modelFacts()?.samplingMetadata?.let(GgufSampling::fromMetadata)
                ?: GenerationOverrides(),
            userParams = user?.params,
            userTouched = user?.touchedParams?.toSet().orEmpty(),
        )
    }

    /**
     * The effective parameters for [modelKey] after every layer, clamped into native-safe ranges.
     * [conversation] is the M6 per-chat layer (precedence 6 in doc 19 §2).
     */
    fun effectiveParams(
        modelKey: String? = currentKey(),
        conversation: GenerationOverrides? = null,
    ): GenerationParams {
        val layers = layers(modelKey)
        return ModelControlResolver.resolveParams(
            code = GenerationParams.DEFAULT,
            appConfig = layers.appConfig,
            familyPreset = layers.familyPreset,
            modelMetadata = layers.modelMetadata,
            userParams = layers.userParams,
            userTouched = layers.userTouched,
            conversation = conversation,
        )
    }

    /** The strongest base layer, for the "defaults from: …" row in Settings > Model. */
    fun defaultsSource(modelKey: String? = currentKey()): DefaultSource {
        val layers = layers(modelKey)
        return ModelControlResolver.paramsSource(
            appConfig = layers.appConfig,
            familyPreset = layers.familyPreset,
            modelMetadata = layers.modelMetadata,
            userParams = layers.userParams,
            userTouched = layers.userTouched,
        )
    }

    /** The context window stored for [modelKey], or null when it follows the app default. */
    fun storedContextTokens(modelKey: String? = currentKey()): Int? =
        modelKey?.let { settingsStore.get(it)?.contextTokens }

    /** The trained window of the loaded model, known only after a successful load. */
    fun trainedContextTokens(modelKey: String? = currentKey()): Int? =
        if (modelKey != null && modelKey == currentKey()) modelFacts()?.nCtxTrain?.takeIf { it > 0 } else null

    /**
     * The window the next load will request: stored choice or app default, clamped to the trained
     * window once the header has answered. Before a load the trained value is unknown, so the native
     * layer is the second clamp — the first load can only be corrected after the facts arrive.
     */
    fun requestedContextTokens(modelKey: String? = currentKey()): Int {
        val requested = storedContextTokens(modelKey) ?: config.engine.contextTokens
        val trained = trainedContextTokens(modelKey) ?: return requested
        return requested.coerceAtMost(trained)
    }

    /**
     * The context sizes the model page offers: the standard ladder up to the trained window, plus the
     * effective value even if it is unusual, sorted so the row order never jumps between recompositions.
     */
    fun contextChoices(modelKey: String? = currentKey()): List<Int> {
        val trained = trainedContextTokens(modelKey)
        val upper = trained ?: config.engine.contextTokens
        val ladder = listOf(512, 1024, 2048, 4096, 8192, 16384).filter { it <= upper }
        return (ladder + requestedContextTokens(modelKey)).distinct().sorted()
    }

    /** Stores the chosen window; null returns to the app default. Takes effect on the next load. */
    fun setContextTokens(tokens: Int?) {
        val key = currentKey() ?: return
        val existing = settingsStore.get(key) ?: ModelSettings(modelKey = key)
        settingsStore.put(
            existing.copy(
                contextTokens = tokens?.takeIf { it > 0 },
                userModifiedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun resolvedTemplate(modelKey: String? = currentKey()): ResolvedTemplate {
        val stored = modelSettingsFor(modelKey)?.templateChoice ?: TemplateChoice.ModelDefault
        // A stored built-in that this engine no longer offers (old build, removed template) falls
        // back to the model template instead of reporting a template that cannot be applied.
        val known = builtinTemplates()
        val choice = if (stored is TemplateChoice.Builtin && known.isNotEmpty() && stored.name !in known) {
            TemplateChoice.ModelDefault
        } else {
            stored
        }
        return ModelControlResolver.resolveTemplate(
            choice = choice,
            hasModelTemplate = modelFacts()?.hasModelTemplate == true,
        )
    }

    fun resolvedPrompt(
        modelKey: String? = currentKey(),
        conversation: String? = null,
    ): ResolvedPrompt =
        ModelControlResolver.resolveSystemPrompt(
            conversation = conversation,
            perModel = modelSettingsFor(modelKey)?.systemPrompt,
            global = settings.systemPrompt.value,
            appDefault = config.engine.systemPrompt,
            resourceFallback = fallbackPrompt,
        )

    override fun options(): GenerationOptions = GenerationOptions(
        params = effectiveParams(),
        template = resolvedTemplate().choice,
        systemPrompt = resolvedPrompt().text,
    )

    /** M6: the conversation's sparse overrides and prompt reach the same resolver as every layer. */
    override fun options(
        conversationOverrides: GenerationOverrides?,
        conversationPrompt: String?,
    ): GenerationOptions = GenerationOptions(
        params = effectiveParams(conversation = conversationOverrides),
        template = resolvedTemplate().choice,
        systemPrompt = resolvedPrompt(conversation = conversationPrompt).text,
    )

    /** Persists a parameter snapshot; only fields in [touched] override lower layers. */
    fun saveParams(params: GenerationParams, touched: Set<String>) {
        val key = currentKey() ?: return
        if (touched.isEmpty()) return
        val existing = settingsStore.get(key)
        settingsStore.put(
            (existing ?: ModelSettings(modelKey = key)).copy(
                params = params.clamped(),
                touchedParams = (existing?.touchedParams.orEmpty() + touched).distinct(),
                userModifiedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Forgets one parameter override, restoring the lower layers for exactly that field. */
    fun resetParam(field: String) {
        val key = currentKey() ?: return
        val existing = settingsStore.get(key) ?: return
        val touched = existing.touchedParams - field
        settingsStore.put(
            existing.copy(
                params = if (touched.isEmpty()) null else existing.params,
                touchedParams = touched,
                userModifiedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Returns to the model's own chat template. */
    fun resetTemplate(): Boolean = setTemplate(TemplateChoice.ModelDefault)

    /** Built-in template names from the engine; empty when the engine is not llama.cpp. */
    fun builtinTemplates(): List<String> = (engine as? LlamaCppEngine)?.builtinTemplates().orEmpty()

    /**
     * Renders what the next request would send, for the template preview. The caller supplies the
     * sample prompt so it can be a localised string resource rather than a hardcoded constant. Null
     * when no model is loaded or the template cannot be applied.
     */
    fun previewPrompt(sample: String): String? = (engine as? LlamaCppEngine)?.previewPrompt(
        systemPrompt = resolvedPrompt().text,
        prompt = sample,
        choice = resolvedTemplate().choice,
    )

    /** Forgets only the parameter overrides, keeping prompt/template choices. */
    fun resetParams() {
        val key = currentKey() ?: return
        val existing = settingsStore.get(key) ?: return
        settingsStore.put(existing.copy(params = null, touchedParams = emptyList(), userModifiedAt = System.currentTimeMillis()))
    }

    /** Forgets every override for the selected model. */
    fun resetModelSettings() {
        currentKey()?.let(settingsStore::reset)
    }

    /**
     * Forces a built-in llama.cpp template, or returns to the model's own template. A stored name
     * that the current engine does not report is rejected, so a stale setting can never shadow the
     * model template with something that cannot be applied.
     */
    fun setTemplate(choice: TemplateChoice): Boolean {
        val key = currentKey() ?: return false
        val known = builtinTemplates()
        if (choice is TemplateChoice.Builtin && known.isNotEmpty() && choice.name !in known) return false
        val existing = settingsStore.get(key) ?: ModelSettings(modelKey = key)
        settingsStore.put(existing.copy(template = choice.storedValue(), userModifiedAt = System.currentTimeMillis()))
        return true
    }

    /** Per-model system prompt; blank/null returns to the global/app default. */
    fun setModelSystemPrompt(prompt: String?) {
        val key = currentKey() ?: return
        val existing = settingsStore.get(key) ?: ModelSettings(modelKey = key)
        settingsStore.put(
            existing.copy(
                systemPrompt = prompt?.trim()?.takeIf { it.isNotBlank() },
                userModifiedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun setGlobalSystemPrompt(prompt: String?) {
        settings.setSystemPrompt(prompt?.trim()?.takeIf { it.isNotBlank() })
    }

    /**
     * Applies a shipped prompt preset in the current UI language. The preset's system prompt becomes
     * the global prompt, any per-model prompt is cleared (otherwise it would shadow the preset), and
     * the preset's sparse parameter layer is merged into the selected model's touched overrides.
     * Unknown ids are ignored.
     */
    fun applyPromptPreset(presetId: String): Boolean {
        val preset = presets.promptPresets.presets.firstOrNull { it.id == presetId } ?: return false
        settings.setSystemPrompt(preset.systemPrompt.inLanguage(settings.language.value))
        currentKey()?.let { key ->
            val existing = settingsStore.get(key) ?: ModelSettings(modelKey = key)
            val merged = preset.params?.let { params ->
                params.applyTo(existing.params ?: effectiveParams(key))
            }
            val touched = preset.params?.touchedFields().orEmpty()
            settingsStore.put(
                existing.copy(
                    systemPrompt = null,
                    params = merged ?: existing.params,
                    touchedParams = (existing.touchedParams + touched).distinct(),
                    userModifiedAt = System.currentTimeMillis(),
                ),
            )
        }
        return true
    }

    fun preset(id: String): PromptPreset? = presets.promptPresets.presets.firstOrNull { it.id == id }

    fun localised(text: LocalisedText): String = text.inLanguage(settings.language.value)

    /** Snapshot of the parameter layers, so resolution and source reporting cannot drift apart. */
    private data class Layers(
        val appConfig: GenerationOverrides,
        val familyPreset: GenerationOverrides?,
        val modelMetadata: GenerationOverrides,
        val userParams: GenerationParams?,
        val userTouched: Set<String>,
    )
}
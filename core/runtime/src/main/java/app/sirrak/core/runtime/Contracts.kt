package app.sirrak.core.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { LIGHT, DARK, SYSTEM }

enum class AppLanguage(val tag: String) {
    ARABIC("ar"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: ARABIC
    }
}

enum class Role { USER, ASSISTANT }

/**
 * One transcript message. [attachments] carries the M7 attachment metadata for this turn (never the
 * bytes); the engine reads the rendered content through [AttachmentPrompt], while the transcript and
 * the backup document keep the durable description. [citations] is the M8 grounded-answer provenance:
 * assistant turns carry the numbered sources the model cited, or - when a small model omits the
 * inline [n] markers - the retrieved sources the answer was grounded on, snapshotted at answer time.
 * Defaults keep M3-M7 data and fakes source-compatible.
 */
data class ChatTurn(
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val citations: List<Citation> = emptyList(),
)

data class SourceRef(val title: String, val note: String? = null)

data class Suggestion(val prompt: String, val gloss: String)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val parameterLabel: String,
    val quant: String,
    val sizeLabel: String,
    val ramLabel: String,
    val status: String,
)

data class DemoReply(val lines: List<String>, val sources: List<SourceRef>)

sealed interface EngineChunk {
    data class Delta(val text: String) : EngineChunk
    data class Completed(val sources: List<SourceRef>) : EngineChunk
    data class Failed(val reason: String) : EngineChunk
}

/** The one seam every real engine (llama.cpp, LiteRT-LM) will implement behind. */
interface InferenceEngine {
    val id: String
    val displayName: String

    /** Observable engine lifecycle; the chat surface and the engine chip both read this. */
    val status: StateFlow<EngineStatus>

    /** GGUF facts of the loaded model, or null when no model is loaded (the demo engine). */
    val modelFacts: ModelFacts? get() = null

    /**
     * What this engine can consume today. The composer's attachment gate reads it, so a text-only
     * engine honestly disables documents/images/audio instead of accepting a file it cannot read
     * (M7). Engines that gain a capability (mmproj in M9) override this without touching the UI.
     */
    val capabilities: EngineCapabilities get() = EngineCapabilities.TextOnly

    fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk>

    /**
     * Generation with the resolved parameter/template layers. Engines that cannot apply options fall
     * back to the simple overload, so adding an option never breaks an engine implementation.
     */
    fun stream(
        prompt: String,
        history: List<ChatTurn>,
        options: GenerationOptions,
    ): Flow<EngineChunk> = stream(prompt, history)

    /**
     * M9: generation with local images attached to the current question. Engines without vision fall
     * back to the text path, so the caller never needs an engine-type check; whether the composer
     * offers images at all is decided by [capabilities].
     */
    fun streamWithImages(
        prompt: String,
        history: List<ChatTurn>,
        images: List<ImageInput>,
        options: GenerationOptions,
    ): Flow<EngineChunk> = stream(prompt, history, options)

    /** Best-effort cooperative cancellation; generation may already have finished. */
    fun stop() {}
}

interface SettingsService {
    val themeMode: StateFlow<ThemeMode>

    /**
     * Persisted theme-pack id. A string, not an enum, because packs are data: :core:design owns the
     * registry and may add/remove packs without :core:runtime knowing any of them. Implementations
     * resolve unknown ids through the registry (default pack) before exposing them.
     */
    val themePackId: StateFlow<String>

    /**
     * Persisted text-size preset id. A string, not a percent, for the same reason as [themePackId]:
     * :core:design owns the preset registry and may add/remove/resize presets without :core:runtime
     * knowing any of them. Implementations resolve unknown ids to the 100% default before exposing.
     */
    val fontScaleId: StateFlow<String>
    val language: StateFlow<AppLanguage>

    /** Global system prompt; null/blank means "use the app.json default / localised resource". */
    val systemPrompt: StateFlow<String?>

    /**
     * Whether Settings > Model shows the advanced parameter group. A persisted preference, not UI
     * state, so the same screen opens the same way after a restart.
     */
    val modelAdvanced: StateFlow<Boolean>
    fun setThemeMode(mode: ThemeMode)
    fun setThemePack(packId: String)
    fun setFontScale(scaleId: String)
    fun setLanguage(language: AppLanguage)
    fun setSystemPrompt(prompt: String?)
    fun setModelAdvanced(enabled: Boolean)
}

/**
 * Durable memory for the chat transcript. The chat feature depends on this seam only, so storage can
 * change (a JSON file today, a database later) without touching conversation logic. [MAX_TURNS]
 * bounds what one conversation may cost on disk and in the prompt; the newest turns win.
 */
interface ConversationStore {
    fun load(): List<ChatTurn>
    fun save(turns: List<ChatTurn>)
    fun clear()

    companion object {
        /** Oldest turns are dropped first once a conversation grows past this. */
        const val MAX_TURNS = 200
    }
}

/** All editable content comes from assets: suggestions, model catalogue and demo answers. */
interface ContentRepository {
    fun suggestions(): List<Suggestion>
    fun models(): List<ModelInfo>
    fun demoReply(prompt: String): DemoReply
}
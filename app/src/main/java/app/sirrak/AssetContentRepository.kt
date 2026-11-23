package app.sirrak

import android.content.Context
import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.AppLanguage
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.ModelPresets
import app.sirrak.core.runtime.ModelPresetsFile
import app.sirrak.core.runtime.PresetRepository
import app.sirrak.core.runtime.PromptPresets
import app.sirrak.core.runtime.PromptPresetsFile
import app.sirrak.core.runtime.SourceRef
import app.sirrak.core.runtime.Suggestion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SuggestionDto(
    val prompt: String,
    val gloss: String,
    val glossAr: String? = null,
    val glossEn: String? = null,
)

@Serializable
private data class SourceDto(val title: String, val note: String? = null)

@Serializable
private data class ReplyDto(
    val triggers: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
private data class DemoChatDto(
    val suggestions: List<SuggestionDto> = emptyList(),
    val replies: List<ReplyDto> = emptyList(),
    val fallback: ReplyDto = ReplyDto(),
)

@Serializable
private data class ModelDto(
    val id: String,
    val displayName: String,
    val parameterLabel: String,
    val quant: String,
    val sizeLabel: String,
    val ramLabel: String,
    val status: String,
)

@Serializable
private data class ModelCatalogDto(val models: List<ModelDto> = emptyList())

/**
 * Reads every editable string set from assets referenced by app.json. Editing suggestions, answers,
 * the model catalogue and the M5 presets is an asset edit, not a code edit, and malformed assets fail
 * loudly (the content is validated by tests before it ships).
 */
class AssetContentRepository(context: Context, private val config: AppConfig) : ContentRepository, PresetRepository {

    private val json = Json { ignoreUnknownKeys = false }
    private val context: Context = context.applicationContext

    private val demoChat: DemoChatDto = load(context, config.content.demoReplies, DemoChatDto.serializer())
    private val catalog: ModelCatalogDto = load(context, config.content.models, ModelCatalogDto.serializer())

    override val promptPresets: PromptPresetsFile =
        loadPresets(config.content.promptPresets, PromptPresets::parse, PromptPresets::problems)
    override val modelPresets: ModelPresetsFile =
        loadPresets(config.content.modelPresets, ModelPresets::parse, ModelPresets::problems)

    override fun suggestions(): List<Suggestion> {
        val arabic = isArabic()
        return demoChat.suggestions.map { suggestion ->
            val gloss = if (arabic) suggestion.glossAr ?: suggestion.gloss else suggestion.glossEn ?: suggestion.gloss
            Suggestion(prompt = suggestion.prompt, gloss = gloss)
        }
    }

    private fun isArabic(): Boolean {
        val prefs = context.getSharedPreferences(SharedPrefsSettingsService.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(SharedPrefsSettingsService.KEY_LANGUAGE, null)
        return AppLanguage.fromTag(saved ?: config.language.defaultLanguage) == AppLanguage.ARABIC
    }

    override fun models(): List<ModelInfo> = catalog.models.map {
        ModelInfo(
            id = it.id,
            displayName = it.displayName,
            parameterLabel = it.parameterLabel,
            quant = it.quant,
            sizeLabel = it.sizeLabel,
            ramLabel = it.ramLabel,
            status = it.status,
        )
    }

    override fun demoReply(prompt: String): DemoReply {
        val normalized = normalize(prompt)
        val reply = demoChat.replies.firstOrNull { candidate ->
            candidate.triggers.any { trigger -> normalize(trigger).let { it.isNotEmpty() && normalized.contains(it) } }
        } ?: demoChat.fallback
        return DemoReply(
            lines = reply.lines,
            sources = reply.sources.map { SourceRef(title = it.title, note = it.note) },
        )
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "").trim()

    private fun <T> load(context: Context, asset: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
        return json.decodeFromString(deserializer, raw)
    }

    /** Content presets are validated, not merely parsed: a duplicate id or blank string fails the build. */
    private fun <T> loadPresets(asset: String, parse: (String) -> Result<T>, problems: (T) -> List<String>): T {
        val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
        val parsed = parse(raw).getOrElse { error("asset " + asset + " is not valid: " + it.message) }
        val found = problems(parsed)
        check(found.isEmpty()) { "asset " + asset + " is invalid: " + found }
        return parsed
    }
}
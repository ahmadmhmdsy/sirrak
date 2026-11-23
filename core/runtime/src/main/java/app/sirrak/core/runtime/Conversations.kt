package app.sirrak.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One saved chat: a stable id, an optional explicit title, timestamps, the transcript and the
 * optional per-conversation system prompt (M6, CHT-12). A null title means "derive it from the first
 * question"; the stored data never hardcodes a human-readable fallback (the UI supplies that).
 */
data class Conversation(
    val id: String,
    /** Explicit user title; null/blank means the first question is the display title. */
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: List<ChatTurn> = emptyList(),
    /** Per-conversation prompt override; null/blank inherits the model/global layers. */
    val systemPrompt: String? = null,
    /**
     * Per-conversation generation override (M6): only the fields set here win over the model/global
     * layers. Stop sequences are the common case, but the whole sparse layer is persisted so a
     * future conversation-settings surface only has to edit data it already has.
     */
    val overrides: GenerationOverrides? = null,
    /**
     * Documents that ground this conversation (M8). Ids only: the library owns text, chunks and
     * vectors, so a deleted document simply drops out of scope. Empty means "the whole library" for
     * explicit asks; the composer also offers a one-tap library-wide scope.
     */
    val documentIds: List<String> = emptyList(),
)

/** What the history list needs without loading a whole transcript. */
data class ConversationSummary(
    val id: String,
    val title: String?,
    /** First line of the last non-blank turn, single-line and bounded. */
    val preview: String,
    val messageCount: Int,
    val updatedAt: Long,
    /** Live title fallback derived from the first question; null until the user asks something. */
    val derivedTitle: String? = null,
)

/**
 * Durable, searchable history: a list of conversations instead of the M3 single transcript. The chat
 * feature depends on this seam only, so storage (a directory of JSON files today, a database later)
 * can change without touching conversation logic. The M3 ConversationStore seam stays for previews
 * and tests, but the app composes this library.
 */
interface ConversationLibrary {
    /** Newest first. */
    fun list(): List<ConversationSummary>

    fun find(id: String): Conversation?

    /** Creates and persists an empty conversation with a fresh stable id. */
    fun create(title: String? = null): Conversation

    /** Replaces the stored transcript; false when the id is unknown. */
    fun save(conversation: Conversation): Boolean

    /** Sets or clears the explicit title; false when the id is unknown. */
    fun rename(id: String, title: String?): Boolean

    /** Removes the conversation and falls back to the newest remaining active id. */
    fun delete(id: String): Boolean

    /** The last opened conversation, restored on launch; null when none was opened yet. */
    fun activeId(): String?

    /** Persists the last opened conversation; false for an unknown non-null id. */
    fun setActiveId(id: String?): Boolean

    /**
     * Searches titles and previews. Implementations that can read whole transcripts (the file
     * library does) override this to search every turn; the default keeps in-memory fakes honest
     * without forcing them to load conversations.
     */
    fun search(query: String): List<ConversationSummary> {
        val needle = query.trim()
        if (needle.isEmpty()) return list()
        return list().filter { summary ->
            summary.title?.contains(needle, ignoreCase = true) == true ||
                summary.derivedTitle?.contains(needle, ignoreCase = true) == true ||
                summary.preview.contains(needle, ignoreCase = true)
        }
    }
}

/**
 * Title rules shared by the list and the chat header. The derived title is stored nowhere, so a
 * rename to blank returns to the live first question instead of a frozen copy. Whitespace is
 * collapsed so a multi-line question cannot break a one-line list row.
 */
object ConversationTitles {
    /** Longest derived title; the list and header ellipsise beyond it anyway. */
    const val MAX_LENGTH = 48

    private val WHITESPACE = Regex("\\s+")

    /** First non-blank user question, single-spaced and capped; null when the user has not asked yet. */
    fun derive(turns: List<ChatTurn>): String? {
        val first = turns.firstOrNull { it.role == Role.USER && it.text.isNotBlank() } ?: return null
        return first.text.trim().replace(WHITESPACE, " ").take(MAX_LENGTH).trimEnd()
    }

    /** Explicit title, else the derived one, else the caller's localised fallback. */
    fun display(title: String?, turns: List<ChatTurn>, fallback: String): String =
        title?.trim()?.takeIf { it.isNotEmpty() } ?: derive(turns) ?: fallback
}

/** Local search over saved conversations: title and every turn, case-insensitive. */
object ConversationSearch {
    fun matches(conversation: Conversation, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        if (conversation.title?.lowercase()?.contains(needle) == true) return true
        return conversation.turns.any { it.text.lowercase().contains(needle) }
    }

    fun filter(conversations: List<Conversation>, query: String): List<Conversation> =
        conversations.filter { matches(it, query) }
}

/**
 * Plain-text exit door for a conversation: GitHub-flavoured Markdown for sharing/reading and a
 * stable JSON document for backup. Labels are parameters, never literals: the feature owns the
 * localised role names.
 */
object ConversationExport {
    const val SCHEMA_VERSION = 1

    fun markdown(
        conversation: Conversation,
        title: String,
        userLabel: String,
        assistantLabel: String,
    ): String {
        val blocks = mutableListOf("# " + title.trim())
        conversation.turns.filter { it.text.isNotBlank() }.forEach { turn ->
            val label = if (turn.role == Role.USER) userLabel else assistantLabel
            blocks += "**" + label + "**"
            blocks += turn.text.trim()
        }
        return blocks.joinToString("\n\n") + "\n"
    }

    /** Compact, versioned backup document; timestamps stay machine-readable. */
    fun json(conversation: Conversation): String {
        val root: JsonObject = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("id", conversation.id)
            put("title", conversation.title)
            put("createdAt", conversation.createdAt)
            put("updatedAt", conversation.updatedAt)
            put("systemPrompt", conversation.systemPrompt)
            conversation.overrides?.let { overrides ->
                put("overrides", Json.encodeToJsonElement(GenerationOverrides.serializer(), overrides))
            }
            if (conversation.documentIds.isNotEmpty()) {
                putJsonArray("documentIds") {
                    conversation.documentIds.forEach { add(it) }
                }
            }
            putJsonArray("turns") {
                conversation.turns.forEach { turn ->
                    addJsonObject {
                        put("role", turn.role.name)
                        put("text", turn.text)
                        if (turn.citations.isNotEmpty()) {
                            putJsonArray("citations") {
                                turn.citations.forEach { citation ->
                                    addJsonObject {
                                        put("documentId", citation.documentId)
                                        put("chunkId", citation.chunkId)
                                        put("documentName", citation.documentName)
                                        put("page", citation.page)
                                        put("snippet", citation.snippet)
                                    }
                                }
                            }
                        }
                        if (turn.attachments.isNotEmpty()) {
                            putJsonArray("attachments") {
                                turn.attachments.forEach { attachment ->
                                    addJsonObject {
                                        put("id", attachment.id)
                                        put("kind", attachment.kind.name)
                                        put("displayName", attachment.displayName)
                                        put("mimeType", attachment.mimeType)
                                        put("sizeBytes", attachment.sizeBytes)
                                        attachment.localPath?.let { put("localPath", it) }
                                        attachment.extractedText?.let { put("extractedText", it) }
                                        if (attachment.meta.isNotEmpty()) {
                                            val meta = buildJsonObject {
                                                attachment.meta.forEach { (key, value) -> put(key, value) }
                                            }
                                            put("meta", meta)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Reads a backup document written by [json] (or the M3-era transcript shape, which shares the
     * turn format). Unreadable input returns null instead of throwing: import is a user action on
     * arbitrary files. Unknown roles are dropped; timestamps default to 0 when absent.
     */
    fun fromJson(raw: String): Conversation? {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val id = root["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val turnsElement = root["turns"] ?: return null
        val turnsArray = runCatching { turnsElement.jsonArray }.getOrNull() ?: return null
        val turns = turnsArray.mapNotNull { element ->
            val turn = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val role = turn["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val text = turn["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val attachments = turn["attachments"]?.let { element ->
                runCatching { element.jsonArray }.getOrNull()?.mapNotNull { item ->
                    val stored = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val kindName = stored["kind"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val kind = AttachmentKind.entries.firstOrNull { it.name == kindName }
                        ?: return@mapNotNull null
                    Attachment(
                        id = stored["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        kind = kind,
                        displayName = stored["displayName"]?.jsonPrimitive?.contentOrNull
                            ?: kind.name.lowercase(),
                        mimeType = stored["mimeType"]?.jsonPrimitive?.contentOrNull
                            ?: "application/octet-stream",
                        sizeBytes = stored["sizeBytes"]?.jsonPrimitive?.contentOrNull
                            ?.toLongOrNull() ?: 0L,
                        localPath = stored["localPath"]?.jsonPrimitive?.contentOrNull,
                        extractedText = stored["extractedText"]?.jsonPrimitive?.contentOrNull,
                        meta = runCatching {
                            stored["meta"]?.jsonObject?.mapValues { (_, value) ->
                                value.jsonPrimitive.content
                            }
                        }.getOrNull() ?: emptyMap(),
                    )
                } ?: emptyList()
            } ?: emptyList()
            val citations = turn["citations"]?.let { element ->
                runCatching { element.jsonArray }.getOrNull()?.mapNotNull { item ->
                    val stored = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val documentId = stored["documentId"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val chunkId = stored["chunkId"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    Citation(
                        documentId = documentId,
                        chunkId = chunkId,
                        documentName = stored["documentName"]?.jsonPrimitive?.contentOrNull
                            ?: documentId,
                        page = stored["page"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        snippet = stored["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                } ?: emptyList()
            } ?: emptyList()
            when (role) {
                Role.USER.name -> ChatTurn(Role.USER, text, attachments, citations)
                Role.ASSISTANT.name -> ChatTurn(Role.ASSISTANT, text, attachments, citations)
                else -> null
            }
        }
        return Conversation(
            id = id,
            title = root["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = root["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            updatedAt = root["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            turns = turns,
            systemPrompt = root["systemPrompt"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            overrides = root["overrides"]?.let { element ->
                runCatching { Json.decodeFromJsonElement(GenerationOverrides.serializer(), element) }
                    .getOrNull()
                    ?.takeIf { !it.isEmpty() }
            },
            documentIds = root["documentIds"]?.let { element ->
                runCatching { element.jsonArray }.getOrNull()?.mapNotNull { item ->
                    item.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
                } ?: emptyList()
            } ?: emptyList(),
        )
    }
}

/**
 * Honest, tokenizer-free context meter: the engine can drop the oldest pairs silently once the
 * prompt stops fitting, so the UI must warn before that happens. The estimate is deliberately
 * conservative (characters per token) and says so in the UI; it is not presented as exact usage.
 */
object TokenEstimate {
    /** Average characters per token used by the heuristic. */
    private const val CHARS_PER_TOKEN = 4

    /** Chat-format overhead (role markers, delimiters) per message. */
    const val MESSAGE_OVERHEAD = 4

    /** Warning threshold: 85% of the context window. */
    const val NEAR_LIMIT_FRACTION = 0.85f

    fun tokens(text: String): Int {
        val length = text.trim().length
        return if (length == 0) 0 else (length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    fun estimate(turns: List<ChatTurn>): Int = turns.sumOf { tokens(it.text) + MESSAGE_OVERHEAD }

    /** True when the estimated prompt is close enough to [limit] that the oldest turns may drop. */
    fun nearLimit(estimated: Int, limit: Int, threshold: Float = NEAR_LIMIT_FRACTION): Boolean =
        limit > 0 && estimated.toFloat() >= limit * threshold
}

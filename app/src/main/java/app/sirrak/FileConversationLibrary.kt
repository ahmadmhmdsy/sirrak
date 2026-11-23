package app.sirrak

import android.util.Log
import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.Citation
import app.sirrak.core.runtime.Conversation
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ConversationStore
import app.sirrak.core.runtime.ConversationSearch
import app.sirrak.core.runtime.ConversationSummary
import app.sirrak.core.runtime.ConversationTitles
import app.sirrak.core.runtime.GenerationOverrides
import app.sirrak.core.runtime.Role
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The M6 conversation library: one directory, one JSON transcript per conversation and a small
 * index that remembers the last opened chat. Nothing here is a database yet; the point is that a
 * database can replace it without touching the chat feature.
 *
 * Durability rules:
 * - every write is atomic (temp file beside the target, then rename); a kill mid-write leaves the
 *   previous transcript intact;
 * - the index is an optimisation and an active-id record, not the source of truth: on load the
 *   transcript files are scanned and the index is rebuilt from them, so a corrupt index loses no
 *   conversation;
 * - a corrupt transcript is skipped and logged, never fatal;
 * - the M3 single file "conversation.json" is imported once as the first conversation and kept as
 *   "conversation.json.migrated" so a rollback is a file rename, not a data loss.
 *
 * The constructor takes a directory (not a Context) and injectable clock/id suppliers so JVM tests
 * drive real files deterministically.
 */
class FileConversationLibrary(
    dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextId: () -> String = { "c-" + UUID.randomUUID().toString().replace("-", "").take(12) },
) : ConversationLibrary {

    private val conversationsDir = File(dir, CONVERSATIONS_DIR)
    private val indexFile = File(conversationsDir, INDEX_FILE)
    private val legacyFile = File(dir, LEGACY_FILE)
    private val migratedFile = File(dir, MIGRATED_FILE)
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val entries = LinkedHashMap<String, EntryDto>()
    private var active: String? = null
    private var initialized = false

    init {
        synchronized(lock) { ensureLoadedLocked() }
    }

    override fun list(): List<ConversationSummary> = synchronized(lock) {
        ensureLoadedLocked()
        sortedEntriesLocked().map { it.toSummary() }
    }

    override fun search(query: String): List<ConversationSummary> = synchronized(lock) {
        ensureLoadedLocked()
        val needle = query.trim()
        if (needle.isEmpty()) return@synchronized sortedEntriesLocked().map { it.toSummary() }
        sortedEntriesLocked().mapNotNull { entry ->
            val conversation = readConversationLocked(entry.id) ?: return@mapNotNull null
            if (ConversationSearch.matches(conversation, needle)) entry.toSummary() else null
        }
    }

    override fun find(id: String): Conversation? = synchronized(lock) {
        ensureLoadedLocked()
        readConversationLocked(id)
    }

    override fun create(title: String?): Conversation = synchronized(lock) {
        ensureLoadedLocked()
        val id = uniqueIdLocked()
        val now = clock()
        val conversation = Conversation(
            id = id,
            title = normaliseTitle(title),
            createdAt = now,
            updatedAt = now,
        )
        writeConversationLocked(conversation)
        entries[id] = entryOf(conversation)
        persistIndexLocked()
        conversation
    }

    override fun save(conversation: Conversation): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        if (!entries.containsKey(conversation.id)) return@synchronized false
        val cleaned = conversation.copy(
            title = normaliseTitle(conversation.title),
            updatedAt = clock(),
            // A message may carry only attachments (a pasted file with no question); keep it.
            turns = conversation.turns
                .filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
                .takeLast(ConversationStore.MAX_TURNS),
        )
        writeConversationLocked(cleaned)
        entries[cleaned.id] = entryOf(cleaned)
        persistIndexLocked()
        true
    }

    override fun rename(id: String, title: String?): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val conversation = readConversationLocked(id) ?: return@synchronized false
        val renamed = conversation.copy(title = normaliseTitle(title), updatedAt = clock())
        writeConversationLocked(renamed)
        entries[id] = entryOf(renamed)
        persistIndexLocked()
        true
    }

    override fun delete(id: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        if (entries.remove(id) == null) return@synchronized false
        transcriptFile(id).delete()
        if (active == id) active = sortedEntriesLocked().firstOrNull()?.id
        persistIndexLocked()
        true
    }

    override fun activeId(): String? = synchronized(lock) {
        ensureLoadedLocked()
        active
    }

    override fun setActiveId(id: String?): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        if (id != null && !entries.containsKey(id)) return@synchronized false
        active = id
        persistIndexLocked()
        true
    }

    // ---- loading -----------------------------------------------------------------------------

    private fun ensureLoadedLocked() {
        if (initialized) return
        initialized = true
        conversationsDir.mkdirs()
        val index = readIndexLocked()
        rebuildFromTranscriptsLocked()
        migrateLegacyLocked()
        // An existing index is authoritative about the active id, including an explicit "none".
        // Only a missing/corrupt index falls back to opening the newest conversation.
        val fallbackActive = if (index == null) sortedEntriesLocked().firstOrNull()?.id else null
        active = index?.activeId?.takeIf { entries.containsKey(it) } ?: fallbackActive
        persistIndexLocked()
    }

    /** Rebuilds the in-memory index from the transcript files; the files are the source of truth. */
    private fun rebuildFromTranscriptsLocked() {
        entries.clear()
        val files = conversationsDir.listFiles { file ->
            file.isFile && file.extension == "json" && file.name != INDEX_FILE
        } ?: return
        files.sortedBy { it.name }.forEach { file ->
            val conversation = runCatching {
                decodeConversationLocked(file.readText())
            }.getOrElse { error ->
                Log.w(TAG, "skipping unreadable conversation file " + file.name, error)
                null
            }
            if (conversation != null) entries[conversation.id] = entryOf(conversation)
        }
    }

    /** The stored index, or null when it is missing or unreadable (a corrupt copy is kept). */
    private fun readIndexLocked(): IndexDto? {
        if (!indexFile.isFile) return null
        val raw = runCatching { indexFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { json.decodeFromString<IndexDto>(raw) }.getOrElse { error ->
            Log.w(TAG, "conversation index is unreadable; rebuilding from transcripts", error)
            backupCorruptIndexLocked()
            null
        }
    }

    private fun migrateLegacyLocked() {
        if (!legacyFile.isFile || migratedFile.isFile) return
        val raw = runCatching { legacyFile.readText() }.getOrNull()
        val turns = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<LegacyTurnDto>>(raw) }
                .getOrElse { error ->
                    Log.w(TAG, "the M3 conversation file is unreadable; keeping it as migrated", error)
                    emptyList()
                }
                .mapNotNull { it.toTurn() }
                .filter { it.text.isNotBlank() }
                .takeLast(ConversationStore.MAX_TURNS)
        }
        if (turns.isNotEmpty()) {
            val now = clock()
            val conversation = Conversation(
                id = uniqueIdLocked(),
                title = null,
                createdAt = legacyFile.lastModified().takeIf { it > 0L } ?: now,
                updatedAt = now,
                turns = turns,
            )
            writeConversationLocked(conversation)
            entries[conversation.id] = entryOf(conversation)
            if (active == null) active = conversation.id
        }
        markLegacyMigratedLocked()
    }

    private fun markLegacyMigratedLocked() {
        runCatching {
            if (legacyFile.renameTo(migratedFile)) return@runCatching
            migratedFile.writeText(legacyFile.readText())
            legacyFile.delete()
        }.onFailure { error -> Log.w(TAG, "could not mark the M3 conversation as migrated", error) }
    }

    // ---- reading / writing -------------------------------------------------------------------

    private fun readConversationLocked(id: String): Conversation? {
        val file = transcriptFile(id)
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { decodeConversationLocked(raw) }.getOrElse { error ->
            Log.w(TAG, "unreadable conversation " + id, error)
            null
        }
    }

    private fun decodeConversationLocked(raw: String): Conversation {
        val dto = json.decodeFromString<TranscriptDto>(raw)
        return Conversation(
            id = dto.id,
            title = normaliseTitle(dto.title),
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt,
            turns = dto.turns.mapNotNull { turn ->
                turn.toTurn(attachmentsFromJson(turn.attachments), citationsFromJson(turn.citations))
            },
            systemPrompt = dto.systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
            overrides = dto.overrides?.takeIf { !it.isEmpty() },
            documentIds = documentIdsFromJson(dto.documentIds),
        )
    }

    private fun writeConversationLocked(conversation: Conversation) {
        val dto = TranscriptDto(
            id = conversation.id,
            title = normaliseTitle(conversation.title),
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            systemPrompt = conversation.systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
            overrides = conversation.overrides?.takeIf { !it.isEmpty() },
            // JsonArray needs elements, not strings: wrap the ids so decode is symmetric with
            // documentIdsFromJson (which accepts both a missing field and a list of primitives).
            documentIds = JsonArray(conversation.documentIds.map { JsonPrimitive(it) }),
            turns = conversation.turns.map { turn ->
                TurnDto(
                    role = turn.role.name,
                    text = turn.text,
                    attachments = JsonArray(attachmentsToJson(turn.attachments)),
                    citations = JsonArray(citationsToJson(turn.citations)),
                )
            },
        )
        writeAtomicallyLocked(transcriptFile(conversation.id), json.encodeToString(dto))
    }

    private fun persistIndexLocked() {
        val dto = IndexDto(
            activeId = active,
            conversations = sortedEntriesLocked(),
        )
        writeAtomicallyLocked(indexFile, json.encodeToString(dto))
    }

    private fun writeAtomicallyLocked(target: File, content: String) {
        runCatching {
            conversationsDir.mkdirs()
            val tmp = File(conversationsDir, target.name + ".tmp")
            tmp.writeText(content)
            if (target.exists() && !target.delete()) {
                target.writeText(content)
                tmp.delete()
                return@runCatching
            }
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }.onFailure { error -> Log.w(TAG, "could not write " + target.name, error) }
    }

    private fun backupCorruptIndexLocked() {
        runCatching {
            if (indexFile.isFile) {
                File(conversationsDir, INDEX_FILE + ".corrupt").writeText(indexFile.readText())
                indexFile.delete()
            }
        }.onFailure { error -> Log.w(TAG, "could not back up the corrupt conversation index", error) }
    }

    // ---- small helpers -----------------------------------------------------------------------

    private fun transcriptFile(id: String): File = File(conversationsDir, id + ".json")

    private fun uniqueIdLocked(): String {
        repeat(100) {
            val id = nextId()
            if (!entries.containsKey(id) && !transcriptFile(id).exists()) return id
        }
        return nextId()
    }

    private fun sortedEntriesLocked(): List<EntryDto> = entries.values.sortedWith(
        compareByDescending<EntryDto> { it.updatedAt }.thenByDescending { it.id },
    )

    private fun entryOf(conversation: Conversation): EntryDto = EntryDto(
        id = conversation.id,
        title = normaliseTitle(conversation.title),
        derivedTitle = ConversationTitles.derive(conversation.turns),
        preview = previewOf(conversation.turns),
        messageCount = conversation.turns.size,
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
    )

    private fun previewOf(turns: List<ChatTurn>): String {
        val last = turns.lastOrNull { it.text.isNotBlank() } ?: return ""
        val firstLine = last.text.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        // The preview is a sentence, not source: drop block markers so a markdown answer does not
        // surface as "## Title" in the library list.
        val readable = firstLine.trim().replace(PREVIEW_MARKER, "").trim()
        return readable.replace(WHITESPACE, " ").take(PREVIEW_LENGTH)
    }

    private fun normaliseTitle(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() }

    // ---- attachment transcripts --------------------------------------------------------------

    /**
     * Attachment metadata for one turn. The bytes stay in the [AttachmentStore]; the transcript keeps
     * the durable description so a reopened conversation still shows what was sent.
     */
    private fun attachmentsToJson(attachments: List<Attachment>): List<JsonElement> =
        attachments.map { attachment ->
            buildJsonObject {
                put("id", attachment.id)
                put("kind", attachment.kind.name)
                put("displayName", attachment.displayName)
                put("mimeType", attachment.mimeType)
                put("sizeBytes", attachment.sizeBytes)
                attachment.localPath?.let { put("localPath", it) }
                attachment.extractedText?.let { put("extractedText", it) }
                if (attachment.meta.isNotEmpty()) {
                    put(
                        "meta",
                        buildJsonObject {
                            attachment.meta.forEach { (key, value) -> put(key, value) }
                        },
                    )
                }
            }
        }

    /**
     * Tolerant read: a missing list is empty, and one corrupt/unknown entry is skipped instead of
     * failing the whole transcript. M3-M6 transcripts simply have no attachments key.
     */
    private fun attachmentsFromJson(element: JsonElement?): List<Attachment> =
        runCatching { element?.jsonArray }.getOrNull()
            ?.mapNotNull { item ->
                runCatching { attachmentFromJson(item.jsonObject) }.getOrNull()
            }
            ?: emptyList()

    private fun attachmentFromJson(element: JsonObject): Attachment? {
        val id = element["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val kindName = element["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = AttachmentKind.entries.firstOrNull { it.name == kindName } ?: return null
        return Attachment(
            id = id,
            kind = kind,
            displayName = element["displayName"]?.jsonPrimitive?.contentOrNull
                ?: kind.name.lowercase(),
            mimeType = element["mimeType"]?.jsonPrimitive?.contentOrNull
                ?: "application/octet-stream",
            sizeBytes = element["sizeBytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            localPath = element["localPath"]?.jsonPrimitive?.contentOrNull,
            extractedText = element["extractedText"]?.jsonPrimitive?.contentOrNull,
            meta = runCatching {
                element["meta"]?.jsonObject?.mapValues { (_, value) ->
                    value.jsonPrimitive.content
                }
            }.getOrNull() ?: emptyMap(),
        )
    }

    // ---- citation transcripts ----------------------------------------------------------------

    /** A grounded answer keeps a snapshot of its cited sources; bytes and vectors stay in the library. */
    private fun citationsToJson(citations: List<Citation>): List<JsonElement> =
        citations.map { citation ->
            buildJsonObject {
                put("documentId", citation.documentId)
                put("chunkId", citation.chunkId)
                put("documentName", citation.documentName)
                put("page", citation.page)
                put("snippet", citation.snippet)
            }
        }

    /** Tolerant read: unknown or partial citation entries are skipped, never fatal. */
    private fun citationsFromJson(element: JsonElement?): List<Citation> =
        runCatching { element?.jsonArray }.getOrNull()
            ?.mapNotNull { item ->
                runCatching {
                    val stored = item.jsonObject
                    val documentId = stored["documentId"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    val chunkId = stored["chunkId"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    Citation(
                        documentId = documentId,
                        chunkId = chunkId,
                        documentName = stored["documentName"]?.jsonPrimitive?.contentOrNull
                            ?: documentId,
                        page = stored["page"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        snippet = stored["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }.getOrNull()
            }
            ?: emptyList()

    /** Conversation document scope: unknown/blank ids are dropped so stale metadata stays harmless. */
    private fun documentIdsFromJson(element: JsonElement?): List<String> =
        runCatching { element?.jsonArray }.getOrNull()
            ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } }
            ?: emptyList()

    // ---- serialised shapes -------------------------------------------------------------------

    @Serializable
    private data class IndexDto(
        val schemaVersion: Int = SCHEMA_VERSION,
        val activeId: String? = null,
        val conversations: List<EntryDto> = emptyList(),
    )

    @Serializable
    private data class EntryDto(
        val id: String,
        val title: String? = null,
        val derivedTitle: String? = null,
        val preview: String = "",
        val messageCount: Int = 0,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    ) {
        fun toSummary(): ConversationSummary = ConversationSummary(
            id = id,
            title = title,
            preview = preview,
            messageCount = messageCount,
            updatedAt = updatedAt,
            derivedTitle = derivedTitle,
        )
    }

    @Serializable
    private data class TranscriptDto(
        val schemaVersion: Int = SCHEMA_VERSION,
        val id: String,
        val title: String? = null,
        val createdAt: Long,
        val updatedAt: Long,
        val systemPrompt: String? = null,
        val overrides: GenerationOverrides? = null,
        /** Raw JSON so M7-era transcripts without a scope stay readable. */
        val documentIds: JsonElement? = null,
        val turns: List<TurnDto> = emptyList(),
    )

    @Serializable
    private data class TurnDto(
        val role: String,
        val text: String,
        /**
         * Raw JSON so a missing, unknown or partially corrupt list can never fail the transcript;
         * writers always pass a JSON array, empty for a turn without attachments.
         */
        val attachments: JsonElement? = null,
        /** Raw JSON citation list, same tolerant-read rule as attachments. */
        val citations: JsonElement? = null,
    ) {
        fun toTurn(
            attachments: List<Attachment> = emptyList(),
            citations: List<Citation> = emptyList(),
        ): ChatTurn? = when (role) {
            Role.USER.name -> ChatTurn(Role.USER, text, attachments, citations)
            Role.ASSISTANT.name -> ChatTurn(Role.ASSISTANT, text, attachments, citations)
            else -> null
        }
    }

    @Serializable
    private data class LegacyTurnDto(val role: String, val text: String) {
        fun toTurn(): ChatTurn? = when (role) {
            Role.USER.name -> ChatTurn(Role.USER, text)
            Role.ASSISTANT.name -> ChatTurn(Role.ASSISTANT, text)
            else -> null
        }
    }

    companion object {
        internal const val CONVERSATIONS_DIR = "conversations"
        internal const val INDEX_FILE = "index.json"
        internal const val LEGACY_FILE = "conversation.json"
        internal const val MIGRATED_FILE = "conversation.json.migrated"
        private const val SCHEMA_VERSION = 1
        private const val PREVIEW_LENGTH = 96
        private const val TAG = "SirrakConversations"
        private val WHITESPACE = Regex("\\s+")
        private val PREVIEW_MARKER = Regex("^(#{1,6}\\s+|>\\s?|[-*•]\\s+|\\d+[.)]\\s+)")
    }
}

package app.sirrak

import android.util.Log
import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ConversationStore
import app.sirrak.core.runtime.Role
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The app's first durable memory: one conversation as a small JSON file in private storage.
 *
 * Writes are atomic (temp file + rename) so a kill mid-write cannot corrupt the transcript, and reads
 * are tolerant: a missing, blank or corrupt file is simply an empty conversation. A bad file is logged
 * once and then overwritten by the next save. The format is deliberately dumb (role + text) so a
 * future database migration is a parser away, not a rewrite.
 *
 * The constructor takes a directory rather than a Context so the JVM tests drive real files.
 */
class FileConversationStore(private val dir: File) : ConversationStore {

    private val file = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<ChatTurn> {
        val raw = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val saved = runCatching { json.decodeFromString<List<SavedTurn>>(raw) }.getOrElse { error ->
            Log.w(TAG, "conversation file is unreadable; starting empty", error)
            return emptyList()
        }
        return saved.mapNotNull { it.toTurn() }
    }

    override fun save(turns: List<ChatTurn>) {
        val cleaned = turns
            .filter { it.text.isNotBlank() }
            .takeLast(ConversationStore.MAX_TURNS)
            .map { SavedTurn(role = it.role.name, text = it.text) }
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, TEMP_FILE_NAME)
            tmp.writeText(json.encodeToString(cleaned))
            if (file.exists() && !file.delete()) {
                // Last resort: overwrite in place rather than lose the newest transcript.
                file.writeText(tmp.readText())
                tmp.delete()
                return@runCatching
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { error -> Log.w(TAG, "could not save the conversation", error) }
    }

    override fun clear() {
        runCatching {
            File(dir, TEMP_FILE_NAME).delete()
            file.delete()
        }.onFailure { error -> Log.w(TAG, "could not clear the conversation", error) }
    }

    @Serializable
    private data class SavedTurn(val role: String, val text: String) {
        fun toTurn(): ChatTurn? = when (role) {
            Role.USER.name -> ChatTurn(Role.USER, text)
            Role.ASSISTANT.name -> ChatTurn(Role.ASSISTANT, text)
            else -> null
        }
    }

    companion object {
        internal const val FILE_NAME = "conversation.json"
        private const val TEMP_FILE_NAME = "$FILE_NAME.tmp"
        private const val TAG = "SirrakConversation"
    }
}

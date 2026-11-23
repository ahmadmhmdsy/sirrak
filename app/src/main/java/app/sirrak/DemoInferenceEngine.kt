package app.sirrak

import app.sirrak.core.runtime.ChatTurn
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.InferenceEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * The M1 engine: streams authored demo answers from assets with realistic pacing so the UI is
 * exercised end to end. It implements the same seam a real llama.cpp / LiteRT engine does, so the
 * swap is one config line and zero UI changes.
 */
class DemoInferenceEngine(
    private val content: ContentRepository,
    engineDisplayName: String,
    private val firstTokenDelayMs: Long = 420,
    private val tokenDelayMs: Long = 14,
) : InferenceEngine {

    override val id: String = ID
    override val displayName: String = engineDisplayName

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Ready(engineDisplayName))
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    override fun stream(prompt: String, history: List<ChatTurn>): Flow<EngineChunk> = flow {
        delay(firstTokenDelayMs)
        val reply = content.demoReply(prompt)
        reply.lines.forEachIndexed { index, line ->
            if (index > 0) {
                emit(EngineChunk.Delta("\n\n"))
                delay(tokenDelayMs * 2)
            }
            var cursor = 0
            while (cursor < line.length) {
                val end = minOf(cursor + 3, line.length)
                emit(EngineChunk.Delta(line.substring(cursor, end)))
                cursor = end
                delay(tokenDelayMs)
            }
        }
        emit(EngineChunk.Completed(reply.sources))
    }

    companion object {
        const val ID = "demo"
    }
}

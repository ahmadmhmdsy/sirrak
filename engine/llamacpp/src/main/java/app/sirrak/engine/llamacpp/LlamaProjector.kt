package app.sirrak.engine.llamacpp

import android.util.Log

/**
 * M9: facts about a multimodal projector (mmproj GGUF) that can be read without loading the text
 * model. The Settings import flow uses this to warn early when a chosen projector cannot see images;
 * the engine still re-checks support after a real load, because the file is the source of truth.
 */
object LlamaProjector {

    data class Capabilities(
        val vision: Boolean,
        val audio: Boolean,
        val audioGeneration: Boolean,
    )

    /** Null when [path] is not a readable mtmd projector. */
    fun inspect(path: String): Capabilities? = runCatching {
        val raw = String(LlamaNative.nativeProjectorInfo(path), Charsets.UTF_8)
        if (raw.isBlank()) return null
        val facts = raw.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
        Capabilities(
            vision = facts["vision"] == "1",
            audio = facts["audio"] == "1",
            audioGeneration = facts["audio_gen"] == "1",
        )
    }.onFailure { Log.w(TAG, "could not inspect projector", it) }.getOrNull()

    private const val TAG = "SirrakProjector"
}

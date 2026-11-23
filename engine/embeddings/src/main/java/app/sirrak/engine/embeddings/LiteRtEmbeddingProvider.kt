package app.sirrak.engine.embeddings

import app.sirrak.core.rag.EmbeddingProvider
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File

/**
 * The product embedding backend: EmbeddingGemma-2 270M running on LiteRT-LM 0.19.1.
 *
 * One instance owns one [EmbeddingEngine], which means one loaded copy of the .litertlm bundle (about
 * 157 MB of weights and its native graph). Opening is expensive and blocking; the app should create a
 * single instance per process on a background dispatcher, reuse it for every chunk and query, and call
 * [close] only when the library is torn down or the model file is replaced.
 *
 * [embed] and [close] are serialised on one private lock, so a close that races a running forward pass
 * waits for that pass instead of freeing native memory underneath it. Calls still block the caller's
 * thread by contract ([EmbeddingProvider.embed]); the caller chooses the dispatcher.
 */
class LiteRtEmbeddingProvider private constructor(
    private val engine: EmbeddingEngine,
    override val modelId: String,
    override val dimensions: Int,
) : EmbeddingProvider {

    /** Guards the native engine: one batch at a time, and no teardown while a batch is in flight. */
    private val lock = Any()

    /**
     * Vectors are returned normalised (unit length). Retrieval ranks by cosine, and dot product then
     * gives the same order without a second pass over each vector.
     */
    private val options = EmbeddingOptions(true, null, null, null)

    @Volatile
    private var closed = false

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        synchronized(lock) {
            if (closed) throw EmbeddingBackendException("embedding engine is already closed")
            return EmbeddingBatch.embed(texts, dimensions) { text ->
                // One InputData per call: the engine returns a single vector per computeEmbedding call,
                // so a batch is a loop and every result keeps its input position.
                engine.computeEmbedding(listOf<InputData>(InputData.Text(text)), options).embedding
            }
        }
    }

    /**
     * Idempotent: the first call releases the native engine, later calls return immediately.
     *
     * Teardown failures are swallowed on purpose - this runs from lifecycle callbacks and `use` blocks
     * where throwing would mask the original error, and a failed native free is not actionable.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                engine.close()
            } catch (ignored: Throwable) {
                // Nothing useful can be done here; see the KDoc above.
            }
        }
    }

    companion object {
        /** Stable id stored with each indexed document; changing it forces a re-index. */
        const val DEFAULT_MODEL_ID: String = "embeddinggemma-2-270m"

        /** EmbeddingGemma-2 270M output width. */
        const val DEFAULT_DIMENSIONS: Int = 768

        /** Two threads leave a core for the UI on the reference device; the spike used four. */
        const val DEFAULT_THREADS: Int = 2

        /**
         * Loads [modelPath] and initialises the engine.
         *
         * Blocking: a cold load takes seconds on the reference device. Throws [EmbeddingBackendException]
         * for a blank/unreadable path, invalid dimensions or threads, and for any native load failure,
         * after closing the partially created engine so nothing leaks.
         */
        fun open(
            modelPath: String,
            modelId: String = DEFAULT_MODEL_ID,
            dimensions: Int = DEFAULT_DIMENSIONS,
            threads: Int = DEFAULT_THREADS,
        ): LiteRtEmbeddingProvider {
            if (modelPath.isBlank()) throw EmbeddingBackendException("model path is blank")
            if (dimensions <= 0) {
                throw EmbeddingBackendException("dimensions must be positive: $dimensions")
            }
            if (threads <= 0) throw EmbeddingBackendException("threads must be positive: $threads")

            val modelFile = File(modelPath)
            if (!modelFile.isFile) throw EmbeddingBackendException("model file not found: $modelPath")
            if (!modelFile.canRead()) {
                throw EmbeddingBackendException("model file is not readable: $modelPath")
            }

            // Full explicit constructor, as proven by the 0.19.1 Java spike: only the model path and the
            // CPU backend are set; cacheDir, modelFd, token limits and activation type keep their null
            // defaults. Some of these parameters expose no shorter overload for Kotlin, hence the nulls.
            val config = EmbeddingEngineConfig(
                modelPath,
                Backend.CPU(threads, null),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )

            return try {
                val engine = EmbeddingEngine(config)
                try {
                    engine.initialize()
                    if (!engine.isInitialized()) {
                        throw EmbeddingBackendException("embedding engine did not initialize: $modelPath")
                    }
                    LiteRtEmbeddingProvider(engine, modelId, dimensions)
                } catch (failure: Throwable) {
                    try {
                        engine.close()
                    } catch (ignored: Throwable) {
                        // The engine is being abandoned anyway.
                    }
                    throw failure
                }
            } catch (failure: Throwable) {
                throw failure as? EmbeddingBackendException
                    ?: EmbeddingBackendException("failed to open embedding model: $modelPath", failure)
            }
        }
    }
}

/**
 * The pure part of a batch: order, the vector-size contract and the defensive copy.
 *
 * It holds no Android or native state, so it is the one piece of this module that a JVM unit test can
 * exercise with a fake compute function. [compute] receives every text unchanged, including blank ones:
 * the contract does not ask the backend to police content.
 */
internal object EmbeddingBatch {

    fun embed(
        texts: List<String>,
        dimensions: Int,
        compute: (String) -> FloatArray,
    ): List<FloatArray> {
        val vectors = ArrayList<FloatArray>(texts.size)
        for (text in texts) {
            val vector = compute(text)
            if (vector.size != dimensions) {
                throw EmbeddingBackendException(
                    "embedding vector has ${vector.size} values, expected $dimensions",
                )
            }
            // The engine hands back a Java array rebuilt from native output; copy it so the caller owns a
            // plain Kotlin array that no later native call can observe or mutate.
            vectors += vector.copyOf()
        }
        return vectors
    }
}

package app.sirrak.engine.embeddings

import app.sirrak.core.rag.EmbeddingProvider
import app.sirrak.core.rag.EmbeddingProviderFactory

/**
 * Opens [LiteRtEmbeddingProvider] instances from an absolute .litertlm file path.
 *
 * No Android Context is required: LiteRT-LM 0.19.1 constructs its engine from
 * [com.google.ai.edge.litertlm.EmbeddingEngineConfig] only, loads its native libraries from the AAR's
 * packaged jni/ directory, and reads the model from the filesystem path it is given. Keeping the factory
 * Context-free is what lets the app wiring and JVM tests share one constructor.
 *
 * [threads] is the CPU thread count for the native backend; two leaves a core for the UI on the
 * reference device.
 */
class LiteRtEmbeddingProviderFactory(
    private val threads: Int = LiteRtEmbeddingProvider.DEFAULT_THREADS,
) : EmbeddingProviderFactory {

    override fun open(modelPath: String): EmbeddingProvider =
        LiteRtEmbeddingProvider.open(modelPath = modelPath, threads = threads)
}

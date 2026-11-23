package app.sirrak.engine.embeddings

/**
 * The one failure type raised by [LiteRtEmbeddingProvider] when the LiteRT-LM engine cannot be opened
 * or used.
 *
 * The message is diagnostic only and is never shown to the user: the app layer catches this as an
 * [Exception] and maps it to a localisable [app.sirrak.core.rag.EmbeddingStatus.Failed] reason code.
 * [cause] keeps the native or Kotlin exception so a log still shows why the bundle was rejected.
 */
class EmbeddingBackendException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

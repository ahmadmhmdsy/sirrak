package app.sirrak.media

import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.RagImportResult
import app.sirrak.core.rag.RagService

/**
 * The app adapter between the media shelf's index seam and the M8 RAG library.
 *
 * This is the one place media bytes are handed to extraction. It stays a thin adapter on purpose: the
 * decision of *whether* to index (KEEP + GRANTED + text-bearing) belongs to [MediaRules] inside the
 * library, not here, so a caller cannot bypass the policy by calling the adapter directly.
 */
class RagMediaIndex(private val rag: RagService) : MediaIndexPort {

    override suspend fun index(source: MediaSource): String? =
        when (val result = rag.import(
            ExtractionSource(
                displayName = source.displayName,
                mimeType = source.mimeType,
                sizeBytes = source.sizeBytes,
                openStream = source.open,
            ),
        )) {
            is RagImportResult.Imported -> result.document.id
            is RagImportResult.Rejected -> null
        }

    override suspend fun remove(documentId: String) {
        rag.delete(documentId)
    }
}

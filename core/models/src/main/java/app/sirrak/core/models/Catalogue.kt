package app.sirrak.core.models

import app.sirrak.core.runtime.GgufFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One multimodal projector (mmproj GGUF) that belongs to a catalogue model. Both files are downloaded
 * from the same Hugging Face repo; the projector is stored next to the model and attached by the
 * store's naming convention, exactly like a SAF import.
 */
@Serializable
data class CatalogueProjector(
    val repo: String? = null,
    val fileName: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
)

/**
 * A curated model the user can install without a browser or a sideload. Every field is display or
 * policy data: the UI renders it, the downloader uses [sizeBytes] for the storage pre-check and
 * [sha256] for verification, and the recommender uses [ramMb] / [minRamMb] / [sizeBytes].
 *
 * Honest limits: [sizeBytes] is the expected download size (advisory for a user-supplied Hugging Face
 * file, where it is 0), [sha256] is verified only when present, and [tokensPerSecondHint] is a
 * benchmark hint scaled by device class, not a measurement on this phone.
 */
@Serializable
data class CatalogueEntry(
    val id: String,
    val displayName: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val parameterLabel: String = "",
    val quantization: String = "",
    val contextTokens: Int = 0,
    /** GGUF `general.architecture`, verified from the file header; null when it has not been verified yet. */
    val architecture: String? = null,
    /** Measured header numbers (probe round). Null means "not verified", never "zero". */
    val layerCount: Int? = null,
    val headCount: Int? = null,
    val kvHeadCount: Int? = null,
    val embeddingLength: Int? = null,
    val ramMb: Int = 0,
    val minRamMb: Int = 0,
    val licence: String = "",
    val licenceUrl: String? = null,
    val gated: Boolean = false,
    val languages: List<String> = emptyList(),
    val descriptionKey: String = "",
    val tokensPerSecondHint: Double? = null,
    val visionProjector: CatalogueProjector? = null,
) {
    /** Public Hugging Face resolver URL for this file; the only network request the app makes. */
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"

    val sizeLabel: String get() = GgufFile.formatSize(sizeBytes)

    /**
     * The architecture facts a fit verdict needs, or null when the header has not been verified yet.
     * A curated entry carries them from the release probe; a web-searched file gets them from the
     * 12 MB range probe before any download decision.
     */
    fun fitArchitecture(): GgufArchitecture? {
        // A missing number is not zero: with kv heads unknown the KV cache would be assumed free and
        // the verdict would say "runs" for a model that cannot. Requiring the measured set keeps the
        // unverified entries at UNKNOWN until a probe answers.
        val name = architecture?.takeIf { it.isNotBlank() } ?: return null
        val layers = layerCount?.takeIf { it > 0 } ?: return null
        val heads = headCount?.takeIf { it > 0 } ?: return null
        val kvHeads = kvHeadCount?.takeIf { it > 0 } ?: return null
        val embedding = embeddingLength?.takeIf { it > 0 } ?: return null
        return GgufArchitecture(
            name = name,
            layerCount = layers,
            headCount = heads,
            kvHeadCounts = listOf(kvHeads),
            embeddingLength = embedding,
            contextTokens = contextTokens.takeIf { it > 0 },
        )
    }
}

/** The whole catalogue asset. Priority is list order: the first suitable entry is recommended. */
@Serializable
data class ModelCatalogue(
    val schemaVersion: Int = 1,
    val models: List<CatalogueEntry> = emptyList(),
)

/**
 * Parsing and validation for the catalogue asset. Validation returns stable codes (never
 * user-visible strings) so a broken asset is reported precisely in logs/tests while the UI keeps
 * running with an empty catalogue.
 */
object CatalogueJson {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(text: String): Result<ModelCatalogue> = runCatching {
        json.decodeFromString<ModelCatalogue>(text)
    }

    fun validationErrors(catalogue: ModelCatalogue): List<String> {
        val errors = mutableListOf<String>()
        if (catalogue.schemaVersion <= 0) errors += "invalid-schema-version"
        val ids = mutableSetOf<String>()
        catalogue.models.forEach { entry ->
            if (entry.id.isBlank()) {
                errors += "blank-id"
            } else if (!ids.add(entry.id)) {
                errors += "duplicate-id:${entry.id}"
            }
            if (entry.displayName.isBlank()) errors += "blank-display-name:${entry.id}"
            if (entry.repo.isBlank()) errors += "blank-repo:${entry.id}"
            if (entry.fileName.isBlank()) errors += "blank-file-name:${entry.id}"
            if (entry.sizeBytes <= 0) errors += "non-positive-size:${entry.id}"
            if (entry.ramMb <= 0) errors += "non-positive-ram:${entry.id}"
            if (entry.minRamMb < 0) errors += "negative-min-ram:${entry.id}"
            if (entry.contextTokens < 0) errors += "negative-context:${entry.id}"
            if (entry.architecture != null && entry.architecture.isBlank()) {
                errors += "blank-architecture:${entry.id}"
            }
            listOf(
                "negative-layers" to entry.layerCount,
                "negative-heads" to entry.headCount,
                "negative-kv-heads" to entry.kvHeadCount,
                "negative-embd" to entry.embeddingLength,
            ).forEach { (code, value) ->
                if (value != null && value <= 0) errors += "$code:${entry.id}"
            }
            if (entry.sha256 != null && !isSha256(entry.sha256)) errors += "invalid-sha256:${entry.id}"
            entry.visionProjector?.let { projector ->
                if (projector.fileName.isBlank()) errors += "blank-projector-file:${entry.id}"
                if (projector.sizeBytes < 0) errors += "negative-projector-size:${entry.id}"
                if (projector.sha256 != null && !isSha256(projector.sha256)) {
                    errors += "invalid-projector-sha256:${entry.id}"
                }
            }
        }
        return errors
    }

    fun isValid(catalogue: ModelCatalogue): Boolean = validationErrors(catalogue).isEmpty()

    fun isSha256(value: String): Boolean = value.length == 64 && value.all { char ->
        char in '0'..'9' || char.lowercaseChar() in 'a'..'f'
    }
}

/** The app reads the catalogue from an asset; tests and previews can provide a literal one. */
interface ModelCatalogueProvider {
    fun catalogue(): ModelCatalogue
}

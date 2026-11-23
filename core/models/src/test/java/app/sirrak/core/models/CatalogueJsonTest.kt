package app.sirrak.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueJsonTest {

    private val validJson = """
        {
          "schemaVersion": 1,
          "models": [
            {
              "id": "tiny",
              "displayName": "Tiny Model",
              "repo": "org/tiny-GGUF",
              "fileName": "tiny-q4_k_m.gguf",
              "sizeBytes": 1048576,
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "parameterLabel": "0.5B",
              "quantization": "Q4_K_M",
              "contextTokens": 4096,
              "ramMb": 700,
              "minRamMb": 3072,
              "licence": "Apache-2.0",
              "licenceUrl": "https://example.test/licence",
              "gated": false,
              "languages": ["en", "ar"],
              "descriptionKey": "catalogue_tiny",
              "tokensPerSecondHint": 14.0,
              "visionProjector": {
                "repo": "org/tiny-GGUF",
                "fileName": "mmproj-tiny.gguf",
                "sizeBytes": 1048576,
                "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesCatalogueWithProjector() {
        val catalogue = CatalogueJson.parse(validJson).getOrThrow()
        assertEquals(1, catalogue.schemaVersion)
        assertEquals(1, catalogue.models.size)
        val entry = catalogue.models.single()
        assertEquals("tiny", entry.id)
        assertEquals("Q4_K_M", entry.quantization)
        assertEquals(listOf("en", "ar"), entry.languages)
        assertEquals("mmproj-tiny.gguf", entry.visionProjector?.fileName)
        assertEquals(1048576L, entry.visionProjector?.sizeBytes)
    }

    @Test
    fun validCatalogueHasNoValidationErrors() {
        val catalogue = CatalogueJson.parse(validJson).getOrThrow()
        assertEquals(emptyList<String>(), CatalogueJson.validationErrors(catalogue))
        assertTrue(CatalogueJson.isValid(catalogue))
    }

    @Test
    fun downloadUrlUsesTheHuggingFaceResolver() {
        val entry = CatalogueJson.parse(validJson).getOrThrow().models.single()
        assertEquals(
            "https://huggingface.co/org/tiny-GGUF/resolve/main/tiny-q4_k_m.gguf",
            entry.downloadUrl,
        )
    }

    @Test
    fun rejectsMalformedJson() {
        assertTrue(CatalogueJson.parse("{ not json").isFailure)
        assertTrue(CatalogueJson.parse("""{"schemaVersion": 1}""").getOrThrow().models.isEmpty())
    }

    @Test
    fun validationFlagsDuplicateIdsAndBlankFields() {
        val catalogue = ModelCatalogue(
            schemaVersion = 1,
            models = listOf(
                validEntry(id = "same"),
                validEntry(id = "same", displayName = " "),
            ),
        )
        val errors = CatalogueJson.validationErrors(catalogue)
        assertTrue(errors.contains("duplicate-id:same"))
        assertTrue(errors.contains("blank-display-name:same"))
    }

    @Test
    fun validationFlagsBadDigestsAndNegativeNumbers() {
        val catalogue = ModelCatalogue(
            models = listOf(
                validEntry(id = "bad", sha256 = "abc", sizeBytes = 0L, ramMb = 0, minRamMb = -1),
            ),
        )
        val errors = CatalogueJson.validationErrors(catalogue)
        assertTrue(errors.contains("invalid-sha256:bad"))
        assertTrue(errors.contains("non-positive-size:bad"))
        assertTrue(errors.contains("non-positive-ram:bad"))
        assertTrue(errors.contains("negative-min-ram:bad"))
    }

    @Test
    fun validationAcceptsUppercaseDigestButRejectsWrongLength() {
        assertTrue(CatalogueJson.isSha256("A".repeat(64)))
        assertFalse(CatalogueJson.isSha256("a".repeat(63)))
        assertFalse(CatalogueJson.isSha256("g".repeat(64)))
    }

    @Test
    fun emptyCatalogueIsStillValid() {
        val catalogue = ModelCatalogue(schemaVersion = 1, models = emptyList())
        assertTrue(CatalogueJson.isValid(catalogue))
    }

    private fun validEntry(
        id: String,
        displayName: String = "Model $id",
        sha256: String? = "a".repeat(64),
        sizeBytes: Long = 1024L,
        ramMb: Int = 512,
        minRamMb: Int = 2048,
    ) = CatalogueEntry(
        id = id,
        displayName = displayName,
        repo = "org/$id",
        fileName = "$id.gguf",
        sizeBytes = sizeBytes,
        sha256 = sha256,
        ramMb = ramMb,
        minRamMb = minRamMb,
    )
}

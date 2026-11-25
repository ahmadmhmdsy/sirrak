package app.sirrak.feature.models

import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.FitReasons
import app.sirrak.core.models.FitStatus
import app.sirrak.core.models.FitVerdict
import app.sirrak.core.models.GgufArchitecture
import app.sirrak.core.models.GgufMetadata
import app.sirrak.core.models.HfRepoFile
import app.sirrak.core.models.HfRepoInfo
import app.sirrak.core.models.ModelFootprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the P3 browser: which files are offered, how a repo file becomes one synthetic
 * catalogue entry the downloader can take, and which download decision a verdict allows. All of it
 * runs without Android or a network so the rules stay checkable while the screens stay thin.
 */
class HfBrowseLogicTest {

    private fun file(path: String, size: Long = 1_000L, sha: String? = null) =
        HfRepoFile(path = path, sizeBytes = size, sha256 = sha)

    private fun info(
        gated: Boolean = false,
        license: String? = "apache-2.0",
        contextTokens: Int? = 8192,
    ) = HfRepoInfo(
        id = "org/model-GGUF",
        gated = gated,
        license = license,
        architecture = "qwen2",
        contextTokens = contextTokens,
        chatTemplate = null,
    )

    private fun metadata(
        name: String = "qwen2",
        layers: Int = 24,
        heads: Int = 14,
        kvHeads: Int = 2,
        embedding: Int = 896,
        context: Int = 32768,
    ) = GgufMetadata(
        magicOk = true,
        version = 3,
        architecture = GgufArchitecture(
            name = name,
            layerCount = layers,
            headCount = heads,
            kvHeadCounts = listOf(kvHeads),
            embeddingLength = embedding,
            contextTokens = context,
        ),
        complete = true,
        bytesRead = 4096,
    )

    private fun verdict(status: FitStatus) = FitVerdict(
        status = status,
        footprint = ModelFootprint(fileBytes = 1_000L, kvBytes = 100L, overheadBytes = 50L),
        usableRamMb = 900,
        freeDiskBytes = 10_000L,
        suggestedContext = 2048,
        requestedContext = 2048,
        reasons = emptyList(),
    )

    // ---------------------------------------------------------------- files

    @Test
    fun `only GGUF files are offered and they stay sorted by path`() {
        val files = listOf(
            file("README.md"),
            file("model-Q8_0.gguf"),
            file("sub/model-Q4_K_M.GGUF"),
            file("tokenizer.json"),
        )

        val offered = HfBrowseLogic.ggufFiles(files)

        assertEquals(listOf("model-Q8_0.gguf", "sub/model-Q4_K_M.GGUF"), offered.map { it.path })
    }

    @Test
    fun `a repo with no GGUF file offers nothing`() {
        assertEquals(emptyList<HfRepoFile>(), HfBrowseLogic.ggufFiles(listOf(file("README.md"))))
    }

    // ---------------------------------------------------------------- quantization

    @Test
    fun `quantization is read from the file name when it is there`() {
        assertEquals("Q4_K_M", HfBrowseLogic.quantizationFromPath("model-Q4_K_M.gguf"))
        assertEquals("Q8_0", HfBrowseLogic.quantizationFromPath("llama-3.2-1b-instruct.Q8_0.gguf"))
        assertEquals("Q5_K_S", HfBrowseLogic.quantizationFromPath("dir/model-q5_k_s.GGUF"))
    }

    @Test
    fun `an unlabelled file reports an empty quantization instead of guessing`() {
        assertEquals("", HfBrowseLogic.quantizationFromPath("model.gguf"))
    }

    // ---------------------------------------------------------------- synthetic entry

    @Test
    fun `a probe result becomes one synthetic catalogue entry with a stable id`() {
        val entry = HfBrowseLogic.syntheticEntry(
            repoId = "org/model-GGUF",
            file = file("sub/model-Q4_K_M.gguf", size = 1_117_320_736L, sha = "a".repeat(64)),
            info = info(gated = true),
            metadata = metadata(),
        )

        assertEquals("hf:org/model-GGUF:sub/model-Q4_K_M.gguf", entry.id)
        assertEquals("model-Q4_K_M.gguf", entry.displayName)
        assertEquals("org/model-GGUF", entry.repo)
        assertEquals("sub/model-Q4_K_M.gguf", entry.fileName)
        assertEquals(1_117_320_736L, entry.sizeBytes)
        assertEquals("a".repeat(64), entry.sha256)
        assertEquals("Q4_K_M", entry.quantization)
        assertEquals("qwen2", entry.architecture)
        assertEquals(24, entry.layerCount)
        assertEquals(14, entry.headCount)
        assertEquals(2, entry.kvHeadCount)
        assertEquals(896, entry.embeddingLength)
        assertEquals(32768, entry.contextTokens)
        assertEquals("apache-2.0", entry.licence)
        assertEquals(true, entry.gated)
    }

    @Test
    fun `an unprobed file keeps every measured field null so the fit stays unverified`() {
        val entry = HfBrowseLogic.syntheticEntry(
            repoId = "org/model-GGUF",
            file = file("model-Q4_K_M.gguf"),
            info = info(),
            metadata = null,
        )

        assertNull(entry.architecture)
        assertNull(entry.layerCount)
        assertNull(entry.headCount)
        assertNull(entry.kvHeadCount)
        assertNull(entry.embeddingLength)
        assertNull(entry.fitArchitecture())
        // The Hub's context is not a measured field, so the catalogue keeps it out of the fit.
        assertEquals(8192, entry.contextTokens)
    }

    @Test
    fun `a partial probe keeps the fields it read and leaves the rest null`() {
        val partial = GgufMetadata(
            magicOk = true,
            version = 3,
            architecture = GgufArchitecture(name = "llama", layerCount = 16),
            complete = false,
            bytesRead = 512,
        )

        val entry = HfBrowseLogic.syntheticEntry(
            repoId = "org/model-GGUF",
            file = file("model.gguf"),
            info = null,
            metadata = partial,
        )

        assertEquals("llama", entry.architecture)
        assertEquals(16, entry.layerCount)
        assertNull(entry.headCount)
        assertNull(entry.kvHeadCount)
        assertNull(entry.embeddingLength)
        assertNull(entry.fitArchitecture())
    }

    // ---------------------------------------------------------------- download decision

    @Test
    fun `a safe verdict allows the download and a tight one asks first`() {
        assertEquals(HfDownloadDecision.ALLOWED, HfBrowseLogic.downloadDecision(verdict(FitStatus.RUNS)))
        assertEquals(HfDownloadDecision.CONFIRM_TIGHT, HfBrowseLogic.downloadDecision(verdict(FitStatus.TIGHT)))
    }

    @Test
    fun `a blocking verdict blocks the download`() {
        assertEquals(HfDownloadDecision.BLOCKED, HfBrowseLogic.downloadDecision(verdict(FitStatus.WONT_RUN)))
    }

    @Test
    fun `an unverified verdict allows the download but never claims it runs`() {
        assertEquals(
            HfDownloadDecision.ALLOWED_UNVERIFIED,
            HfBrowseLogic.downloadDecision(verdict(FitStatus.UNKNOWN)),
        )
    }

    @Test
    fun `the tight confirmation is required by the reason alone even before the download`() {
        val tight = verdict(FitStatus.TIGHT)
            .copy(reasons = listOf(FitReasons.RAM_TIGHT))
        assertEquals(HfDownloadDecision.CONFIRM_TIGHT, HfBrowseLogic.downloadDecision(tight))
    }
}

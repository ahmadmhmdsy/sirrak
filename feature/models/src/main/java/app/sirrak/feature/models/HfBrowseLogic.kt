package app.sirrak.feature.models

import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.FitStatus
import app.sirrak.core.models.FitVerdict
import app.sirrak.core.models.GgufMetadata
import app.sirrak.core.models.HfRepoFile
import app.sirrak.core.models.HfRepoInfo

/** What the download button may do after a fit verdict; the copy differs per case. */
enum class HfDownloadDecision {
    /** The file is predicted to fit: one tap downloads it. */
    ALLOWED,

    /** The file should load but the margin is thin: warn, then download only after a yes. */
    CONFIRM_TIGHT,

    /** A blocking reason: the button must not download, and the reason is shown instead. */
    BLOCKED,

    /** No header was read: download is allowed, but the screen must state it is unverified. */
    ALLOWED_UNVERIFIED,
}

/** How the persisted download queue took one synthetic entry; the screen explains each case. */
enum class HfEnqueueOutcome {
    /** A new row was added. */
    ADDED,

    /** The same file is already in the list; the existing row is the truth. */
    DUPLICATE,

    /** The file is already installed, so no second copy is requested. */
    INSTALLED,

    /** The queue refused the row (for example a broken store); nothing will download. */
    REJECTED,
}

/**
 * The pure rules of the P3 browser, kept out of the composables so they can be tested on the JVM:
 * which files the repo page offers, what a repo file becomes as a synthetic catalogue entry for the
 * download bridge, and what a fit verdict permits.
 *
 * A synthetic entry is honestly partial: the repository card and the probe only know what the Hub
 * and the header state, so a field that was not measured stays null instead of being invented. The
 * downloader verifies the file when the LFS oid is present and only checks the size otherwise.
 */
object HfBrowseLogic {

    /**
     * The published files a model download can target: `.gguf` only, case-insensitively, sorted so
     * two openings of the same repo list the same order. Directories are already dropped by
     * [app.sirrak.core.models.HfClient.files].
     */
    fun ggufFiles(files: List<HfRepoFile>): List<HfRepoFile> =
        files.filter { it.path.endsWith(".gguf", ignoreCase = true) }.sortedBy { it.path }

    /**
     * The quantization label read from the file name ("Q4_K_M", "Q8_0", "IQ2_XXS"), or empty when
     * the name does not carry one. It is a label, not a measurement: the header probe is what proves
     * the file, and an unlabelled file honestly shows no quantization.
     */
    fun quantizationFromPath(path: String): String {
        val name = path.substringAfterLast('/')
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        val afterDot = if (base.contains('.')) base.substringAfterLast('.') else null
        val afterDash = base.substringAfterLast('-').takeIf { it != base }
        val candidate = (afterDot ?: afterDash).orEmpty().uppercase()
        return candidate.takeIf { QUANTIZATION.matches(it) }.orEmpty()
    }

    /**
     * One catalogue entry for [file], derived from the Hub card and (when it ran) the header probe.
     * The id is deterministic per repo and path, so asking twice maps to one queue row instead of
     * two indistinguishable transfers.
     */
    fun syntheticEntry(
        repoId: String,
        file: HfRepoFile,
        info: HfRepoInfo?,
        metadata: GgufMetadata?,
    ): CatalogueEntry {
        val architecture = metadata?.architecture
        val measuredContext = architecture?.contextTokens?.takeIf { it > 0 } ?: 0
        val hubContext = info?.contextTokens?.takeIf { it > 0 } ?: 0
        return CatalogueEntry(
            id = "hf:$repoId:${file.path}",
            displayName = file.path.substringAfterLast('/'),
            repo = repoId,
            fileName = file.path,
            sizeBytes = file.sizeBytes,
            sha256 = file.sha256,
            parameterLabel = "",
            quantization = quantizationFromPath(file.path),
            contextTokens = if (measuredContext > 0) measuredContext else hubContext,
            architecture = architecture?.name,
            layerCount = architecture?.layerCount,
            headCount = architecture?.headCount,
            kvHeadCount = architecture?.kvHeadCounts?.distinct()?.singleOrNull(),
            embeddingLength = architecture?.embeddingLength,
            licence = info?.license.orEmpty(),
            gated = info?.gated ?: false,
        )
    }

    /** The one rule the download button follows; the screen only decides how to phrase it. */
    fun downloadDecision(verdict: FitVerdict): HfDownloadDecision = when (verdict.status) {
        FitStatus.RUNS -> HfDownloadDecision.ALLOWED
        FitStatus.TIGHT -> HfDownloadDecision.CONFIRM_TIGHT
        FitStatus.WONT_RUN -> HfDownloadDecision.BLOCKED
        FitStatus.UNKNOWN -> HfDownloadDecision.ALLOWED_UNVERIFIED
    }

    private val QUANTIZATION = Regex("^I?Q[0-9][A-Z0-9_]*$")
}

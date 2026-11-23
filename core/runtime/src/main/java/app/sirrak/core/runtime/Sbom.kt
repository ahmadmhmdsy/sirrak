package app.sirrak.core.runtime

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * P3.7 CycloneDX SBOM. This is the machine-readable twin of the licence registry that drives the About
 * screen and the NOTICE file: one JSON source (`assets/config/licences.json`) produces all three, so a
 * component is added or removed in exactly one place and every artefact follows.
 *
 * The document is deterministic (no timestamp, no random serial number, registry order preserved), which
 * is what makes the checked-in `sbom.cdx.json` drift-testable byte-for-byte. Scope is honest: bundled
 * components are `required`, user-downloaded models and test tools are `excluded` (they are part of the
 * licence surface but not of the shipped binaries).
 */
@Serializable
data class SbomProperty(val name: String, val value: String)

@Serializable
data class SbomExternalReference(val type: String, val url: String)

/** Either an SPDX [id] or a free-form [name]; CycloneDX forbids both being set at once. */
@Serializable
data class SbomLicense(val id: String? = null, val name: String? = null, val url: String? = null)

@Serializable
data class SbomComponent(
    val type: String,
    val name: String,
    val version: String? = null,
    val scope: String? = null,
    val purl: String? = null,
    val licenses: List<SbomLicense>? = null,
    val externalReferences: List<SbomExternalReference>? = null,
    val properties: List<SbomProperty>? = null,
)

@Serializable
data class SbomMetadata(val component: SbomComponent)

@Serializable
data class SbomDocument(
    val bomFormat: String,
    val specVersion: String,
    val version: Int,
    val metadata: SbomMetadata,
    val components: List<SbomComponent>,
)

object Sbom {

    const val SPEC_FORMAT = "CycloneDX"
    const val SPEC_VERSION = "1.5"

    /** SPDX identifiers the registry currently uses; anything else is emitted as a licence name. */
    private val SPDX_IDS = setOf("Apache-2.0", "MIT", "OFL-1.1", "EPL-1.0", "BSD-3-Clause", "ISC", "MPL-2.0")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
    }

    /** CycloneDX component type for each registry kind; test tools are tagged as applications but excluded. */
    fun componentType(kind: LicenceKind): String = when (kind) {
        LicenceKind.APP -> "application"
        LicenceKind.FRAMEWORK -> "framework"
        LicenceKind.LIBRARY -> "library"
        LicenceKind.ENGINE -> "library"
        LicenceKind.FONT -> "file"
        LicenceKind.MODEL -> "machine-learning-model"
        LicenceKind.TOOL -> "application"
    }

    /** Package URL for one registry id; the version is omitted when the registry does not pin one. */
    fun purl(id: String, version: String): String =
        if (version.isBlank()) "pkg:generic/$id" else "pkg:generic/$id@$version"

    private fun licence(entry: LicenceEntry): SbomLicense {
        val url = entry.licenceUrl.ifBlank { null }
        return if (entry.licence in SPDX_IDS) {
            SbomLicense(id = entry.licence, url = url)
        } else {
            SbomLicense(name = entry.licence, url = url)
        }
    }

    private fun component(entry: LicenceEntry): SbomComponent {
        val kind = entry.kindOrLibrary
        return SbomComponent(
            type = componentType(kind),
            name = entry.name,
            version = entry.version.ifBlank { null },
            scope = if (entry.bundled) "required" else "excluded",
            purl = purl(entry.id, entry.version),
            licenses = listOf(licence(entry)),
            externalReferences = if (entry.url.isBlank()) {
                null
            } else {
                listOf(SbomExternalReference(type = "website", url = entry.url))
            },
            properties = listOf(
                SbomProperty("sirrak:kind", kind.id),
                SbomProperty("sirrak:bundled", entry.bundled.toString()),
            ),
        )
    }

    /**
     * Builds the SBOM document from the registry. [appVersion] is the build's `BuildConfig.VERSION_NAME`
     * (the harness supplies it, the registry itself does not pin the app version).
     *
     * Fails loudly when the registry has no app entry: an SBOM without the subject component is invalid,
     * and the drift test must catch that rather than emit a broken file.
     */
    fun document(registry: LicenceRegistry, appVersion: String): SbomDocument {
        val app = registry.entries.singleOrNull { it.kindOrLibrary == LicenceKind.APP }
            ?: throw IllegalArgumentException("SBOM needs exactly one app entry in the licence registry")
        val metadataComponent = SbomComponent(
            type = componentType(LicenceKind.APP),
            name = app.name,
            version = appVersion,
            purl = purl(app.id, appVersion),
            licenses = listOf(licence(app)),
            externalReferences = if (app.url.isBlank()) {
                null
            } else {
                listOf(SbomExternalReference(type = "website", url = app.url))
            },
            properties = listOf(SbomProperty("sirrak:kind", LicenceKind.APP.id)),
        )
        return SbomDocument(
            bomFormat = SPEC_FORMAT,
            specVersion = SPEC_VERSION,
            version = 1,
            metadata = SbomMetadata(component = metadataComponent),
            components = registry.entries
                .filterNot { it.kindOrLibrary == LicenceKind.APP }
                .map(::component),
        )
    }

    /** Serialised JSON with a trailing newline, so the checked-in file is diff-friendly like NOTICE. */
    fun json(registry: LicenceRegistry, appVersion: String): String =
        json.encodeToString(document(registry, appVersion)) + "\n"
}

package app.sirrak.core.runtime

import kotlinx.serialization.Serializable

/**
 * P3.2 licence/SBOM registry. One data source (`assets/config/licences.json`) drives the About
 * screen, the NOTICE file and (later) the store listing, so adding or removing a component is a
 * JSON edit - never a code edit. The registry is pure data; the host loads it from assets.
 */
enum class LicenceKind(val id: String) {
    APP("app"),
    FRAMEWORK("framework"),
    LIBRARY("library"),
    ENGINE("engine"),
    FONT("font"),
    MODEL("model"),
    TOOL("tool"),
    ;

    companion object {
        fun fromId(id: String): LicenceKind? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class LicenceEntry(
    val id: String,
    val name: String,
    val version: String = "",
    /** SPDX identifier, e.g. "Apache-2.0", "MIT", "OFL-1.1". */
    val licence: String = "",
    /** One of [LicenceKind] ids; unknown values fall back to "library" with a validation error. */
    val kind: String = "library",
    val copyright: String = "",
    /** Project home page (https). */
    val url: String = "",
    /** Canonical licence text (https). */
    val licenceUrl: String = "",
    /** True when the component ships inside the APK; false when the user fetches it later. */
    val bundled: Boolean = true,
    /** Honest extra context (e.g. why a model is not bundled). */
    val note: String = "",
    /**
     * Asset path of the full licence text, when the text ships inside the app (offline reading).
     * Blank means the UI offers [licenceUrl] instead.
     */
    val textAsset: String = "",
) {
    val kindOrLibrary: LicenceKind get() = LicenceKind.fromId(kind) ?: LicenceKind.LIBRARY
}

@Serializable
data class LicenceRegistry(
    val schemaVersion: Int = 1,
    val entries: List<LicenceEntry> = emptyList(),
)

object LicenceRules {

    const val MAX_ENTRIES = 200
    const val MAX_NAME = 120
    const val MAX_VERSION = 40
    const val MAX_LICENCE = 40
    const val MAX_COPYRIGHT = 200
    const val MAX_NOTE = 300
    const val MAX_URL = 300

    /** Structural validation shared by the asset loader, the tests and any future tooling. */
    fun validate(registry: LicenceRegistry): List<String> = buildList {
        if (registry.schemaVersion != 1) add("unsupported schemaVersion ${registry.schemaVersion}")
        if (registry.entries.isEmpty()) add("licences.entries is empty")
        if (registry.entries.size > MAX_ENTRIES) add("licences.entries has more than $MAX_ENTRIES entries")
        val duplicated = registry.entries.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (duplicated.isNotEmpty()) add("licences.entries duplicate ids: ${duplicated.keys}")
        registry.entries.forEachIndexed { index, entry ->
            val where = "licences.entries[$index]"
            if (entry.id.isBlank()) add("$where.id is blank")
            if (entry.name.isBlank() || entry.name.length > MAX_NAME) add("$where.name is blank or too long")
            if (entry.licence.isBlank() || entry.licence.length > MAX_LICENCE) add("$where.licence is blank or too long")
            if (LicenceKind.fromId(entry.kind) == null) add("$where.kind '${entry.kind}' is not a known kind")
            if (entry.version.length > MAX_VERSION) add("$where.version is too long")
            if (entry.copyright.length > MAX_COPYRIGHT) add("$where.copyright is too long")
            if (entry.note.length > MAX_NOTE) add("$where.note is too long")
            if (entry.url.isNotBlank() && (!entry.url.startsWith("https://") || entry.url.length > MAX_URL)) {
                add("$where.url must be an https URL")
            }
            if (entry.licenceUrl.isNotBlank() &&
                (!entry.licenceUrl.startsWith("https://") || entry.licenceUrl.length > MAX_URL)
            ) {
                add("$where.licenceUrl must be an https URL")
            }
            if (entry.textAsset.isNotBlank()) {
                val asset = entry.textAsset
                if (asset.startsWith("/") || asset.contains("..") || asset.length > MAX_URL) {
                    add("$where.textAsset must be a relative asset path")
                }
            }
        }
    }

    /** Entries that ship inside the APK, grouped by kind, in registry order. */
    fun bundledByKind(registry: LicenceRegistry): List<Pair<LicenceKind, List<LicenceEntry>>> =
        LicenceKind.entries.mapNotNull { kind ->
            val entries = registry.entries.filter { it.bundled && it.kindOrLibrary == kind }
            if (entries.isEmpty()) null else kind to entries
        }

    fun externalEntries(registry: LicenceRegistry): List<LicenceEntry> = registry.entries.filterNot { it.bundled }

    /**
     * The NOTICE text. Deterministic so the root NOTICE file and the About screen can never drift:
     * the test in :app compares this output with the checked-in NOTICE file.
     */
    fun noticeText(registry: LicenceRegistry, appLabel: String, appVersion: String, appCopyright: String): String =
        buildString {
            appendLine(appLabel + " " + appVersion)
            appendLine(appCopyright)
            appendLine("Licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).")
            appendLine()
            appendLine("This product includes the following third-party components:")
            appendLine()
            registry.entries.forEach { entry ->
                append(entry.name)
                if (entry.version.isNotBlank()) append(" " + entry.version)
                appendLine(" - " + entry.licence)
                if (entry.copyright.isNotBlank()) appendLine("  " + entry.copyright)
                if (entry.url.isNotBlank()) appendLine("  " + entry.url)
                if (entry.licenceUrl.isNotBlank()) appendLine("  Licence: " + entry.licenceUrl)
                if (entry.note.isNotBlank()) appendLine("  " + entry.note)
                appendLine()
            }
            appendLine("Model weights are not bundled with the app. Each model is downloaded by the user under")
            appendLine("its own licence, shown in the model catalogue before download.")
        }
}

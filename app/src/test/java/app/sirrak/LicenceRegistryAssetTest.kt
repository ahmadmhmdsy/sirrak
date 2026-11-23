package app.sirrak

import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.LicenceKind
import app.sirrak.core.runtime.LicenceRegistry
import app.sirrak.core.runtime.LicenceRules
import app.sirrak.core.runtime.Sbom
import app.sirrak.core.runtime.SbomDocument
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P3.2 guards the single source of truth: the shipped licence registry is valid, every bundled
 * component's full licence text is really inside the APK assets, and the root NOTICE file matches
 * the registry byte-for-byte (the test writes the expected text to build/reports when it drifts).
 */
class LicenceRegistryAssetTest {

    private val registryFile = File("src/main/assets/config/licences.json")
    private val noticeFile = File("../NOTICE")
    private val sbomFile = File("../sbom.cdx.json")

    private fun registry(): LicenceRegistry {
        assertTrue("missing asset ${registryFile.absolutePath}", registryFile.isFile)
        return json.decodeFromString<LicenceRegistry>(registryFile.readText())
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
    }

    @Test
    fun shippedRegistryIsValidAndCoversEveryLayer() {
        val registry = registry()
        assertEquals(emptyList<String>(), LicenceRules.validate(registry))
        assertTrue("expected a real SBOM, saw ${registry.entries.size}", registry.entries.size >= 20)
        val kinds = registry.entries.map { it.kindOrLibrary }.toSet()
        assertTrue("the app itself must be listed", LicenceKind.APP in kinds)
        assertTrue("engines must be listed", LicenceKind.ENGINE in kinds)
        assertTrue("fonts must be listed", LicenceKind.FONT in kinds)
        assertTrue("user-downloaded models must be listed", LicenceKind.MODEL in kinds)
        assertTrue(
            "at least one model must be marked as downloaded separately",
            registry.entries.any { it.kindOrLibrary == LicenceKind.MODEL && !it.bundled },
        )
        assertEquals(
            "config must point at the shipped registry",
            "config/licences.json",
            AppConfig().licences.assetPath,
        )
    }

    @Test
    fun everyBundledLicenceTextIsShippedInsideTheApp() {
        val registry = registry()
        val withText = registry.entries.filter { it.textAsset.isNotBlank() }
        assertTrue("bundled components need an offline licence text", withText.size >= 10)
        withText.forEach { entry ->
            val file = File("src/main/assets/" + entry.textAsset)
            assertTrue("missing licence text ${entry.textAsset} for ${entry.id}", file.isFile)
            assertTrue("licence text ${entry.textAsset} looks empty", file.length() > 500L)
        }
        val apache = withText.filter { it.licence == "Apache-2.0" }
        assertTrue(apache.isNotEmpty())
        assertTrue(
            "Apache-2.0 text must contain its grant clause",
            File("src/main/assets/" + apache.first().textAsset).readText().contains("Apache License"),
        )
    }

    @Test
    fun noticeFileMatchesTheRegistry() {
        val registry = registry()
        val expected = LicenceRules.noticeText(
            registry = registry,
            appLabel = "Sirrak",
            appVersion = BuildConfig.VERSION_NAME,
            appCopyright = "Copyright 2026 Sirrak contributors",
        ).trim()
        assertTrue("root NOTICE must exist at ${noticeFile.absolutePath}", noticeFile.isFile)
        val actual = noticeFile.readText().trim()
        if (actual != expected) {
            val report = File("build/reports/notice-expected.txt").apply { parentFile?.mkdirs() }
            report.writeText(expected + "\n")
            fail("NOTICE is out of date with config/licences.json; expected text written to ${report.absolutePath}")
        }
    }

    @Test
    fun sbomFileMatchesTheRegistry() {
        val registry = registry()
        val expected = Sbom.json(registry, BuildConfig.VERSION_NAME).trim()
        assertTrue("root sbom.cdx.json must exist at ${sbomFile.absolutePath}", sbomFile.isFile)
        val actual = sbomFile.readText().trim()
        if (actual != expected) {
            val report = File("build/reports/sbom-expected.json").apply { parentFile?.mkdirs() }
            report.writeText(expected + "\n")
            fail("sbom.cdx.json is out of date with config/licences.json; expected document written to ${report.absolutePath}")
        }
    }

    @Test
    fun sbomCoversEveryRegistryEntry() {
        val registry = registry()
        assertTrue("root sbom.cdx.json must exist at ${sbomFile.absolutePath}", sbomFile.isFile)
        val document = json.decodeFromString<SbomDocument>(sbomFile.readText())
        assertEquals("CycloneDX", document.bomFormat)
        assertEquals("1.5", document.specVersion)
        assertEquals(BuildConfig.VERSION_NAME, document.metadata.component.version)
        val names = document.components.map { it.name } + document.metadata.component.name
        assertEquals("every registry entry must appear exactly once", registry.entries.size, names.size)
        registry.entries.forEach { entry ->
            assertTrue("SBOM misses ${entry.name}", entry.name in names)
        }
    }

    @Test
    fun everyRegistryEntryAppearsInTheNotice() {
        val notice = noticeFile.readText()
        registry().entries.forEach { entry ->
            assertTrue("NOTICE misses ${entry.name}", notice.contains(entry.name))
            assertTrue("NOTICE misses the licence of ${entry.name}", notice.contains(entry.licence))
        }
    }
}

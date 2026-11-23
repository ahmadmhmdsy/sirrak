package app.sirrak.core.runtime

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P3.7: the CycloneDX SBOM is generated from the licence registry, so these tests pin the mapping
 * (kind → component type/scope, licence → SPDX id or name), determinism, and the failure mode. The
 * byte-for-byte comparison with the checked-in root `sbom.cdx.json` lives in the :app asset test, next
 * to the NOTICE check, because that is where the shipped registry is parsed.
 */
class SbomTest {

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
    }

    private fun entry(
        id: String,
        name: String = id.replaceFirstChar { it.uppercase() },
        licence: String = "Apache-2.0",
        kind: String = "library",
        version: String = "",
        bundled: Boolean = true,
        url: String = "",
        licenceUrl: String = "https://example.org/licence",
    ) = LicenceEntry(
        id = id,
        name = name,
        version = version,
        licence = licence,
        kind = kind,
        url = url,
        licenceUrl = licenceUrl,
        bundled = bundled,
    )

    private fun registry() = LicenceRegistry(
        entries = listOf(
            entry(id = "sirrak", name = "Sirrak", kind = "app", licence = "Apache-2.0"),
            entry(id = "kotlin", name = "Kotlin", kind = "framework", version = "2.0.21", url = "https://kotlinlang.org"),
            entry(id = "llama-cpp", name = "llama.cpp", kind = "engine", licence = "MIT"),
            entry(id = "amiri", name = "Amiri", kind = "font", licence = "OFL-1.1"),
            entry(id = "qwen", name = "Qwen2.5", kind = "model", licence = "Apache-2.0", bundled = false),
            entry(id = "llama-3.2", name = "Llama 3.2", kind = "model", licence = "Llama-3.2-Community", bundled = false),
            entry(id = "junit", name = "JUnit", kind = "tool", licence = "EPL-1.0", version = "4.13.2", bundled = false),
        ),
    )

    @Test
    fun documentDeclaresCycloneDxAndTheSubjectComponent() {
        val document = Sbom.document(registry(), appVersion = "1.0.0-rc1")
        assertEquals("CycloneDX", document.bomFormat)
        assertEquals("1.5", document.specVersion)
        assertEquals(1, document.version)
        val app = document.metadata.component
        assertEquals("application", app.type)
        assertEquals("Sirrak", app.name)
        assertEquals("1.0.0-rc1", app.version)
        assertEquals("pkg:generic/sirrak@1.0.0-rc1", app.purl)
        assertEquals(listOf("Apache-2.0"), app.licenses?.map { it.id })
        // The app entry is the subject, never a second component.
        assertTrue(document.components.none { it.name == "Sirrak" })
        assertEquals(registry().entries.size - 1, document.components.size)
    }

    @Test
    fun registryKindsAndBundledFlagsMapToCycloneDxTypesAndScopes() {
        val components = Sbom.document(registry(), appVersion = "1.0.0").components.associateBy { it.name }
        assertEquals("framework", components.getValue("Kotlin").type)
        assertEquals("library", components.getValue("llama.cpp").type)
        assertEquals("file", components.getValue("Amiri").type)
        assertEquals("machine-learning-model", components.getValue("Qwen2.5").type)
        assertEquals("application", components.getValue("JUnit").type)
        assertEquals("required", components.getValue("Kotlin").scope)
        assertEquals("excluded", components.getValue("Qwen2.5").scope)
        assertEquals("excluded", components.getValue("JUnit").scope)
        val kind = components.getValue("llama.cpp").properties!!.associate { it.name to it.value }
        assertEquals("engine", kind["sirrak:kind"])
        assertEquals("true", kind["sirrak:bundled"])
    }

    @Test
    fun spdxIdentifiersStayIdsWhileNonSpdxLicencesBecomeNames() {
        val components = Sbom.document(registry(), appVersion = "1.0.0").components.associateBy { it.name }
        val apache = components.getValue("Kotlin").licenses!!.single()
        assertEquals("Apache-2.0", apache.id)
        assertNull(apache.name)
        assertEquals("https://example.org/licence", apache.url)
        val llama = components.getValue("Llama 3.2").licenses!!.single()
        assertNull(llama.id)
        assertEquals("Llama-3.2-Community", llama.name)
    }

    @Test
    fun purlsOmitUnknownVersionsAndStayUnique() {
        val document = Sbom.document(registry(), appVersion = "1.0.0")
        val byName = document.components.associateBy { it.name }
        assertEquals("pkg:generic/kotlin@2.0.21", byName.getValue("Kotlin").purl)
        assertEquals("pkg:generic/llama-cpp", byName.getValue("llama.cpp").purl)
        assertNull(byName.getValue("llama.cpp").version)
        val purls = document.components.mapNotNull { it.purl }
        assertEquals(purls.size, purls.distinct().size)
    }

    @Test
    fun outputIsDeterministicAndRoundTrips() {
        val registry = registry()
        val first = Sbom.json(registry, appVersion = "1.0.0-rc1")
        val second = Sbom.json(registry, appVersion = "1.0.0-rc1")
        assertEquals(first, second)
        assertTrue(first.endsWith("\n"))
        val decoded = json.decodeFromString<SbomDocument>(first)
        assertEquals(Sbom.document(registry, "1.0.0-rc1"), decoded)
    }

    @Test
    fun aRegistryWithoutTheAppEntryFailsLoudly() {
        val registry = LicenceRegistry(entries = listOf(entry(id = "kotlin")))
        try {
            Sbom.document(registry, appVersion = "1.0.0")
            fail("a registry without the app entry must not produce an SBOM")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("exactly one app entry"))
        }
    }
}

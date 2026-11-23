package app.sirrak.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesTest {

    private fun entry(
        id: String = "component",
        name: String = "Component",
        licence: String = "Apache-2.0",
        kind: String = "library",
        bundled: Boolean = true,
        textAsset: String = "",
    ) = LicenceEntry(
        id = id,
        name = name,
        licence = licence,
        kind = kind,
        bundled = bundled,
        textAsset = textAsset,
    )

    @Test
    fun aValidRegistryHasNoProblems() {
        val registry = LicenceRegistry(entries = listOf(entry(), entry(id = "other", name = "Other", kind = "font")))
        assertEquals(emptyList<String>(), LicenceRules.validate(registry))
    }

    @Test
    fun duplicateIdsBlankFieldsAndUnknownKindsAreRejected() {
        val registry = LicenceRegistry(
            entries = listOf(
                entry(id = "dup"),
                entry(id = "dup"),
                entry(id = "", name = ""),
                entry(id = "kind", kind = "bogus"),
                entry(id = "url", textAsset = "../escape.txt"),
            ),
        )
        val problems = LicenceRules.validate(registry)
        assertTrue(problems.toString(), problems.any { it.contains("duplicate ids") })
        assertTrue(problems.toString(), problems.any { it.contains("id is blank") })
        assertTrue(problems.toString(), problems.any { it.contains("name is blank") })
        assertTrue(problems.toString(), problems.any { it.contains("not a known kind") })
        assertTrue(problems.toString(), problems.any { it.contains("textAsset") })
    }

    @Test
    fun emptyRegistryIsRejected() {
        assertTrue(LicenceRules.validate(LicenceRegistry()).any { it.contains("empty") })
    }

    @Test
    fun kindsGroupBundledEntriesInDisplayOrder() {
        val registry = LicenceRegistry(
            entries = listOf(
                entry(id = "lib", kind = "library"),
                entry(id = "app", kind = "app"),
                entry(id = "font", kind = "font"),
                entry(id = "model", kind = "model", bundled = false),
            ),
        )
        val groups = LicenceRules.bundledByKind(registry)
        assertEquals(listOf(LicenceKind.APP, LicenceKind.LIBRARY, LicenceKind.FONT), groups.map { it.first })
        assertEquals(listOf("model"), LicenceRules.externalEntries(registry).map { it.id })
    }

    @Test
    fun noticeTextIsDeterministicAndListsEveryEntry() {
        val registry = LicenceRegistry(
            entries = listOf(
                entry(id = "a", name = "Alpha", licence = "MIT", kind = "engine", textAsset = "licenses/MIT.txt"),
                entry(id = "b", name = "Beta", licence = "OFL-1.1", kind = "font"),
            ),
        )
        val text = LicenceRules.noticeText(registry, "Sirrak", "1.0.0", "Copyright 2026 Sirrak contributors")
        assertEquals(text, LicenceRules.noticeText(registry, "Sirrak", "1.0.0", "Copyright 2026 Sirrak contributors"))
        assertTrue(text.contains("Alpha - MIT"))
        assertTrue(text.contains("Beta - OFL-1.1"))
        assertTrue(text.contains("Sirrak 1.0.0"))
        assertTrue(text.contains("Model weights are not bundled"))
    }

    @Test
    fun unknownKindFallsBackToLibraryWithoutCrashingTheUi() {
        val parsed = LicenceEntry(id = "x", name = "X", licence = "MIT", kind = "something-new")
        assertEquals(LicenceKind.LIBRARY, parsed.kindOrLibrary)
        assertNull(LicenceKind.fromId("something-new"))
        assertNotNull(LicenceKind.fromId("font"))
    }
}

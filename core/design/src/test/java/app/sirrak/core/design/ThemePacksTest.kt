package app.sirrak.core.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the theme-pack system (M4). The tests are deliberately data-driven: the pair list lives
 * in production code (ThemePack.contrastPairs), so adding a pack or a role extends the gate here for
 * free instead of needing a new assertion.
 */
class ThemePacksTest {

    @Test
    fun registryShipsTheAgreedPacksWithStableIds() {
        assertEquals(5, ThemePacks.all.size)
        assertEquals(
            listOf("girih_ink", "lapis_night", "turquoise_dawn", "madder_dusk", "paper_contrast"),
            ThemePacks.all.map { it.id },
        )
        assertEquals("girih_ink", ThemePacks.DEFAULT_ID)
        assertEquals(ThemePacks.default, ThemePacks.byId("girih_ink"))
        assertEquals(ThemePacks.all.size, ThemePacks.all.map { it.id }.toSet().size)
    }

    @Test
    fun everyPackHasLocalisedCopyBothModesAndItsOwnGround() {
        for (pack in ThemePacks.all) {
            assertNotEquals("${pack.id} needs an AR/EN name resource", 0, pack.nameRes)
            assertNotEquals("${pack.id} needs an AR/EN description resource", 0, pack.descriptionRes)
            assertNotEquals("${pack.id} light and dark must differ", pack.light, pack.dark)
        }
        val grounds = ThemePacks.all.map { it.light.background }.toSet()
        assertEquals("each pack needs a distinct light ground", ThemePacks.all.size, grounds.size)
        val darkGrounds = ThemePacks.all.map { it.dark.background }.toSet()
        assertEquals("each pack needs a distinct dark ground", ThemePacks.all.size, darkGrounds.size)
    }

    @Test
    fun unknownOrBlankPackIdFallsBackToDefault() {
        for (raw in listOf(null, "", "   ", "ghost", "girih_ink_2", "GIRIH_INK")) {
            assertEquals("'$raw' must fall back", ThemePacks.default, ThemePacks.byId(raw))
        }
        assertTrue(ThemePacks.isKnown("girih_ink"))
        assertTrue(ThemePacks.isKnown(" paper_contrast "))
        assertTrue(!ThemePacks.isKnown("ghost"))
        assertTrue(!ThemePacks.isKnown(null))
    }

    @Test
    fun everyGatedPairMeetsWcagInEveryPackAndMode() {
        val failures = mutableListOf<String>()
        for (pack in ThemePacks.all) {
            for (dark in listOf(false, true)) {
                val mode = if (dark) "dark" else "light"
                for (pair in pack.contrastPairs(dark)) {
                    val min = when (pair.tier) {
                        ContrastTier.BODY -> 4.5
                        ContrastTier.UI -> 3.0
                    }
                    val ratio = Contrast.ratio(pair.foreground, pair.background)
                    if (ratio < min) {
                        failures += "${pack.id} $mode ${pair.label} = ${"%.2f".format(ratio)} < $min"
                    }
                }
            }
        }
        assertTrue("WCAG failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun surfaceLadderIsMonotonicInEveryMode() {
        for (pack in ThemePacks.all) {
            for (dark in listOf(false, true)) {
                val p = if (dark) pack.dark else pack.light
                val ladder = listOf(
                    p.surfaceContainerLowest,
                    p.surfaceContainerLow,
                    p.surfaceContainer,
                    p.surfaceContainerHigh,
                    p.surfaceContainerHighest,
                )
                assertEquals("${pack.id} ${if (dark) "dark" else "light"} ladder must be 5 steps", 5, ladder.toSet().size)
            }
        }
    }

    @Test
    fun defaultPackReproducesTheM3ColourSchemeExactly() {
        val light = ThemePacks.default.colorScheme(false)
        assertEquals(Color(0xFFF7F3EA).value, light.background.value)
        assertEquals(Color(0xFF1B2430).value, light.onBackground.value)
        assertEquals(Color(0xFFFDFAF3).value, light.surface.value)
        assertEquals(Color(0xFF1D3F6E).value, light.primary.value)
        assertEquals(Color(0xFF1F6F6B).value, light.secondary.value)
        assertEquals(Color(0xFF6F561A).value, light.tertiary.value)
        assertEquals(Color(0xFFA63A2E).value, light.error.value)
        assertEquals(Color(0xFF8A8F93).value, light.outline.value)
        assertEquals(Color(0xFFE6DFCE).value, light.surfaceContainerHigh.value)
        assertEquals(Color(0xFFD8E2F0).value, light.primaryContainer.value)

        val dark = ThemePacks.default.colorScheme(true)
        assertEquals(Color(0xFF0F1416).value, dark.background.value)
        assertEquals(Color(0xFFE9E4D6).value, dark.onBackground.value)
        assertEquals(Color(0xFF9DB8DD).value, dark.primary.value)
        assertEquals(Color(0xFF253438).value, dark.surfaceContainerHighest.value)
        assertEquals(Color(0xFFE8A79C).value, dark.error.value)
        assertEquals(Color(0xFF000000).value, dark.scrim.value)
    }

    @Test
    fun everyPaletteRoleReachesItsColorSchemeSlot() {
        for (pack in ThemePacks.all) {
            for (dark in listOf(false, true)) {
                val p = if (dark) pack.dark else pack.light
                val s = pack.colorScheme(dark)
                assertEquals("${pack.id} background", Color(p.background).value, s.background.value)
                assertEquals("${pack.id} onBackground", Color(p.onBackground).value, s.onBackground.value)
                assertEquals("${pack.id} surface", Color(p.surface).value, s.surface.value)
                assertEquals("${pack.id} onSurface", Color(p.onSurface).value, s.onSurface.value)
                assertEquals("${pack.id} surfaceVariant", Color(p.surfaceVariant).value, s.surfaceVariant.value)
                assertEquals("${pack.id} onSurfaceVariant", Color(p.onSurfaceVariant).value, s.onSurfaceVariant.value)
                assertEquals("${pack.id} surfaceContainerLowest", Color(p.surfaceContainerLowest).value, s.surfaceContainerLowest.value)
                assertEquals("${pack.id} surfaceContainerLow", Color(p.surfaceContainerLow).value, s.surfaceContainerLow.value)
                assertEquals("${pack.id} surfaceContainer", Color(p.surfaceContainer).value, s.surfaceContainer.value)
                assertEquals("${pack.id} surfaceContainerHigh", Color(p.surfaceContainerHigh).value, s.surfaceContainerHigh.value)
                assertEquals("${pack.id} surfaceContainerHighest", Color(p.surfaceContainerHighest).value, s.surfaceContainerHighest.value)
                assertEquals("${pack.id} primary", Color(p.primary).value, s.primary.value)
                assertEquals("${pack.id} onPrimary", Color(p.onPrimary).value, s.onPrimary.value)
                assertEquals("${pack.id} primaryContainer", Color(p.primaryContainer).value, s.primaryContainer.value)
                assertEquals("${pack.id} onPrimaryContainer", Color(p.onPrimaryContainer).value, s.onPrimaryContainer.value)
                assertEquals("${pack.id} inversePrimary", Color(p.inversePrimary).value, s.inversePrimary.value)
                assertEquals("${pack.id} secondary", Color(p.secondary).value, s.secondary.value)
                assertEquals("${pack.id} onSecondary", Color(p.onSecondary).value, s.onSecondary.value)
                assertEquals("${pack.id} secondaryContainer", Color(p.secondaryContainer).value, s.secondaryContainer.value)
                assertEquals("${pack.id} onSecondaryContainer", Color(p.onSecondaryContainer).value, s.onSecondaryContainer.value)
                assertEquals("${pack.id} tertiary", Color(p.tertiary).value, s.tertiary.value)
                assertEquals("${pack.id} onTertiary", Color(p.onTertiary).value, s.onTertiary.value)
                assertEquals("${pack.id} tertiaryContainer", Color(p.tertiaryContainer).value, s.tertiaryContainer.value)
                assertEquals("${pack.id} onTertiaryContainer", Color(p.onTertiaryContainer).value, s.onTertiaryContainer.value)
                assertEquals("${pack.id} outline", Color(p.outline).value, s.outline.value)
                assertEquals("${pack.id} outlineVariant", Color(p.outlineVariant).value, s.outlineVariant.value)
                assertEquals("${pack.id} error", Color(p.error).value, s.error.value)
                assertEquals("${pack.id} onError", Color(p.onError).value, s.onError.value)
                assertEquals("${pack.id} errorContainer", Color(p.errorContainer).value, s.errorContainer.value)
                assertEquals("${pack.id} onErrorContainer", Color(p.onErrorContainer).value, s.onErrorContainer.value)
                assertEquals("${pack.id} inverseSurface", Color(p.inverseSurface).value, s.inverseSurface.value)
                assertEquals("${pack.id} inverseOnSurface", Color(p.inverseOnSurface).value, s.inverseOnSurface.value)
            }
        }
    }

    @Test
    fun decorationsFollowTheSelectedPack() {
        for (pack in ThemePacks.all) {
            for (dark in listOf(false, true)) {
                val p = if (dark) pack.dark else pack.light
                val d = pack.decorations(dark)
                assertEquals("${pack.id} girihLine", Color(p.girihLine).value, d.girihLine.value)
                assertEquals("${pack.id} brass", Color(p.brass).value, d.brass.value)
                assertEquals("${pack.id} brassText", Color(p.brassText).value, d.brassText.value)
                assertEquals("${pack.id} hairline", Color(p.hairline).value, d.hairline.value)
            }
        }
        // The default pack keeps M1–M3 decorations byte-identical.
        val legacy = sirrakTokens(dark = false)
        assertEquals(Color(0xFFB08D3E).value, legacy.decorations.brass.value)
        assertEquals(Color(0xFFE3DCCB).value, legacy.decorations.hairline.value)
        assertEquals(Color(0xFF6F561A).value, legacy.decorations.brassText.value)
    }

    @Test
    fun contrastMathMatchesKnownWcagRatios() {
        // Black on white is 21:1; identical colours 1:1.
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF123456, 0xFF123456), 0.001)
    }
}
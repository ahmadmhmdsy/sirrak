package app.sirrak.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the app-wide text-size presets (M6 THM-6). The registry is data: adding a preset is one
 * [FontScales.all] entry plus its AR/EN label, and every consumer (settings UI, root density wrapper)
 * follows without a new branch. Unknown or numeric persisted values must always resolve to something
 * usable, never crash and never leave the 85..130 range.
 */
class FontScalesTest {

    @Test
    fun `registry ships the four agreed presets in order`() {
        assertEquals(4, FontScales.all.size)
        assertEquals(listOf(85, 100, 115, 130), FontScales.all.map { it.percent })
    }

    @Test
    fun `default preset is one hundred percent`() {
        assertEquals("default", FontScales.DEFAULT_ID)
        assertEquals(100, FontScales.default.percent)
        assertEquals(1f, FontScales.default.factor, 0f)
        assertEquals(FontScales.default, FontScales.byId(FontScales.DEFAULT_ID))
    }

    @Test
    fun `every preset has a stable unique id and an AR EN label`() {
        assertEquals(FontScales.all.size, FontScales.all.map { it.id }.toSet().size)
        for (scale in FontScales.all) {
            assertTrue("${scale.id} id must not be blank", scale.id.isNotBlank())
            assertNotEquals("${scale.id} needs an AR/EN label resource", 0, scale.labelRes)
        }
    }

    @Test
    fun `unknown null and blank persisted ids fall back to default`() {
        for (raw in listOf(null, "", "   ", "ghost", "small_2")) {
            assertEquals("'$raw' must fall back", FontScales.default, FontScales.byId(raw))
        }
    }

    @Test
    fun `ids are trimmed before matching`() {
        assertEquals("large", FontScales.byId("  large  ").id)
    }

    @Test
    fun `numeric persisted values clamp into the supported range`() {
        assertEquals(85, FontScales.byId("50").percent)
        assertEquals(85, FontScales.byId("80").percent)
        assertEquals(130, FontScales.byId("140").percent)
        assertEquals(130, FontScales.byId("999").percent)
    }

    @Test
    fun `numeric persisted values snap to the nearest preset`() {
        assertEquals(115, FontScales.byId("108").percent)
        assertEquals(115, FontScales.byId("112").percent)
        assertEquals(100, FontScales.byId("105").percent)
        assertEquals(130, FontScales.byId("125").percent)
    }

    @Test
    fun `factor is the percent over one hundred`() {
        for (scale in FontScales.all) {
            assertEquals("${scale.id} factor", scale.percent / 100f, scale.factor, 0.0001f)
        }
    }

    @Test
    fun `applying a preset multiplies only the platform font scale`() {
        assertEquals(1f, FontScales.default.applyTo(1f), 0f)
        assertEquals(1.495f, FontScales.byId("large").applyTo(1.3f), 0.0001f)
        assertEquals(0.7225f, FontScales.byId("small").applyTo(0.85f), 0.0001f)
        assertEquals(2.6f, FontScales.byId("largest").applyTo(2f), 0.0001f)
    }
}

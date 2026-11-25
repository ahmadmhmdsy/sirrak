package app.sirrak.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf must never render Arabic-Indic digits in a size: an Arabic-first screen with a number the
 * user cannot compare to a file manager is a defect, so the locale is pinned and tested.
 */
class MediaTextTest {

    @Test
    fun sizesUseLatinDigitsAndLatinUnits() {
        val samples = listOf(
            MediaText.bytes(512L) to "512 B",
            MediaText.bytes(2_048L) to "2 kB",
            MediaText.bytes(5_000_000L) to "5 MB",
            MediaText.bytes(1_250_000_000L) to "1.3 GB",
        )
        samples.forEach { (actual, expected) ->
            assertEquals(expected, actual)
            assertTrue("size must be ASCII: " + actual, actual.all { it.code < 128 })
        }
    }

    @Test
    fun extensionIsOnlyASafeSuffix() {
        assertEquals("pdf", MediaText.extensionOf("تقرير.pdf"))
        assertEquals("m4a", MediaText.extensionOf("مذكرة.M4A"))
        assertEquals("", MediaText.extensionOf("بلا امتداد"))
        assertEquals("", MediaText.extensionOf("اسم."))
    }
}

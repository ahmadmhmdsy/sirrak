package app.sirrak.core.design

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings values mix Arabic labels with Latin technical values (versions, file names, licence ids).
 * A Latin value must be laid out left-to-right even inside an RTL screen, or bidi reorders it
 * ("0.1.0-m4" renders as "m4-0.1.0"). Arabic values must keep content direction so the script shapes
 * and orders correctly. One rule, shared by every settings row type.
 */
class SettingsValueDirectionTest {

    @Test
    fun latinVersionValueReadsLeftToRight() {
        assertEquals(TextDirection.Ltr, settingsValueTextDirection("0.1.0-m4"))
    }

    @Test
    fun arabicValueKeepsContentDirection() {
        assertEquals(TextDirection.Content, settingsValueTextDirection("غير محمّل"))
    }

    @Test
    fun latinLicenceLineReadsLeftToRight() {
        assertEquals(TextDirection.Ltr, settingsValueTextDirection("Amiri · SIL OFL 1.1"))
    }

    @Test
    fun arabicValueWithLatinDigitsStaysContentDirected() {
        assertEquals(TextDirection.Content, settingsValueTextDirection("النموذج Qwen2.5"))
    }
}

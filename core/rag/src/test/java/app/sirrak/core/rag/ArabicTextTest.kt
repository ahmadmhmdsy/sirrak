package app.sirrak.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicTextTest {

    @Test
    fun normalize_stripsDiacriticsAndTatweel() {
        assertEquals("مرحبا", ArabicText.normalizeForMatch("مَرْحَبًا"))
        assertEquals("كتاب", ArabicText.normalizeForMatch("كـــتاب"))
    }

    @Test
    fun normalize_unifiesAlefForms() {
        assertEquals("احمد ابراهيم ادم", ArabicText.normalizeForMatch("أحمد إبراهيم آدم"))
    }

    @Test
    fun normalize_foldsTaMarbutaAndAlefMaqsura() {
        assertEquals("مدرسه", ArabicText.normalizeForMatch("مدرسة"))
        assertEquals("علي", ArabicText.normalizeForMatch("على"))
    }

    @Test
    fun normalize_foldsHamzaForms() {
        assertEquals("موسسه", ArabicText.normalizeForMatch("مؤسسة"))
        assertEquals("مسوول", ArabicText.normalizeForMatch("مسؤول"))
    }

    @Test
    fun normalize_convertsArabicIndicDigits() {
        assertEquals("2026 12", ArabicText.normalizeForMatch("٢٠٢٦ ۱۲"))
    }

    @Test
    fun normalize_removesInvisibleCharactersAndCollapsesWhitespace() {
        assertEquals("نص عربي", ArabicText.normalizeForMatch("  نص\u200C\n\n  عربي  "))
    }

    @Test
    fun tokens_keepsArabicLatinAndDigits() {
        assertEquals(
            listOf("ما", "هو", "موعد", "2026", "meeting"),
            ArabicText.tokens("ما هو موعد ٢٠٢٦؟ Meeting!"),
        )
    }

    @Test
    fun tokens_dropsOverlongRuns() {
        assertTrue(ArabicText.tokens("x".repeat(80)).isEmpty())
            assertEquals(listOf("نص"), ArabicText.tokens("نص " + "y".repeat(80)))
    }

    @Test
    fun splitSentences_handlesArabicQuestionMarkAndNewlines() {
        assertEquals(
            listOf("ما هذا؟", "هذا كتاب.", "جملة ثالثة"),
            ArabicText.splitSentences("ما هذا؟ هذا كتاب.\nجملة ثالثة"),
        )
    }

    @Test
    fun splitSentences_doesNotSplitDecimals() {
        assertEquals(listOf("النموذج 1.5B جيد."), ArabicText.splitSentences("النموذج 1.5B جيد."))
    }

    @Test
    fun splitSentences_handlesEnglishTerminals() {
        assertEquals(listOf("One.", "Two!", "Three"), ArabicText.splitSentences("One. Two! Three"))
    }

    @Test
    fun splitSentences_emptyInputIsEmpty() {
        assertTrue(ArabicText.splitSentences("").isEmpty())
        assertTrue(ArabicText.splitSentences("   ").isEmpty())
    }
}

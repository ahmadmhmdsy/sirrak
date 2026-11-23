package app.sirrak.core.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the dependency-free Markdown reading surface (M6, docs 17 A3). The parser must stay
 * deterministic and total: assistant text arrives token by token, so every prefix of a message has to
 * parse without throwing — an unterminated fence is a code block to end of input, not a crash.
 *
 * Inline formatting is a render-time concern: [MarkdownParser] keeps the raw markers so a fenced block
 * showing Markdown source reads correctly, while [inlineMarkdown] produces the styled text.
 */
class MarkdownTest {

    private fun parse(source: String): List<MarkdownBlock> = MarkdownParser.parse(source)

    // ---- block structure -------------------------------------------------

    @Test
    fun emptySourceParsesToNoBlocks() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   \n\n\t\n").isEmpty())
        assertTrue(parse("\r\n\r\n").isEmpty())
    }

    @Test
    fun atxHeadingsCaptureLevelAndText() {
        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "عنوان"),
                MarkdownBlock.Heading(2, "Two"),
                MarkdownBlock.Heading(6, "Deep"),
            ),
            parse("# عنوان\n\n## Two\n\n###### Deep"),
        )
    }

    @Test
    fun sevenHashesAndMissingSpaceAreNotHeadings() {
        assertEquals(listOf(MarkdownBlock.Paragraph("####### too deep")), parse("####### too deep"))
        assertEquals(listOf(MarkdownBlock.Paragraph("#hashtag")), parse("#hashtag"))
    }

    @Test
    fun paragraphsJoinSoftLineBreaksWithASingleSpace() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("first line second line"), MarkdownBlock.Paragraph("later")),
            parse("first line\nsecond line\n\nlater"),
        )
    }

    @Test
    fun fencedCodeBlockKeepsRawTextAndLanguage() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock(language = "kotlin", code = "val x = 1\nprintln(x)")),
            parse("```kotlin\nval x = 1\nprintln(x)\n```"),
        )
    }

    @Test
    fun fencedCodeBlockWithoutLanguageHasNullLanguage() {
        val block = parse("```\nplain\ncode\n```").single() as MarkdownBlock.CodeBlock
        assertNull(block.language)
        assertEquals("plain\ncode", block.code)
    }

    @Test
    fun unterminatedFenceParsesToEndOfInput() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock(language = "python", code = "print(1)\nprint(2)")),
            parse("```python\nprint(1)\nprint(2)"),
        )
    }

    @Test
    fun formattingMarkersInsideAFenceStayLiteral() {
        val block = parse(
            "```\n**not bold**\n- not a list\n> not a quote\n```",
        ).single() as MarkdownBlock.CodeBlock
        assertEquals("**not bold**\n- not a list\n> not a quote", block.code)
    }

    @Test
    fun bulletListAcceptsDashStarAndBulletMarkers() {
        assertEquals(
            listOf(MarkdownBlock.BulletList(listOf("one", "two", "three"))),
            parse("- one\n* two\n• three"),
        )
    }

    @Test
    fun orderedListCapturesItsStartNumber() {
        assertEquals(
            listOf(MarkdownBlock.OrderedList(start = 3, items = listOf("three", "four"))),
            parse("3. three\n4. four"),
        )
        assertEquals(listOf(MarkdownBlock.OrderedList(start = 1, items = listOf("one"))), parse("1. one"))
    }

    @Test
    fun blockquoteCollectsConsecutiveQuotedLines() {
        assertEquals(listOf(MarkdownBlock.Quote("first\nsecond")), parse("> first\n> second"))
    }

    @Test
    fun horizontalRulesParseAsRuleBlocks() {
        assertEquals(
            listOf(MarkdownBlock.Rule, MarkdownBlock.Rule, MarkdownBlock.Rule),
            parse("---\n\n***\n\n___"),
        )
    }

    @Test
    fun tableParsesHeaderRowAndTrimsCells() {
        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    header = listOf("Name", "Age"),
                    rows = listOf(listOf("Ada", "36"), listOf("Omar", "41")),
                ),
                MarkdownBlock.Paragraph("After."),
            ),
            parse("| Name | Age |\n| --- | :---: |\n|  Ada | 36  |\n| Omar | 41 |\n\nAfter."),
        )
    }

    @Test
    fun raggedTableRowIsPaddedWithEmptyCells() {
        val table = parse("| A | B | C |\n|---|---|---|\n| 1 | 2 |").single() as MarkdownBlock.Table
        assertEquals(listOf("A", "B", "C"), table.header)
        assertEquals(listOf(listOf("1", "2", "")), table.rows)
    }

    @Test
    fun dashInsideParagraphIsNotAList() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("Use a dash - like this and keep - bullet text as prose.")),
            parse("Use a dash - like this\nand keep - bullet text as prose."),
        )
    }

    @Test
    fun arabicParagraphIsPreservedVerbatim() {
        val arabic = "مرحبًا بك في سرّاق. الردّ العربي يُحفظ كما هو، بلا انعكاس."
        assertEquals(listOf(MarkdownBlock.Paragraph(arabic)), parse(arabic))
    }

    @Test
    fun mixedDocumentKeepsBlockOrder() {
        val source = """
            ## الجواب

            نصّ **مهم** هنا.

            - أول
            - ثانٍ

            ```ts
            const x = 1
            ```
        """.trimIndent()
        assertEquals(
            listOf(
                MarkdownBlock.Heading(2, "الجواب"),
                MarkdownBlock.Paragraph("نصّ **مهم** هنا."),
                MarkdownBlock.BulletList(listOf("أول", "ثانٍ")),
                MarkdownBlock.CodeBlock(language = "ts", code = "const x = 1"),
            ),
            parse(source),
        )
    }

    @Test
    fun crlfInputParsesLikeLf() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("first"), MarkdownBlock.BulletList(listOf("second"))),
            parse("first\r\n\r\n- second"),
        )
    }

    // ---- inline formatting ----------------------------------------------

    @Test
    fun boldItalicAndInlineCodeProduceSpanStyles() {
        val annotated = inlineMarkdown("This is **bold**, *italic* and `code`.")

        assertEquals("This is bold, italic and code.", annotated.text)

        val bold = annotated.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold }
        assertNotNull(bold)
        assertEquals("bold", annotated.text.substring(bold!!.start, bold.end))

        val italic = annotated.spanStyles.firstOrNull { it.item.fontStyle == FontStyle.Italic }
        assertNotNull(italic)
        assertEquals("italic", annotated.text.substring(italic!!.start, italic.end))

        val code = annotated.spanStyles.firstOrNull { it.item.fontFamily == FontFamily.Monospace }
        assertNotNull(code)
        assertEquals("code", annotated.text.substring(code!!.start, code.end))
    }

    @Test
    fun strikeThroughProducesLineThrough() {
        val annotated = inlineMarkdown("~~gone~~")
        assertEquals("gone", annotated.text)
        assertNotNull(annotated.spanStyles.firstOrNull { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun linkRendersAsItsLabelOnly() {
        val source = "See [the docs](https://example.com/page) now."
        val annotated = inlineMarkdown(source)

        assertEquals("See the docs now.", annotated.text)
        assertTrue("the URL must not leak into the prose", !annotated.text.contains("example.com"))

        val links = markdownLinks(source)
        assertEquals(1, links.size)
        assertEquals("the docs", links[0].label)
        assertEquals("https://example.com/page", links[0].url)
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        for (raw in listOf("**bold", "a * b", "~~strike", "`code", "_word", "[label](url")) {
            assertEquals(raw, inlineMarkdown(raw).text)
        }
    }

    @Test
    fun intrawordUnderscoresStayLiteral() {
        val annotated = inlineMarkdown("snake_case_name and _real italic_")
        assertEquals("snake_case_name and real italic", annotated.text)

        val italics = annotated.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italics.size)
        assertEquals("real italic", annotated.text.substring(italics[0].start, italics[0].end))
    }

    @Test
    fun inlineCodeKeepsMarkersLiteral() {
        val annotated = inlineMarkdown("`**not bold**`")
        assertEquals("**not bold**", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(annotated.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun boldCanNestItalic() {
        val annotated = inlineMarkdown("**bold *both* bold**")
        assertEquals("bold both bold", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }
}

package app.sirrak.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * A dependency-free Markdown reading surface for assistant answers (M6, docs 17 A3).
 *
 * The file has two halves that never share state:
 *
 *  * [MarkdownParser] turns the raw message into typed [MarkdownBlock]s. It is deterministic and total:
 *    a streaming answer arrives token by token, so every prefix of a message must parse without
 *    throwing. An unterminated fence becomes a [MarkdownBlock.CodeBlock] that runs to the end of input.
 *  * [MarkdownText] paints those blocks with Material 3 tokens only: no hex literal, no hardcoded type
 *    size, no hardcoded English label. Inline emphasis is a render-time concern, so [inlineMarkdown]
 *    styles bold, italic, inline code, strike-through and links, while a fenced block keeps its source raw.
 */

/** One semantic block of a Markdown message. */
sealed interface MarkdownBlock {

    /** An ATX heading, [level] 1..6. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** Running prose; soft line breaks were joined with a single space. */
    data class Paragraph(val text: String) : MarkdownBlock

    /** A fenced code block. [language] is the info-string tag when present, otherwise null. */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock

    /** A "-", "*" or "•" list. */
    data class BulletList(val items: List<String>) : MarkdownBlock

    /** A "1." list; [start] is the first number, so a message may continue a list. */
    data class OrderedList(val start: Int, val items: List<String>) : MarkdownBlock

    /** A "> " quote; consecutive quoted lines are kept on their own lines. */
    data class Quote(val text: String) : MarkdownBlock

    /** A GitHub-style pipe table: header plus data rows padded to the header's width. */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock

    /** A thematic break: "---", "***" or "___". */
    data object Rule : MarkdownBlock
}

/**
 * Line-oriented Markdown to [MarkdownBlock] parser. Pure Kotlin, allocation-light, and total for any
 * input string — in particular for a partially streamed answer.
 *
 * Rules, in precedence order: fenced code (backtick fence, optional language tag), ATX heading,
 * thematic rule, blockquote, pipe table (a header line containing "|" followed by a separator row),
 * ordered list, bullet list, paragraph. Blocks are separated by blank lines and a paragraph's internal
 * newlines are joined with a single space. Formatting markers inside a code fence stay raw.
 */
object MarkdownParser {

    fun parse(source: String): List<MarkdownBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }

            val fence = fenceOf(line)
            if (fence != null) {
                val body = mutableListOf<String>()
                index++
                while (index < lines.size && !isFenceClose(lines[index], fence.ticks)) {
                    body += lines[index]
                    index++
                }
                if (index < lines.size) index++
                blocks += MarkdownBlock.CodeBlock(
                    language = fence.language,
                    code = body.joinToString("\n"),
                )
                continue
            }

            val heading = headingOf(line)
            if (heading != null) {
                blocks += MarkdownBlock.Heading(level = heading.level, text = heading.text)
                index++
                continue
            }

            if (isRule(line)) {
                blocks += MarkdownBlock.Rule
                index++
                continue
            }

            if (isQuoteLine(line)) {
                val quoted = mutableListOf<String>()
                while (index < lines.size && isQuoteLine(lines[index])) {
                    quoted += quoteText(lines[index])
                    index++
                }
                blocks += MarkdownBlock.Quote(quoted.joinToString("\n").trim())
                continue
            }

            if (startsTable(lines, index)) {
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                index += 2
                while (index < lines.size && isTableRowLine(lines[index])) {
                    rows += padRow(splitRow(lines[index]), header.size)
                    index++
                }
                blocks += MarkdownBlock.Table(header = header, rows = rows)
                continue
            }

            val ordered = orderedItemPattern.matchEntire(line)
            if (ordered != null) {
                val start = ordered.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<String>()
                var current: MatchResult? = ordered
                while (current != null) {
                    items += current.groupValues[2].trim()
                    index++
                    current = if (index < lines.size) orderedItemPattern.matchEntire(lines[index]) else null
                }
                blocks += MarkdownBlock.OrderedList(start = start, items = items)
                continue
            }

            val bullet = bulletItemPattern.matchEntire(line)
            if (bullet != null) {
                val items = mutableListOf<String>()
                var current: MatchResult? = bullet
                while (current != null) {
                    items += current.groupValues[1].trim()
                    index++
                    current = if (index < lines.size) bulletItemPattern.matchEntire(lines[index]) else null
                }
                blocks += MarkdownBlock.BulletList(items)
                continue
            }

            val paragraph = mutableListOf<String>()
            while (index < lines.size && isParagraphLine(lines, index)) {
                paragraph += lines[index].trim()
                index++
            }
            if (paragraph.isEmpty()) {
                // Defensive: never loop forever on an input shape no rule claimed.
                paragraph += line.trim()
                index++
            }
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
        }

        return blocks
    }
}

// ---- line classification -------------------------------------------------

private val orderedItemPattern = Regex("""^([0-9]{1,9})\.\s+(.+)$""")
private val bulletItemPattern = Regex("""^[-*•]\s+(.+)$""")
private val tableSeparatorCell = Regex("""^:?-+:?$""")

private data class Fence(val ticks: Int, val language: String?)

private data class HeadingSpec(val level: Int, val text: String)

private fun fenceOf(line: String): Fence? {
    val trimmed = line.trimStart()
    val marker = "```"
    if (!trimmed.startsWith(marker)) return null
    val ticks = trimmed.takeWhile { it == '`' }.length
    if (ticks < 3) return null
    val info = trimmed.substring(ticks).trim()
    val language = info.takeWhile { !it.isWhitespace() }.ifBlank { null }
    return Fence(ticks = ticks, language = language)
}

private fun isFenceClose(line: String, ticks: Int): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= ticks && trimmed.all { it == '`' }
}

private fun headingOf(line: String): HeadingSpec? {
    val hashes = line.takeWhile { it == '#' }
    if (hashes.isEmpty() || hashes.length > 6) return null
    val rest = line.substring(hashes.length)
    if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
    return HeadingSpec(level = hashes.length, text = rest.trim())
}

private fun isRule(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < 3) return false
    val marker = trimmed[0]
    if (marker != '-' && marker != '*' && marker != '_') return false
    return trimmed.all { it == marker }
}

private fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

private fun quoteText(line: String): String {
    var text = line.trimStart().substring(1)
    if (text.startsWith(" ")) text = text.substring(1)
    return text.trimEnd()
}

private fun startsTable(lines: List<String>, index: Int): Boolean =
    index + 1 < lines.size &&
        lines[index].contains('|') &&
        isTableSeparator(lines[index + 1])

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return false
    val cells = splitRow(trimmed)
    return cells.isNotEmpty() && cells.all { tableSeparatorCell.matches(it) }
}

private fun splitRow(line: String): List<String> {
    var cells = line.trim()
    if (cells.startsWith("|")) cells = cells.substring(1)
    if (cells.endsWith("|")) cells = cells.dropLast(1)
    return cells.split('|').map { it.trim() }
}

private fun padRow(cells: List<String>, width: Int): List<String> =
    List(width) { column -> cells.getOrElse(column) { "" } }

private fun isTableRowLine(line: String): Boolean =
    line.isNotBlank() && line.contains('|') && !isBlockStart(line)

private fun isParagraphLine(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    return line.isNotBlank() && !isBlockStart(line) && !startsTable(lines, index)
}

private fun isBlockStart(line: String): Boolean =
    fenceOf(line) != null ||
        headingOf(line) != null ||
        isRule(line) ||
        isQuoteLine(line) ||
        orderedItemPattern.matchEntire(line) != null ||
        bulletItemPattern.matchEntire(line) != null

// ---- inline formatting ---------------------------------------------------

/** A link found in inline text; the renderer can grow tap targets from it later. */
data class MarkdownLink(
    val label: String,
    val url: String,
    val start: Int,
    val end: Int,
)

/** Every label of a [label](url) pair, with its range in the rendered text. */
fun markdownLinks(text: String): List<MarkdownLink> {
    val renderer = InlineRenderer(codeStyle = null, linkStyle = null)
    renderer.append(text)
    return renderer.links
}

/**
 * Styled text for one inline Markdown run: bold, italic, inline code, strike-through and links
 * (rendered as their label). Unmatched markers are left literal, code spans are never interpreted, and
 * intraword underscores (snake_case_name) are not emphasis.
 */
fun inlineMarkdown(text: String): AnnotatedString = inlineMarkdown(text, codeStyle = null)

/** Renderer overload: inline code inherits a theme token colour instead of the plain monospace fallback. */
internal fun inlineMarkdown(text: String, codeStyle: SpanStyle?): AnnotatedString =
    inlineMarkdown(text = text, codeStyle = codeStyle, linkStyle = null)

private fun inlineMarkdown(text: String, codeStyle: SpanStyle?, linkStyle: SpanStyle?): AnnotatedString {
    val renderer = InlineRenderer(codeStyle = codeStyle, linkStyle = linkStyle)
    renderer.append(text)
    return renderer.toAnnotatedString()
}

private class InlineRenderer(
    private val codeStyle: SpanStyle?,
    private val linkStyle: SpanStyle?,
) {
    private val builder = AnnotatedString.Builder()
    val links = mutableListOf<MarkdownLink>()
    private var length = 0

    fun toAnnotatedString(): AnnotatedString = builder.toAnnotatedString()

    fun append(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '`' -> {
                    val close = text.indexOf('`', index + 1)
                    if (close < 0) {
                        appendLiteral(text[index])
                        index++
                    } else {
                        val code = text.substring(index + 1, close)
                        val style = codeStyle ?: SpanStyle(fontFamily = FontFamily.Monospace)
                        builder.pushStyle(style)
                        appendRaw(code)
                        builder.pop()
                        index = close + 1
                    }
                }

                char == '*' && index + 1 < text.length && text[index + 1] == '*' -> {
                    val close = text.indexOf("**", index + 2)
                    if (close < 0) {
                        appendLiteral(text[index])
                        index++
                    } else {
                        builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(index + 2, close))
                        builder.pop()
                        index = close + 2
                    }
                }

                char == '*' -> {
                    val close = findStarClose(text, index + 1)
                    if (close < 0) {
                        appendLiteral(text[index])
                        index++
                    } else {
                        builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(index + 1, close))
                        builder.pop()
                        index = close + 1
                    }
                }

                char == '_' && canOpenUnderscore(text, index) -> {
                    val close = findUnderscoreClose(text, index + 1)
                    if (close < 0) {
                        appendLiteral(text[index])
                        index++
                    } else {
                        builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(index + 1, close))
                        builder.pop()
                        index = close + 1
                    }
                }

                char == '~' && index + 1 < text.length && text[index + 1] == '~' -> {
                    val close = text.indexOf("~~", index + 2)
                    if (close < 0) {
                        appendLiteral(text[index])
                        index++
                    } else {
                        builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(text.substring(index + 2, close))
                        builder.pop()
                        index = close + 2
                    }
                }

                char == '[' -> {
                    val next = appendLink(text, index)
                    if (next < 0) {
                        appendLiteral(text[index])
                        index++
                    } else {
                        index = next
                    }
                }

                else -> {
                    appendLiteral(text[index])
                    index++
                }
            }
        }
    }

    /** Returns the index after a parsed link, or -1 when this "[" does not start one. */
    private fun appendLink(text: String, index: Int): Int {
        val closeBracket = text.indexOf(']', index + 1)
        if (closeBracket < 0) return -1
        val openParen = closeBracket + 1
        if (openParen >= text.length || text[openParen] != '(') return -1
        val closeParen = text.indexOf(')', openParen + 1)
        if (closeParen <= openParen + 1) return -1

        val label = text.substring(index + 1, closeBracket)
        val url = text.substring(openParen + 1, closeParen).trim()
        val start = length
        if (linkStyle != null) builder.pushStyle(linkStyle)
        append(label)
        if (linkStyle != null) builder.pop()
        links += MarkdownLink(label = label, url = url, start = start, end = length)
        return closeParen + 1
    }

    private fun appendLiteral(char: Char) {
        builder.append(char)
        length++
    }

    private fun appendRaw(text: String) {
        builder.append(text)
        length += text.length
    }
}

private fun canOpenUnderscore(text: String, index: Int): Boolean =
    index == 0 || !text[index - 1].isLetterOrDigit()

private fun findUnderscoreClose(text: String, from: Int): Int {
    var index = from
    while (index < text.length) {
        if (text[index] == '_' && (index + 1 == text.length || !text[index + 1].isLetterOrDigit())) {
            return index
        }
        index++
    }
    return -1
}

private fun findStarClose(text: String, from: Int): Int {
    var index = from
    while (index < text.length) {
        if (text[index] == '*') {
            val nextIsStar = index + 1 < text.length && text[index + 1] == '*'
            if (!nextIsStar) return index
        }
        index++
    }
    return -1
}

// ---- renderer ------------------------------------------------------------

private val blockGap = 12.dp
private val headingGap = 20.dp
private val listMarkerWidth = 18.dp
private val listMarkerGap = 4.dp
private val quoteBarWidth = 2.dp
private val quoteBarGap = 12.dp
private val codePadding = 12.dp
private val codeVerticalPadding = 10.dp
private val tableCellHorizontalPadding = 8.dp
private val tableCellVerticalPadding = 6.dp
private val tableDividerThickness = 1.dp

private const val bulletGlyph = "•"

/**
 * Renders a Markdown message as calm, Arabic-first reading blocks.
 *
 * Every colour and type size comes from [MaterialTheme]; code is the one exception to the two-family
 * rule and is set in [FontFamily.Monospace] and forced left-to-right, because source must not bidi-flip
 * inside an RTL screen. Headings scale from the caller's reading [style] (itself a theme token) so the
 * hierarchy stays correct whether the surface passes 16sp bodyLarge or the 19sp assistant prose role.
 *
 * Limitations: no nested lists, no inline images, no clickable links (labels only), and a table whose
 * separator row has not streamed in yet reads as prose until it arrives.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    if (blocks.isEmpty()) return

    val inlineCodeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                val gap = if (block is MarkdownBlock.Heading || block is MarkdownBlock.Rule) headingGap else blockGap
                Spacer(Modifier.height(gap))
            }
            when (block) {
                is MarkdownBlock.Heading -> MarkdownHeadingBlock(block, style, color, inlineCodeStyle)
                is MarkdownBlock.Paragraph -> MarkdownParagraphBlock(block, style, color, inlineCodeStyle)
                is MarkdownBlock.CodeBlock -> MarkdownCodeBlock(block)
                is MarkdownBlock.BulletList -> MarkdownBulletListBlock(block, style, color, inlineCodeStyle)
                is MarkdownBlock.OrderedList -> MarkdownOrderedListBlock(block, style, color, inlineCodeStyle)
                is MarkdownBlock.Quote -> MarkdownQuoteBlock(block, style, inlineCodeStyle)
                is MarkdownBlock.Table -> MarkdownTableBlock(block, color)
                MarkdownBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun MarkdownHeadingBlock(
    block: MarkdownBlock.Heading,
    baseStyle: TextStyle,
    color: Color,
    inlineCodeStyle: SpanStyle,
) {
    Text(
        text = inlineMarkdown(block.text, inlineCodeStyle),
        style = markdownHeadingStyle(baseStyle, block.level).copy(textDirection = TextDirection.Content),
        color = color,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun MarkdownParagraphBlock(
    block: MarkdownBlock.Paragraph,
    baseStyle: TextStyle,
    color: Color,
    inlineCodeStyle: SpanStyle,
) {
    Text(
        text = inlineMarkdown(block.text, inlineCodeStyle),
        style = baseStyle.copy(textDirection = TextDirection.Content),
        color = color,
    )
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.CodeBlock) {
    // Source is LTR even inside an Arabic screen: the layout direction is pinned too, otherwise an
    // RTL scroll container starts at the right edge of a long line instead of at its beginning.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = codePadding, vertical = codeVerticalPadding),
            ) {
                Text(
                    text = block.code,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun MarkdownBulletListBlock(
    block: MarkdownBlock.BulletList,
    baseStyle: TextStyle,
    color: Color,
    inlineCodeStyle: SpanStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(listMarkerGap)) {
        block.items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = bulletGlyph,
                    style = baseStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(listMarkerWidth),
                )
                MarkdownListItemText(item, baseStyle, color, inlineCodeStyle)
            }
        }
    }
}

@Composable
private fun MarkdownOrderedListBlock(
    block: MarkdownBlock.OrderedList,
    baseStyle: TextStyle,
    color: Color,
    inlineCodeStyle: SpanStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(listMarkerGap)) {
        block.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = (block.start + index).toString() + ".",
                    style = baseStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = listMarkerWidth),
                )
                Spacer(Modifier.width(listMarkerGap))
                MarkdownListItemText(item, baseStyle, color, inlineCodeStyle)
            }
        }
    }
}

@Composable
private fun RowScope.MarkdownListItemText(
    item: String,
    baseStyle: TextStyle,
    color: Color,
    inlineCodeStyle: SpanStyle,
) {
    Text(
        text = inlineMarkdown(item, inlineCodeStyle),
        style = baseStyle.copy(textDirection = TextDirection.Content),
        color = color,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun MarkdownQuoteBlock(
    block: MarkdownBlock.Quote,
    baseStyle: TextStyle,
    inlineCodeStyle: SpanStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(quoteBarWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Text(
            text = inlineMarkdown(block.text, inlineCodeStyle),
            style = baseStyle.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = quoteBarGap),
        )
    }
}

@Composable
private fun MarkdownTableBlock(
    block: MarkdownBlock.Table,
    color: Color,
) {
    if (block.header.isEmpty()) return
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownTableRow(cells = block.header, header = true, color = color)
        HorizontalDivider(color = dividerColor, thickness = tableDividerThickness)
        block.rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(color = dividerColor, thickness = tableDividerThickness)
            }
            MarkdownTableRow(cells = row, header = false, color = color)
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    header: Boolean,
    color: Color,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            Text(
                text = inlineMarkdown(cell, null),
                style = if (header) {
                    MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content)
                } else {
                    MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
                },
                color = color,
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        horizontal = tableCellHorizontalPadding,
                        vertical = tableCellVerticalPadding,
                    ),
            )
        }
    }
}

/**
 * Headings stay in the caller's reading voice (Amiri when the surface passes assistant prose) and scale
 * from that token so hierarchy survives both 16sp and 19sp bodies. The ratios are relations, not sizes;
 * the family and every colour still come from MaterialTheme.
 */
@Composable
private fun markdownHeadingStyle(baseStyle: TextStyle, level: Int): TextStyle {
    val scale = when (level) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        else -> 1f
    }
    if (!baseStyle.fontSize.isSp) {
        return markdownHeadingFallback(level)
    }
    val lineHeight = if (baseStyle.lineHeight.isSp) baseStyle.lineHeight * scale else TextUnit.Unspecified
    return baseStyle.copy(
        fontSize = baseStyle.fontSize * scale,
        lineHeight = lineHeight,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun markdownHeadingFallback(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when (level) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        else -> typography.titleSmall
    }.copy(fontWeight = FontWeight.SemiBold)
}

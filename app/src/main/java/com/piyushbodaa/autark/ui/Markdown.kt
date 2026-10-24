package com.piyushbodaa.autark.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.piyushbodaa.autark.ui.theme.LocalPalette

/**
 * The Markdown that chat replies use: headings, lists (nested too), tables, quotes, rules, code
 * blocks with a copy button, **bold**, *italic*, ~~struck~~, `code` and [links](https://…).
 * An unfinished code block renders as code while a reply streams in.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parse(text) }
    val p = LocalPalette.current
    val body = MaterialTheme.typography.bodyLarge
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                when (block) {
                    is Block.Code -> CodeBlock(block)
                    is Block.Heading -> Text(
                        text = inline(block.text, p.pill, p.ink),
                        color = p.ink,
                        style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    )
                    is Block.Item -> Row(Modifier.padding(start = 20.dp * block.depth)) {
                        Text(block.marker, style = body, color = p.ink, modifier = Modifier.width(24.dp))
                        Text(inline(block.text, p.pill, p.ink), style = body, color = p.ink)
                    }
                    is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(p.line),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(inline(block.text, p.pill, p.ink), style = body, color = p.muted)
                    }
                    Block.Rule -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(1.dp)
                            .background(p.line),
                    )
                    is Block.Table -> Table(block)
                    is Block.Paragraph -> Text(inline(block.text, p.pill, p.ink), style = body, color = p.ink)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: Block.Code) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.pill),
    ) {
        DisableSelection {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Fine(block.language.ifEmpty { "Code" }, upper = true, modifier = Modifier.weight(1f))
                NavText("Copy", onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Code", block.code))
                })
            }
        }
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = p.ink,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

/** Each column as wide as its widest cell, up to a limit past which cells wrap; wide tables scroll sideways. */
@Composable
private fun Table(table: Block.Table) {
    val p = LocalPalette.current
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columns = table.rows.maxOf { it.size }
    val cells = remember(table, p) { table.rows.map { row -> List(columns) { inline(row.getOrElse(it) { "" }, p.pill, p.ink) } } }
    val widths = remember(cells, style, density) {
        val limit = with(density) { 220.dp.roundToPx() }
        List(columns) { c ->
            val widest = cells.maxOf { row ->
                measurer.measure(row[c], style.copy(fontWeight = FontWeight.SemiBold), constraints = Constraints(maxWidth = limit)).size.width
            }
            with(density) { widest.toDp() } + 16.dp
        }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(p.paper2)
            .horizontalScroll(rememberScrollState()),
    ) {
        cells.forEachIndexed { r, row ->
            Row {
                row.forEachIndexed { c, cell ->
                    Text(
                        cell,
                        style = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                        color = p.ink,
                        modifier = Modifier
                            .width(widths[c])
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            if (r < cells.lastIndex) {
                Box(
                    Modifier
                        .width(widths.fold(0.dp) { sum, w -> sum + w })
                        .height(1.dp)
                        .background(p.line),
                )
            }
        }
    }
}

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val marker: String, val text: String, val depth: Int) : Block
    data class Quote(val text: String) : Block
    data object Rule : Block
    data class Table(val rows: List<List<String>>) : Block
    data class Code(val code: String, val language: String) : Block
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val numberRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val quoteRegex = Regex("^\\s*>\\s?(.*)$")
private val tableDelimiterRegex = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
private val bullets = listOf("•", "◦", "▪", "▪")

/** How far a list item is nested, from its indent: models use two to four spaces a level. */
private fun depthOf(indent: String): Int {
    val width = indent.replace("\t", "    ").length
    return when {
        width == 0 -> 0
        width <= 4 -> 1
        width <= 8 -> 2
        else -> 3
    }
}

private fun cellsOf(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun parse(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    val quote = StringBuilder()
    var code: StringBuilder? = null
    var language = ""
    val lines = text.lines()

    fun flush() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.clear()
        if (quote.isNotBlank()) blocks += Block.Quote(quote.toString().trim())
        quote.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        i++
        val fence = line.trimStart().startsWith("```")
        val inCode = code
        if (inCode != null) {
            if (fence) {
                blocks += Block.Code(inCode.toString().trimEnd('\n'), language)
                code = null
            } else {
                inCode.append(line).append('\n')
            }
            continue
        }
        if (fence) {
            flush()
            code = StringBuilder()
            language = line.trimStart().removePrefix("```").trim()
            continue
        }
        // A table is a row of cells followed by a row of dashes.
        if (line.contains('|') && i < lines.size && lines[i].contains('|') && tableDelimiterRegex.matches(lines[i])) {
            flush()
            val rows = mutableListOf(cellsOf(line))
            i++
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows += cellsOf(lines[i])
                i++
            }
            blocks += Block.Table(rows)
            continue
        }
        val quoted = quoteRegex.find(line)
        if (quoted != null) {
            if (paragraph.isNotBlank()) {
                blocks += Block.Paragraph(paragraph.toString().trim())
                paragraph.clear()
            }
            if (quote.isNotEmpty()) quote.append('\n')
            quote.append(quoted.groupValues[1])
            continue
        }
        if (quote.isNotEmpty()) flush()
        val heading = headingRegex.find(line)
        val number = numberRegex.find(line)
        when {
            line.isBlank() -> flush()
            ruleRegex.matches(line) -> {
                flush()
                blocks += Block.Rule
            }
            heading != null -> {
                flush()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            number != null -> {
                flush()
                blocks += Block.Item("${number.groupValues[2]}.", number.groupValues[3], depthOf(number.groupValues[1]))
            }
            else -> {
                val bullet = bulletRegex.find(line)
                if (bullet != null) {
                    flush()
                    val depth = depthOf(bullet.groupValues[1])
                    blocks += Block.Item(bullets[depth], bullet.groupValues[2], depth)
                } else {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
        }
    }
    code?.let { blocks += Block.Code(it.toString().trimEnd('\n'), language) }
    flush()
    return blocks
}

private val linkRegex = Regex("^\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")

private fun inline(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val link = if (text[i] == '[') linkRegex.find(text.substring(i)) else null
        when {
            link != null -> {
                val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                withLink(LinkAnnotation.Url(link.groupValues[2], style)) { append(link.groupValues[1]) }
                i += link.value.length
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(inline(text.substring(i + 2, end), codeBackground, linkColor))
                    }
                    i = end + 2
                } else {
                    append(marker); i += 2
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append("~~"); i += 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append('`'); i++
                }
            }
            (text[i] == '*' || text[i] == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = text.indexOf(text[i], i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

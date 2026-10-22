package com.piyushbodaa.autark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.piyushbodaa.autark.ui.theme.LocalPalette

/**
 * Just enough Markdown for chat replies: headings, lists, code blocks, **bold**,
 * *italic* and `code`. Unfinished code blocks render as code while a reply streams in.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parse(text) }
    val ink = LocalPalette.current.ink
    val codeBackground = LocalPalette.current.pill
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                when (block) {
                    is Block.Code -> Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = ink,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(codeBackground)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                    is Block.Heading -> Text(
                        text = inline(block.text, codeBackground),
                        color = ink,
                        style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    )
                    is Block.Item -> Row {
                        Text(block.marker, style = MaterialTheme.typography.bodyLarge, color = ink, modifier = Modifier.width(24.dp))
                        Text(inline(block.text, codeBackground), style = MaterialTheme.typography.bodyLarge, color = ink)
                    }
                    is Block.Paragraph -> Text(inline(block.text, codeBackground), style = MaterialTheme.typography.bodyLarge, color = ink)
                }
            }
        }
    }
}

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val marker: String, val text: String) : Block
    data class Code(val code: String) : Block
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
private val numberRegex = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")

private fun parse(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    var code: StringBuilder? = null

    fun flush() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    for (line in text.lines()) {
        val fence = line.trimStart().startsWith("```")
        val inCode = code
        if (inCode != null) {
            if (fence) {
                blocks += Block.Code(inCode.toString().trimEnd('\n'))
                code = null
            } else {
                inCode.append(line).append('\n')
            }
            continue
        }
        if (fence) {
            flush()
            code = StringBuilder()
            continue
        }
        val heading = headingRegex.find(line)
        val bullet = bulletRegex.find(line)
        val number = numberRegex.find(line)
        when {
            line.isBlank() -> flush()
            heading != null -> {
                flush()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            bullet != null -> {
                flush()
                blocks += Block.Item("•", bullet.groupValues[1])
            }
            number != null -> {
                flush()
                blocks += Block.Item("${number.groupValues[1]}.", number.groupValues[2])
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    code?.let { blocks += Block.Code(it.toString().trimEnd('\n')) }
    flush()
    return blocks
}

private fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append("**"); i += 2
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

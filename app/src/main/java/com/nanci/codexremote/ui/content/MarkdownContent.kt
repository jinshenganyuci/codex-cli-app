package com.nanci.codexremote.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A native, inert Markdown renderer. No HTML, scripts, or remote requests execute here. */
@Composable
fun MarkdownContent(text: String, modifier: Modifier = Modifier, onOpenLink: (String) -> Unit = {}) {
    val blocks = remember(text) { markdownBlocks(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (block in blocks) when (block) {
                is MdBlock.Paragraph -> RichText(block.text, onOpenLink = onOpenLink)
                is MdBlock.Heading -> {
                    val size = when (block.level) { 1 -> 26; 2 -> 23; 3 -> 20; else -> 17 }
                    RichText(block.text, Modifier.padding(top = if (block.level <= 2) 10.dp else 4.dp),
                        TextStyle(fontSize = size.sp, lineHeight = (size + 9).sp, fontWeight = FontWeight.SemiBold), onOpenLink)
                }
                is MdBlock.Code -> CodeContent(block.language, block.text)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 20).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker, color = ContentInk, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.widthIn(min = 14.dp))
                    RichText(block.text, Modifier.weight(1f), onOpenLink = onOpenLink)
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(Color(0xFFD6D6D6)))
                    RichText(block.text, Modifier.weight(1f), TextStyle(color = ContentMuted), onOpenLink)
                }
                is MdBlock.Table -> MarkdownTable(block, onOpenLink)
                MdBlock.Rule -> HorizontalDivider(color = Color(0xFFE5E5E5))
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun RichText(text: String, modifier: Modifier = Modifier, style: TextStyle = TextStyle(), onOpenLink: (String) -> Unit) {
    val annotated = remember(text) { inlineMarkdown(text) }
    ClickableText(annotated, modifier, style = TextStyle(color = ContentInk, fontSize = 16.sp, lineHeight = 24.sp).merge(style),
        onClick = { offset -> annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { onOpenLink(it.item) } })
}

@Composable
private fun CodeContent(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().background(ContentField, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(language.ifBlank { "代码" }, color = ContentMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(40.dp)) {
                ContentIcon("copy", Modifier.size(18.dp))
            }
        }
        Text(code, fontFamily = FontFamily.Monospace, color = ContentInk, fontSize = 13.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, onOpenLink: (String) -> Unit) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        table.rows.forEachIndexed { index, row ->
            Row(Modifier.background(if (index == 0) ContentField else Color.Transparent)) {
                row.forEach { cell ->
                    RichText(cell, Modifier.width(160.dp).padding(horizontal = 12.dp, vertical = 9.dp),
                        TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal), onOpenLink)
                }
            }
            HorizontalDivider(color = Color(0xFFE5E5E5))
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class ListItem(val depth: Int, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
private val listPattern = Regex("^(\\s*)([-+*]|\\d+[.)])\\s+(.+)$")
private val tableRule = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")

private fun markdownBlocks(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").lines()
    val result = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() { if (paragraph.isNotEmpty()) { result += MdBlock.Paragraph(paragraph.toString()); paragraph.clear() } }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val heading = headingPattern.matchEntire(line)
        val list = listPattern.matchEntire(line)
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush(); val fence = trimmed.take(3); val language = trimmed.drop(3).trim(); val content = mutableListOf<String>(); i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { content += lines[i]; i++ }
                result += MdBlock.Code(language, content.joinToString("\n"))
            }
            line.isBlank() -> flush()
            heading != null -> { flush(); result += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].removeSuffix(" #")) }
            i + 1 < lines.size && line.contains('|') && tableRule.matches(lines[i + 1]) -> {
                flush(); val rows = mutableListOf(splitTableRow(line)); i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += splitTableRow(lines[i]); i++ }
                i--; result += MdBlock.Table(rows)
            }
            list != null -> {
                flush(); val marker = list.groupValues[2]
                val raw = list.groupValues[3]
                val checked = raw.startsWith("[x] ", true)
                val unchecked = raw.startsWith("[ ] ")
                result += MdBlock.ListItem((list.groupValues[1].replace("\t", "    ").length / 2).coerceAtMost(6),
                    when { checked -> "☑"; unchecked -> "☐"; marker.first().isDigit() -> marker; else -> "•" },
                    if (checked || unchecked) raw.drop(4) else raw)
            }
            trimmed.startsWith(">") -> { flush(); result += MdBlock.Quote(trimmed.removePrefix(">").trimStart()) }
            trimmed.matches(Regex("([-*_])(?:\\s*\\1){2,}")) -> { flush(); result += MdBlock.Rule }
            else -> { if (paragraph.isNotEmpty()) paragraph.append('\n'); paragraph.append(line) }
        }
        i++
    }
    flush()
    return result
}

private fun splitTableRow(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** Parsers preserve unknown Markdown literally rather than discarding model output. */
internal fun inlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    fun appendRange(text: String, depth: Int = 0) {
        if (depth > 8) { append(text); return }
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!|") { append(text[i + 1]); i += 2; continue }
            if (text[i] == '[') {
                val middle = text.indexOf("](", i + 1)
                val end = if (middle >= 0) text.indexOf(')', middle + 2) else -1
                if (middle > i && end > middle) {
                    val url = text.substring(middle + 2, end).substringBefore(" \"")
                    pushStringAnnotation("url", url)
                    pushStyle(SpanStyle(color = Color(0xFF2755B4), textDecoration = TextDecoration.Underline))
                    appendRange(text.substring(i + 1, middle), depth + 1); pop(); pop(); i = end + 1; continue
                }
            }
            val token = when { text.startsWith("**", i) -> "**"; text.startsWith("__", i) -> "__"; text[i] == '`' -> "`"; text[i] == '*' -> "*"; text[i] == '_' -> "_"; else -> null }
            if (token != null) {
                // Underscores inside identifiers are literal. Use delimiter flanking, rather than
                // pairing the first two underscores in CODEX_MOBILE_SMOKE_OK.
                if (token != "`" && !delimiterCanOpen(text, i, token.length)) {
                    append(token); i += token.length; continue
                }
                var end = text.indexOf(token, i + token.length)
                if (token != "`") {
                    while (end >= 0 && (isEscapedDelimiter(text, end) ||
                            !delimiterCanClose(text, end, token.length))) {
                        end = text.indexOf(token, end + token.length)
                    }
                }
                if (end > i + token.length) {
                    val span = when (token) {
                        "**", "__" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                        "`" -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFECECEC))
                        else -> SpanStyle(fontStyle = FontStyle.Italic)
                    }
                    pushStyle(span)
                    val inner = text.substring(i + token.length, end)
                    if (token == "`") append(inner) else appendRange(inner, depth + 1)
                    pop(); i = end + token.length; continue
                }
            }
            append(text[i]); i++
        }
    }
    appendRange(source)
}

private fun isMarkdownPunctuation(c: Char?): Boolean = c != null && when(Character.getType(c)) {
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt() -> true
    else -> false
}

private fun delimiterFlanking(text: String, start: Int, length: Int): Pair<Boolean,Boolean> {
    val before=text.getOrNull(start-1)
    val after=text.getOrNull(start+length)
    val beforeSpace=before==null || before.isWhitespace()
    val afterSpace=after==null || after.isWhitespace()
    val beforePunctuation=isMarkdownPunctuation(before)
    val afterPunctuation=isMarkdownPunctuation(after)
    val left=!afterSpace && (!afterPunctuation || beforeSpace || beforePunctuation)
    val right=!beforeSpace && (!beforePunctuation || afterSpace || afterPunctuation)
    return left to right
}

private fun delimiterCanOpen(text: String,start: Int,length: Int): Boolean {
    val (left,right)=delimiterFlanking(text,start,length)
    return left && (text[start]!='_' || !right || isMarkdownPunctuation(text.getOrNull(start-1)))
}

private fun delimiterCanClose(text: String,start: Int,length: Int): Boolean {
    val (left,right)=delimiterFlanking(text,start,length)
    return right && (text[start]!='_' || !left || isMarkdownPunctuation(text.getOrNull(start+length)))
}

private fun isEscapedDelimiter(text: String,index: Int): Boolean {
    var backslashes=0
    var cursor=index-1
    while(cursor>=0 && text[cursor]=='\\') { backslashes++;cursor-- }
    return backslashes%2==1
}

/** Small deterministic checks for debug verification; tests the actual renderer parser and spans. */
internal fun markdownInlineSmokeChecks(): List<String> = buildList {
    val identifier=inlineMarkdown("CODEX_MOBILE_SMOKE_OK")
    if(identifier.text!="CODEX_MOBILE_SMOKE_OK" || identifier.spanStyles.isNotEmpty()) add("identifier underscores")
    val italic=inlineMarkdown("_italic_")
    if(italic.text!="italic" || italic.spanStyles.none { it.item.fontStyle==FontStyle.Italic }) add("italic")
    val bold=inlineMarkdown("**bold**")
    if(bold.text!="bold" || bold.spanStyles.none { it.item.fontWeight==FontWeight.SemiBold }) add("bold")
    val code=inlineMarkdown("`CODEX_MOBILE_SMOKE_OK`")
    if(code.text!="CODEX_MOBILE_SMOKE_OK" || code.spanStyles.none { it.item.fontFamily==FontFamily.Monospace }) add("inline code")
    if(inlineMarkdown("foo__bar__baz").text!="foo__bar__baz") add("double underscore identifier")
    if(inlineMarkdown("_one_two_").text!="one_two") add("inner underscore")
}

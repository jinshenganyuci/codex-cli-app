package com.nanci.codexremote.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.ui.theme.NativeTheme

/** Native Markdown: image IO is delegated to the scoped loader; no HTML or scripts execute. */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    onOpenLink: (String) -> Unit = {},
    imageLoader: ImageLoader? = null,
    onOpenImage: (ImageSource) -> Unit = {},
    imageBaseDirectory: String? = null,
    imageKey: String = "markdown",
    referenceDefinitions: Map<String, String> = emptyMap(),
) {
    val document = remember(text) { markdownReferenceDocument(text) }
    val references = remember(document, referenceDefinitions) { document.references + referenceDefinitions }
    val blocks = remember(document.body) { markdownBlocks(document.body) }
    val imageContext = remember(imageLoader, onOpenImage, imageBaseDirectory, imageKey, references) {
        imageLoader?.let { MarkdownImageContext(it, onOpenImage, imageBaseDirectory, imageKey, references) }
    }
    CompositionLocalProvider(LocalMarkdownImages provides imageContext) {
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
                    is MdBlock.Math -> MathBlock(block.latex, block.source)
                    is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 20).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(block.marker, color = ContentInk, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.widthIn(min = 14.dp))
                        RichText(block.text, Modifier.weight(1f), onOpenLink = onOpenLink)
                    }
                    is MdBlock.Quote -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(ContentBorder))
                        RichText(block.text, Modifier.weight(1f), TextStyle(color = ContentMuted), onOpenLink)
                    }
                    is MdBlock.Table -> MarkdownTable(block, onOpenLink)
                    MdBlock.Rule -> HorizontalDivider(color = ContentBorder)
                }
            }
        }
    }
}

private data class MarkdownImageContext(
    val loader: ImageLoader,
    val open: (ImageSource) -> Unit,
    val cwd: String?,
    val key: String,
    val references: Map<String, String>,
)
private val LocalMarkdownImages = compositionLocalOf<MarkdownImageContext?> { null }

@Composable
private fun RichText(text: String, modifier: Modifier = Modifier, style: TextStyle = TextStyle(), onOpenLink: (String) -> Unit) {
    val context = LocalMarkdownImages.current
    val images = remember(text, context?.cwd, context?.key, context?.references, context != null) {
        if (context == null) emptyList() else markdownImageSpans(text, context.references).mapNotNull { span ->
            resolveMarkdownImageSource(span.destination, "${context.key}:${text.hashCode()}:${span.start}", context.cwd)
                ?.let { source -> span to source }
        }
    }
    if (images.isEmpty() || context == null) {
        TextOnlyRichText(text, modifier, style, onOpenLink)
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var cursor = 0
        images.forEach { (span, source) ->
            val preceding = text.substring(cursor, span.start)
            if (preceding.isNotBlank()) TextOnlyRichText(preceding, style = style, onOpenLink = onOpenLink)
            key(source.cacheKey) {
                MessageImage(source, inlineMarkdown(span.alt).text.ifBlank { "图片" }, context.loader, context.open, clampAspectRatio = false)
            }
            cursor = span.end
        }
        val remaining = text.substring(cursor)
        if (remaining.isNotBlank()) TextOnlyRichText(remaining, style = style, onOpenLink = onOpenLink)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun TextOnlyRichText(text: String, modifier: Modifier = Modifier, style: TextStyle = TextStyle(), onOpenLink: (String) -> Unit) {
    val colors = NativeTheme.colors
    val annotated = remember(text, colors.accent, colors.field) { inlineMarkdown(text, colors.accent, colors.field) }
    val textStyle = TextStyle(color = ContentInk, fontSize = 16.sp, lineHeight = 24.sp).merge(style)
    val expressions = remember(annotated) {
        annotated.getStringAnnotations(MATH_ANNOTATION, 0, annotated.length).map { it.item }.toSet()
    }
    if (expressions.isEmpty()) {
        ClickableText(annotated, modifier, style = textStyle, onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { onOpenLink(it.item) }
        })
    } else {
        val layouts = formulaLayouts(expressions, textStyle.color, displayMode = false)
        val openLink by rememberUpdatedState(onOpenLink)
        val linked = remember(annotated) {
            AnnotatedString.Builder(annotated).apply {
                annotated.getStringAnnotations("url", 0, annotated.length).forEach { link ->
                    addLink(LinkAnnotation.Clickable(link.item) { openLink(link.item) }, link.start, link.end)
                }
            }.toAnnotatedString()
        }
        BoxWithConstraints(modifier) {
            val fontSize = with(LocalDensity.current) { textStyle.fontSize.toPx() }
            val formulas = inlineFormulas(layouts, fontSize, constraints.maxWidth.toFloat())
            BasicText(linked, style = textStyle, inlineContent = formulas)
        }
    }
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
            HorizontalDivider(color = ContentBorder)
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class Math(val latex: String, val source: String) : MdBlock
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
        val math = displayMathAt(lines, i)
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush()
                val fenceCharacter = trimmed.first()
                val fenceLength = trimmed.takeWhile { it == fenceCharacter }.length
                val language = trimmed.drop(fenceLength).trim()
                val content = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val candidate = lines[i].trimStart()
                    val closingLength = candidate.takeWhile { it == fenceCharacter }.length
                    if (closingLength >= fenceLength && candidate.drop(closingLength).isBlank()) break
                    content += lines[i]
                    i++
                }
                result += MdBlock.Code(language, content.joinToString("\n"))
            }
            math != null -> {
                flush()
                result += MdBlock.Math(math.latex, lines.subList(i, math.end).joinToString("\n"))
                i = math.end - 1
            }
            line.isBlank() -> flush()
            heading != null -> { flush(); result += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].removeSuffix(" #")) }
            i + 1 < lines.size && line.contains('|') && tableRule.matches(lines[i + 1]) -> {
                flush(); val rows = mutableListOf(splitTableRow(line)); i += 2
                while (i < lines.size && lines[i].contains('|') && displayMathAt(lines, i) == null) {
                    rows += splitTableRow(lines[i]); i++
                }
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

/** Parsers preserve unknown Markdown literally rather than discarding model output. */
internal fun inlineMarkdown(source: String, linkColor: Color = Color(0xFF2755B4),
    codeBackground: Color = Color(0xFFECECEC)): AnnotatedString = buildAnnotatedString {
    fun appendRange(text: String, depth: Int = 0) {
        if (depth > 8) { append(text); return }
        var i = 0
        while (i < text.length) {
            val math = inlineMathAt(text, i)
            if (math != null) {
                pushStringAnnotation(MATH_ANNOTATION, math.latex)
                appendInlineContent(math.latex, alternateText = text.substring(i, math.end))
                pop()
                i = math.end
                continue
            }
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_~{}[]()#+-.!|$") { append(text[i + 1]); i += 2; continue }
            if (text[i] == '[') {
                val middle = text.indexOf("](", i + 1)
                val end = if (middle >= 0) linkDestinationEnd(text, middle + 2) else -1
                if (middle > i && end > middle) {
                    val url = markdownLinkDestination(text.substring(middle + 2, end))
                    if (url == null) { append(text[i]); i++; continue }
                    pushStringAnnotation("url", url)
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    appendRange(text.substring(i + 1, middle), depth + 1); pop(); pop(); i = end + 1; continue
                }
            }
            val combined = when {
                matchesDelimiterRun(text, i, "***") -> "***"
                matchesDelimiterRun(text, i, "___") -> "___"
                else -> null
            }
            // Without a closing triple, the existing double-marker path can still render nested emphasis.
            val combinedEnd = if (combined != null && delimiterCanOpen(text, i, combined.length)) {
                emphasisEnd(text, i + combined.length, combined)
            } else -1
            val token = when {
                combinedEnd >= 0 -> combined
                text.startsWith("**", i) -> "**"
                text.startsWith("__", i) -> "__"
                matchesDelimiterRun(text, i, "~~") -> "~~"
                text[i] == '`' -> text.substring(i).takeWhile { it == '`' }
                text[i] == '*' -> "*"
                text[i] == '_' -> "_"
                else -> null
            }
            if (token != null) {
                val codeToken = token.first() == '`'
                // Underscores inside identifiers are literal. Use delimiter flanking, rather than
                // pairing the first two underscores in CODEX_MOBILE_SMOKE_OK.
                if (!codeToken && !delimiterCanOpen(text, i, token.length)) {
                    append(token); i += token.length; continue
                }
                val end = when {
                    combinedEnd >= 0 -> combinedEnd
                    codeToken -> matchingMarkdownBackticks(text, i + token.length, token.length)
                    else -> emphasisEnd(text, i + token.length, token)
                }
                if (end > i + token.length) {
                    val span = when {
                        codeToken -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                        token == "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        token.length == 3 -> SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
                        token == "**" || token == "__" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                        else -> SpanStyle(fontStyle = FontStyle.Italic)
                    }
                    pushStyle(span)
                    val inner = text.substring(i + token.length, end)
                    if (codeToken) append(inner) else appendRange(inner, depth + 1)
                    pop(); i = end + token.length; continue
                }
            }
            append(text[i]); i++
        }
    }
    appendRange(source)
}

private fun emphasisEnd(text: String, start: Int, token: String): Int {
    val exactRun = token == "~~" || token.length == 3
    var index = start
    while (index < text.length) {
        val escaped = isEscapedDelimiter(text, index)
        val math = if (!escaped) inlineMathAt(text, index) else null
        if (math != null) {
            index = math.end
            continue
        }
        if (exactRun && !escaped && text[index] == '`') {
            val run = text.substring(index).takeWhile { it == '`' }.length
            val end = matchingMarkdownBackticks(text, index + run, run)
            index = if (end >= 0) end + run else index + run
            continue
        }
        val matches = if (exactRun) matchesDelimiterRun(text, index, token) else text.startsWith(token, index)
        if (!escaped && matches) {
            if (delimiterCanClose(text, index, token.length)) return index
            // Keep an unmatched triple from consuming a distinct bold-italic span farther on.
            if (token.length == 3 && delimiterCanOpen(text, index, token.length)) return -1
        }
        index++
    }
    return -1
}

private fun matchesDelimiterRun(text: String, start: Int, token: String): Boolean =
    text.startsWith(token, start) && text.getOrNull(start - 1) != token.first() &&
        text.getOrNull(start + token.length) != token.first()

/** File names can contain balanced/escaped parentheses, or be enclosed in angle brackets. */
internal fun linkDestinationEnd(text: String, start: Int): Int {
    var nesting = 0
    var angled = false
    var quoted = false
    var i = start
    while (i < text.length && text[i] in " \t") i++
    val destinationStart = i
    while (i < text.length) {
        val c = text[i]
        if (c == '\\') { i += 2; continue }
        if (i == destinationStart && c == '<') angled = true
        else if (angled) { if (c == '>') angled = false }
        else if (c == '"') quoted = !quoted
        else if (!quoted) when (c) {
            '(' -> nesting++
            ')' -> if (nesting == 0) return i else nesting--
            '\n' -> return -1
        }
        i++
    }
    return -1
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

internal fun isEscapedDelimiter(text: String,index: Int): Boolean {
    var backslashes=0
    var cursor=index-1
    while(cursor>=0 && text[cursor]=='\\') { backslashes++;cursor-- }
    return backslashes%2==1
}

/** Small deterministic checks for debug verification; tests the actual renderer parser and spans. */
internal fun markdownInlineSmokeChecks(): List<String> = buildList {
    addAll(markdownImageSmokeChecks())
    val fenced=markdownBlocks("```markdown\n![hidden](/x.png)\n```")
    if(fenced.size!=1 || fenced.single() !is MdBlock.Code) add("image syntax must remain in fenced code")
    val longerFence=markdownBlocks("````markdown\n```\n![hidden](/x.png)\n````")
    if(longerFence.size!=1 || longerFence.single() !is MdBlock.Code) add("shorter fence must not end a code block")
    val identifier=inlineMarkdown("CODEX_MOBILE_SMOKE_OK")
    if(identifier.text!="CODEX_MOBILE_SMOKE_OK" || identifier.spanStyles.isNotEmpty()) add("identifier underscores")
    val italic=inlineMarkdown("_italic_")
    if(italic.text!="italic" || italic.spanStyles.none { it.item.fontStyle==FontStyle.Italic }) add("italic")
    val bold=inlineMarkdown("**bold**")
    if(bold.text!="bold" || bold.spanStyles.none { it.item.fontWeight==FontWeight.SemiBold }) add("bold")
    val strike = inlineMarkdown("Keep ~~old~~ and **new**.")
    if (strike.text != "Keep old and new." || strike.spanStyles.none {
            it.start == 5 && it.end == 8 && it.item.textDecoration == TextDecoration.LineThrough
        }) add("strikethrough")
    for (marker in listOf("***", "___")) {
        val combined = inlineMarkdown("This is ${marker}both${marker}.")
        if (combined.text != "This is both." || combined.spanStyles.none {
                it.start == 8 && it.end == 12 && it.item.fontWeight == FontWeight.SemiBold && it.item.fontStyle == FontStyle.Italic
            }) add("bold italic $marker")
    }
    val nested = inlineMarkdown("***em* bold** and ***both***")
    if (nested.text != "em bold and both" || nested.spanStyles.none {
            it.start == 0 && it.end == 2 && it.item.fontStyle == FontStyle.Italic
        } || nested.spanStyles.none { it.start == 0 && it.end == 7 && it.item.fontWeight == FontWeight.SemiBold }) add("nested emphasis")
    if (inlineMarkdown("\\~~escaped~~").text != "~~escaped~~" || inlineMarkdown("~~~literal~~~").text != "~~~literal~~~") {
        add("literal tildes")
    }
    val markedCode = inlineMarkdown("~~use `~~` here~~ and ***use `***` here***")
    if (markedCode.text != "use ~~ here and use *** here" || markedCode.spanStyles.count { it.item.fontFamily == FontFamily.Monospace } != 2) {
        add("code in new emphasis styles")
    }
    val code=inlineMarkdown("`CODEX_MOBILE_SMOKE_OK`")
    if(code.text!="CODEX_MOBILE_SMOKE_OK" || code.spanStyles.none { it.item.fontFamily==FontFamily.Monospace }) add("inline code")
    if(inlineMarkdown("foo__bar__baz").text!="foo__bar__baz") add("double underscore identifier")
    if(inlineMarkdown("_one_two_").text!="one_two") add("inner underscore")
    val mathBlocks = markdownBlocks("代入：\n\\[\n11d=297,\\qquad d=27\n\\]\n$$\\frac{1}{2}$$\n验算")
    if (mathBlocks.mapNotNull { (it as? MdBlock.Math)?.latex } != listOf("11d=297,\\qquad d=27", "\\frac{1}{2}")) {
        add("display math blocks")
    }
    val inlineMath = inlineMarkdown("验算 \\(60=10\\times6\\) 和 \$x_1^2\$。 `\\(literal\\)`")
    if (inlineMath.getStringAnnotations(MATH_ANNOTATION, 0, inlineMath.length).map { it.item } != listOf("60=10\\times6", "x_1^2")) {
        add("inline math and code delimiters")
    }
    val literal = inlineMarkdown("\$5 and \$10, \\\\(x\\), \\(x+1")
    if (literal.getStringAnnotations(MATH_ANNOTATION, 0, literal.length).isNotEmpty()) add("currency, escaped and unfinished math")
    val escapedDollar = inlineMarkdown("\\\$x\$")
    if (escapedDollar.text != "\$x\$" || escapedDollar.getStringAnnotations(MATH_ANNOTATION, 0, escapedDollar.length).isNotEmpty()) {
        add("escaped dollar")
    }
    val currencyAndMarkdown = inlineMarkdown("\$5，请看 `\$HOME` 和 [链接](/prices/\$retail)")
    if (currencyAndMarkdown.getStringAnnotations(MATH_ANNOTATION, 0, currencyAndMarkdown.length).isNotEmpty() ||
        currencyAndMarkdown.getStringAnnotations("url", 0, currencyAndMarkdown.length).singleOrNull()?.item != "/prices/\$retail" ||
        currencyAndMarkdown.spanStyles.none { it.item.fontFamily == FontFamily.Monospace }) add("currency before code and links")
    val emphasizedMath = inlineMarkdown("*\\(x*y\\) 乘法*")
    if (emphasizedMath.getStringAnnotations(MATH_ANNOTATION, 0, emphasizedMath.length).singleOrNull()?.item != "x*y" ||
        emphasizedMath.spanStyles.none { it.item.fontStyle == FontStyle.Italic && it.end == emphasizedMath.length }) add("emphasis around math")
    val unfinishedBlock = markdownBlocks("\$\$\nx +\n\n\$\$y\$\$")
    if (unfinishedBlock.filterIsInstance<MdBlock.Math>().singleOrNull()?.latex != "y") add("unfinished block before complete block")
    if (markdownBlocks("\\[a\\] 与 \\[b\\]").singleOrNull() !is MdBlock.Paragraph) add("two formulas with prose on one line")
    if (markdownImageSpans("\\(\\text{![hidden](/x.png)}\\)").isNotEmpty()) add("image syntax in math")
    if (splitTableRow("| \\(|x|\\) | y |") != listOf("\\(|x|\\)", "y")) add("formula pipes in table cells")
    val tableAndMath = markdownBlocks("| X | Y |\n| --- | --- |\n| 1 | 2 |\n\\[|x|\\]")
    if (tableAndMath.size != 2 || tableAndMath.last() !is MdBlock.Math) add("display formula after table")
}

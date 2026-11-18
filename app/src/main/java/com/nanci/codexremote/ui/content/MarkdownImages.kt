package com.nanci.codexremote.ui.content

import java.util.Locale

/** Offsets refer to the original paragraph; unsupported syntax remains ordinary Markdown. */
internal data class MarkdownImageSpan(val start: Int, val end: Int, val alt: String, val destination: String)

internal fun markdownImageSpans(text: String, references: Map<String, String> = emptyMap()): List<MarkdownImageSpan> {
    val images = mutableListOf<MarkdownImageSpan>()
    var i = 0
    while (i < text.length) {
        val math = inlineMathAt(text, i)
        if (math != null) {
            i = math.end
            continue
        }
        if (text[i] == '\\') { i += 2; continue }
        if (text[i] == '`') {
            val run = text.substring(i).takeWhile { it == '`' }.length
            val token = "`".repeat(run)
            var end = text.indexOf(token, i + run)
            while (end >= 0 && (text.getOrNull(end - 1) == '`' || text.getOrNull(end + run) == '`'))
                end = text.indexOf(token, end + run)
            // A streaming, unclosed code span must not start a network/file image load.
            if (end < 0) break
            i = end + run
            continue
        }
        val isImage = text.startsWith("![", i)
        val bracket = if (isImage) i + 1 else i
        if (text.getOrNull(bracket) == '[') {
            val close = markdownLabelEnd(text, bracket + 1)
            if (close >= 0 && text.getOrNull(close + 1) == '(') {
                val end = linkDestinationEnd(text, close + 2)
                if (end >= 0) {
                    val destination = markdownLinkDestination(text.substring(close + 2, end))
                    if (isImage && destination != null)
                        images += MarkdownImageSpan(i, end + 1, text.substring(bracket + 1, close), destination)
                    // Do not split an ordinary link's label into independently loading images.
                    i = end + 1
                    continue
                }
            }
            if (close >= 0 && text.getOrNull(close + 1) != '(') {
                val label = text.substring(bracket + 1, close)
                var end = close + 1
                val reference = if (text.getOrNull(end) == '[') {
                    val referenceEnd = markdownLabelEnd(text, end + 1)
                    if (referenceEnd < 0) null else {
                        val explicit = text.substring(end + 1, referenceEnd)
                        end = referenceEnd + 1
                        explicit.ifEmpty { label }
                    }
                } else label
                val destination = reference?.let { references[normalizeMarkdownReference(it)] }
                if (destination != null) {
                    if (isImage) images += MarkdownImageSpan(i, end, label, destination)
                    i = end
                    continue
                }
            }
        }
        i++
    }
    return images
}

internal data class MarkdownReferenceDocument(val body: String, val references: Map<String, String>)

/** Definitions are document-scoped. Fences and indented code never create file/network sources. */
internal fun markdownReferenceDocument(text: String): MarkdownReferenceDocument {
    val lines = text.replace("\r\n", "\n").lines().toMutableList()
    val references = linkedMapOf<String, String>()
    var fence: Char? = null
    var fenceLength = 0
    var mathEnd = 0
    for (index in lines.indices) {
        if (index < mathEnd) continue
        val line = lines[index]
        val indent = line.takeWhile { it == ' ' }.length
        val trimmed = line.trimStart()
        if (fence != null) {
            val run = trimmed.takeWhile { it == fence }.length
            if (indent <= 3 && run >= fenceLength && trimmed.drop(run).isBlank()) fence = null
            continue
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            fence = trimmed.first()
            fenceLength = trimmed.takeWhile { it == fence }.length
            continue
        }
        val math = displayMathAt(lines, index)
        if (math != null) {
            mathEnd = math.end
            continue
        }
        if (indent > 3 || line.startsWith('\t') || !trimmed.startsWith('[')) continue
        val close = markdownLabelEnd(trimmed, 1)
        if (close < 0 || trimmed.getOrNull(close + 1) != ':') continue
        val label = normalizeMarkdownReference(trimmed.substring(1, close))
        if (label.isBlank() || label.length > 999) continue
        val destination = referenceDestination(trimmed.substring(close + 2)) ?: continue
        references.putIfAbsent(label, destination)
        lines[index] = ""
    }
    return MarkdownReferenceDocument(lines.joinToString("\n"), references)
}

private fun normalizeMarkdownReference(label: String): String = label
    .replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+.!|<>-])"), "$1")
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

private fun referenceDestination(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    var end = 0
    if (text.startsWith('<')) {
        end = 1
        while (end < text.length && (text[end] != '>' || isEscapedMarkdownCharacter(text, end))) end++
        if (end == text.length) return null
        end++
    } else {
        var nesting = 0
        while (end < text.length) {
            val char = text[end]
            if (char == '\\' && end + 1 < text.length) { end += 2; continue }
            if (char.isWhitespace()) break
            if (char == '(') nesting++
            if (char == ')' && --nesting < 0) return null
            end++
        }
        if (nesting != 0) return null
    }
    val suffix = text.substring(end).trim()
    if (suffix.isNotEmpty() && !(suffix.length >= 2 && when (suffix.first()) {
            '"' -> suffix.last() == '"'
            '\'' -> suffix.last() == '\''
            '(' -> suffix.last() == ')'
            else -> false
        })) return null
    return markdownLinkDestination(text.substring(0, end))
}

private fun markdownLabelEnd(text: String, start: Int): Int {
    var nesting = 0
    var i = start
    while (i < text.length) {
        when (text[i]) {
            '\\' -> { i += 2; continue }
            '[' -> nesting++
            ']' -> if (nesting == 0) return i else nesting--
            '\n' -> return -1
        }
        i++
    }
    return -1
}

internal fun markdownLinkDestination(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val destination = if (text.startsWith('<')) {
        var i = 1
        while (i < text.length && (text[i] != '>' || isEscapedMarkdownCharacter(text, i))) i++
        if (i >= text.length) return null
        val suffix = text.substring(i + 1).trim()
        if (suffix.isNotEmpty() && !(suffix.length >= 2 && suffix.first() == '"' && suffix.last() == '"')) return null
        text.substring(1, i)
    } else {
        // Existing remote file links permit unescaped spaces; only a quoted suffix is a title.
        val title = Regex("\\s+\"(?:[^\"\\\\]|\\\\.)*\"\\s*$").find(text)
        if (title == null) text else text.substring(0, title.range.first)
    }
    return destination.replace(Regex("\\\\([\\\\()<>\\[\\]])"), "$1").takeIf { it.isNotBlank() }
}

private fun isEscapedMarkdownCharacter(text: String, index: Int): Boolean {
    var count = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') { count++; i-- }
    return count % 2 == 1
}

/** Covers actual parsing boundaries and resolution, without touching files or network. */
internal fun markdownImageSmokeChecks(): List<String> = buildList {
    val mixed = "before ![cat](</home/codex/cat one (2).svg>) after ![png](./cat\\(2\\).png)"
    val spans = markdownImageSpans(mixed)
    if (spans.map { it.destination } != listOf("/home/codex/cat one (2).svg", "./cat(2).png")) add("image destinations and order")
    if (spans.firstOrNull()?.let { mixed.substring(0, it.start) } != "before ") add("text before image")
    if (markdownImageSpans("`![hidden](/x.png)` `` ![hidden](/y.svg) ``").isNotEmpty()) add("images in code spans")
    if (markdownImageSpans("`unfinished ![hidden](/x.svg)").isNotEmpty()) add("unfinished code span")
    if (markdownImageSpans("\\![escaped](/x.png) [download](/x.png)").isNotEmpty()) add("ordinary and escaped links")
    if (markdownImageSpans("![streaming](/x.png").isNotEmpty()) add("unfinished image destination")
    if (resolveMarkdownImageSource("../cat%20one.png", "test", "/home/codex/project")?.value != "/home/codex/cat one.png") add("relative image resolution")
    if (resolveMarkdownImageSource("sandbox:/home/codex/cat.svg", "test")?.value != "/home/codex/cat.svg") add("sandbox path")
    if (resolveMarkdownImageSource("file:///home/codex/cat.svg", "test")?.value != "/home/codex/cat.svg") add("file URI")
    if (resolveMarkdownImageSource("file://other-host/home/cat.svg", "test") != null) add("foreign file authority")
    if (resolveMarkdownImageSource("javascript:bad", "test", "/home/codex") != null) add("unsupported image scheme")
    if (resolveMarkdownImageSource("./cat.png", "test") != null) add("relative image without workspace")
    if (resolveMarkdownImageSource("/home/codex/a%00.png", "test") != null) add("encoded control character")
}

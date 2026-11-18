package com.nanci.codexremote.ui.content

internal const val MATH_ANNOTATION = "latex"
private const val MAX_FORMULA_LENGTH = 4096

internal data class MarkdownMath(val latex: String, val end: Int)

// end is the first character after an inline formula, or the first line after a display formula.
internal fun inlineMathAt(text: String, start: Int): MarkdownMath? {
    val close = when {
        text.startsWith("\\(", start) -> "\\)"
        text.startsWith("\\[", start) -> "\\]"
        text[start] == '$' && text.getOrNull(start - 1) != '$' &&
            text.getOrNull(start + 1)?.let { it != '$' && !it.isWhitespace() } == true -> "$"
        else -> return null
    }
    val bodyStart = start + close.length
    var end = bodyStart
    while (end < text.length && end - bodyStart <= MAX_FORMULA_LENGTH) {
        if (text[end] == '\n') return null
        if (close == "$") {
            if (text[end] == '`') return null
            if (text[end] == '[') {
                val bracketEnd = text.indexOf(']', end + 1)
                if (bracketEnd >= 0 && text.getOrNull(bracketEnd + 1) == '(') return null
            }
        }
        if (text.startsWith(close, end)) {
            if (close == "$" && (end == bodyStart || text[end - 1].isWhitespace() ||
                    text.getOrNull(end - 1) == '$' || text.getOrNull(end + 1)?.let { it == '$' || it.isDigit() } == true)) {
                return null
            }
            val latex = text.substring(bodyStart, end).trim()
            return if (latex.isBlank()) null else MarkdownMath(latex, end + close.length)
        }
        // Skip escaped characters, including a backslash or literal dollar within the formula.
        end += if (text[end] == '\\' && text.getOrNull(end + 1) != '\n') 2 else 1
    }
    return null
}

internal fun displayMathAt(lines: List<String>, start: Int): MarkdownMath? {
    val firstLine = lines[start].trim()
    val close = when {
        firstLine.startsWith("\\[") -> "\\]"
        firstLine.startsWith("$$") && !firstLine.startsWith("$$$") -> "$$"
        else -> return null
    }
    val body = StringBuilder()
    for (index in start until lines.size) {
        val line = if (index == start) firstLine.drop(close.length) else lines[index].trimEnd()
        if (index > start) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~") || trimmed.startsWith("\\[") ||
                (trimmed.startsWith("$$") && trimmed != "$$")) return null
        }
        if (line.length + body.length > MAX_FORMULA_LENGTH) return null
        var end = line.indexOf(close)
        while (end >= 0 && isEscapedDelimiter(line, end)) end = line.indexOf(close, end + close.length)
        if (end >= 0) {
            if (end + close.length != line.length) return null
            body.append(line, 0, end)
            val latex = body.toString().trim()
            return if (latex.isBlank()) null else MarkdownMath(latex, index + 1)
        }
        body.appendLine(line)
    }
    return null
}

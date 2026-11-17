package com.nanci.codexremote.ui.content

/** Pipes in escaped text or complete backtick spans belong to a cell, not a new column. */
internal fun splitTableRow(line: String): List<String> {
    val text = line.trim()
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var codeRun = 0
    var firstDelimiter = -1
    var lastDelimiter = -1
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\\' && index + 1 < text.length) {
            if (text[index + 1] == '|') cell.append('|')
            else cell.append(text[index]).append(text[index + 1])
            index += 2
            continue
        }
        if (character == '`') {
            val run = text.substring(index).takeWhile { it == '`' }.length
            if (codeRun == run) codeRun = 0
            else if (codeRun == 0 && matchingMarkdownBackticks(text, index + run, run) >= 0) codeRun = run
            cell.append("`".repeat(run))
            index += run
            continue
        }
        if (character == '|' && codeRun == 0) {
            if (firstDelimiter < 0) firstDelimiter = index
            lastDelimiter = index
            cells += cell.toString().trim()
            cell.clear()
        } else cell.append(character)
        index++
    }
    cells += cell.toString().trim()
    if (lastDelimiter == text.lastIndex && cells.size > 1) cells.removeAt(cells.lastIndex)
    if (firstDelimiter == 0 && cells.size > 1) cells.removeAt(0)
    return cells
}

internal fun matchingMarkdownBackticks(text: String, start: Int, count: Int): Int {
    var index = start
    while (index < text.length) {
        if (text[index] != '`') { index++; continue }
        val run = text.substring(index).takeWhile { it == '`' }.length
        if (run == count) return index
        index += run
    }
    return -1
}

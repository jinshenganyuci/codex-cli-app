package com.nanci.codexremote.ui.content

import java.io.ByteArrayOutputStream

enum class ChangedFilesScope { WorkingTree, CurrentTurn }

/** A single server snapshot. Never sum this with older item or turn patches. */
data class ChangedFilesSummary(
    val patch: String,
    val files: List<ChangedFile>,
    val complete: Boolean = true,
    val scope: ChangedFilesScope = ChangedFilesScope.WorkingTree,
    val basisNote: String? = null,
) {
    val additions: Int? get() = files.takeIf { complete && it.isNotEmpty() && it.all(ChangedFile::countsKnown) }?.sumOf { it.additions }
    val deletions: Int? get() = files.takeIf { complete && it.isNotEmpty() && it.all(ChangedFile::countsKnown) }?.sumOf { it.deletions }
    val title: String get() {
        val prefix = if (scope == ChangedFilesScope.CurrentTurn) "本轮" else ""
        return if (files.isEmpty()) "${prefix}代码差异" else if (complete) "${prefix}已更改 ${files.size} 个文件" else "${prefix}已加载 ${files.size} 个文件更改"
    }
}

data class ChangedFile(
    val id: String,
    val path: String,
    val oldPath: String?,
    val kind: String,
    val rows: List<ChangedLine>,
    val additions: Int,
    val deletions: Int,
    val countsKnown: Boolean,
    val patch: String,
) {
    val title: String get() = oldPath?.takeIf { it != path && it != "/dev/null" }?.let { "$it → $path" } ?: path
}

enum class ChangedLineKind { Context, Addition, Deletion, Separator, Metadata }
data class ChangedLine(val kind: ChangedLineKind, val text: String, val oldNumber: Int? = null, val newNumber: Int? = null)

/** Parses git/unified patches only. Unknown formats remain copyable, without invented counts. */
fun parseChangedFiles(patch: String, scope: ChangedFilesScope = ChangedFilesScope.WorkingTree): ChangedFilesSummary? {
    if (patch.isBlank()) return null
    val lines = patch.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
    val starts = lines.indices.filter { lines[it].startsWith("diff --git ") }
    val blocks = if (starts.isNotEmpty()) starts.mapIndexed { index, start ->
        lines.subList(start, starts.getOrElse(index + 1) { lines.size })
    } else {
        val unifiedStarts = lines.indices.filter { i -> lines[i].startsWith("--- ") && lines.getOrNull(i + 1)?.startsWith("+++ ") == true }
        unifiedStarts.mapIndexed { index, start -> lines.subList(start, unifiedStarts.getOrElse(index + 1) { lines.size }) }
    }
    val files = blocks.mapIndexedNotNull { index, block -> parseChangedFile(index, block) }
    return ChangedFilesSummary(patch, files, blocks.isNotEmpty() && files.size == blocks.size, scope)
}

private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")
private fun parseChangedFile(index: Int, lines: List<String>): ChangedFile? {
    var oldPath: String? = null
    var path: String? = null
    lines.firstOrNull()?.takeIf { it.startsWith("diff --git ") }?.let {
        val value = it.removePrefix("diff --git ")
        val tokens = gitPathTokens(value)
        val names = if (tokens.size == 2) tokens else unquotedSamePathPair(value)
        if (names.size == 2) { oldPath = stripGitPrefix(names[0]); path = stripGitPrefix(names[1]) }
    }
    var kind = "修改"
    var binary = false
    var countsKnown = true
    var oldLine = 0
    var newLine = 0
    var oldRemaining = 0
    var newRemaining = 0
    var inHunk = false
    var previousOldEnd = 0
    var previousNewEnd = 0
    var additions = 0
    var deletions = 0
    val rows = mutableListOf<ChangedLine>()
    for (line in lines) {
        val hunk = hunkHeader.matchEntire(line)
        if (hunk != null) {
            if (inHunk && (oldRemaining != 0 || newRemaining != 0)) countsKnown = false
            oldLine = hunk.groupValues[1].toIntOrNull() ?: return null
            newLine = hunk.groupValues[3].toIntOrNull() ?: return null
            oldRemaining = hunk.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            newRemaining = hunk.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            val unchanged = minOf((oldLine - previousOldEnd - 1).coerceAtLeast(0), (newLine - previousNewEnd - 1).coerceAtLeast(0))
            if (unchanged > 0) rows += ChangedLine(ChangedLineKind.Separator, "$unchanged 行未更改")
            val section = hunk.groupValues[5].trim()
            if (section.isNotBlank()) rows += ChangedLine(ChangedLineKind.Metadata, section)
            inHunk = true
            continue
        }
        if (inHunk && (oldRemaining > 0 || newRemaining > 0)) {
            when (line.firstOrNull()) {
                '+' -> { rows += ChangedLine(ChangedLineKind.Addition, line.drop(1), newNumber = newLine++); additions++; newRemaining-- }
                '-' -> { rows += ChangedLine(ChangedLineKind.Deletion, line.drop(1), oldNumber = oldLine++); deletions++; oldRemaining-- }
                ' ' -> { rows += ChangedLine(ChangedLineKind.Context, line.drop(1), oldLine++, newLine++); oldRemaining--; newRemaining-- }
                '\\' -> rows += ChangedLine(ChangedLineKind.Metadata, "文件末尾没有换行")
                else -> { rows += ChangedLine(ChangedLineKind.Metadata, line); countsKnown = false }
            }
            if (oldRemaining < 0 || newRemaining < 0) countsKnown = false
            previousOldEnd = oldLine - 1; previousNewEnd = newLine - 1
            continue
        }
        if (line.startsWith("\\ No newline at end of file")) {
            rows += ChangedLine(ChangedLineKind.Metadata, "文件末尾没有换行"); continue
        }
        // Headers are recognized only outside hunk content, so a literal +++ added line is counted.
        when {
            line.startsWith("--- ") -> oldPath = stripGitPrefix(headerPath(line.drop(4)))
            line.startsWith("+++ ") -> path = stripGitPrefix(headerPath(line.drop(4)))
            line.startsWith("rename from ") -> { oldPath = unquoteGitPath(line.removePrefix("rename from ")); kind = "重命名" }
            line.startsWith("rename to ") -> { path = unquoteGitPath(line.removePrefix("rename to ")); kind = "重命名" }
            line.startsWith("copy from ") -> { oldPath = unquoteGitPath(line.removePrefix("copy from ")); kind = "复制" }
            line.startsWith("copy to ") -> { path = unquoteGitPath(line.removePrefix("copy to ")); kind = "复制" }
            line.startsWith("new file mode ") -> kind = "新增"
            line.startsWith("deleted file mode ") -> kind = "删除"
            line.startsWith("Binary files ") || line == "GIT binary patch" -> { binary = true; countsKnown = false }
            line.startsWith("old mode ") || line.startsWith("new mode ") -> rows += ChangedLine(ChangedLineKind.Metadata, line)
            line.startsWith("@@") -> { countsKnown = false; rows += ChangedLine(ChangedLineKind.Metadata, line) }
            line.startsWith("diff --git ") || line.startsWith("index ") || line.startsWith("similarity index ") || line.startsWith("dissimilarity index ") -> Unit
            line.isNotEmpty() && !binary -> { countsKnown = false; rows += ChangedLine(ChangedLineKind.Metadata, line) }
        }
    }
    if (inHunk && (oldRemaining != 0 || newRemaining != 0)) countsKnown = false
    val resolvedPath = path?.takeUnless { it == "/dev/null" } ?: oldPath?.takeUnless { it == "/dev/null" } ?: return null
    if (oldPath == "/dev/null") kind = "新增"
    if (path == "/dev/null") kind = "删除"
    if (binary) rows += ChangedLine(ChangedLineKind.Metadata, "二进制文件已更改，无法显示逐行差异")
    if (rows.isEmpty()) rows += ChangedLine(ChangedLineKind.Metadata, when(kind) { "重命名" -> "文件已重命名，内容未更改"; "复制" -> "文件已复制，内容未更改"; else -> "文件元数据已更改" })
    return ChangedFile("$index:$resolvedPath", resolvedPath, oldPath?.takeIf { it != resolvedPath && it != "/dev/null" }, kind,
        rows, additions, deletions, countsKnown, lines.joinToString("\n"))
}

private fun stripGitPrefix(path: String): String = when { path.startsWith("a/") || path.startsWith("b/") -> path.drop(2); else -> path }
private fun headerPath(value: String): String = if (value.startsWith('"')) gitPathTokens(value).firstOrNull() ?: value else value.substringBefore('\t')

/** Git C-quoted paths encode non-ASCII UTF-8 bytes with octal escapes. */
private fun unquoteGitPath(value: String): String {
    if (!value.startsWith('"') || !value.endsWith('"')) return value
    val text = value.substring(1, value.length - 1)
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 >= text.length) { out.write('\\'.code); i++; continue }
        if (text[i] != '\\') {
            val end = text.indexOf('\\', i).takeIf { it >= 0 } ?: text.length
            out.write(text.substring(i, end).toByteArray(Charsets.UTF_8)); i = end; continue
        }
        i++
        val escaped = text[i]
        if (escaped in '0'..'7') {
            var end = i + 1
            while (end < text.length && end < i + 3 && text[end] in '0'..'7') end++
            out.write(text.substring(i, end).toInt(8)); i = end
        } else {
            val decoded = when (escaped) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000C'; 'v' -> '\u000B'; 'a' -> '\u0007'; else -> escaped }
            out.write(decoded.toString().toByteArray(Charsets.UTF_8)); i++
        }
    }
    return out.toByteArray().toString(Charsets.UTF_8)
}

private fun gitPathTokens(value: String): List<String> {
    val tokens = mutableListOf<String>()
    var i = 0
    while (i < value.length) {
        while (i < value.length && value[i] == ' ') i++
        if (i == value.length) break
        val start = i
        if (value[i] == '"') {
            i++
            while (i < value.length) {
                if (value[i] == '\\') { i += 2; continue }
                if (value[i++] == '"') break
            }
        } else while (i < value.length && value[i] != ' ') i++
        tokens += unquoteGitPath(value.substring(start, i.coerceAtMost(value.length)))
    }
    return tokens
}

private fun unquotedSamePathPair(value: String): List<String> {
    if (!value.startsWith("a/")) return emptyList()
    var boundary = value.indexOf(" b/", 2)
    while (boundary >= 0) {
        val old = value.substring(0, boundary)
        val current = value.substring(boundary + 1)
        if (old.drop(2) == current.drop(2)) return listOf(old, current)
        boundary = value.indexOf(" b/", boundary + 1)
    }
    return emptyList()
}

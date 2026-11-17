package com.nanci.codexremote.ui.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.ui.theme.NativeTheme
import com.nanci.codexremote.ui.theme.NativeTypography
import com.nanci.codexremote.ui.theme.nativeShadow

private val AddedInk: Color @Composable get() = NativeTheme.colors.success
private val RemovedInk: Color @Composable get() = NativeTheme.colors.danger
private val DiffBorder: Color @Composable get() = NativeTheme.colors.border

/** Native APK FloatingDiffBadge defaults: 34dp minimum, 320dp maximum, 16dp inset/gap. */
@Composable
fun ChangedFilesChip(summary: ChangedFilesSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 320.dp).heightIn(min = 34.dp)
            .nativeShadow(CircleShape,20.dp,Color.Black.copy(alpha=.05f))
            .clickable(onClick = onClick),
        shape = CircleShape, color = NativeTheme.colors.surface, border = BorderStroke(.5.dp, DiffBorder),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(summary.title, Modifier.weight(1f, fill = false), color = ContentMuted, style=NativeTypography.body14Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            summary.additions?.let { Text("+$it", color = AddedInk, style=NativeTypography.body14Medium) }
            summary.deletions?.let { Text("-$it", color = RemovedInk, style=NativeTypography.body14Medium) }
        }
    }
}

/** Offline native renderer. It never loads remote scripts or interprets patch text as HTML. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangedFilesSheet(summary: ChangedFilesSummary, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val colors = NativeTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var menu by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    val patchLines = remember(summary.patch) { if (summary.files.isEmpty() || !summary.complete) summary.patch.lines() else emptyList() }
    // File identifiers include path and ordinal so snapshots with stable order retain their folds.
    LaunchedEffect(summary.files.map { it.id }) { collapsed = collapsed.intersect(summary.files.map { it.id }.toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = colors.surface, contentColor = ContentInk, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp)) {
                Text(summary.title, Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                    color = ContentInk, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "差异选项" }) {
                        Canvas(Modifier.size(24.dp)) {
                            val unit = size.width / 24f
                            listOf(6f, 12f, 18f).forEach { y -> drawCircle(colors.ink, 1.7f * unit, Offset(12f * unit, y * unit)) }
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                        DropdownMenuItem(text = { Text("复制全部差异") }, onClick = { menu = false; onCopy(summary.patch) })
                        DropdownMenuItem(text = { Text(if (wrap) "关闭自动换行" else "自动换行") }, onClick = { wrap = !wrap; menu = false })
                        if (summary.files.isNotEmpty()) DropdownMenuItem(text = { Text(if (collapsed.size == summary.files.size) "展开所有文件" else "折叠所有文件") },
                            onClick = { collapsed = if (collapsed.size == summary.files.size) emptySet() else summary.files.map { it.id }.toSet(); menu = false })
                    }
                }
            }
            summary.basisNote?.takeIf { it.isNotBlank() }?.let { note ->
                Text(note, color = ContentMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            HorizontalDivider(color = DiffBorder)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                summary.files.forEach { file ->
                    item("header:${file.id}") {
                        FileDiffHeader(file, expanded = file.id !in collapsed) {
                            collapsed = if (file.id in collapsed) collapsed - file.id else collapsed + file.id
                        }
                    }
                    if (file.id !in collapsed) {
                        itemsIndexed(file.rows, key = { index, _ -> "${file.id}:$index" }) { _, row -> DiffLineRow(row, wrap) }
                    }
                    item("space:${file.id}") { Spacer(Modifier.height(12.dp)) }
                }
                if (patchLines.isNotEmpty()) {
                    item("raw-label") {
                        Text(if (summary.files.isEmpty()) "差异原文" else "部分差异无法逐行解析，完整原文如下", color = ContentMuted,
                            fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                    itemsIndexed(patchLines, key = { index, _ -> "raw:$index" }) { _, line ->
                        SelectionContainer {
                            Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                                color = ContentInk, softWrap = wrap, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDiffHeader(file: ChangedFile, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ContentField)) {
        HorizontalDivider(color = DiffBorder)
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(onClick = onToggle)
            .semantics { contentDescription = "${if (expanded) "折叠" else "展开"} ${file.title}" }
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContentIcon("chevron", Modifier.size(16.dp).rotate(if (expanded) 90f else 0f), ContentMuted)
            Text(file.title, Modifier.weight(1f), color = ContentInk, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (file.countsKnown) {
                Text("+${file.additions}", color = AddedInk, fontSize = 12.sp, lineHeight = 18.sp)
                Text("-${file.deletions}", color = RemovedInk, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        HorizontalDivider(color = DiffBorder)
    }
}

@Composable
internal fun DiffLineRow(row: ChangedLine, wrap: Boolean) {
    if (row.kind == ChangedLineKind.Separator) {
        Text(row.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
            .background(ContentField, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            color = ContentMuted, fontSize = 12.sp, lineHeight = 18.sp)
        return
    }
    if (row.kind == ChangedLineKind.Metadata) {
        Text(row.text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            color = ContentMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
        return
    }
    val colors = NativeTheme.colors
    val background = when (row.kind) {
        ChangedLineKind.Addition -> if(colors.isDark) colors.success.copy(alpha=.16f) else Color(0xFFEAF8EE)
        ChangedLineKind.Deletion -> if(colors.isDark) colors.danger.copy(alpha=.16f) else Color(0xFFFFEDED)
        else -> colors.surface
    }
    val marker = when (row.kind) { ChangedLineKind.Addition -> "+"; ChangedLineKind.Deletion -> "−"; else -> " " }
    val ink = when (row.kind) {
        ChangedLineKind.Addition -> if(colors.isDark || colors.highContrast) colors.success else Color(0xFF00682A)
        ChangedLineKind.Deletion -> if(colors.isDark || colors.highContrast) colors.danger else Color(0xFFAC2020)
        else -> ContentInk
    }
    val number = if (row.kind == ChangedLineKind.Deletion) row.oldNumber else row.newNumber
    Row(Modifier.fillMaxWidth().background(background), verticalAlignment = Alignment.Top) {
        Text(number?.toString().orEmpty(), Modifier.widthIn(min = 30.dp).padding(start = 4.dp, end = 6.dp),
            color = ContentMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp)
        Text(marker, Modifier.width(12.dp), color = ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
        SelectionContainer(Modifier.weight(1f)) {
            Text(row.text.ifEmpty { " " }, color = ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                softWrap = wrap, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())))
        }
    }
}

package com.nanci.codexremote.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** User-requested local list action; the APK's native Codex project row has no equivalent menu. */
@Composable
internal fun ProjectContextMenu(path: String, anchor: Rect, onDismiss: () -> Unit, onRemove: (String) -> Unit) {
    val origin = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(popupPositionProvider = origin, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true,
            dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        val geometry = rememberPopupWindowGeometry(LocalView.current)
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onDismiss).clearAndSetSemantics { })
            Layout(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (geometry == null) 0f else 1f },
                content = {
                    MenuCard {
                        Column(Modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                            ThreadMenuRow("从列表移除", "folder-minus") { onDismiss(); onRemove(path) }
                        }
                    }
                }) { children, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val screenX = geometry?.screenX ?: 0
                val screenY = geometry?.screenY ?: 0
                val frame = geometry?.visibleScreen
                val left = ((frame?.left ?: screenX) - screenX).coerceIn(0, (width - 1).coerceAtLeast(0))
                val top = ((frame?.top ?: screenY) - screenY).coerceIn(0, (height - 1).coerceAtLeast(0))
                val right = ((frame?.right ?: (screenX + width)) - screenX).coerceIn(left + 1, width.coerceAtLeast(left + 1))
                val bottom = ((frame?.bottom ?: (screenY + height)) - screenY).coerceIn(top + 1, height.coerceAtLeast(top + 1))
                val inset = 12.dp.roundToPx()
                val maxWidth = minOf(300.dp.roundToPx(), (right - left - inset * 2).coerceAtLeast(1))
                val maxHeight = minOf(600.dp.roundToPx(), ((bottom - top) * .75f).roundToInt().coerceAtLeast(1))
                val card = children.single().measure(Constraints(minWidth = minOf(200.dp.roundToPx(), maxWidth), maxWidth = maxWidth, maxHeight = maxHeight))
                val local = Rect(anchor.left - screenX, anchor.top - screenY, anchor.right - screenX, anchor.bottom - screenY)
                val below = local.bottom.roundToInt() + 10.dp.roundToPx()
                val above = local.top.roundToInt() - 10.dp.roundToPx() - card.height
                val preferBelow = local.center.y < (top + bottom) / 2f
                val desiredY = when {
                    preferBelow && below + card.height <= bottom - inset -> below
                    !preferBelow && above >= top + inset -> above
                    above >= top + inset -> above
                    below + card.height <= bottom - inset -> below
                    else -> local.center.y.roundToInt() - card.height / 2
                }
                val x = clampMenuPosition(local.left.roundToInt() + inset, left, right, card.width, inset)
                val y = clampMenuPosition(desiredY, top, bottom, card.height, inset)
                layout(width, height) { card.place(x, y) }
            }
        }
    }
}

@Composable
internal fun RemovedProjectsDialog(paths: Set<String>, onDismiss: () -> Unit, onRestore: ((String) -> Unit)?) {
    LaunchedEffect(paths.isEmpty()) { if (paths.isEmpty()) onDismiss() }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), containerColor = Color.White,
        title = { Text("已移除的项目", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(paths.sorted(), key = { it }) { path ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(path.trimEnd('/').substringAfterLast('/').ifBlank { path }, color = ShellInk,
                                fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                            Text(path, color = ShellMuted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = onRestore != null, onClick = { onRestore?.invoke(path) }) { Text("恢复", color = if (onRestore != null) ShellInk else ShellMuted) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = ShellInk) } })
}

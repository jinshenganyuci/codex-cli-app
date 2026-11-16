package com.nanci.codexremote.ui.shell

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * Android APK evidence: ine1 -> jq41/uer0, pf01/fer0, mfr0/jmh0.
 * The transparent popup contains the complete menu stack so outside taps and Back have
 * separate owners: outside closes the stack, while Back first removes its top menu.
 */
@Composable
internal fun ThreadContextMenu(
    thread: ShellThread,
    // Screen coordinates are shared across the Activity and the separately positioned Popup window.
    anchor: Rect,
    state: ShellState,
    actions: ThreadMenuActions,
    onDismiss: () -> Unit,
) {
    var sectionsOpen by remember(thread.id) { mutableStateOf(false) }
    var moveRowTop by remember(thread.id) { mutableStateOf<Float?>(null) }
    var childAnchorTop by remember(thread.id) { mutableStateOf<Float?>(null) }
    val availability = state.threadMenuAvailability
    fun finish(action: () -> Unit) { onDismiss(); action() }
    val origin = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(
        popupPositionProvider = origin,
        onDismissRequest = { if (sectionsOpen) sectionsOpen = false else onDismiss() },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true,
            dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        val popupView = LocalView.current
        val windowGeometry = rememberPopupWindowGeometry(popupView)
        Box(Modifier.fillMaxSize()) {
            // Deliberately no dim: the official menu leaves the list visible, highlighting only its row.
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onDismiss,
            ).clearAndSetSemantics { })
            Layout(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (windowGeometry == null) 0f else 1f },
                content = {
                    MenuCard(parentCovered = sectionsOpen) {
                        Column(Modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                            ThreadMenuRow(if (thread.pinned) "取消置顶" else "置顶", if (thread.pinned) "unpin-tilted" else "pin-tilted",
                                enabled = availability.canTogglePin, disabledReason = availability.disabledReason) {
                                if (!sectionsOpen) finish { actions.onTogglePin(thread.id) }
                            }
                            ThreadMenuRow("重命名", "pencil", enabled = availability.canRename, disabledReason = availability.disabledReason) {
                                if (!sectionsOpen) finish { actions.onRename(thread.id) }
                            }
                            ThreadMenuRow("移至分区", "folder-down", trailing = "right", enabled = availability.canMoveToSection,
                                disabledReason = availability.disabledReason,
                                modifier = Modifier.onGloballyPositioned { if (!sectionsOpen) moveRowTop = windowRectToScreen(popupView, it.boundsInWindow()).top }) {
                                if (!sectionsOpen) { childAnchorTop = moveRowTop; sectionsOpen = true; actions.onOpenSections(thread.id) }
                            }
                            ThreadMenuRow("标记为未读", "unread", enabled = availability.canMarkUnread && thread.readStateAvailable, disabledReason = availability.disabledReason) {
                                if (!sectionsOpen) finish { actions.onMarkUnread(thread.id) }
                            }
                            ThreadMenuRow("复制对话串 ID", "copy") { if (!sectionsOpen) finish { actions.onCopyId(thread.id) } }
                            ThreadMenuRow("归档", "archive", color = Color(0xFFD91843), enabled = availability.canArchive,
                                disabledReason = availability.disabledReason) {
                                if (!sectionsOpen) finish { actions.onArchive(thread.id) }
                            }
                            ThreadMenuRow("永久删除", "trash", color = Color(0xFFD91843),
                                enabled = availability.canDelete && actions.onDelete != null,
                                disabledReason = availability.disabledReason ?: "服务器尚未确认支持永久删除") {
                                if (!sectionsOpen) actions.onDelete?.let { delete -> finish { delete(thread.id) } }
                            }
                        }
                    }
                    if (sectionsOpen) MenuCard {
                        Column(Modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 8.dp)) {
                            ThreadMenuRow("移至分区", "folder-down", trailing = "chevron") { sectionsOpen = false }
                            HorizontalDivider(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp), color = Color(0xFFE7E7E7))
                            Spacer(Modifier.height(8.dp))
                            if (state.sectionsLoading) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp, color = ShellMuted)
                                    Spacer(Modifier.width(16.dp))
                                    Text("正在加载分区…", fontSize = 14.sp, color = ShellMuted)
                                }
                            }
                            if (!state.sectionsError.isNullOrBlank()) {
                                Text(state.sectionsError, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = ShellMuted, fontSize = 13.sp, lineHeight = 18.sp)
                                ThreadMenuRow("重试加载", "clock", enabled = !state.sectionsLoading) { actions.onOpenSections(thread.id) }
                            }
                            state.sections.forEach { section ->
                                key(section.id) {
                                    ThreadMenuRow(section.name, "folder", trailing = if (section.id == thread.sectionId) "check" else null,
                                        enabled = availability.canMoveToSection, disabledReason = availability.disabledReason) {
                                        finish { actions.onMoveToSection(thread.id, section.id) }
                                    }
                                }
                            }
                            if (state.sections.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Color(0xFFE7E7E7))
                            ThreadMenuRow("新建分区…", "folder-plus", enabled = availability.canMoveToSection, disabledReason = availability.disabledReason) {
                                finish { actions.onCreateSection(thread.id) }
                            }
                            if (thread.sectionId != null) ThreadMenuRow("移出分区", "folder-minus", enabled = availability.canMoveToSection,
                                disabledReason = availability.disabledReason) { finish { actions.onMoveToSection(thread.id, null) } }
                        }
                    }
                },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val screenX = windowGeometry?.screenX ?: 0
                val screenY = windowGeometry?.screenY ?: 0
                val frame = windowGeometry?.visibleScreen
                // WindowManager may clip a requested full-screen Popup to the system-bar/IME
                // display frame without reducing Compose's measure constraints. Clamp cards to
                // the real visible intersection, not the requested layout height.
                val left = ((frame?.left ?: screenX) - screenX).coerceIn(0, (width - 1).coerceAtLeast(0))
                val top = ((frame?.top ?: screenY) - screenY).coerceIn(0, (height - 1).coerceAtLeast(0))
                val right = ((frame?.right ?: (screenX + width)) - screenX).coerceIn(left + 1, width.coerceAtLeast(left + 1))
                val bottom = ((frame?.bottom ?: (screenY + height)) - screenY).coerceIn(top + 1, height.coerceAtLeast(top + 1))
                val visibleWidth = right - left
                val visibleHeight = bottom - top
                val localAnchor = Rect(anchor.left - screenX, anchor.top - screenY, anchor.right - screenX, anchor.bottom - screenY)
                val inset = 12.dp.roundToPx()
                val maxWidth = minOf(300.dp.roundToPx(), (visibleWidth - inset * 2).coerceAtLeast(1))
                val maxHeight = minOf(600.dp.roundToPx(), (visibleHeight * .75f).roundToInt().coerceAtLeast(1), (visibleHeight - inset * 2).coerceAtLeast(1))
                val cardConstraints = Constraints(minWidth = minOf(200.dp.roundToPx(), maxWidth), maxWidth = maxWidth, maxHeight = maxHeight)
                val main = measurables[0].measure(cardConstraints)
                val child = measurables.getOrNull(1)?.measure(cardConstraints)
                val gap = 12.dp.roundToPx()
                val anchorInset = 2.dp.roundToPx()
                val below = localAnchor.bottom.roundToInt() - anchorInset + gap
                val above = localAnchor.top.roundToInt() + anchorInset - gap - main.height
                val preferBelow = localAnchor.center.y < (top + bottom) / 2f
                val mainY = when {
                    preferBelow && below + main.height <= bottom - inset -> below
                    !preferBelow && above >= top + inset -> above
                    above >= top + inset -> above
                    below + main.height <= bottom - inset -> below
                    else -> localAnchor.center.y.roundToInt() - main.height / 2
                }.let { clampMenuPosition(it, top, bottom, main.height, inset) }
                val mainX = clampMenuPosition(localAnchor.left.roundToInt() + inset, left, right, main.width, inset)
                val childX = if (child == null) 0 else clampMenuPosition(mainX, left, right, child.width, inset)
                // The submenu opens over the Move row. Large lists may shift upward to stay visible.
                val childY = if (child == null) 0 else clampMenuPosition(
                    childAnchorTop?.let { (it - screenY).roundToInt() } ?: (mainY + 104.dp.roundToPx()), top, bottom, child.height, inset)
                layout(width, height) { main.place(mainX, mainY); child?.place(childX, childY) }
            }
        }
    }
}

/** Converts a Compose window rectangle through the actual Android window-to-screen offset. */
internal fun windowRectToScreen(view: View, bounds: Rect): Rect {
    val screen = IntArray(2)
    val window = IntArray(2)
    view.getLocationOnScreen(screen)
    view.getLocationInWindow(window)
    val x = screen[0] - window[0]
    val y = screen[1] - window[1]
    return Rect(bounds.left + x, bounds.top + y, bounds.right + x, bounds.bottom + y)
}

internal data class PopupWindowGeometry(val screenX: Int, val screenY: Int, val visibleScreen: IntRect)

/** The Popup is positioned by WindowManager after composition; observe its actual drawn frame. */
@Composable
internal fun rememberPopupWindowGeometry(view: View): PopupWindowGeometry? {
    var geometry by remember(view) { mutableStateOf<PopupWindowGeometry?>(null) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            val location = IntArray(2)
            val visible = android.graphics.Rect()
            view.getLocationOnScreen(location)
            view.getWindowVisibleDisplayFrame(visible)
            val next = if (view.isAttachedToWindow && !visible.isEmpty) PopupWindowGeometry(location[0], location[1],
                IntRect(visible.left, visible.top, visible.right, visible.bottom)) else null
            if (geometry != next) {
                geometry = next
                // Redraw after relayout, avoiding a one-frame flash at a stale Activity origin.
                false
            } else true
        }
        observer.addOnPreDrawListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) else view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }
    return geometry
}

internal fun clampMenuPosition(desired: Int, start: Int, end: Int, size: Int, inset: Int): Int {
    val minimum = start + inset.coerceAtMost((end - start - size).coerceAtLeast(0))
    val maximum = (end - size - inset).coerceAtLeast(minimum)
    return desired.coerceIn(minimum, maximum)
}

@Composable
internal fun MenuCard(parentCovered: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.graphicsLayer { scaleX = if (parentCovered) .97f else 1f; scaleY = if (parentCovered) .97f else 1f }
        .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .07f), spotColor = Color.Black.copy(alpha = .04f))
        .clip(shape).background(Color.White)
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        .then(if (parentCovered) Modifier.clearAndSetSemantics { } else Modifier), propagateMinConstraints = true) {
        content()
        if (parentCovered) Box(Modifier.matchParentSize().background(Color(0xFFFCFCFC).copy(alpha = .5f)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {},
        ))
    }
}

/** mfr0 + jmh0: 48dp minimum total height, 8dp vertical padding, 24/24/16 leading segment. */
@Composable
internal fun ThreadMenuRow(label: String, glyph: String, trailing: String? = null, enabled: Boolean = true,
    disabledReason: String? = null, color: Color = ShellInk, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (enabled) color else Color(0xFFAAAAAA)
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick)
        .then(if (!enabled && !disabledReason.isNullOrBlank()) Modifier.semantics { stateDescription = disabledReason } else Modifier)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(24.dp))
        ShellGlyph(glyph, Modifier.size(24.dp), tint)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), color = tint, fontSize = 16.sp, lineHeight = 24.sp,
            letterSpacing = .1.sp, fontWeight = FontWeight.Medium)
        if (trailing == null) Spacer(Modifier.width(24.dp)) else {
            Spacer(Modifier.width(16.dp)); ShellGlyph(trailing, Modifier.size(24.dp), tint); Spacer(Modifier.width(20.dp))
        }
    }
}

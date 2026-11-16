package com.nanci.codexremote.ui.shell

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun ShellMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    preserveEditorFocus: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val menuMaxHeight=minOf(600.dp,LocalConfiguration.current.screenHeightDp.dp*.75f)
    val touchExploration=rememberMenuTouchExploration()
    val keepEditorFocus=preserveEditorFocus && !touchExploration
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = 200.dp, max = 300.dp).heightIn(max = menuMaxHeight).shadow(
            elevation = 24.dp,
            shape = RoundedCornerShape(22.dp),
            clip = false,
            ambientColor = Color.Black.copy(alpha = .05f),
            spotColor = Color.Black.copy(alpha = .03f),
        ),
        offset = DpOffset(0.dp, 4.dp),
        shape = RoundedCornerShape(22.dp),
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        // Composer menus keep the editor's window and IME focused. Their Back routing is
        // handled separately; outside taps continue to dismiss the popup.
        properties = PopupProperties(focusable=!keepEditorFocus,
            dismissOnBackPress=!keepEditorFocus,dismissOnClickOutside=true),
        content = content,
    )
}

/** Match the APK's TouchExploration branch without treating every accessibility service as TalkBack. */
@Composable
private fun rememberMenuTouchExploration(): Boolean {
    val context=LocalContext.current
    val manager=remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isEnabled==true && manager?.isTouchExplorationEnabled==true) }
    DisposableEffect(manager) {
        fun refresh() { enabled=manager?.isEnabled==true && manager?.isTouchExplorationEnabled==true }
        val exploration=AccessibilityManager.TouchExplorationStateChangeListener { refresh() }
        val accessibility=AccessibilityManager.AccessibilityStateChangeListener { refresh() }
        manager?.addTouchExplorationStateChangeListener(exploration)
        manager?.addAccessibilityStateChangeListener(accessibility)
        refresh()
        onDispose {
            manager?.removeTouchExplorationStateChangeListener(exploration)
            manager?.removeAccessibilityStateChangeListener(accessibility)
        }
    }
    return enabled
}

@Composable
internal fun MenuRow(label: String, glyph: String? = null, subtitle: String? = null, checked: Boolean = false, trailing: Boolean = false, enabled: Boolean = true, color: Color = ShellInk, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { ShellGlyph(glyph, Modifier.size(24.dp), if (enabled) color else Color(0xFFAAAAAA)); Spacer(Modifier.width(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) color else Color(0xFFAAAAAA), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = Color(0xFF929292), fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (checked || trailing) { Spacer(Modifier.width(12.dp)); ShellGlyph(if (checked) "check" else "right", Modifier.size(if (checked) 24.dp else 16.dp), if (enabled) color else Color(0xFFAAAAAA)) }
    }
}

@Composable
internal fun MenuDivider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color(0xFFE7E7E7))

@Composable
fun ShellCircle(glyph: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.size(44.dp), shape = CircleShape, color = Color.White) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) { ShellGlyph(glyph, Modifier.size(24.dp), description = description) }
    }
}

/** Floating home chrome and plain text rows. Parent supplies system insets and bottom composer. */
@Composable
fun HomeScreen(state: ShellState, actions: ShellActions, modifier: Modifier = Modifier, bottomPadding: Dp = 100.dp) {
    var titleMenu by remember { mutableStateOf(false) }
    var sideMenu by remember { mutableStateOf(false) }
    var pressedId by remember(state.hostName) { mutableStateOf<String?>(null) }
    var pressedBounds by remember { mutableStateOf(Rect.Zero) }
    var projectMenuPath by remember(state.hostName) { mutableStateOf<String?>(null) }
    var projectMenuBounds by remember { mutableStateOf(Rect.Zero) }
    var removedProjectsOpen by remember(state.hostName) { mutableStateOf(false) }
    val pressed = state.threads.firstOrNull { it.id == pressedId }
    val haptics = LocalHapticFeedback.current
    val openMenu: ((ShellThread, Rect) -> Unit)? = if (actions.threadMenu == null) null else { thread, bounds ->
        titleMenu = false; sideMenu = false; projectMenuPath = null
        pressedBounds = bounds; pressedId = thread.id
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(pressedId, pressed) { if (pressedId != null && pressed == null) pressedId = null }
    val pinnedRows = state.threads.filter { it.pinned }
    val pinnedSectionId = pinnedRows.firstOrNull { it.sectionId != null && state.sectionThreadIds.containsKey(it.sectionId) }?.sectionId
    val pinned = sectionOrdered(pinnedRows, state.sectionThreadIds[pinnedSectionId])
    val customIds = state.sections.mapTo(mutableSetOf()) { it.id }
    val unpinned = state.threads.filterNot { it.pinned || it.sectionId in customIds }.let { threads -> if(state.grouping==ThreadGrouping.Priority)threads.sortedByDescending {it.running} else threads }
    val visibleProjectPaths = unpinned.mapNotNull { it.cwd.takeIf { path -> path.isNotBlank() && path !in state.hiddenProjectPaths } }.toSet()
    val openProjectMenu: ((String, Rect) -> Unit)? = if (actions.onRemoveProject == null) null else { path, bounds ->
        titleMenu = false; sideMenu = false; pressedId = null
        projectMenuPath = path; projectMenuBounds = bounds
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(projectMenuPath, state.grouping, visibleProjectPaths) {
        if (state.grouping != ThreadGrouping.Project || projectMenuPath !in visibleProjectPaths) projectMenuPath = null
    }
    Box(modifier.fillMaxSize().background(ShellBackground)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 128.dp, bottom = bottomPadding)) {
            if (state.loading && state.threads.isEmpty()) {
                items(7) { i -> Box(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).width((160 + i % 3 * 55).dp).height(18.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFEDEDED))) }
            } else if (state.threads.isNotEmpty()) {
                if (pinned.isNotEmpty()) {
                    item { GroupHeading("已置顶聊天") }
                    items(pinned, key = { it.id }) { ThreadRow(it, actions.onThread, selected = it.id == pressedId, onLongPress = openMenu) }
                }
                state.sections.forEach { section ->
                    val sectionThreads = sectionOrdered(state.threads.filter { !it.pinned && it.sectionId == section.id }, state.sectionThreadIds[section.id])
                    if (sectionThreads.isNotEmpty()) {
                        item(key = "section-heading:${section.id}") { GroupHeading(section.name) }
                        items(sectionThreads, key = { it.id }) { ThreadRow(it, actions.onThread, selected = it.id == pressedId, onLongPress = openMenu) }
                    }
                }
                if (state.grouping == ThreadGrouping.Project) {
                    // Local project-list preference only: all other groupings keep these chats.
                    val projects = unpinned.filter { it.cwd in visibleProjectPaths }.groupBy { it.cwd }
                    if (projects.isNotEmpty()) item { GroupHeading("项目") }
                    projects.forEach { (cwd, threads) ->
                        item(key = "project:$cwd") { ProjectGroup(cwd, threads, actions, pressedId, openMenu,
                            projectMenuPath == cwd, openProjectMenu) }
                    }
                    val recent = unpinned.filter { it.cwd.isBlank() }
                    if (recent.isNotEmpty()) item { GroupHeading("最近") }
                    items(recent, key = { it.id }) { ThreadRow(it, actions.onThread, selected = it.id == pressedId, onLongPress = openMenu) }
                } else {
                    if (unpinned.isNotEmpty() && pinned.isNotEmpty()) item { GroupHeading(if (state.grouping == ThreadGrouping.Priority) "聊天" else "最近") }
                    val rows = if (state.grouping == ThreadGrouping.Priority) unpinned.sortedByDescending { it.running } else unpinned
                    items(rows, key = { it.id }) { ThreadRow(it, actions.onThread, selected = it.id == pressedId, onLongPress = openMenu) }
                }
                if (state.hasMore) item { TextButton(onClick = actions.onLoadMore, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("加载更多聊天", color = ShellMuted) } }
            }
        }
        if (state.threads.isEmpty() && !state.loading) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 36.dp).offset(y = (-24).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ShellGlyph("laptop", Modifier.size(54.dp), tint = if (state.connected) ShellMuted else Color(0xFFAAAAAA))
                Spacer(Modifier.height(24.dp))
                Text(if (state.connected) "开始使用 Codex" else if (state.connecting) "正在连接本地 Codex…" else "连接你的 Codex", color = ShellInk, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(if (state.connected) "在你的开发服务器上开始新的聊天。" else if (state.connecting) "正在连接 ${state.hostName}" else "添加本地开发服务器，即可查看和继续聊天。", color = ShellMuted, fontSize = 16.sp, lineHeight = 25.sp, textAlign = TextAlign.Center)
                if (!state.connected && !state.connecting) TextButton(onClick = actions.onConnect, modifier = Modifier.padding(top = 14.dp)) { Text("添加连接", color = ShellInk) }
            }
        }
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(ShellBackground, ShellBackground.copy(alpha = .96f), ShellBackground.copy(alpha = .8f), Color.Transparent)))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    ShellCircle("menu", "菜单", { sideMenu = true })
                    ShellMenu(sideMenu, { sideMenu = false }) {
                        MenuRow("新聊天", "new") { sideMenu = false; actions.onNewChat() }
                        MenuRow("搜索聊天", "search") { sideMenu = false; actions.onSearch() }
                        MenuDivider()
                        MenuRow("连接", "link") { sideMenu = false; actions.onConnect() }
                        MenuRow("设置", "settings") { sideMenu = false; actions.onSettings() }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box {
                        Row(Modifier.clip(CircleShape).clickable { titleMenu = true }.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Codex", color = ShellInk, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(5.dp)); ShellGlyph("chevron", Modifier.size(12.dp))
                        }
                        ShellMenu(titleMenu, { titleMenu = false }, Modifier.width(272.dp)) {
                            MenuRow("优先级", "bell", checked = state.grouping == ThreadGrouping.Priority) { titleMenu = false; actions.onGroupingChanged(ThreadGrouping.Priority) }
                            MenuRow("按项目", "folder", checked = state.grouping == ThreadGrouping.Project) { titleMenu = false; actions.onGroupingChanged(ThreadGrouping.Project) }
                            MenuRow("按时间顺序排列", "clock", checked = state.grouping == ThreadGrouping.Time) { titleMenu = false; actions.onGroupingChanged(ThreadGrouping.Time) }
                            if (state.hiddenProjectPaths.isNotEmpty()) {
                                MenuDivider()
                                MenuRow("已移除的项目", "folder-minus") { titleMenu = false; removedProjectsOpen = true }
                            }
                            MenuDivider()
                            MenuRow("添加连接", "link") { titleMenu = false; actions.onConnect() }
                            MenuRow("设置", "settings") { titleMenu = false; actions.onSettings() }
                        }
                    }
                }
                ShellCircle("search", "搜索聊天", actions.onSearch)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("全部", Modifier.clip(CircleShape).clickable { actions.onAll() }.padding(horizontal = 12.dp, vertical = 8.dp), color = ShellMuted, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                // This client intentionally has no Cloud source: the user's scope is local Codex.
                Row(Modifier.clip(CircleShape).background(Color(0xFFE9E9E9)).clickable(onClick = actions.onHost).padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.connecting) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.3.dp, color = ShellMuted) else Box(Modifier.size(6.dp).clip(CircleShape).background(if (state.connected) Color(0xFF4AA356) else Color(0xFFAAAAAA)))
                    Spacer(Modifier.width(8.dp)); ShellGlyph("laptop", Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text(state.hostName, color = ShellInk, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
                }
            }
        }
        if (pressed != null && actions.threadMenu != null) ThreadContextMenu(pressed, pressedBounds, state, actions.threadMenu) { pressedId = null }
        val removeProject = actions.onRemoveProject
        projectMenuPath?.let { path ->
            if (removeProject != null) ProjectContextMenu(path, projectMenuBounds, { projectMenuPath = null }, removeProject)
        }
        if (removedProjectsOpen) RemovedProjectsDialog(state.hiddenProjectPaths, { removedProjectsOpen = false }, actions.onRestoreProject)
    }
}

/** Server ranks precede unranked rows; Kotlin's stable sort preserves each unknown row's position. */
private fun sectionOrdered(threads: List<ShellThread>, order: List<String>?): List<ShellThread> {
    if (order.isNullOrEmpty()) return threads
    val ranks = order.withIndex().associate { it.value to it.index }
    return threads.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
}

@Composable private fun GroupHeading(title: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, lineHeight = 24.sp, color = ShellInk, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ThreadRow(thread: ShellThread, onThread: (String) -> Unit, indent: Dp = 16.dp,
    selected: Boolean = false, onLongPress: ((ShellThread, Rect) -> Unit)? = null) {
    var windowBounds by remember(thread.id) { mutableStateOf(Rect.Zero) }
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().onGloballyPositioned { windowBounds = it.boundsInWindow() }
        // Selected gray is matched to supplied screenshots; its APK token is not yet established.
        .background(if (selected) Color(0xFFE8E8E8) else Color.Transparent)
        .semantics { this.selected = selected }
        .combinedClickable(onClick = { onThread(thread.id) }, onLongClickLabel = if (onLongPress != null) "聊天操作" else null,
            onLongClick = if (onLongPress == null) null else ({ onLongPress(thread, windowRectToScreen(view, windowBounds)) }))
        .heightIn(min = 54.dp).padding(start = indent, end = 18.dp, top = 13.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(thread.title.ifBlank { "新聊天" }, Modifier.weight(1f), color = ShellInk, fontSize = 16.sp, lineHeight = 24.sp,
            fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (thread.running) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF3566F0))) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ProjectGroup(cwd: String, threads: List<ShellThread>, actions: ShellActions,
    selectedId: String?, onLongPress: ((ShellThread, Rect) -> Unit)?, projectSelected: Boolean,
    onProjectLongPress: ((String, Rect) -> Unit)?) {
    var expanded by remember(cwd) { mutableStateOf(true) }
    var bounds by remember(cwd) { mutableStateOf(Rect.Zero) }
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInWindow() }
        .background(if (projectSelected) Color(0xFFE8E8E8) else Color.Transparent).semantics { selected = projectSelected }
        .combinedClickable(onClick = { expanded = !expanded }, onLongClickLabel = if (onProjectLongPress != null) "项目列表操作" else null,
            onLongClick = if (onProjectLongPress == null) null else ({ onProjectLongPress(cwd, windowRectToScreen(view, bounds)) }))
        .heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ShellGlyph("folder", Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Text(cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ShellInk)
        ShellGlyph(if (expanded) "chevron" else "right", Modifier.size(16.dp), ShellMuted)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(30.dp).clickable {actions.onProjectChat(cwd)}, contentAlignment = Alignment.Center) { ShellGlyph("new", Modifier.size(20.dp), ShellMuted) }
    }
    if (expanded) threads.forEach { thread -> key(thread.id) { ThreadRow(thread, actions.onThread, 50.dp, thread.id == selectedId, onLongPress) } }
}

/** Only the floating top controls. Keep this over the scrolling chat transcript. */
@Composable
fun ChatChrome(state: ShellState, actions: ShellActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(ShellBackground.copy(alpha = .98f), ShellBackground.copy(alpha = .88f), Color.Transparent))).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        ShellCircle("back", "返回", actions.onBack)
        Spacer(Modifier.width(8.dp))
        Surface(Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(22.dp), color = Color.White) {
            Column(Modifier.fillMaxSize().clickable(onClick = actions.onWorkspace).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                Text(state.title.ifBlank { "新聊天" }, color = ShellInk, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShellGlyph("folder", Modifier.size(12.dp), ShellMuted); Spacer(Modifier.width(3.dp))
                    Text(state.subtitle.ifBlank { "聊天" }, color = ShellMuted, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false), overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(4.dp)); ShellGlyph("laptop", Modifier.size(12.dp), ShellMuted)
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (state.connected) Color(0xFF4AA356) else Color(0xFFAAAAAA)))
                    Text(state.hostName, Modifier.weight(1f), color = ShellMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            Surface(shape = CircleShape, color = Color.White) {
                Row(Modifier.height(44.dp)) {
                    Box(Modifier.size(42.dp, 44.dp).clickable(onClick = actions.onNewChat), contentAlignment = Alignment.Center) { ShellGlyph("new", Modifier.size(23.dp), description = "新聊天") }
                    Box(Modifier.size(38.dp, 44.dp).clickable { menu = true }, contentAlignment = Alignment.Center) { ShellGlyph("more", Modifier.size(21.dp), description = "更多") }
                }
            }
            ShellMenu(menu, { menu = false }, Modifier.width(222.dp)) {
                MenuRow(if (state.pinned) "取消置顶" else "置顶", if (state.pinned) "unpin" else "pin", enabled = state.threadMenuAvailability.canTogglePin) { menu = false; actions.onTogglePin() }
                MenuRow("重命名", "pencil") { menu = false; actions.onRename() }
                MenuRow("复制对话串 ID", "copy") { menu = false; actions.onCopyId() }
                MenuRow("归档", "archive", color = Color(0xFFD91843)) { menu = false; actions.onArchive() }
                MenuRow("永久删除", "trash", color = Color(0xFFD91843),
                    enabled = state.threadMenuAvailability.canDelete && state.threadId != null && actions.onDelete != null) {
                    val target = state.threadId
                    val delete = actions.onDelete
                    if (target != null && delete != null) { menu = false; delete(target) }
                }
            }
        }
    }
}

@Composable
fun NewChatContext(state: ShellState, actions: ShellActions, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = actions.onHost).padding(horizontal = 8.dp, vertical = 12.dp).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShellGlyph("laptop", Modifier.size(16.dp), ShellMuted); Spacer(Modifier.width(10.dp)); Text(state.hostName, color = ShellMuted, fontSize = 16.sp, lineHeight = 24.sp); Spacer(Modifier.width(10.dp)); ShellGlyph("chevron", Modifier.size(12.dp), ShellMuted)
        }
        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = actions.onWorkspace).padding(horizontal = 8.dp, vertical = 12.dp).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShellGlyph("folder", Modifier.size(16.dp), ShellMuted); Spacer(Modifier.width(10.dp)); Text(state.workspace, color = ShellMuted, fontSize = 16.sp, lineHeight = 24.sp); Spacer(Modifier.width(10.dp)); ShellGlyph("chevron", Modifier.size(12.dp), ShellMuted)
        }
    }
}

/** The empty compose route uses the centered Codex title, not an existing-chat title pill. */
@Composable
fun NewChatChrome(actions: ShellActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ShellCircle("back", "返回", actions.onBack)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box {
                Row(Modifier.clip(CircleShape).clickable { menu = true }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Codex", color = ShellInk, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(5.dp)); ShellGlyph("chevron", Modifier.size(12.dp))
                }
                ShellMenu(menu, { menu = false }) {
                    MenuRow("添加连接", "link") { menu = false; actions.onConnect() }
                    MenuRow("设置", "settings") { menu = false; actions.onSettings() }
                }
            }
        }
        ShellCircle("search", "搜索聊天", actions.onSearch)
    }
}

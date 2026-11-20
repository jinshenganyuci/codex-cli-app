package com.nanci.codexremote.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.core.RepositoryState
import com.nanci.codexremote.ui.theme.NativeTheme
import kotlinx.coroutines.flow.collect

/** Shared by the real chat and debug UI checks. The parent owns IME/composer insets. */
@Composable
fun ChatTimelineContent(
    state: RepositoryState,
    creating: Boolean = false,
    latestRequest: Int = 0,
    onLoadEarlier: () -> Unit,
    onCopy: (String) -> Unit,
    onPlanOpen: (String) -> Unit,
    onDownload: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    imageLoader: ImageLoader,
    onOpenImage: (ImageSource) -> Unit,
    onCopyImage: (ImageSource) -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 84.dp,
) {
    val threadId = state.selectedThread?.id
    val scroll = remember(threadId) { LazyListState() }
    var followLatest by remember(threadId) { mutableStateOf(true) }
    var resumeAfterGesture by remember(threadId) { mutableStateOf(false) }
    val ready by rememberUpdatedState(!state.loadingThread && !creating)
    val blocks = remember(creating, state.turns) { if (creating) emptyList() else buildTimelineBlocks(state.turns) }
    val expansionOverrides = remember(threadId) { mutableStateMapOf<String, ActivityExpansion>() }
    val inspectActivity = { followLatest = false; resumeAfterGesture = false }

    // Only explicit user movement disables following. IME/composer resizing must not
    // be mistaken for scrolling away from the bottom.
    val manualScroll = remember(scroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    followLatest = false
                    resumeAfterGesture = true
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(threadId, latestRequest) { followLatest = true; resumeAfterGesture = false }
    LaunchedEffect(scroll) {
        snapshotFlow {
            val layout = scroll.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            FollowLayout(
                followLatest, ready, scroll.canScrollForward, resumeAfterGesture, scroll.isScrollInProgress,
                layout.totalItemsCount, layout.viewportEndOffset - layout.viewportStartOffset,
                last?.index ?: -1, last?.offset ?: 0, last?.size ?: 0,
            )
        }.collect { frame ->
            // Post-scroll callbacks can still see the previous measurement. Re-enable
            // following only after the settled layout confirms the gesture reached the end.
            // Loading an earlier page never opts into this automatic resumption.
            if (frame.ready && frame.resumeAfterGesture && !frame.scrolling && !frame.canScroll) {
                followLatest = true
                resumeAfterGesture = false
            }
            if (frame.follow && frame.ready && frame.canScroll && frame.count > 0 && frame.height > 0) {
                // A trailing layout anchor reaches the actual end, including a long
                // streaming message; scrolling to the message's start does not.
                scroll.scrollToItem(frame.count - 1)
            }
        }
    }
    key(threadId) {
        LazyColumn(
            state = scroll,
            modifier = modifier.fillMaxSize().nestedScroll(manualScroll),
            // Each message adds its own spacing so activity rows can stay compact.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset),
        ) {
            // Restoring the same chat must leave its cached messages and scroll
            // geometry intact; skeletons are only useful before any content exists.
            if ((state.loadingThread || creating) && blocks.isEmpty()) items(3) { index ->
                Column(Modifier.padding(bottom = TimelineItemGap).padding(vertical = 12.dp)) {
                    repeat(if (index == 0) 2 else 3) { i ->
                        Box(Modifier.padding(vertical = 5.dp).fillMaxWidth(if (i == 2) .6f else .92f)
                            .height(14.dp).background(ContentField, RoundedCornerShape(7.dp)))
                    }
                }
            }
            if (state.nextTurnsCursor != null) item(key = "timeline-earlier") {
                Box(Modifier.padding(bottom = TimelineItemGap)) {
                    TextButton(onClick = { inspectActivity(); onLoadEarlier() }, enabled = !state.loadingThread) {
                        Text("加载更早的消息")
                    }
                }
            }
            for (block in blocks) when (block) {
                is TimelineBlock.Message -> item(key = block.key) {
                    MessageContent(block.item.raw, modifier = Modifier.padding(bottom = TimelineItemGap), onCopy = onCopy,
                        onPlanOpen = onPlanOpen, onOpenLink = onOpenLink, onDownload = onDownload, imageLoader = imageLoader,
                        onOpenImage = onOpenImage, onCopyImage = onCopyImage, imageBaseDirectory = state.selectedThread?.cwd)
                }
                is TimelineBlock.CommandActivity -> {
                    val expanded = expansionOverrides[block.key]?.takeIf { it.running == block.running }?.expanded ?: block.running
                    item(key = block.key) {
                        ActivityDisclosure(
                            title = "命令记录 · ${block.commandCount} 条",
                            expanded = expanded,
                            onToggle = {
                                inspectActivity()
                                expansionOverrides[block.key] = ActivityExpansion(block.running, !expanded)
                            },
                            summary = true,
                            modifier = Modifier.padding(bottom = if (expanded) 4.dp else TimelineItemGap).testTag(block.key),
                        )
                    }
                    if (expanded) items(block.items.size, key = { index -> "${block.key}:child:${block.items[index].id}" }) { index ->
                        MessageContent(block.items[index].raw,
                            modifier = Modifier.padding(start = 12.dp, bottom = if (index == block.items.lastIndex) TimelineItemGap else 3.dp),
                            onCopy = onCopy, onPlanOpen = onPlanOpen, onOpenLink = onOpenLink, onDownload = onDownload,
                            imageLoader = imageLoader, onOpenImage = onOpenImage, onCopyImage = onCopyImage,
                            imageBaseDirectory = state.selectedThread?.cwd, compactActivity = true, onInspectActivity = inspectActivity)
                    }
                }
            }
            state.plans[state.activeTurnId]?.let { plan -> item(key = "timeline-plan") {
                Column(Modifier.fillMaxWidth().padding(bottom = TimelineItemGap)
                    .background(NativeTheme.colors.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("任务进度", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    plan.steps.forEach { step -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when (step.status) { "completed" -> "✓"; "inProgress", "in_progress" -> "◉"; else -> "○" }, color = ContentMuted)
                        Text(step.step, fontSize = 14.sp, lineHeight = 20.sp)
                    } }
                }
            } }
            state.turns.lastOrNull()?.error?.let { error -> item(key = "timeline-error") {
                Text(error, Modifier.padding(bottom = TimelineItemGap), color = NativeTheme.colors.danger, fontSize = 14.sp)
            } }
            if (state.activeTurnId != null) item(key = "timeline-running") {
                Row(Modifier.padding(bottom = TimelineItemGap),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Text("正在处理…", fontSize = 13.sp, color = ContentMuted)
                }
            }
            item(key = "timeline-end") { Spacer(Modifier.height(0.dp)) }
        }
    }
}

private val TimelineItemGap = 18.dp
private data class ActivityExpansion(val running: Boolean, val expanded: Boolean)

private data class FollowLayout(
    val follow: Boolean, val ready: Boolean, val canScroll: Boolean,
    val resumeAfterGesture: Boolean, val scrolling: Boolean,
    val count: Int, val height: Int, val lastIndex: Int, val lastOffset: Int, val lastSize: Int,
)

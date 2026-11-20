package com.nanci.codexremote.ui.content

import com.nanci.codexremote.core.ChatItem
import com.nanci.codexremote.core.ChatTurn

internal sealed interface TimelineBlock {
    val key: String

    data class Message(val item: ChatItem) : TimelineBlock {
        override val key = "${item.turnId}:${item.id}"
    }

    data class CommandActivity(
        val items: List<ChatItem>,
        val running: Boolean,
        override val key: String,
    ) : TimelineBlock {
        val commandCount = items.count { it.type == "commandExecution" }
    }
}

internal fun buildTimelineBlocks(turns: List<ChatTurn>): List<TimelineBlock> = buildList {
    for (turn in turns) {
        val activity = mutableListOf<ChatItem>()
        fun flushActivity() {
            val firstCommand = activity.firstOrNull { it.type == "commandExecution" }
            if (firstCommand == null) {
                activity.forEach { add(TimelineBlock.Message(it)) }
            } else {
                add(TimelineBlock.CommandActivity(
                    items = activity.toList(),
                    running = turn.status == "inProgress",
                    key = "activity:${turn.id}:${firstCommand.id}",
                ))
            }
            activity.clear()
        }

        for (item in turn.items) {
            if (item.isCommandActivity()) {
                activity.add(item)
            } else {
                flushActivity()
                add(TimelineBlock.Message(item))
            }
        }
        flushActivity()
    }
}

/** Some providers do not supply a phase. Keep their assistant text visible in the timeline. */
private fun ChatItem.isCommandActivity(): Boolean = when (type) {
    "commandExecution", "reasoning" -> true
    "agentMessage" -> raw.opt("phase") == "commentary" &&
        (raw.optJSONArray("questions")?.length() ?: 0) == 0 && !text.contains("![")
    else -> false
}

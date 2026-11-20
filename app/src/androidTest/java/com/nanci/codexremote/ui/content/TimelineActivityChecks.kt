package com.nanci.codexremote.ui.content

import com.nanci.codexremote.core.ChatItem
import com.nanci.codexremote.core.ChatTurn
import org.json.JSONArray
import org.json.JSONObject

object TimelineActivityChecks {
    fun run() {
        fun item(id: String, type: String, phase: String? = null, text: String = "") =
            ChatItem(id, "turn-a", type, text, "completed", JSONObject().put("phase", phase))

        val intro = item("intro", "agentMessage", "commentary", "正在读取文件")
        val user = item("user", "userMessage")
        val read = item("read", "commandExecution")
        val summary = item("summary", "reasoning")
        val write = item("write", "commandExecution")
        val media = item("media", "imageGeneration")
        val fileDiff = item("diff", "fileChange")
        val plan = item("plan", "plan")
        val referenceImage = item("reference-image", "agentMessage", "commentary", "![图][a]\n\n[a]: /path/test.png")
        val notes = item("legacy-text", "agentMessage", text = "Older server omitted phase")
        val question = item("question", "agentMessage", "commentary").also {
            it.raw.put("questions", JSONArray().put(JSONObject()))
        }
        val partial = item("partial", "agentMessage", "partial_answer")
        val answer = item("answer", "agentMessage", "final_answer")
        val end = item("end", "commandExecution")
        val followUp = item("follow-up", "agentMessage", "commentary")
        val visible = listOf(media, fileDiff, plan, referenceImage, notes, question, partial, answer)
        val turn = ChatTurn("turn-a", "inProgress", listOf(user, intro, read, summary, write) + visible + end + followUp,
            raw = JSONObject())
        val live = buildTimelineBlocks(listOf(turn))
        val liveActivity = live.filterIsInstance<TimelineBlock.CommandActivity>()
        check(liveActivity.size == 2 && liveActivity.all { it.running })
        check(liveActivity[0].items == listOf(intro, read, summary, write) && liveActivity[0].commandCount == 2)
        check(liveActivity[1].items == listOf(end, followUp) && liveActivity[1].commandCount == 1)
        check(live.filterIsInstance<TimelineBlock.Message>().map { it.item } == listOf(user) + visible)

        for (status in listOf("completed", "failed", "interrupted")) {
            val finished = buildTimelineBlocks(listOf(turn.copy(status = status)))
            check(finished.map { it.key } == live.map { it.key })
            check(finished.filterIsInstance<TimelineBlock.CommandActivity>().none { it.running })
        }
        val later = ChatTurn("turn-b", "completed", listOf(read.copy(turnId = "turn-b")), raw = JSONObject())
        val twoTurns = buildTimelineBlocks(listOf(turn, later))
        check(twoTurns.map { it.key }.distinct().size == twoTurns.size)
        check((twoTurns.last() as TimelineBlock.CommandActivity).items.single().turnId == "turn-b")
        val textOnly = buildTimelineBlocks(listOf(turn.copy(items = listOf(intro, summary, answer))))
        check(textOnly.filterIsInstance<TimelineBlock.Message>().map { it.item } == listOf(intro, summary, answer))
    }
}

package com.nanci.codexremote.core

import org.json.JSONArray
import org.json.JSONObject

/** Pure event reduction: history and streaming share the same item representation. */
internal object ProtocolReducer {
    fun reduce(state: RepositoryState, method: String, p: JSONObject): RepositoryState {
        val threadId = p.stringOrNull("threadId")
        val eventThreadId = threadId ?: p.optJSONObject("thread")?.stringOrNull("id")
        if (method != "thread/deleted" && eventThreadId in state.deletedThreadIds) return state
        if (method == "thread/deleted" && threadId != null) {
            val selected = state.selectedThread?.id == threadId
            return state.copy(
                deletedThreadIds = state.deletedThreadIds + threadId,
                threads = state.threads.filterNot { it.id == threadId },
                readStates = state.readStates - threadId,
                sectionThreadIds = state.sectionThreadIds.mapValues { (_, ids) -> ids.filterNot { it == threadId } },
                pendingRequests = state.pendingRequests.filterNot { it.threadId == threadId },
                selectedThread = if (selected) null else state.selectedThread,
                selectedThreadReady = if (selected) false else state.selectedThreadReady,
                turns = if (selected) emptyList() else state.turns,
                nextTurnsCursor = if (selected) null else state.nextTurnsCursor,
                plans = if (selected) emptyMap() else state.plans,
                diff = if (selected) "" else state.diff,
                diffTurnId = if (selected) null else state.diffTurnId,
                threadSettings = if (selected) null else state.threadSettings,
                guardianApprovalEnabled = if (selected) null else state.guardianApprovalEnabled,
                loadingThread = if (selected) false else state.loadingThread,
                sending = if (selected) false else state.sending,
                lastError = if (selected) null else state.lastError,
            )
        }
        if (method == "serverRequest/resolved") {
            val id = p.opt("requestId")
            return state.copy(pendingRequests = state.pendingRequests.filterNot {
                it.threadId == threadId && sameId(it.rpcId, id)
            })
        }
        if (method == "thread/started") {
            val raw = p.optJSONObject("thread") ?: return state
            val summary = ProtocolParser.thread(raw)
            return state.copy(threads = (listOf(summary) + state.threads.filterNot { it.id == summary.id }))
        }
        if (method == "thread/name/updated") {
            if (!p.has("threadName")) return state
            fun rename(t: ThreadSummary) = if (t.id == threadId) t.copy(title = p.optString("threadName")) else t
            return state.copy(threads = state.threads.map(::rename), selectedThread = state.selectedThread?.let(::rename))
        }
        if (method == "thread/section/changed") {
            if (threadId == null || !p.has("sectionId")) return state
            val sectionId = p.stringOrNull("sectionId")
            fun moved(t: ThreadSummary) = if (t.id == threadId) t.copy(sectionId = sectionId,
                raw = t.raw.copyJson().put("section", sectionId?.let { id ->
                    jsonObject("id" to id).apply { state.sections.find { it.id == id }?.let { put("name", it.name) } }
                } ?: JSONObject.NULL)) else t
            return state.copy(threads = state.threads.map(::moved), selectedThread = state.selectedThread?.let(::moved))
        }
        if (method == "thread/readState/changed") {
            if (threadId == null) return state
            val readState = ProtocolParser.readState(p.optJSONObject("readState")) ?: return state
            return state.copy(readStates = state.readStates + (threadId to readState), readStateSupported = true)
        }
        if (method == "thread/archived" && threadId != null)
            return state.copy(threads = state.threads.filterNot { it.id == threadId }, readStates = state.readStates - threadId)
        if (method == "thread/settings/updated") {
            val settings = p.optJSONObject("threadSettings") ?: return state
            fun changed(t: ThreadSummary) = if (t.id == threadId) t.copy(
                model = settings.stringOrNull("model") ?: t.model,
                modelProvider = settings.stringOrNull("modelProvider") ?: t.modelProvider,
                cwd = settings.stringOrNull("cwd") ?: t.cwd,
            ) else t
            // Normalize legacy resume names while retaining the complete current wire settings.
            val normalized = settings.copyJson().apply {
                if (has("effort")) put("reasoningEffort", opt("effort"))
                if (has("sandboxPolicy")) put("sandbox", opt("sandboxPolicy"))
            }
            return state.copy(threads = state.threads.map(::changed), selectedThread = state.selectedThread?.let(::changed),
                threadSettings = if (state.selectedThread?.id == threadId) normalized else state.threadSettings)
        }
        if (method == "thread/status/changed") {
            val status = p.optJSONObject("status")?.optString("type") ?: return state
            fun changed(t: ThreadSummary) = if (t.id == threadId) t.copy(status = status) else t
            return state.copy(threads = state.threads.map(::changed), selectedThread = state.selectedThread?.let(::changed))
        }
        if (threadId != null && threadId != state.selectedThread?.id) return state
        val turnId = p.stringOrNull("turnId")
        return when (method) {
            "turn/started", "turn/completed" -> {
                val incoming = p.optJSONObject("turn")?.let(ProtocolParser::turn) ?: return state
                val old = state.turns.find { it.id == incoming.id }
                val turn = incoming.copy(items = mergeItems(old?.items.orEmpty(), incoming.items))
                val turns = upsertTurn(state.turns, turn)
                state.copy(turns = turns,
                    lastError = incoming.error ?: state.lastError,
                    pendingRequests = if (method == "turn/completed") state.pendingRequests.filterNot { it.turnId == turn.id } else state.pendingRequests)
            }
            "item/started", "item/completed" -> {
                val raw = p.optJSONObject("item") ?: return state
                if (turnId == null) return state
                val item = ProtocolParser.item(raw, turnId)
                withItem(state, turnId, item.id) { item }
            }
            "item/agentMessage/delta", "item/plan/delta", "item/commandExecution/outputDelta",
            "item/fileChange/outputDelta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> {
                val itemId = p.stringOrNull("itemId") ?: return state
                if (turnId == null) return state
                val type = when (method) {
                    "item/agentMessage/delta" -> "agentMessage"
                    "item/plan/delta" -> "plan"
                    "item/commandExecution/outputDelta" -> "commandExecution"
                    "item/fileChange/outputDelta" -> "fileChange"
                    else -> "reasoning"
                }
                withItem(state, turnId, itemId) { old ->
                    val raw = old?.raw?.copyJson() ?: jsonObject("id" to itemId, "type" to type, "status" to "inProgress")
                    val delta = p.optString("delta")
                    when (type) {
                        "reasoning" -> {
                            val key = if (method.contains("summary")) "summary" else "content"
                            val index = p.optInt(if (key == "summary") "summaryIndex" else "contentIndex", 0)
                            val array = raw.optJSONArray(key) ?: JSONArray()
                            if (index in 0..10000) {
                                while (array.length() <= index) array.put("")
                                array.put(index, array.optString(index) + delta)
                                raw.put(key, array)
                            }
                        }
                        "commandExecution", "fileChange" -> raw.put("aggregatedOutput", raw.stringOrNull("aggregatedOutput").orEmpty() + delta)
                        else -> raw.put("text", raw.stringOrNull("text").orEmpty() + delta)
                    }
                    ProtocolParser.item(raw, turnId)
                }
            }
            "turn/plan/updated" -> {
                if (turnId == null) return state
                val plan = ExecutionPlan(turnId, p.stringOrNull("explanation"), p.optJSONArray("plan")?.objects()?.map {
                    PlanStep(it.optString("step"), it.optString("status"))
                }.orEmpty())
                state.copy(plans = state.plans + (turnId to plan))
            }
            "turn/diff/updated" -> state.copy(diff = p.optString("diff"), diffTurnId = p.stringOrNull("turnId"))
            "error" -> state.copy(lastError = p.optJSONObject("error")?.stringOrNull("message") ?: "服务器报告了错误")
            else -> state
        }
    }

    fun asyncQuestions(state: RepositoryState, hidden: Set<String>): List<AsyncQuestion> {
        val candidates = state.turns.asSequence().filter { it.status == "inProgress" }
            .flatMap { it.items.asSequence() }
            .filter { it.type == "agentMessage" && (it.raw.optJSONArray("questions")?.length() ?: 0) > 0 }
            .toList()
        if (candidates.isEmpty()) return emptyList()
        val answered = mutableSetOf<String>()
        state.items.filter { it.type == "userMessage" }.forEach { item ->
            val start = item.text.indexOf("<send_user_message_question_reply>")
            val end = item.text.indexOf("</send_user_message_question_reply>")
            if (start >= 0 && end > start) runCatching {
                val raw = item.text.substring(start + 34, end).trim()
                val values = if (raw.startsWith('[')) JSONArray(raw).objects() else listOf(JSONObject(raw))
                values.mapNotNullTo(answered) { it.stringOrNull("questionItemId") ?: it.stringOrNull("question_item_id") }
            }
        }
        return candidates.flatMap { item ->
            item.raw.optJSONArray("questions")?.objects()?.mapIndexedNotNull { index, q ->
                val key = jsonArray(listOf("request_user_input_async", item.id, index)).toString()
                if (key in hidden || key in answered) null
                else AsyncQuestion(key, item.id, item.turnId, q.optString("title"), q.optJSONArray("options")?.strings().orEmpty(), q.copyJson())
            }.orEmpty()
        }
    }

    fun sameId(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number -> left.toString() == right.toString()
        left is String && right is String -> left == right
        else -> false
    }

    /** Called only after the server acknowledges turn/start or turn/steer. */
    fun confirmUserInput(
        state: RepositoryState, threadId: String, turnId: String, clientId: String,
        input: List<JSONObject>, startsTurn: Boolean,
    ): RepositoryState {
        if (state.selectedThread?.id != threadId) return state
        val turn = state.turns.find { it.id == turnId }
            ?: ChatTurn(turnId, "inProgress", emptyList(), raw = jsonObject("id" to turnId, "status" to "inProgress"))
        if (turn.items.any { it.type == "userMessage" && it.raw.stringOrNull("clientId") == clientId }) return state
        val item = ProtocolParser.item(jsonObject(
            "type" to "userMessage", "id" to "ack:$clientId", "clientId" to clientId,
            "content" to jsonArray(input.map { it.copyJson() }), "localAcknowledged" to true,
        ), turnId)
        val items = if (startsTurn) listOf(item) + turn.items else turn.items + item
        return state.copy(turns = upsertTurn(state.turns, turn.copy(items = items)))
    }

    /** clientId bridges the temporary acknowledged item ID and the eventual persisted server ID. */
    fun mergeItems(base: List<ChatItem>, updates: List<ChatItem>): List<ChatItem> {
        val result = base.toMutableList()
        updates.forEach { update ->
            val index = result.indexOfFirst { existing ->
                existing.id == update.id || (existing.type == "userMessage" && update.type == "userMessage" &&
                    existing.raw.stringOrNull("clientId")?.let { it == update.raw.stringOrNull("clientId") } == true)
            }
            if (index < 0) result += update
            else {
                val existing = result[index]
                // A later history read supplies the canonical ID; never replace it with a local placeholder.
                if (!(update.raw.optBoolean("localAcknowledged") && !existing.raw.optBoolean("localAcknowledged")))
                    result[index] = update
            }
        }
        return result
    }

    private fun withItem(state: RepositoryState, turnId: String, itemId: String, transform: (ChatItem?) -> ChatItem): RepositoryState {
        val turn = state.turns.find { it.id == turnId }
            ?: ChatTurn(turnId, "inProgress", emptyList(), raw = jsonObject("id" to turnId, "status" to "inProgress"))
        val old = turn.items.find { it.id == itemId }
        val updated = transform(old)
        val items = mergeItems(turn.items, listOf(updated))
        return state.copy(turns = upsertTurn(state.turns, turn.copy(items = items)))
    }

    private fun upsertTurn(turns: List<ChatTurn>, turn: ChatTurn): List<ChatTurn> =
        if (turns.any { it.id == turn.id }) turns.map { if (it.id == turn.id) turn else it } else turns + turn
}

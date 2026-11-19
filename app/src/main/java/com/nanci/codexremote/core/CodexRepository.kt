package com.nanci.codexremote.core

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList

/** Session owner. All published snapshots, callbacks and public completions are main-thread confined. */
class CodexRepository : CoreContract {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(RepositoryState) -> Unit>()
    @Volatile override var state = RepositoryState(); private set
    private var profile: ConnectionProfile? = null
    private var connecting: Pair<ConnectionProfile, CompletableFuture<JSONObject>>? = null
    private var epoch = 0L
    private var selection = 0L
    private var listQuery = 0L
    private var sectionsQuery = 0L
    private var metadataRevision = 0L
    private var sectionMutationRevision = 0L
    private val threadMetadataRevisions = mutableMapOf<String, Long>()
    private val sectionOrderQueries = mutableMapOf<String, Long>()
    private var sectionOrderQuery = 0L
    private var receiptQuery = 0L
    private val receiptQueries = mutableMapOf<String, Long>()
    private data class UnsubmittedDraft(val started: JSONObject, val generation: Long)
    private val unsubmittedDrafts = mutableMapOf<String, UnsubmittedDraft>()
    private var disposed = false
    private val hiddenAsync = mutableSetOf<String>()
    private val transport: SshTransport = SshTransport(object : SshTransport.Listener {
        override fun onMessage(generation: Long, message: JSONObject) = onMain {
            if (disposed || generation != transportGeneration() || !transport.isOpen) return@onMain
            val method = message.optString("method")
            val params = message.optJSONObject("params") ?: JSONObject()
            val inboundThreadId = params.stringOrNull("threadId") ?: params.stringOrNull("conversationId")
                ?: params.optJSONObject("thread")?.stringOrNull("id")
            if (method != "thread/deleted" && inboundThreadId in state.deletedThreadIds) return@onMain
            if (message.has("id") && !message.isNull("id")) {
                val id = message.opt("id")
                if (id !is String && id !is Number) return@onMain
                val key = "$generation:${if (id is String) "s" else "n"}:$id"
                val kind = when (method) {
                    "item/commandExecution/requestApproval", "execCommandApproval" -> RequestKind.COMMAND_APPROVAL
                    "item/fileChange/requestApproval", "applyPatchApproval" -> RequestKind.FILE_APPROVAL
                    "item/permissions/requestApproval" -> RequestKind.PERMISSIONS_APPROVAL
                    "item/tool/requestUserInput" -> RequestKind.USER_INPUT
                    "mcpServer/elicitation/request" -> RequestKind.ELICITATION
                    else -> RequestKind.UNSUPPORTED
                }
                if (kind == RequestKind.UNSUPPORTED) {
                    // No tool/attestation handler is advertised. Explicit rejection avoids a hung turn.
                    transport.errorResponse(id, -32601, "This client does not implement $method")
                    publish(state.copy(lastError = "服务器请求了尚不支持的能力：$method"))
                    return@onMain
                }
                val request = PendingRequest(key, id, method, kind,
                    params.stringOrNull("threadId") ?: params.stringOrNull("conversationId"),
                    params.stringOrNull("turnId"), params.stringOrNull("itemId") ?: params.stringOrNull("callId"), params.copyJson())
                publish(state.copy(pendingRequests = state.pendingRequests.filterNot { it.key == key } + request))
            } else {
                if (method == "turn/started") params.stringOrNull("threadId")?.let { unsubmittedDrafts.remove(it) }
                if (method in THREAD_METADATA_EVENTS) applyThreadMetadata(method, params)
                else publish(ProtocolReducer.reduce(state, method, params))
                if (method == "thread/section/changed" && !state.loadingSections &&
                    params.stringOrNull("sectionId")?.let { id -> state.sections.none { it.id == id } } == true)
                    refreshSections()
            }
        }
        override fun onDisconnected(generation: Long, reason: String) = onMain {
            if (disposed || generation != transportGeneration()) return@onMain
            // Invalidate already-queued successful futures as well as still-pending requests.
            // Otherwise a late initialized success can resurrect CONNECTED after a natural disconnect.
            ++epoch; ++selection; ++listQuery; ++sectionsQuery
            publish(state.copy(connection = state.connection.copy(status = ConnectionStatus.DISCONNECTED, message = reason),
                pendingRequests = emptyList(), sending = false, selectedThreadReady = false,
                loadingThread = false, loadingThreads = false, loadingSections = false))
        }
    })

    private fun transportGeneration(): Long = transport.generation

    override fun addListener(listener: (RepositoryState) -> Unit): AutoCloseable {
        listeners += listener
        onMain { if (listener in listeners) listener(state) }
        return AutoCloseable { listeners -= listener }
    }

    override fun connect(profile: ConnectionProfile, credential: SshCredential): CompletableFuture<JSONObject> =
        connect(profile, credential, resetSession = false)

    /** Different saved keys can route the same SSH login to different chat spaces. */
    fun connect(profile: ConnectionProfile, credential: SshCredential, resetSession: Boolean): CompletableFuture<JSONObject> {
        // Copy before returning, so the credential vault may erase its decrypted arrays immediately.
        val secret = credential.copySecret()
        return task {
            if (disposed) { secret.close(); return@task failed(CodexException("连接器已关闭")) }
            connecting?.takeIf { !resetSession && it.first == profile && !it.second.isDone }?.let {
                secret.close(); return@task it.second
            }
            val sameHost = !resetSession && ComposerConnectionPolicy.sameOwner(this.profile, profile)
            this.profile = profile
            val token = ++epoch
            ++selection; ++listQuery; ++sectionsQuery
            metadataRevision++; sectionMutationRevision++; threadMetadataRevisions.clear(); sectionOrderQueries.clear(); receiptQueries.clear()
            if (!sameHost) { hiddenAsync.clear(); unsubmittedDrafts.clear(); publish(RepositoryState()) }
            val selectedId = state.selectedThread?.id
            publish(state.copy(connection = ConnectionState(ConnectionStatus.CONNECTING, profile.name, "正在连接服务器…"),
                pendingRequests = emptyList(), lastError = null, sending = false, selectedThreadReady = false,
                configRequirements = null, guardianApprovalEnabled = null,
                loadingSections = false, sectionsError = null, threadDeletionSupported = null))
            val connection = transport.connect(profile, secret)
            secret.close()
            val attempt = mainFuture(connection, token).thenCompose { g ->
                publish(state.copy(connection = ConnectionState(ConnectionStatus.INITIALIZING, profile.name, "正在连接 Codex…", g)))
                val params = jsonObject(
                    "clientInfo" to jsonObject("name" to "codex_mobile_lan", "title" to "Codex Mobile LAN", "version" to "2.0.0"),
                    "capabilities" to jsonObject("experimentalApi" to true, "requiredHumanApproval" to true, "requestAttestation" to false),
                )
                rpc("initialize", params, token).thenCompose { initialized ->
                    mainFuture(transport.notification("initialized"), token).thenApply {
                        publish(state.copy(connection = ConnectionState(ConnectionStatus.CONNECTED, profile.name, "已连接", g),
                            initialization = initialized, loadingThread = selectedId != null,
                            threadDeletionSupported = supportsNativeThreadDeletion(initialized)))
                        refreshThreads()
                        refreshSections()
                        rpc("model/list", jsonObject("includeHidden" to false), token, false).thenAccept { result ->
                            publish(state.copy(models = result.optJSONArray("data")?.objects()?.map(ProtocolParser::model).orEmpty()))
                        }
                        rpc("config/read", jsonObject("includeLayers" to false), token, false).thenAccept { result ->
                            publish(state.copy(config = redactConfig(result)))
                        }
                        rpc("configRequirements/read", JSONObject(), token, false).thenAccept { result ->
                            publish(state.copy(configRequirements = redactConfig(result)))
                        }
                        if (selectedId != null) openThread(selectedId) else refreshGuardianFeature(null)
                        initialized
                    }
                }
            }.whenComplete { _, error ->
                if (error != null && token == epoch) {
                    transport.disconnect()
                    publish(state.copy(connection = state.connection.copy(status = ConnectionStatus.DISCONNECTED, message = safeError(error)),
                        pendingRequests = emptyList(), selectedThreadReady = false, loadingThread = false, lastError = safeError(error)))
                }
            }
            connecting = profile to attempt
            attempt.whenComplete { _, _ -> if (connecting?.second === attempt) connecting = null }
            attempt
        }
    }

    override fun disconnect() = disconnect(clearSession = false)

    fun disconnect(clearSession: Boolean) = onMain {
        connecting = null
        ++epoch; ++selection; ++listQuery; ++sectionsQuery; transport.disconnect()
        if (clearSession) {
            profile = null
            hiddenAsync.clear(); unsubmittedDrafts.clear(); threadMetadataRevisions.clear(); receiptQueries.clear(); sectionOrderQueries.clear()
            publish(RepositoryState(connection = state.connection.copy(status = ConnectionStatus.DISCONNECTED, message = "连接已关闭")))
        } else publish(state.copy(connection = state.connection.copy(status = ConnectionStatus.DISCONNECTED, message = "连接已关闭"),
            pendingRequests = emptyList(), selectedThreadReady = false, loadingThread = false, loadingThreads = false, loadingSections = false, sending = false))
    }

    override fun clearError() = onMain { publish(state.copy(lastError = null)) }

    override fun request(method: String, params: JSONObject): CompletableFuture<JSONObject> = task {
        if (!state.connected) failed(CodexException("请先连接服务器")) else rpc(method, params)
    }

    /** Background diff reads propagate failures to their owner without creating chat error banners. */
    fun backgroundRead(method: String, params: JSONObject): CompletableFuture<JSONObject> = task {
        if (method !in setOf("gitDiffToRemote", "command/exec"))
            return@task failed(CodexException("该方法不支持后台读取"))
        if (!state.connected) failed(CodexException("请先连接服务器")) else rpc(method, params, reportError = false)
    }

    override fun refreshThreads(cursor: String?, search: String?, archived: Boolean): CompletableFuture<JSONObject> = task {
        val token = epoch
        val revision = metadataRevision
        val query = if (cursor == null) ++listQuery else listQuery
        publish(state.copy(loadingThreads = true))
        val params = jsonObject("limit" to 50, "sortKey" to "updated_at", "sortDirection" to "desc",
            "modelProviders" to JSONArray(), "archived" to archived)
            .putIfNotNull("cursor", cursor).putIfNotNull("searchTerm", search?.takeIf { it.isNotBlank() })
        request("thread/list", params).whenComplete { result, error ->
            if (query == listQuery && token == epoch) {
                if (error == null && revision != metadataRevision) {
                    // A confirmed mutation/notification is newer than this snapshot. Re-read only;
                    // never replay a write, and never roll back an acknowledged pin/read state.
                    refreshThreads(cursor, search, archived)
                    return@whenComplete
                }
                val incoming = result?.optJSONArray("data")?.objects()?.map(ProtocolParser::thread).orEmpty()
                    .filterNot { it.id in state.deletedThreadIds }
                val receipts = result?.optJSONObject("readStates")
                val incomingReceipts = receipts?.keys()?.asSequence()?.mapNotNull { id ->
                    ProtocolParser.readState(receipts.optJSONObject(id))?.let { id to it }
                }?.toMap().orEmpty().filterKeys { it !in state.deletedThreadIds }
                val mergedReceipts = if (cursor == null) incomingReceipts else
                    (state.readStates - incoming.map { it.id }.toSet()) + incomingReceipts
                publish(state.copy(loadingThreads = false,
                    threads = if (error != null) state.threads else if (cursor == null) incoming else (state.threads + incoming).distinctBy { it.id },
                    readStateSupported = if (error != null) state.readStateSupported else receipts != null,
                    readStates = if (error != null) state.readStates else if (receipts == null) emptyMap() else mergedReceipts,
                    nextThreadsCursor = if (error == null) result.stringOrNull("nextCursor") else state.nextThreadsCursor))
                if (error == null) refreshLoadedSectionOrders()
            }
        }
    }

    override fun openThread(threadId: String): CompletableFuture<JSONObject> = task {
        checkedId(threadId)
        if (threadId in state.deletedThreadIds) return@task failed(CodexException("此聊天已永久删除"))
        val selectionId = ++selection
        val metadataAtStart = threadMetadataRevisions[threadId] ?: 0L
        val old = state.selectedThread?.takeIf { it.id == threadId }
        val summary = state.threads.find { it.id == threadId } ?: old
            ?: ThreadSummary(threadId, "正在加载…", "", "", 0, "notLoaded", null, null, null, jsonObject("id" to threadId))
        publish(state.copy(selectedThread = summary, turns = if (old != null) state.turns else emptyList(), loadingThread = true,
            selectedThreadReady = false, nextTurnsCursor = if (old != null) state.nextTurnsCursor else null,
            plans = if (old != null) state.plans else emptyMap(), diff = if (old != null) state.diff else "",
            diffTurnId = if (old != null) state.diffTurnId else null, threadSettings = if (old != null) state.threadSettings else null,
            guardianApprovalEnabled = null, lastError = null))
        val draft = unsubmittedDrafts[threadId]
        if (draft != null) return@task reopenUnsubmittedDraft(threadId, draft, selectionId)
            .whenComplete { _, _ -> if (selectionId == selection) publish(state.copy(loadingThread = false)) }
        request("thread/resume", jsonObject("threadId" to threadId, "excludeTurns" to true,
            "initialTurnsPage" to jsonObject("limit" to HISTORY_PAGE_SIZE, "sortDirection" to "desc", "itemsView" to "full")))
            .thenCompose { result ->
                if (selectionId == selection) hydrate(result, metadataAtStart).thenApply { result }
                else CompletableFuture.completedFuture(result)
            }.whenComplete { _, _ -> if (selectionId == selection) publish(state.copy(loadingThread = false)) }
    }

    override fun newThread(cwd: String?, model: String?): CompletableFuture<JSONObject> = task {
        val selectionId = ++selection
        publish(state.copy(loadingThread = true, selectedThreadReady = false, lastError = null))
        request("thread/start", JSONObject().putIfNotNull("cwd", cwd?.takeIf { it.isNotBlank() }).putIfNotNull("model", model))
            .thenCompose { result ->
                rememberUnsubmittedDraft(result)
                if (selectionId == selection) {
                    val capability = hydrate(result, loadFallbackHistory = false)
                    refreshThreads()
                    capability.thenApply { result }
                } else CompletableFuture.completedFuture(result)
            }.whenComplete { _, _ -> if (selectionId == selection) publish(state.copy(loadingThread = false)) }
    }

    private fun rememberUnsubmittedDraft(result: JSONObject) {
        val id = result.optJSONObject("thread")?.stringOrNull("id") ?: return
        unsubmittedDrafts[id] = UnsubmittedDraft(result.copyJson(), transportGeneration())
    }

    private fun reopenUnsubmittedDraft(threadId: String, draft: UnsubmittedDraft, selectionId: Long): CompletableFuture<JSONObject> {
        if (draft.generation == transportGeneration())
            return hydrate(draft.started, loadFallbackHistory = false).thenApply { draft.started }
        // Another client may have materialized it while this connection was away. Only the
        // exact native not-materialized result permits replacing our never-submitted draft.
        return rpc("thread/turns/list", jsonObject("threadId" to threadId, "limit" to 1,
            "sortDirection" to "desc", "itemsView" to "full"), reportError = false).handle { _, error ->
            if (selectionId != selection || threadId in state.deletedThreadIds)
                return@handle failed<JSONObject>(CodexException("聊天已变化，请重新操作"))
            if (error == null) {
                unsubmittedDrafts.remove(threadId)
                return@handle openThread(threadId)
            }
            val cause = unwrap(error)
            val expected = "thread $threadId is not materialized yet; thread/turns/list is unavailable before first user message"
            if (cause !is CodexException || cause.code != -32600 || cause.safeMessage != expected) {
                publish(state.copy(lastError = safeError(cause)))
                return@handle failed<JSONObject>(if (cause is Exception) cause else CodexException("无法恢复空聊天"))
            }
            request("thread/start", JSONObject().putIfNotNull("cwd", draft.started.stringOrNull("cwd"))
                .putIfNotNull("model", draft.started.stringOrNull("model"))).thenCompose { fresh ->
                rememberUnsubmittedDraft(fresh)
                if (selectionId != selection) return@thenCompose CompletableFuture.completedFuture(fresh)
                unsubmittedDrafts.remove(threadId)
                hydrate(fresh, loadFallbackHistory = false, replacedDraftId = threadId).thenApply { fresh }
            }
        }.thenCompose { it }
    }

    private fun hydrate(result: JSONObject, observedMetadataRevision: Long? = null,
        loadFallbackHistory: Boolean = true, replacedDraftId: String? = null): CompletableFuture<Unit> {
        val raw = result.optJSONObject("thread") ?: throw CodexException("服务器没有返回聊天数据")
        var summary = ProtocolParser.thread(raw)
        if (summary.id in state.deletedThreadIds) throw CodexException("此聊天已永久删除")
        if (observedMetadataRevision != null && observedMetadataRevision != (threadMetadataRevisions[summary.id] ?: 0L)) {
            val current = state.threads.find { it.id == summary.id } ?: state.selectedThread?.takeIf { it.id == summary.id }
            if (current != null) summary = summary.copy(title = current.title, sectionId = current.sectionId,
                raw = summary.raw.copyJson().put("name", current.title).put("section", current.raw.opt("section") ?: JSONObject.NULL))
        }
        val page = result.optJSONObject("initialTurnsPage")
        val pageTurns = page?.optJSONArray("data")?.objects()?.map(ProtocolParser::turn)?.reversed()
        val turns = pageTurns ?: raw.optJSONArray("turns")?.objects()?.map(ProtocolParser::turn).orEmpty()
        val needsHistory = loadFallbackHistory && page == null && raw.optString("historyMode") == "paginated"
        publish(state.copy(selectedThread = summary, selectedThreadReady = false, turns = turns,
            diff = if (state.selectedThread?.id == summary.id) state.diff else "",
            diffTurnId = if (state.selectedThread?.id == summary.id) state.diffTurnId else null,
            draftReplacements = if (replacedDraftId == null) state.draftReplacements else state.draftReplacements + (replacedDraftId to summary.id),
            threads = state.threads.map { if (it.id == summary.id) summary else it },
            nextTurnsCursor = if (page != null) page.stringOrNull("nextCursor") else result.stringOrNull("turnsBackwardsCursor"),
            threadSettings = result.copyJson(excluding = setOf("thread", "initialTurnsPage")).apply {
                if (has("reasoningEffort") && !has("effort")) put("effort", opt("reasoningEffort"))
                if (has("sandbox") && !has("sandboxPolicy")) put("sandboxPolicy", opt("sandbox"))
            }, guardianApprovalEnabled = null, loadingThread = needsHistory))
        val capability = refreshGuardianFeature(summary.id)
        // Servers with paginated history may omit full turns from resume; load the newest page explicitly.
        val history = if (needsHistory)
            loadTurnPage(null).thenApply { Unit } else CompletableFuture.completedFuture(Unit)
        val selected = selection
        return capability.thenCombine(history) { _, _ -> Unit }
            .whenComplete { _, error -> if (selected == selection) publish(state.copy(loadingThread = false, selectedThreadReady = error == null)) }
    }

    /** Feature state is per-thread; old connection/selection replies cannot enable a different chat. */
    private fun refreshGuardianFeature(threadId: String?): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        val token = epoch
        val selected = selection
        val seen = mutableSetOf<String>()
        fun finish(enabled: Boolean?) {
            publish(state.copy(guardianApprovalEnabled = enabled))
            completion.complete(Unit)
        }
        fun page(cursor: String?, index: Int) {
            val params = jsonObject("limit" to 100).putIfNotNull("threadId", threadId).putIfNotNull("cursor", cursor)
            rpc("experimentalFeature/list", params, token, false).whenComplete { result, error ->
                if (token != epoch || selected != selection) {
                    completion.completeExceptionally(CodexException("连接或聊天已变化，请重新操作"))
                    return@whenComplete
                }
                if (error != null) { finish(null); return@whenComplete }
                val feature = result.optJSONArray("data")?.objects()?.firstOrNull { it.optString("name") == "guardian_approval" }
                if (feature != null) {
                    finish(feature.opt("enabled") as? Boolean)
                    return@whenComplete
                }
                val next = result.stringOrNull("nextCursor")
                if (next == null) finish(false)
                else if (index < 9 && seen.add(next)) page(next, index + 1)
                else finish(null)
            }
        }
        page(null, 0)
        return completion
    }

    override fun loadEarlierTurns(): CompletableFuture<JSONObject> = task {
        val cursor = state.nextTurnsCursor ?: return@task failed(CodexException("没有更早的聊天记录"))
        loadTurnPage(cursor)
    }

    private fun loadTurnPage(cursor: String?): CompletableFuture<JSONObject> {
        val threadId = state.selectedThread?.id ?: return failed(CodexException("请先打开聊天"))
        val selected = selection
        return request("thread/turns/list", jsonObject("threadId" to threadId, "limit" to HISTORY_PAGE_SIZE,
            "sortDirection" to "desc", "itemsView" to "full").putIfNotNull("cursor", cursor)).thenApply { result ->
            if (selected == selection) {
                val incoming = result.optJSONArray("data")?.objects()?.map(ProtocolParser::turn)?.reversed().orEmpty()
                // Preserve live turns that have advanced while the older page was in flight.
                val existing = state.turns.associateBy { it.id }
                val merged = incoming.map { saved ->
                    val live = existing[saved.id]
                    if (live?.status != "inProgress") saved
                    else {
                        live.copy(items = ProtocolReducer.mergeItems(saved.items, live.items))
                    }
                } + state.turns.filter { old -> incoming.none { it.id == old.id } }
                publish(state.copy(turns = merged, nextTurnsCursor = result.stringOrNull("nextCursor")))
            }
            result
        }
    }

    override fun sendMessage(text: String, inputs: List<JSONObject>, options: TurnOptions): CompletableFuture<JSONObject> = task {
        if (!state.canSend) return@task failed(CodexException("当前聊天暂时不能发送消息"))
        val threadId = state.selectedThread?.id ?: return@task failed(CodexException("请先创建聊天"))
        if (text.isBlank() && inputs.isEmpty()) return@task failed(CodexException("请输入消息或添加附件"))
        val input = UserInputs.prepareForSend((if (text.isNotBlank()) listOf(UserInputs.text(text)) else emptyList()) + inputs)
        if (state.activeTurnId != null) {
            return@task steerInput(threadId, state.activeTurnId!!, input)
        }
        val clientId = UUID.randomUUID().toString()
        val params = jsonObject("threadId" to threadId, "clientUserMessageId" to clientId, "input" to jsonArray(input))
        TurnOptionPayload.apply(params, options, state)
        val selected = selection
        publish(state.copy(sending = true, lastError = null))
        // Submission may succeed even if its reply is lost. Never recreate/replay this draft after it.
        unsubmittedDrafts.remove(threadId)
        request("turn/start", params).thenApply { result ->
            if (selected == selection) result.optJSONObject("turn")?.let { turn ->
                val accepted = ProtocolReducer.reduce(state, "turn/started", jsonObject("threadId" to threadId, "turn" to turn))
                publish(ProtocolReducer.confirmUserInput(accepted, threadId, turn.optString("id"), clientId, input, startsTurn = true))
            }
            result
        }.whenComplete { _, _ -> if (selected == selection) publish(state.copy(sending = false)) }
    }

    override fun steer(text: String): CompletableFuture<JSONObject> = task {
        val id = state.selectedThread?.id ?: return@task failed(CodexException("请先打开聊天"))
        val turn = state.activeTurnId ?: return@task failed(CodexException("当前没有进行中的任务"))
        if (text.isBlank()) return@task failed(CodexException("请输入消息"))
        steerInput(id, turn, listOf(UserInputs.text(text)))
    }

    private fun steerInput(threadId: String, turnId: String, inputs: List<JSONObject>): CompletableFuture<JSONObject> {
        if (state.sending) return failed(CodexException("消息正在发送"))
        val preparedInputs = UserInputs.prepareForSend(inputs)
        val selected = selection
        val clientId = UUID.randomUUID().toString()
        publish(state.copy(sending = true, lastError = null))
        return request("turn/steer", jsonObject("threadId" to threadId, "expectedTurnId" to turnId,
            "clientUserMessageId" to clientId, "input" to jsonArray(preparedInputs)))
            .thenApply { result ->
                if (result.optString("turnId") != turnId) throw CodexException("服务器返回了不同的任务，请刷新后检查消息状态", outcomeUnknown = true)
                if (selected == selection) publish(ProtocolReducer.confirmUserInput(state, threadId, turnId, clientId, preparedInputs, startsTurn = false))
                result
            }.whenComplete { _, _ -> if (selected == selection) publish(state.copy(sending = false)) }
    }

    override fun interrupt(): CompletableFuture<JSONObject> = task {
        val id = state.selectedThread?.id ?: return@task failed(CodexException("请先打开聊天"))
        val turn = state.activeTurnId ?: return@task failed(CodexException("当前没有进行中的任务"))
        request("turn/interrupt", jsonObject("threadId" to id, "turnId" to turn))
    }

    /** Fully paginated, server-owned sections. The built-in Pinned section is retained for pin actions. */
    fun refreshSections(): CompletableFuture<JSONObject> = task {
        if (!state.connected) return@task failed(CodexException("请先连接服务器"))
        val token = epoch
        val query = ++sectionsQuery
        val revision = sectionMutationRevision
        val completion = CompletableFuture<JSONObject>()
        val rows = mutableListOf<JSONObject>()
        val cursors = mutableSetOf<String>()
        publish(state.copy(loadingSections = true, sectionsError = null))
        fun page(cursor: String?, count: Int) {
            rpc("threadSection/list", jsonObject("limit" to 100).putIfNotNull("cursor", cursor), token, false)
                .whenComplete { result, error ->
                    if (token != epoch || query != sectionsQuery) {
                        completion.completeExceptionally(CodexException("连接或分区列表已变化")); return@whenComplete
                    }
                    if (error != null) {
                        val unsupported = (unwrap(error) as? CodexException)?.code == -32601
                        val message = if (unsupported) "此服务器不支持聊天分区" else safeError(error)
                        publish(state.copy(loadingSections = false, sectionsSupported = if (unsupported) false else state.sectionsSupported,
                            sectionsError = message))
                        completion.completeExceptionally(CodexException(message)); return@whenComplete
                    }
                    val data = result.optJSONArray("data")?.objects()
                    if (data == null || data.any { ProtocolParser.section(it) == null }) {
                        publish(state.copy(loadingSections = false, sectionsError = "服务器分区数据格式无效"))
                        completion.completeExceptionally(CodexException("服务器分区数据格式无效")); return@whenComplete
                    }
                    rows += data
                    val next = result.stringOrNull("nextCursor")
                    if (next != null) {
                        if (count >= 9 || !cursors.add(next)) {
                            publish(state.copy(loadingSections = false, sectionsError = "分区列表分页超过限制，请重试"))
                            completion.completeExceptionally(CodexException("分区列表分页超过限制，请重试"))
                        } else page(next, count + 1)
                    } else {
                        if (revision != sectionMutationRevision) {
                            refreshSections().whenComplete { fresh, failure ->
                                if (failure == null) completion.complete(fresh) else completion.completeExceptionally(failure)
                            }
                            return@whenComplete
                        }
                        val sections = rows.mapNotNull(ProtocolParser::section).distinctBy { it.id }
                        publish(state.copy(sections = sections, sectionsSupported = true, loadingSections = false, sectionsError = null,
                            sectionThreadIds = state.sectionThreadIds.filterKeys { id -> sections.any { it.id == id } }))
                        refreshLoadedSectionOrders()
                        completion.complete(result.copyJson().put("data", jsonArray(rows)))
                    }
                }
        }
        page(null, 0)
        completion
    }

    private fun refreshLoadedSectionOrders() {
        if (!state.connected || state.sectionsSupported != true) return
        state.threads.mapNotNull { it.sectionId }.distinct().filter { id -> state.sections.any { it.id == id } }
            .forEach { refreshSectionOrder(it) }
    }

    /** Does not inject unrequested historical threads into the global list. */
    fun refreshSectionOrder(sectionId: String): CompletableFuture<JSONObject> = task {
        if (!state.connected || state.sectionsSupported != true || state.sections.none { it.id == sectionId })
            return@task failed(CodexException("分区当前不可用"))
        val targets = state.threads.filter { it.sectionId == sectionId }.map { it.id }.toSet()
        if (targets.isEmpty()) return@task CompletableFuture.completedFuture(jsonObject("data" to JSONArray(), "nextCursor" to null))
        val token = epoch
        val revision = sectionMutationRevision
        val query = ++sectionOrderQuery
        sectionOrderQueries[sectionId] = query
        val ids = mutableListOf<String>()
        val cursors = mutableSetOf<String>()
        val completion = CompletableFuture<JSONObject>()
        fun page(cursor: String?, index: Int) {
            rpc("thread/list", jsonObject("sectionId" to sectionId, "sortKey" to "section_position", "sortDirection" to "asc",
                "limit" to 100, "archived" to false, "modelProviders" to JSONArray()).putIfNotNull("cursor", cursor), token, false)
                .whenComplete { result, error ->
                    if (token != epoch || revision != sectionMutationRevision || query != sectionOrderQueries[sectionId] || state.sections.none { it.id == sectionId }) {
                        completion.completeExceptionally(CodexException("分区顺序已变化")); return@whenComplete
                    }
                    if (error != null) { completion.completeExceptionally(error); return@whenComplete }
                    ids += result.optJSONArray("data")?.objects()?.mapNotNull { it.stringOrNull("id") }.orEmpty()
                    val next = result.stringOrNull("nextCursor")
                    if (ids.containsAll(targets) || next == null || index >= 9 || !cursors.add(next)) {
                        publish(state.copy(sectionThreadIds = state.sectionThreadIds + (sectionId to ids.distinct())))
                        completion.complete(result)
                    } else page(next, index + 1)
                }
        }
        page(null, 0)
        completion
    }

    fun createSection(name: String): CompletableFuture<JSONObject> = task {
        val normalized = checkedName(name, "分区")
        if (state.sectionsSupported != true) return@task failed(CodexException("请先成功读取服务器分区"))
        request("threadSection/create", jsonObject("name" to normalized)).thenApply { result ->
            val section = result.optJSONObject("section")?.let(ProtocolParser::section)
                ?: throw CodexException("服务器未返回新分区")
            ++sectionMutationRevision
            publish(state.copy(sections = (state.sections.filterNot { it.id == section.id } + section).sortedBy { it.id }, sectionsError = null))
            refreshLoadedSectionOrders()
            result
        }
    }

    fun moveThreadToSection(threadId: String, sectionId: String?): CompletableFuture<JSONObject> = task {
        checkedId(threadId)
        val beforeRevision = threadMetadataRevisions[threadId]
        if (state.sectionsSupported != true) return@task failed(CodexException("此连接尚未确认支持聊天分区"))
        if (sectionId != null && state.sections.none { it.id == sectionId })
            return@task failed(CodexException("目标分区已变化，请刷新后重新选择"))
        request("thread/section/move", jsonObject("threadId" to threadId, "sectionId" to sectionId)).thenApply { result ->
            val current = state.threads.find { it.id == threadId } ?: state.selectedThread?.takeIf { it.id == threadId }
            if (beforeRevision != threadMetadataRevisions[threadId] && current?.sectionId != sectionId) {
                refreshThreadReadState(threadId); return@thenApply result
            }
            applyThreadMetadata("thread/section/changed", jsonObject("threadId" to threadId, "sectionId" to sectionId))
            result
        }
    }

    /** Read-only receipt refresh; it does not mark the thread read or resume a conversation. */
    fun refreshThreadReadState(threadId: String): CompletableFuture<JSONObject> = task {
        checkedId(threadId)
        if (threadId in state.deletedThreadIds) return@task failed(CodexException("此聊天已永久删除"))
        val token = epoch
        val revision = threadMetadataRevisions[threadId]
        val query = ++receiptQuery
        receiptQueries[threadId] = query
        if (!state.connected) return@task failed(CodexException("请先连接服务器"))
        rpc("thread/read", jsonObject("threadId" to threadId, "includeTurns" to false), token, false).thenApply { result ->
            if (threadId in state.deletedThreadIds || revision != threadMetadataRevisions[threadId] || receiptQueries[threadId] != query) return@thenApply result
            val receipt = ProtocolParser.readState(result.optJSONObject("readState"))
            val summary = result.optJSONObject("thread")?.let(ProtocolParser::thread)?.takeIf { it.id == threadId }
            val old = state.threads.find { it.id == threadId } ?: state.selectedThread?.takeIf { it.id == threadId }
            val sectionChanged = summary != null && summary.sectionId != old?.sectionId
            if (receipt != state.readStates[threadId] || (summary != null && (summary.title != old?.title || sectionChanged)))
                threadMetadataRevisions[threadId] = ++metadataRevision
            if (sectionChanged) ++sectionMutationRevision
            publish(state.copy(readStates = if (receipt == null) state.readStates - threadId else state.readStates + (threadId to receipt),
                readStateSupported = if (receipt != null) true else state.readStateSupported,
                threads = if (summary == null) state.threads else state.threads.map { if (it.id == threadId) summary else it },
                selectedThread = if (summary != null && state.selectedThread?.id == threadId) summary else state.selectedThread))
            if (sectionChanged) refreshLoadedSectionOrders()
            result
        }
    }

    /** Conflicting revisions are refreshed for display only; never silently repeat the user's write. */
    fun updateThreadReadState(threadId: String, unread: Boolean, expectedRevision: String): CompletableFuture<JSONObject> = task {
        checkedId(threadId)
        if (expectedRevision.isBlank() || expectedRevision.length > 2048) return@task failed(CodexException("未读状态已失效，请刷新后重试"))
        if (state.readStateSupported != true || state.readStates[threadId] == null)
            return@task failed(CodexException("此聊天不支持持久化未读状态"))
        if (!state.connected) return@task failed(CodexException("请先连接服务器"))
        val token = epoch
        val beforeRevision = threadMetadataRevisions[threadId]
        rpc("thread/readState/update", jsonObject("threadId" to threadId,
            "operation" to jsonObject("type" to if (unread) "unread" else "read"), "expectedRevision" to expectedRevision), token, false)
            .handle { result, error ->
                if (error != null) {
                    val cause = unwrap(error)
                    val code = (cause as? CodexException)?.code
                    if (token == epoch) {
                        if (code == -32601) publish(state.copy(readStateSupported = false, readStates = emptyMap()))
                        else if (code == -32600) refreshThreadReadState(threadId)
                    }
                    throw CompletionException(if (code == -32600) CodexException("聊天未读状态已变化，请重新确认后操作", code) else cause)
                }
                val receipt = ProtocolParser.readState(result.optJSONObject("readState"))
                    ?: throw CodexException("服务器未返回有效未读状态")
                if (beforeRevision != threadMetadataRevisions[threadId] && state.readStates[threadId] != receipt) {
                    refreshThreadReadState(threadId); return@handle result
                }
                applyThreadMetadata("thread/readState/changed", jsonObject("threadId" to threadId, "readState" to result.optJSONObject("readState")))
                result
            }
    }

    override fun renameThread(threadId: String, name: String): CompletableFuture<JSONObject> = task {
        checkedId(threadId)
        val normalized = checkedName(name, "聊天")
        val beforeRevision = threadMetadataRevisions[threadId]
        request("thread/name/set", jsonObject("threadId" to threadId, "name" to normalized)).thenApply { result ->
            val current = state.threads.find { it.id == threadId } ?: state.selectedThread?.takeIf { it.id == threadId }
            if (beforeRevision != threadMetadataRevisions[threadId] && current?.title != normalized) {
                refreshThreadReadState(threadId); return@thenApply result
            }
            applyThreadMetadata("thread/name/updated", jsonObject("threadId" to threadId, "threadName" to normalized))
            result
        }
    }

    override fun archiveThread(threadId: String, archived: Boolean): CompletableFuture<JSONObject> = task {
        checkedId(threadId)
        val beforeRevision = threadMetadataRevisions[threadId]
        request(if (archived) "thread/archive" else "thread/unarchive", jsonObject("threadId" to threadId)).thenApply { result ->
            if (beforeRevision != threadMetadataRevisions[threadId]) { refreshThreads(); return@thenApply result }
            if (archived) applyThreadMetadata("thread/archived", jsonObject("threadId" to threadId))
            else refreshThreads()
            result
        }
    }

    /** Read the complete dependency set before asking the user to confirm a destructive scope. */
    fun prepareThreadDeletion(threadId: String): CompletableFuture<ThreadDeletionPlan> = deletionTask {
        requireThreadDeletion(threadId)
        prepareThreadDeletion(threadId, epoch)
    }

    /** Preserve the old single-target contract: this overload never silently deletes other forks. */
    fun deleteThread(threadId: String): CompletableFuture<JSONObject> = deletionTask {
        requireThreadDeletion(threadId)
        deleteNativeThread(threadId, epoch)
    }

    /** Only IDs shown in the confirmed plan may be explicitly deleted. Recheck before the first RPC. */
    fun deleteThread(plan: ThreadDeletionPlan): CompletableFuture<JSONObject> = deletionTask {
        requireThreadDeletion(plan.rootId)
        requireDeletionConnection(plan.generation)
        val confirmed = plan.threads.map { it.id }.toSet()
        if (confirmed.isEmpty() || confirmed.size != plan.threads.size || plan.rootId !in confirmed)
            return@deletionTask failed(CodexException("删除范围无效，请重新打开确认窗口"))
        prepareThreadDeletion(plan.rootId, plan.generation).thenCompose { current ->
            if (current.threads.map { it.id }.toSet() != confirmed)
                return@thenCompose failed<JSONObject>(CodexException("关联聊天已发生变化，请重新打开删除窗口确认"))
            var deleted = 0
            var sequence = CompletableFuture.completedFuture(JSONObject())
            current.threads.forEach { thread ->
                sequence = sequence.thenCompose {
                    requireDeletionConnection(plan.generation)
                    if (thread.id in state.deletedThreadIds) CompletableFuture.completedFuture(JSONObject())
                    else deleteNativeThread(thread.id, plan.generation).thenApply { result -> deleted++; result }
                }
            }
            sequence.handle { _, error ->
                if (error != null) {
                    val reason = deletionError(unwrap(error))
                    throw CompletionException(CodexException(if (deleted == 0) reason
                        else "已删除 $deleted 条聊天，其余已停止删除。$reason"))
                }
                jsonObject("deletedThreadIds" to jsonArray(current.threads.map { it.id }))
            }
        }
    }

    private fun requireThreadDeletion(threadId: String) {
        checkedId(threadId)
        if (!state.connected) throw CodexException("请先连接服务器")
        if (state.threadDeletionSupported != true) throw CodexException("此服务器版本尚未确认支持永久删除聊天")
        if (threadId in state.deletedThreadIds) throw CodexException("此聊天已永久删除")
    }

    private fun requireDeletionConnection(token: Long) {
        if (disposed || token != epoch || !state.connected || !transport.isOpen)
            throw CodexException("连接已改变，已停止删除，请重新确认")
    }

    private fun <T> deletionTask(block: () -> CompletableFuture<T>): CompletableFuture<T> = task {
        // The confirmation dialog owns these failures; do not also show a chat-wide snackbar.
        try { block() } catch (error: Exception) { failed(error) }
    }

    private fun deleteNativeThread(threadId: String, token: Long): CompletableFuture<JSONObject> {
        requireDeletionConnection(token)
        return rpc("thread/delete", jsonObject("threadId" to threadId), token, false).handle { result, error ->
            if (error != null) {
                val cause = unwrap(error)
                val unsupported = (cause as? CodexException)?.code == -32601
                if (unsupported && token == epoch) publish(state.copy(threadDeletionSupported = false))
                throw CompletionException(CodexException(deletionError(cause), (cause as? CodexException)?.code))
            }
            applyThreadMetadata("thread/deleted", jsonObject("threadId" to threadId))
            result
        }
    }

    private fun deletionError(error: Throwable): String {
        val cause = unwrap(error)
        if ((cause as? CodexException)?.code == -32601) return "此服务器不支持永久删除聊天"
        val message = safeError(cause)
        return if (message.contains("forked history still references it", ignoreCase = true))
            "仍有其他分叉聊天引用这段历史，服务器已保留它。请重新读取关联聊天后再确认删除。"
        else message
    }

    private fun prepareThreadDeletion(threadId: String, token: Long): CompletableFuture<ThreadDeletionPlan> {
        val threads = linkedMapOf<String, ThreadSummary>()
        val hydrated = mutableSetOf<String>()
        val inspectedAttached = mutableSetOf<String>()
        var requestCount = 0
        fun read(method: String, params: JSONObject): CompletableFuture<JSONObject> {
            requireDeletionConnection(token)
            if (++requestCount > 12_000) throw CodexException("关联聊天过多，无法完整确认删除范围；未执行删除")
            return rpc(method, params, token, false)
        }
        fun add(raw: JSONObject) {
            val id = raw.stringOrNull("id") ?: throw CodexException("服务器返回了不完整的聊天列表；未执行删除")
            checkedId(id)
            if (id !in hydrated) threads[id] = ProtocolParser.thread(raw)
            if (threads.size > 10_000) throw CodexException("聊天数量过多，无法完整确认删除范围；未执行删除")
        }
        fun list(archived: Boolean, attachedTo: String?): CompletableFuture<Unit> {
            val cursors = mutableSetOf<String>()
            var pages = 0
            fun page(cursor: String?): CompletableFuture<Unit> {
                if (++pages > 100) throw CodexException("聊天分页过多，无法完整确认删除范围；未执行删除")
                val params = jsonObject("limit" to 100, "archived" to archived,
                    "modelProviders" to JSONArray(), "sourceKinds" to jsonArray(DELETION_SOURCE_KINDS))
                if (cursor != null) params.put("cursor", cursor)
                if (attachedTo != null) params.put("attachedToThreadId", attachedTo)
                return read("thread/list", params).thenCompose { result ->
                    val rows = result.optJSONArray("data") ?: throw CodexException("服务器返回了不完整的聊天列表；未执行删除")
                    for (index in 0 until rows.length()) {
                        val raw = rows.optJSONObject(index) ?: throw CodexException("服务器返回了无效的聊天记录；未执行删除")
                        if (attachedTo != null && raw.stringOrNull("attachedToThreadId") != attachedTo)
                            throw CodexException("服务器未正确返回附属聊天；未执行删除")
                        add(raw)
                    }
                    val next = result.stringOrNull("nextCursor")
                    if (next == null) CompletableFuture.completedFuture(Unit)
                    else {
                        if (!cursors.add(next)) throw CodexException("聊天分页重复，无法完整确认删除范围；未执行删除")
                        page(next)
                    }
                }
            }
            return page(null)
        }
        fun hydrateMissing(): CompletableFuture<Unit> {
            val ids = threads.keys.filterNot { it in hydrated }
            fun batch(offset: Int): CompletableFuture<Unit> {
                if (offset >= ids.size) return CompletableFuture.completedFuture(Unit)
                val end = minOf(offset + 4, ids.size)
                val pending = ids.subList(offset, end).map { id ->
                    read("thread/read", jsonObject("threadId" to id, "includeTurns" to false)).thenApply { result ->
                        val raw = result.optJSONObject("thread") ?: throw CodexException("无法读取聊天关系；未执行删除")
                        if (raw.stringOrNull("id") != id) throw CodexException("服务器返回的聊天标识不匹配；未执行删除")
                        // 0.169.0 thread/list intentionally sets forkedFromId=null. Only
                        // metadata-only thread/read reconstructs the persisted fork lineage.
                        threads[id] = ProtocolParser.thread(raw)
                        hydrated += id
                        Unit
                    }
                }
                return CompletableFuture.allOf(*pending.toTypedArray()).thenCompose { batch(end) }
            }
            return batch(0)
        }
        fun completeClosure(): CompletableFuture<ThreadDeletionPlan> = hydrateMissing().thenCompose {
            val affected = ThreadDeletionGraph.affectedIds(threadId, threads)
            val unchecked = affected.firstOrNull { it !in inspectedAttached }
            if (unchecked == null) CompletableFuture.completedFuture(
                ThreadDeletionPlan(threadId, ThreadDeletionGraph.ordered(threadId, threads), token))
            else {
                inspectedAttached += unchecked
                list(false, unchecked).thenCompose { list(true, unchecked) }.thenCompose { completeClosure() }
            }
        }
        return list(false, null).thenCompose { list(true, null) }.thenCompose {
            if (threadId !in threads) threads[threadId] = ThreadSummary(threadId, "", "", "", 0, "notLoaded", null, null, null,
                jsonObject("id" to threadId))
            completeClosure()
        }.handle { result, error ->
            if (error != null) throw CompletionException(CodexException("无法完整读取关联聊天，未执行删除。${deletionError(unwrap(error))}"))
            result
        }
    }

    override fun forkThread(threadId: String): CompletableFuture<JSONObject> = task {
        request("thread/fork", jsonObject("threadId" to threadId)).thenApply { result ->
            ++selection; hydrate(result); refreshThreads(); result
        }
    }

    override fun approvalDecisions(request: PendingRequest): List<Any> {
        return approvalDecisionsFor(request.method, request.params)
    }

    override fun approve(requestKey: String, decision: Any): CompletableFuture<Unit> = task {
        val pending = requirePending(requestKey)
        if (!isApprovalDecisionAllowed(pending.method, pending.params, decision))
            return@task failed(CodexException("此审批不允许该决定"))
        val result = if (pending.kind == RequestKind.PERMISSIONS_APPROVAL) {
            jsonObject("permissions" to (if (decision == "accept" || decision == "acceptForSession")
                pending.params.optJSONObject("permissions") ?: JSONObject() else JSONObject()),
                "scope" to if (decision == "acceptForSession") "session" else "turn")
        } else jsonObject("decision" to decision)
        answer(requestKey, result)
    }

    override fun answerQuestions(requestKey: String, answers: Map<String, String>): CompletableFuture<Unit> = task {
        val pending = requirePending(requestKey)
        if (pending.kind != RequestKind.USER_INPUT) return@task failed(CodexException("请求类型已改变"))
        val result = JSONObject()
        for (question in pending.params.optJSONArray("questions")?.objects().orEmpty()) {
            val id = question.optString("id")
            val answer = answers[id]?.trim().orEmpty()
            if (answer.isBlank()) return@task failed(CodexException("请回答所有问题"))
            val options = question.optJSONArray("options")?.objects()?.map { it.optString("label") }.orEmpty()
            if (options.isNotEmpty() && !question.optBoolean("isOther") && answer !in options)
                return@task failed(CodexException("请从提供的选项中选择"))
            result.put(id, jsonObject("answers" to jsonArray(listOf(answer))))
        }
        answer(requestKey, jsonObject("answers" to result))
    }

    override fun answerElicitation(requestKey: String, action: String, content: JSONObject?): CompletableFuture<Unit> = task {
        val pending = requirePending(requestKey)
        if (pending.kind != RequestKind.ELICITATION || action !in listOf("accept", "decline", "cancel"))
            return@task failed(CodexException("无效的表单回复"))
        if (action == "accept") {
            val mode = pending.params.optString("mode")
            if (mode == "openai/userVerification") return@task failed(CodexException("此客户端尚不支持设备身份验证"))
            if (mode != "url") validateForm(pending.params.optJSONObject("requestedSchema"), content)
        }
        answer(requestKey, jsonObject("action" to action).putIfNotNull("content", content))
    }

    override fun answer(requestKey: String, result: JSONObject): CompletableFuture<Unit> = task {
        val pending = requirePending(requestKey)
        when (pending.kind) {
            RequestKind.COMMAND_APPROVAL, RequestKind.FILE_APPROVAL ->
                if (!isApprovalDecisionAllowed(pending.method, pending.params, result.opt("decision")))
                    return@task failed(CodexException("此审批不允许该决定"))
            RequestKind.PERMISSIONS_APPROVAL -> validatePermissionReply(pending.params, result)
            else -> Unit
        }
        val token = epoch
        publish(state.copy(pendingRequests = state.pendingRequests.map { if (it.key == requestKey) it.copy(submitting = true) else it }))
        mainFuture(transport.response(pending.rpcId, result), token).whenComplete { _, error ->
            if (token == epoch) publish(state.copy(pendingRequests = if (error == null) state.pendingRequests.filterNot { it.key == requestKey }
                else state.pendingRequests.map { if (it.key == requestKey) it.copy(submitting = false) else it },
                lastError = error?.let(::safeError) ?: state.lastError))
        }
    }

    private fun requirePending(key: String): PendingRequest {
        if (!state.connected || !key.startsWith("${transport.generation}:")) throw CodexException("此请求已失效，请等待服务器刷新")
        val pending = state.pendingRequests.find { it.key == key } ?: throw CodexException("此请求已处理或已过期")
        if (pending.submitting) throw CodexException("回复正在发送")
        return pending
    }

    override fun replyAsync(answers: Map<String, String>): CompletableFuture<JSONObject> = task {
        val questions = state.asyncQuestions.filter { !answers[it.key].isNullOrBlank() }
        if (questions.isEmpty()) return@task failed(CodexException("没有可发送的答案"))
        val turnId = questions.first().turnId
        if (questions.any { it.turnId != turnId } || state.activeTurnId != turnId) return@task failed(CodexException("关联任务已变化，请刷新"))
        val payload = jsonArray(questions.map { q -> jsonObject("questionItemId" to q.key, "question" to q.title, "answer" to answers[q.key]!!.trim()) })
        steerInput(state.selectedThread!!.id, turnId, listOf(UserInputs.text(
            "<send_user_message_question_reply>\n$payload\n</send_user_message_question_reply>"))).thenApply { result ->
                hiddenAsync.addAll(questions.map { it.key }); publish(state); result
            }
    }

    override fun dismissAsync(questionKey: String) = onMain { hiddenAsync += questionKey; publish(state) }

    override fun implementPlan(planItemId: String, options: TurnOptions): CompletableFuture<JSONObject> = task {
        validatePlan(planItemId)
        sendMessage("Implement the proposed plan.", options = TurnOptionPayload.forPlan(options, "default"))
    }
    override fun revisePlan(planItemId: String, feedback: String, options: TurnOptions): CompletableFuture<JSONObject> = task {
        validatePlan(planItemId)
        if (feedback.isBlank()) return@task failed(CodexException("请输入对计划的修改意见"))
        sendMessage(feedback, options = TurnOptionPayload.forPlan(options, "plan"))
    }
    private fun validatePlan(id: String) {
        if (state.items.lastOrNull { it.type == "plan" }?.id != id) throw CodexException("该计划已失效")
        if (state.activeTurnId != null) throw CodexException("请等待当前计划生成完成")
    }

    override fun uploadAttachment(name: String, input: InputStream, size: Long): CompletableFuture<String> = task {
        mainFuture(transport.upload(name, input, size), epoch)
    }
    override fun listDirectory(absolutePath: String): CompletableFuture<List<RemoteEntry>> = task {
        mainFuture(transport.listDirectory(absolutePath), epoch)
    }
    override fun readFile(absolutePath: String, maxBytes: Int): CompletableFuture<ByteArray> = task {
        mainFuture(transport.readFile(absolutePath, maxBytes), epoch)
    }

    /** Progress runs on the transport worker; the returned future completes on the main thread. */
    fun downloadFile(
        absolutePath: String,
        destination: File,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): CompletableFuture<Long> = task {
        if (!destination.isAbsolute || destination.exists() && (!destination.isFile || destination.length() != 0L))
            return@task failed(CodexException("无法创建临时下载文件"))
        mainFuture(transport.downloadFile(absolutePath, destination, cancelled, onProgress), epoch)
            .thenApply { size ->
                if (cancelled()) throw CodexException("文件读取已取消")
                size
            }.whenComplete { _, error ->
                // The repository may invalidate a completed transport download before main-thread delivery.
                if (error != null) runCatching { destination.delete() }
            }
    }

    private fun rpc(method: String, params: JSONObject, token: Long = epoch, reportError: Boolean = true): CompletableFuture<JSONObject> =
        mainFuture(transport.request(method, params, if (method == "initialize") 15_000 else 60_000), token).whenComplete { _, error ->
            if (error != null && token == epoch && reportError) publish(state.copy(lastError = safeError(error)))
        }

    private fun publish(value: RepositoryState) {
        check(Looper.myLooper() == Looper.getMainLooper())
        state = value.copy(asyncQuestions = ProtocolReducer.asyncQuestions(value, hiddenAsync))
        listeners.forEach { listener -> runCatching { listener(state) } }
    }

    private fun applyThreadMetadata(method: String, params: JSONObject) {
        val id = params.stringOrNull("threadId")
        if (method != "thread/deleted" && id in state.deletedThreadIds) return
        if (method == "thread/deleted" && id != null) {
            unsubmittedDrafts.remove(id)
            receiptQueries.remove(id)
            if (state.selectedThread?.id == id) ++selection
        }
        if (id != null) threadMetadataRevisions[id] = ++metadataRevision
        if (method in setOf("thread/section/changed", "thread/archived", "thread/unarchived", "thread/deleted")) ++sectionMutationRevision
        publish(ProtocolReducer.reduce(state, method, params))
        if (method in setOf("thread/section/changed", "thread/archived", "thread/deleted")) refreshLoadedSectionOrders()
        if (method == "thread/unarchived") refreshThreads()
    }
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
    private fun <T> task(block: () -> CompletableFuture<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        onMain {
            try { block().whenComplete { value, error ->
                onMain { if (error == null) future.complete(value) else future.completeExceptionally(unwrap(error)) }
            } } catch (error: Exception) {
                publish(state.copy(lastError = safeError(error))); future.completeExceptionally(error)
            }
        }
        return future
    }
    private fun <T> mainFuture(source: CompletableFuture<T>, token: Long): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        source.whenComplete { value, error -> onMain {
            when {
                token != epoch || disposed -> result.completeExceptionally(CodexException("连接已切换"))
                error != null -> result.completeExceptionally(unwrap(error))
                !transport.isOpen -> result.completeExceptionally(CodexException("连接已失效，请重新连接"))
                else -> result.complete(value)
            }
        } }
        return result
    }
    override fun close() = onMain { if (!disposed) { disconnect(); disposed = true; transport.close(); listeners.clear() } }

    companion object {
        private val DELETION_SOURCE_KINDS = listOf("cli", "vscode", "exec", "appServer", "subAgent",
            "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown")
        private val THREAD_METADATA_EVENTS = setOf("thread/name/updated", "thread/section/changed", "thread/readState/changed", "thread/archived", "thread/unarchived", "thread/deleted")
        private val NATIVE_THREAD_DELETION_VERSIONS = setOf("0.169.0", "0.170.0")
        internal fun supportsNativeThreadDeletion(initialization: JSONObject): Boolean =
            initialization.optString("userAgent").substringBefore(' ').substringAfterLast('/') in NATIVE_THREAD_DELETION_VERSIONS
        private fun checkedId(id: String) {
            if (id.isBlank() || id.length > 512 || id.any { it.code < 32 || it.code == 127 }) throw CodexException("聊天标识无效")
        }
        private fun checkedName(name: String, kind: String): String {
            val value = name.trim()
            if (value.isEmpty()) throw CodexException("${kind}名称不能为空")
            if (value.length > 500 || value.any { it.code < 32 || it.code == 127 }) throw CodexException("${kind}名称过长或含控制字符")
            return value
        }
        // Full turn pages can include multi-megabyte inline images. Avoid duplicating them in thread.turns.
        private const val HISTORY_PAGE_SIZE = 3
        private fun <T> failed(error: Exception) = CompletableFuture<T>().apply { completeExceptionally(error) }
        private fun unwrap(error: Throwable): Throwable = if (error is CompletionException && error.cause != null) unwrap(error.cause!!) else error
        private fun safeError(error: Throwable): String = (unwrap(error).let { if (it is CodexException) it.safeMessage else "操作失败，请检查连接和输入" })
            .replace(Regex("sk-[A-Za-z0-9_-]{12,}"), "[已隐藏]")
        private fun jsonEqual(a: Any?, b: Any?): Boolean {
            if (a is JSONObject && b is JSONObject) {
                val keys = a.keys().asSequence().toSet()
                return keys == b.keys().asSequence().toSet() && keys.all { jsonEqual(a.opt(it), b.opt(it)) }
            }
            if (a is JSONArray && b is JSONArray) return a.length() == b.length() && (0 until a.length()).all { jsonEqual(a.opt(it), b.opt(it)) }
            return a == b
        }
        private fun validatePermissionReply(params: JSONObject, result: JSONObject) {
            if (result.opt("scope") !in listOf("turn", "session")) throw CodexException("权限回复范围无效")
            val granted = result.optJSONObject("permissions") ?: throw CodexException("权限回复格式无效")
            val requested = params.optJSONObject("permissions") ?: JSONObject()
            // The UI can deny everything or grant requested categories; it cannot invent a wider grant.
            if (granted.keys().asSequence().any { key -> !requested.has(key) || !jsonEqual(granted.opt(key), requested.opt(key)) })
                throw CodexException("权限回复超出了本次请求范围")
        }
        private fun redactConfig(raw: JSONObject): JSONObject {
            fun clean(value: Any?): Any? = when (value) {
                is JSONObject -> JSONObject().apply { value.keys().forEach { key ->
                    put(key, if (key.lowercase().let { it.contains("token") || it.contains("password") || it.contains("secret") || it == "api_key" || it == "http_headers" })
                        "[已隐藏]" else clean(value.opt(key)))
                } }
                is JSONArray -> jsonArray((0 until value.length()).map { clean(value.opt(it)) })
                else -> value
            }
            return clean(raw) as JSONObject
        }
        private fun validateForm(schema: JSONObject?, content: JSONObject?) {
            if (schema == null) throw CodexException("服务器表单缺少结构定义")
            val properties = schema.optJSONObject("properties") ?: throw CodexException("尚不支持该表单结构")
            val answer = content ?: JSONObject()
            schema.optJSONArray("required")?.strings()?.forEach { if (!answer.has(it) || answer.isNull(it)) throw CodexException("请填写所有必填项") }
            answer.keys().forEach { name ->
                val property = properties.optJSONObject(name) ?: throw CodexException("表单包含未知字段")
                val value = answer.opt(name)
                val valid = when (property.optString("type")) {
                    "string" -> value is String
                    "boolean" -> value is Boolean
                    "number" -> value is Number
                    "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                    "array" -> value is JSONArray
                    else -> false
                }
                if (!valid) throw CodexException("表单字段类型不匹配：$name")
                property.optJSONArray("enum")?.let { choices ->
                    if ((0 until choices.length()).none { jsonEqual(choices.opt(it), value) }) throw CodexException("表单选项无效：$name")
                }
            }
        }
    }
}

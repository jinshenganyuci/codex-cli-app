package com.nanci.codexremote.core

import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.CompletableFuture

/** All listeners and public future completions are delivered on Android's main thread. */
interface CoreContract : AutoCloseable {
    val state: RepositoryState
    fun addListener(listener: (RepositoryState) -> Unit): AutoCloseable
    fun connect(profile: ConnectionProfile, credential: SshCredential): CompletableFuture<JSONObject>
    fun disconnect()
    fun clearError()
    /** The result body, not the JSON-RPC envelope. No automatic retry of mutating requests. */
    fun request(method: String, params: JSONObject = JSONObject()): CompletableFuture<JSONObject>
    fun refreshThreads(cursor: String? = null, search: String? = null, archived: Boolean = false): CompletableFuture<JSONObject>
    fun openThread(threadId: String): CompletableFuture<JSONObject>
    fun newThread(cwd: String? = null, model: String? = null): CompletableFuture<JSONObject>
    fun loadEarlierTurns(): CompletableFuture<JSONObject>
    fun sendMessage(text: String, inputs: List<JSONObject> = emptyList(), options: TurnOptions = TurnOptions()): CompletableFuture<JSONObject>
    fun steer(text: String): CompletableFuture<JSONObject>
    fun interrupt(): CompletableFuture<JSONObject>
    fun renameThread(threadId: String, name: String): CompletableFuture<JSONObject>
    fun archiveThread(threadId: String, archived: Boolean = true): CompletableFuture<JSONObject>
    fun forkThread(threadId: String): CompletableFuture<JSONObject>
    /** Decisions must be drawn from approvalDecisions(), preserving JSON object variants. */
    fun approvalDecisions(request: PendingRequest): List<Any>
    fun approve(requestKey: String, decision: Any): CompletableFuture<Unit>
    fun answerQuestions(requestKey: String, answers: Map<String, String>): CompletableFuture<Unit>
    fun answerElicitation(requestKey: String, action: String, content: JSONObject? = null): CompletableFuture<Unit>
    fun answer(requestKey: String, result: JSONObject): CompletableFuture<Unit>
    /** Async questions use turn/steer, not item/tool/requestUserInput responses. */
    fun replyAsync(answers: Map<String, String>): CompletableFuture<JSONObject>
    fun dismissAsync(questionKey: String)
    fun implementPlan(planItemId: String, options: TurnOptions = TurnOptions()): CompletableFuture<JSONObject>
    fun revisePlan(planItemId: String, feedback: String, options: TurnOptions = TurnOptions()): CompletableFuture<JSONObject>
    fun uploadAttachment(name: String, input: InputStream, size: Long): CompletableFuture<String>
    fun listDirectory(absolutePath: String): CompletableFuture<List<RemoteEntry>>
    fun readFile(absolutePath: String, maxBytes: Int = 1024 * 1024): CompletableFuture<ByteArray>
}

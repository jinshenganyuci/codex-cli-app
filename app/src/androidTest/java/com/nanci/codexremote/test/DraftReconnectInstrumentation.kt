package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.core.CodexException
import com.nanci.codexremote.core.CodexRepository
import com.nanci.codexremote.core.ConnectionStatus
import com.nanci.codexremote.core.RepositoryState
import com.nanci.codexremote.core.jsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Reproduces the daemon unloading a test-only draft during a connection interruption. */
class DraftReconnectInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        var stage = "load saved connection"
        val repo = CodexRepository()
        try {
            val store = ConnectionStore(targetContext)
            val connection = store.list().single { it.id == store.activeId }
            fun connect() = requireNotNull(store.loadCredential(connection)).use {
                repo.connect(connection.profile, it).get(65, TimeUnit.SECONDS)
            }

            stage = "create unsent test draft"
            connect()
            val started = repo.newThread(null, null).get(25, TimeUnit.SECONDS)
            val id = started.getJSONObject("thread").getString("id")
            val original = requireNotNull(repo.state.selectedThread)
            val generation = repo.state.connection.generation
            check(original.id == id && repo.state.canSend && repo.state.turns.isEmpty())

            stage = "wait for daemon to unload the draft"
            val thread = jsonObject("threadId" to id)
            check(repo.request("thread/unsubscribe", thread).get(10, TimeUnit.SECONDS)
                .getString("status") == "unsubscribed")
            val deadline = SystemClock.elapsedRealtime() + 85_000
            val notLoaded = "thread not loaded: $id"
            while (true) {
                val error = try {
                    repo.request("thread/turns/list", jsonObject("threadId" to id, "limit" to 1))
                        .get(10, TimeUnit.SECONDS)
                    null
                } catch (exception: ExecutionException) {
                    exception.cause as? CodexException ?: throw exception
                }
                if (error?.code == -32600 && error.safeMessage == notLoaded) break
                if (error != null) check(error.code == -32600 && error.safeMessage ==
                    "thread $id is not materialized yet; thread/turns/list is unavailable before first user message")
                check(SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(1000)
            }
            report.putBoolean("native_missing_draft_reproduced", true)

            stage = "restore draft on reconnect"
            runOnMainSync {
                repo.disconnect()
                repo.clearError()
            }
            check(repo.state.connection.status == ConnectionStatus.DISCONNECTED)
            val restored = CompletableFuture<RepositoryState>()
            repo.addListener { state ->
                if (state.connected && state.connection.generation != generation) {
                    if (state.lastError != null) restored.completeExceptionally(IllegalStateException("Reconnect surfaced a chat error"))
                    if (state.canSend && id in state.draftReplacements) restored.complete(state)
                }
            }.use {
                connect()
                val state = restored.get(25, TimeUnit.SECONDS)
                val selected = requireNotNull(state.selectedThread)
                check(selected.id != id && state.draftReplacements[id] == selected.id)
                check(selected.cwd == original.cwd && selected.model == original.model)
                check(state.turns.isEmpty() && state.selectedThreadReady && state.lastError == null)
            }
            report.putBoolean("replacement_ready_without_error", true)
            report.putString("status", "passed")
        } catch (failure: Throwable) {
            report.putString("status", "failed")
            report.putString("stage", stage)
            report.putString("failure", failure.javaClass.simpleName)
        } finally {
            runOnMainSync { repo.close() }
        }
        finish(if (report.getString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}

package com.nanci.codexremote.test

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nanci.codexremote.RemoteViewModel
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.connection.SavedConnection
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production draft state and SSH app-server repository against a disposable fixture. */
class DraftTransferInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private var viewModels: ViewModelStore? = null
    private var connection: SavedConnection? = null
    private var fixtureFile: File? = null
    private var output: File? = null
    private var stage = "setup"
    private val prefs: SharedPreferences get() = targetContext.getSharedPreferences("ui-state", Context.MODE_PRIVATE)
    private val homeKey get() = "draft:${requireNotNull(connection).identityKey}:new"

    override fun onCreate(arguments: Bundle?) {
        this.arguments = Bundle(arguments ?: Bundle())
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = JSONObject()
        try {
            check(targetContext.packageName.startsWith("com.nanci.codexremote.")) { "Install the isolated QA APK" }
            val directory = requireNotNull(targetContext.getExternalFilesDir(null))
            output = File(directory, "draft-results.json")
            val name = arguments.getString("fixtureFile", "draft-fixture.json")
            check(name.matches(Regex("[A-Za-z0-9_-]+\\.json"))) { "Invalid fixture filename" }
            fixtureFile = File(directory, name)
            val store = ConnectionStore(targetContext)
            check(store.list().isEmpty()) { "The isolated APK needs an empty connection store" }
            connection = installFixture(store, requireNotNull(fixtureFile))
            check(prefs.edit().putString(homeKey, LEGACY_DRAFT).commit())
            var vm = startViewModel()

            stage = "delete_legacy_home_draft"
            checkHome(vm, LEGACY_DRAFT)
            focusHomeDraft(vm)
            onMain {
                check(vm.draft == LEGACY_DRAFT) { "New chat did not carry the existing Home text" }
                vm.changeDraft("")
                vm.showHome()
            }
            checkHome(vm, "")
            report.put("legacy_text_deleted_without_resurrection", true)

            stage = "retain_edited_unsent_draft"
            focusHomeDraft(vm)
            onMain {
                vm.changeDraft(REPLACEMENT_DRAFT)
                vm.showHome()
            }
            checkHome(vm, REPLACEMENT_DRAFT)
            vm = startViewModel()
            checkHome(vm, REPLACEMENT_DRAFT)
            report.put("replacement_survives_back_and_viewmodel_restart", true)

            stage = "accepted_focused_send"
            focusHomeDraft(vm)
            onMain {
                check(vm.draft == REPLACEMENT_DRAFT) { "Unsent replacement did not follow the next focus" }
                vm.send()
            }
            verifyAcceptedSendClearsHome(vm, REPLACEMENT_DRAFT)
            report.put("focused_send_clears_home_after_acceptance", true)

            stage = "accepted_direct_home_send"
            val directDraft = "Draft fixture: send directly from Home"
            onMain {
                vm.changeDraft(directDraft)
                vm.send()
            }
            verifyAcceptedSendClearsHome(vm, directDraft)
            report.put("direct_send_clears_home_after_acceptance", true)

            stage = "failed_creation"
            val retained = "Draft fixture: preserve on failed creation"
            onMain {
                vm.changeDraft(retained)
                vm.updateWorkspace("$WORKSPACE/fail")
                vm.newChat(focus = true)
            }
            await("fixture rejection") { !vm.creating && vm.message?.contains("Draft fixture rejected creation") == true }
            onMain { vm.showHome() }
            checkHome(vm, retained)
            report.put("failed_creation_preserves_unsent_text", true)

            stage = "back_during_creation"
            val editedWhileWaiting = "Draft fixture: updated while creating"
            val newer = "Draft fixture: newest Home edit"
            val barrier = onMain {
                vm.updateWorkspace("$WORKSPACE/slow")
                vm.newChat(focus = true)
                check(vm.creating && vm.editorDraftReady) { "Fixture did not delay creation" }
                vm.changeDraft(editedWhileWaiting)
                vm.showHome()
                check(vm.draft == editedWhileWaiting) { "Back discarded an edit made during creation" }
                vm.changeDraft(newer)
                // The fixture serves requests in order, so this reply follows the delayed start.
                vm.repo.request("thread/list", JSONObject())
            }
            barrier.get(12, TimeUnit.SECONDS)
            checkHome(vm, newer)
            report.put("late_creation_preserves_latest_home_edit", true)
            report.put("status", "passed")
        } catch (error: Exception) {
            val description = if (error is JSONException) "Invalid fixture JSON" else error.message ?: error.javaClass.simpleName
            report.put("status", "failed").put("error", description)
        } finally {
            runCatching { onMain { viewModels?.clear(); viewModels = null } }
            connection?.let { saved -> runCatching { ConnectionStore(targetContext).remove(saved.id) } }
            fixtureFile?.delete()
        }
        report.put("stage", stage)
        output?.writeText(report.toString(2))
        finish(if (report.optString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("report", report.toString())
                putString("output", output?.absolutePath.orEmpty())
            })
    }

    private fun installFixture(store: ConnectionStore, file: File): SavedConnection {
        check(file.isFile && file.length() in 1..32768) { "Fixture configuration file is missing or oversized" }
        val fixture = JSONObject(file.readText())
        val privateKey = fixture.optString("privateKey").takeIf { it.isNotBlank() }?.encodeToByteArray()
        val password = fixture.optString("password").takeIf { it.isNotBlank() }?.toCharArray()
        return SshCredential(privateKey = privateKey, password = password).use { credentials ->
            val saved = store.save(ConnectionProfile(
                host = fixture.getString("host"), port = fixture.getInt("port"), user = fixture.getString("username"),
                fingerprint = fixture.getString("fingerprint"), name = "Draft regression fixture",
                hostKeyAlgorithm = fixture.getString("hostKeyAlgorithm"), codexPath = fixture.getString("codexPath"),
            ), credentials)
            store.setWorkspace(saved, WORKSPACE)
            saved
        }
    }

    private fun startViewModel(): RemoteViewModel {
        val vm = onMain {
            viewModels?.clear()
            val owner = ViewModelStore().also { viewModels = it }
            val app = targetContext.applicationContext as Application
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            ViewModelProvider(owner, factory)[RemoteViewModel::class.java].also { it.connect() }
        }
        try {
            await("fixture connected and composer options loaded") {
                vm.state.connected && vm.state.configRequirements != null && vm.home && vm.editorDraftReady
            }
        } catch (failure: IllegalStateException) {
            val details = onMain {
                "${vm.state.connection.status}; configured=${vm.state.configRequirements != null}; " +
                    "editor=${vm.editorDraftReady}; ${vm.message ?: vm.state.connection.message}"
            }
            throw IllegalStateException("Fixture connection did not become ready: $details", failure)
        }
        return vm
    }

    private fun focusHomeDraft(vm: RemoteViewModel) {
        onMain { vm.newChat(focus = true) }
        await("new chat ready") { !vm.home && !vm.creating && vm.editorDraftReady && vm.state.canSend }
    }

    private fun verifyAcceptedSendClearsHome(vm: RemoteViewModel, text: String) {
        await("first message accepted") {
            !vm.creating && !vm.state.sending && vm.draft.isEmpty() &&
                vm.state.items.any { it.type == "userMessage" && it.text == text }
        }
        onMain { vm.showHome() }
        checkHome(vm, "")
    }

    private fun checkHome(vm: RemoteViewModel, expected: String) {
        onMain {
            check(vm.home && vm.editorDraftReady && vm.draft == expected) { "Unexpected Home draft after $stage" }
            check(prefs.getString(homeKey, "").orEmpty() == expected) { "Unexpected persisted Home draft after $stage" }
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(predicate)) return
            SystemClock.sleep(40)
        }
        error("Timed out waiting for $label")
    }

    private fun <T> onMain(run: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(run)) }
        return requireNotNull(result.get()).getOrThrow()
    }

    private companion object {
        const val WORKSPACE = "/draft-fixture"
        const val LEGACY_DRAFT = "这是升级前保留的草稿。"
        const val REPLACEMENT_DRAFT = "Draft fixture: replacement remains unsent"
    }
}

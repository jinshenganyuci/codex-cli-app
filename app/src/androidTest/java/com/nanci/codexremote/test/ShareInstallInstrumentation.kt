package com.nanci.codexremote.test

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import com.nanci.codexremote.RemoteViewModel
import com.nanci.codexremote.connection.BootstrapException
import com.nanci.codexremote.connection.BootstrapKeys
import com.nanci.codexremote.connection.BootstrapPhase
import com.nanci.codexremote.connection.BootstrapSpec
import com.nanci.codexremote.connection.BootstrapTransport
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.connection.SavedConnection
import com.nanci.codexremote.update.UpdateRepository
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device-only verification. Run with am instrument --user <temporary non-owner user>.
 * No Activity is exported, and this class is packaged only in the separate androidTest APK.
 */
class ShareInstallInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private var stage = "setup"
    private val report = Bundle()
    private val models = ViewModelStore()
    private var model: RemoteViewModel? = null

    override fun onCreate(arguments: Bundle?) {
        this.arguments = Bundle(arguments ?: Bundle())
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val mode = arguments.getString("mode", "activate")
        report.putString("mode", mode)
        var result = Activity.RESULT_CANCELED
        try {
            // Refuse accidental execution against the owner's established app data.
            requireCheck(Process.myUid() / 100_000 != 0, "temporary_android_user_required")
            requireCheck(targetContext.packageName == "com.nanci.codexremote", "unexpected_target_package")
            when (mode) {
                "activate" -> activate()
                "upgrade" -> upgrade()
                else -> throw CheckFailure("unsupported_mode")
            }
            report.putString("status", "passed")
            result = Activity.RESULT_OK
        } catch (failure: CheckFailure) {
            report.putString("status", "failed")
            report.putString("failure", failure.code)
        } catch (_: Exception) {
            // Deliberately omit exception text and stack traces, which can contain SSH input.
            report.putString("status", "failed")
            report.putString("failure", "operation_failed")
        } finally {
            report.putString("stage", stage)
            try { clearModel() } catch (_: Exception) {
                report.putString("status", "failed")
                report.putString("failure", "model_cleanup_failed")
                result = Activity.RESULT_CANCELED
            }
            report.putString("stream", "\nShare install $mode: ${report.getString("status")} (${report.getString("stage")})\n")
            finish(result, report)
        }
    }

    private fun activate() {
        stage = "fresh_install_precondition"
        val store = ConnectionStore(targetContext)
        requireCheck(store.list().isEmpty() && store.activeId == null, "connection_store_not_empty")
        requireCheck(hasBootstrapAsset(), "bootstrap_asset_missing")
        requireCheck(!verificationPrefs().contains("connectionId"), "activation_already_recorded")

        stage = "automatic_activation"
        startModel()
        waitUntil(100_000, "activation_timeout") {
            onMain {
                val vm = requireNotNull(model)
                if (vm.bootstrap.state.phase == BootstrapPhase.Failed)
                    throw CheckFailure(bootstrapFailure(vm.bootstrap.state.message))
                vm.activeConnection != null && vm.state.connected
            }
        }
        val saved = soleConnection(store)
        requireCheck(!store.canBootstrap(), "activation_completion_not_persisted")
        requireCheck(onMain { model!!.activeConnection?.id == saved.id }, "active_connection_not_refreshed")
        refreshChatList()

        stage = "same_key_retry"
        readSpec().use { spec ->
            store.loadPendingBootstrap(spec.inviteId).use { pending ->
                requireCheck(pending == null, "pending_key_not_removed")
            }
            val publicKey = (store.loadCredential(saved.id) ?: throw CheckFailure("credential_missing")).use {
                BootstrapKeys.publicKey(it)
            }
            BootstrapTransport().use { it.enroll(spec, publicKey) }
            report.putBoolean("same_key_retry", true)

            stage = "other_key_rejected"
            var rejected = false
            BootstrapKeys.generate().use { other ->
                try {
                    BootstrapTransport().use { it.enroll(spec, BootstrapKeys.publicKey(other)) }
                } catch (failure: BootstrapException) {
                    rejected = failure.safeMessage == "这份安装包已在另一台设备激活，请获取新的安装包。"
                }
            }
            requireCheck(rejected, "other_key_not_rejected_as_claimed")
            report.putBoolean("other_key_rejected", true)
        }

        stage = "persist_upgrade_baseline"
        val credentialHash = credentialHash(store, saved.id)
        val installed = installedVersion()
        requireCheck(verificationPrefs().edit()
            .putString("connectionId", saved.id)
            .putString("credentialSha256", credentialHash)
            .putString("profileSha256", profileHash(saved))
            .putString("workspace", store.workspace)
            .putLong("installedVersionCode", installed)
            .commit(), "verification_baseline_write_failed")
        report.putLong("installed_version_code", installed)
        report.putBoolean("automatic_activation", true)

        // This check uses its own SSH session after the App Server client is closed.
        clearModel()
        checkUpdate(store, saved)
        stage = "activation_complete"
    }

    private fun upgrade() {
        stage = "upgrade_precondition"
        requireCheck(!hasBootstrapAsset(), "ordinary_apk_contains_bootstrap")
        val baseline = verificationPrefs()
        requireCheck(baseline.contains("connectionId"), "upgrade_baseline_missing")
        val store = ConnectionStore(targetContext)
        val saved = soleConnection(store)
        requireCheck(saved.id == baseline.getString("connectionId", null), "connection_id_changed")
        requireCheck(credentialHash(store, saved.id) == baseline.getString("credentialSha256", null), "credential_changed")
        requireCheck(profileHash(saved) == baseline.getString("profileSha256", null), "connection_profile_changed")
        requireCheck(store.workspace == baseline.getString("workspace", null), "workspace_changed")
        val installed = installedVersion()
        requireCheck(installed > baseline.getLong("installedVersionCode", Long.MAX_VALUE), "version_did_not_increase")
        requireCheck(!store.canBootstrap(), "upgrade_lost_activation_completion")
        report.putBoolean("connection_preserved", true)
        report.putBoolean("credential_preserved", true)
        report.putBoolean("bootstrap_asset_absent", true)
        report.putLong("installed_version_code", installed)

        // Independent updater must work before any App Server connection is opened.
        checkUpdate(store, saved)
        stage = "upgraded_appserver_connection"
        startModel()
        waitUntil(90_000, "upgraded_connection_timeout") { onMain { model!!.state.connected } }
        requireCheck(onMain { model!!.activeConnection?.id == saved.id }, "upgraded_active_connection_changed")
        refreshChatList()
        requireCheck(soleConnection(store).id == saved.id, "connection_replaced_after_launch")
        requireCheck(credentialHash(store, saved.id) == baseline.getString("credentialSha256", null), "credential_replaced_after_launch")
        stage = "upgrade_complete"
    }

    private fun startModel() {
        onMain {
            val vm = RemoteViewModel(targetContext.applicationContext as Application)
            model = vm
            models.put("share-install-verification", vm)
            vm.foreground()
        }
    }

    private fun clearModel() {
        onMain {
            model?.background()
            models.clear()
            model = null
        }
    }

    private fun refreshChatList() {
        stage = "read_chat_list"
        val request = onMain { model!!.repo.refreshThreads(null, null, false) }
        request.get(60, TimeUnit.SECONDS)
        waitUntil(10_000, "chat_list_state_timeout") { onMain { !model!!.state.loadingThreads } }
        val count = onMain { model!!.state.threads.size }
        report.putInt("chat_count_in_first_page", count)
        report.putBoolean("appserver_connected", true)
        report.putBoolean("chat_list_read", true)
    }

    private fun checkUpdate(store: ConnectionStore, saved: SavedConnection) {
        stage = "independent_update_index"
        UpdateRepository(targetContext).use { updates ->
            val check = (store.loadCredential(saved.id) ?: throw CheckFailure("update_credential_missing")).use {
                updates.check(saved.profile, it)
            }.get(60, TimeUnit.SECONDS)
            report.putBoolean("independent_update_index_read", true)
            report.putInt("update_version_code", check.latest.versionCode)
            report.putBoolean("update_available", check.available)
            if (arguments.getString("downloadUpdate", "false") == "true") {
                requireCheck(check.available, "requested_update_not_available")
                stage = "download_and_verify_update"
                val apk = (store.loadCredential(saved.id) ?: throw CheckFailure("update_credential_missing")).use {
                    updates.download(check.latest, it) {}
                }.get(5, TimeUnit.MINUTES)
                requireCheck(apk.file.isFile && apk.file.length() == check.latest.sizeBytes, "verified_update_missing")
                report.putBoolean("update_download_verified", true)
                // A validated download is only a test artifact; system install is a separate step.
                requireCheck(apk.file.delete(), "update_test_file_cleanup_failed")
            }
        }
    }

    private fun soleConnection(store: ConnectionStore): SavedConnection {
        val rows = store.list()
        requireCheck(rows.size == 1 && rows.single().hasCredential, "expected_single_saved_connection")
        val saved = rows.single()
        requireCheck(store.activeId == saved.id, "saved_connection_not_active")
        return saved
    }

    private fun readSpec(): BootstrapSpec {
        val bytes = targetContext.assets.open(BootstrapSpec.ASSET_NAME).use { it.readBytes() }
        return try { BootstrapSpec.parse(bytes) } finally { bytes.fill(0) }
    }

    private fun hasBootstrapAsset(): Boolean =
        targetContext.assets.list("")?.contains(BootstrapSpec.ASSET_NAME) == true

    private fun credentialHash(store: ConnectionStore, id: String): String =
        (store.loadCredential(id) ?: throw CheckFailure("stored_credential_missing")).use { secret ->
            requireCheck(secret.privateKey != null && secret.password == null, "unexpected_credential_type")
            hash(requireNotNull(secret.privateKey))
        }

    private fun profileHash(saved: SavedConnection): String = hash(listOf(
        saved.profile.host, saved.profile.port.toString(), saved.profile.user, saved.profile.fingerprint,
        saved.profile.name, saved.profile.hostKeyAlgorithm, saved.profile.codexPath,
    ).joinToString("\u0000").toByteArray(Charsets.UTF_8))

    private fun verificationPrefs() = targetContext.getSharedPreferences("share-install-verification", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long {
        val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun waitUntil(timeout: Long, failure: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= until) throw CheckFailure(failure)
            SystemClock.sleep(200)
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        runOnMainSync { value.set(runCatching(action)) }
        return value.get().getOrThrow()
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun requireCheck(value: Boolean, code: String) { if (!value) throw CheckFailure(code) }
    private class CheckFailure(val code: String) : Exception()

    private fun bootstrapFailure(message: String?): String = when (message) {
        "这份安装包已在另一台设备激活，请获取新的安装包。" -> "bootstrap_already_claimed"
        "这份安装包的激活期限已过，请获取新的安装包。" -> "bootstrap_expired"
        "这份安装包的连接授权已撤回，请联系分享者。" -> "bootstrap_revoked"
        "服务器身份校验未通过，已停止连接。请联系分享者。" -> "bootstrap_host_identity_mismatch"
        else -> "bootstrap_failed"
    }
}

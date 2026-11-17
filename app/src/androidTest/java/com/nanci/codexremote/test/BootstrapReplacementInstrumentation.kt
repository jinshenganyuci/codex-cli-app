package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.nanci.codexremote.connection.BootstrapKeys
import com.nanci.codexremote.connection.ConnectionStorageException
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import java.security.MessageDigest

/** Local state migration only: isolated app ID, real Android Keystore, no enrollment network calls. */
class BootstrapReplacementInstrumentation : Instrumentation() {
    private val report = Bundle()
    private var stage = "setup"
    private var safeToClean = false
    private val prefs get() = targetContext.getSharedPreferences("connections-v2", 0)
    private val profile = ConnectionProfile(host = "example.invalid", user = "codex",
        fingerprint = "SHA256:" + "A".repeat(43))

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var result = Activity.RESULT_CANCELED
        try {
            check(targetContext.packageName == "com.nanci.codexremote.bootstrapqa")
            check(prefs.all.isEmpty())
            check(targetContext.assets.list("")?.contains("codex-bootstrap.json") != true)
            safeToClean = true
            BootstrapKeys.generate().use { oldKey ->
                BootstrapKeys.generate().use { newKey ->
                    stage = "legacy_failed_invitation_replacement"
                    var store = fresh()
                    val oldPending = store.savePendingBootstrap(OLD, oldKey)!!
                    val oldId = oldPending.connectionId
                    oldPending.close()
                    check(!prefs.contains("bootstrap:invite")) // Reproduce the older APK's stored state.
                    check(store.prepareBootstrap(NEW))
                    check(store.loadPendingBootstrap(NEW) == null)
                    store.savePendingBootstrap(NEW, newKey)!!.use {
                        check(it.connectionId != oldId && digest(it.credential) == digest(newKey))
                    }
                    val saved = store.finishBootstrap(NEW, profile, "/home/codex")!!
                    check(store.list().single().id == saved.id)
                    check(store.loadCredential(saved.id)!!.use { digest(it) } == digest(newKey))
                    report.putBoolean(stage, true)

                    stage = "same_invitation_keeps_device_key"
                    store = fresh()
                    val original = store.savePendingBootstrap(OLD, oldKey)!!.use { it.connectionId }
                    repeat(2) {
                        check(store.prepareBootstrap(OLD))
                        store.loadPendingBootstrap(OLD)!!.use {
                            check(it.connectionId == original && digest(it.credential) == digest(oldKey))
                        }
                    }
                    report.putBoolean(stage, true)

                    stage = "legacy_cancelled_invitation_replacement"
                    store = fresh()
                    store.savePendingBootstrap(OLD, oldKey)!!.close()
                    check(!prefs.contains("bootstrap:invite"))
                    store.cancelBootstrap()
                    check(!store.prepareBootstrap(OLD))
                    store.loadPendingBootstrap(OLD)!!.use { check(digest(it.credential) == digest(oldKey)) }
                    check(store.prepareBootstrap(NEW))
                    check(store.loadPendingBootstrap(NEW) == null)
                    store.savePendingBootstrap(NEW, newKey)!!.close()
                    check(store.finishBootstrap(NEW, profile, "/home/codex") != null)
                    report.putBoolean(stage, true)

                    stage = "early_cancellation_is_invitation_specific"
                    store = fresh()
                    check(store.prepareBootstrap(OLD))
                    store.cancelBootstrap()
                    check(!store.prepareBootstrap(OLD))
                    check(store.prepareBootstrap(NEW))
                    report.putBoolean(stage, true)

                    stage = "existing_connection_preserved"
                    store = fresh()
                    val existing = store.save(profile, oldKey)
                    store.workspace = "/home/codex/original"
                    check(!store.prepareBootstrap(NEW))
                    check(store.list().single() == existing && store.activeId == existing.id)
                    check(store.workspace == "/home/codex/original")
                    check(store.loadCredential(existing.id)!!.use { digest(it) } == digest(oldKey))
                    store.remove(existing.id)
                    check(!store.prepareBootstrap(NEW))
                    report.putBoolean(stage, true)

                    stage = "completed_enrollment_never_reset"
                    store = fresh()
                    store.savePendingBootstrap(OLD, oldKey)!!.close()
                    val completed = store.finishBootstrap(OLD, profile, "/home/codex")!!
                    check(!store.prepareBootstrap(NEW))
                    check(store.list().single().id == completed.id)
                    check(store.loadCredential(completed.id)!!.use { digest(it) } == digest(oldKey))
                    store.remove(completed.id)
                    check(!store.prepareBootstrap(NEW))
                    check(prefs.getString("bootstrap:state", null) == "completed")
                    report.putBoolean(stage, true)

                    stage = "unknown_legacy_cancellation_preserved"
                    store = fresh()
                    check(prefs.edit().putString("bootstrap:state", "cancelled").commit())
                    check(!store.prepareBootstrap(NEW))
                    report.putBoolean(stage, true)

                    stage = "malformed_pending_preserved"
                    store = fresh()
                    check(prefs.edit().putString("bootstrap:state", "pending")
                        .putString("bootstrap:pending", "invalid-json").commit())
                    var rejected = false
                    try { store.prepareBootstrap(NEW) } catch (_: ConnectionStorageException) { rejected = true }
                    check(rejected && prefs.getString("bootstrap:pending", null) == "invalid-json")
                    report.putBoolean(stage, true)
                }
            }
            report.putString("status", "passed")
            result = Activity.RESULT_OK
        } catch (failure: Throwable) {
            report.putString("status", "failed")
            report.putString("error_type", failure.javaClass.simpleName)
        } finally {
            if (safeToClean && !prefs.edit().clear().commit()) {
                report.putString("status", "failed")
                result = Activity.RESULT_CANCELED
            }
            report.putString("stage", stage)
            report.putString("stream", "\nBootstrap replacement: ${report.getString("status")} ($stage)\n")
            finish(result, report)
        }
    }

    private fun fresh(): ConnectionStore {
        check(prefs.edit().clear().commit())
        return ConnectionStore(targetContext)
    }
    private fun digest(credential: SshCredential): String = MessageDigest.getInstance("SHA-256")
        .digest(requireNotNull(credential.privateKey)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val OLD = "qa-old-invitation-not-a-real-grant"
        private const val NEW = "_qa-new-invitation-not-a-real-grant"
    }
}

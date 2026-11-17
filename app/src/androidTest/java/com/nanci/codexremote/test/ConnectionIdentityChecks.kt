package com.nanci.codexremote.test

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.connection.ModelPreferences
import com.nanci.codexremote.connection.ModelSelection
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

/** Called by the audit runner. Uses uniquely named local preferences, no connection or model RPC. */
object ConnectionIdentityChecks {
    fun run(context: Context): List<String> {
        val namespace = "identity-check-${UUID.randomUUID()}:"
        val names = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += namespace + name
                return context.getSharedPreferences(namespace + name, mode)
            }
        }
        val prefs = isolated.getSharedPreferences("connections-v2", Context.MODE_PRIVATE)
        val modelPrefs = isolated.getSharedPreferences("model-defaults-v1", Context.MODE_PRIVATE)
        val passed = mutableListOf<String>()
        val profile = ConnectionProfile("example.invalid", fingerprint = "SHA256:" + "A".repeat(43))
        fun test(name: String, block: () -> Unit) { block(); passed += name }
        fun reset(): ConnectionStore {
            check(prefs.edit().clear().commit() && modelPrefs.edit().clear().commit())
            return ConnectionStore(isolated)
        }
        try {
            SshCredential(password = "fixture-one-not-a-real-password".toCharArray()).use { firstSecret ->
                SshCredential(password = "fixture-two-not-a-real-password".toCharArray()).use { secondSecret ->
                    test("new_identity_persists_and_rename_preserves_scoped_state") {
                        val store = reset()
                        val saved = store.save(profile, firstSecret)
                        check(saved.credentialRevision.isNotEmpty())
                        check(ConnectionStore(isolated).list().single() == saved)
                        store.setWorkspace(saved, "/workspace/one")
                        ModelPreferences(isolated).save(saved, ModelSelection("model-one", "high"))
                        val renamed = store.save(profile.copy(name = "Renamed"), id = saved.id)
                        check(renamed.ownerKey == saved.ownerKey && renamed.credentialRevision == saved.credentialRevision)
                        check(store.workspaceFor(renamed) == "/workspace/one")
                        check(ModelPreferences(isolated).read(renamed) == ModelSelection("model-one", "high"))
                    }
                    test("credential_replacement_separates_workspace_and_model_preferences") {
                        val store = reset()
                        val saved = store.save(profile, firstSecret)
                        store.setWorkspace(saved, "/workspace/old")
                        ModelPreferences(isolated).save(saved, ModelSelection("model-old", "low"))
                        val replaced = store.save(profile, secondSecret, saved.id)
                        check(replaced.id == saved.id && replaced.credentialRevision != saved.credentialRevision)
                        check(replaced.ownerKey != saved.ownerKey && replaced.identityKey != saved.identityKey)
                        check(store.workspaceFor(replaced).isEmpty() && store.workspace.isEmpty())
                        check(ModelPreferences(isolated).read(replaced) == ModelSelection())
                        check(runCatching { store.setWorkspace(saved, "/stale-write") }.isFailure)
                        check(runCatching { store.loadCredential(saved)?.close() }.isFailure)
                        store.loadCredential(replaced)!!.close()
                        check(store.workspaceFor(replaced).isEmpty())
                    }
                    test("profile_owner_change_separates_state_without_replacing_secret") {
                        val store = reset()
                        val saved = store.save(profile, firstSecret)
                        store.setWorkspace(saved, "/workspace/old")
                        val changed = store.save(profile.copy(user = "other-space"), id = saved.id)
                        check(changed.credentialRevision != saved.credentialRevision)
                        check(store.workspaceFor(changed).isEmpty() && changed.hasCredential)
                    }
                    test("legacy_identity_and_model_scope_are_preserved") {
                        val store = reset()
                        val saved = store.save(profile, firstSecret)
                        val rows = JSONArray(prefs.getString("profiles", "[]"))
                        rows.getJSONObject(0).remove("credentialRevision")
                        check(prefs.edit().putString("profiles", rows.toString()).commit())
                        val legacy = store.list().single()
                        check(legacy.credentialRevision.isEmpty())
                        val p = legacy.profile
                        val oldIdentity = listOf(legacy.id, p.host, p.port.toString(), p.user,
                            p.fingerprint.trimEnd('='), p.hostKeyAlgorithm, p.codexPath).joinToString("\u0000")
                        val oldScope = MessageDigest.getInstance("SHA-256").digest(oldIdentity.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        check(legacy.identityKey == oldScope)
                        check(modelPrefs.edit().putString("$oldScope:model", "legacy-model")
                            .putString("$oldScope:effort", "high").putBoolean("$oldScope:initial-choice", true).commit())
                        val models = ModelPreferences(isolated)
                        check(models.read(legacy) == ModelSelection("legacy-model", "high"))
                        check(models.initialChoicePending(legacy))
                        check(store.save(profile.copy(name = "Legacy renamed"), id = saved.id).identityKey == oldScope)
                    }
                    test("forced_model_migration_survives_reads_and_failed_credentials_until_accepted") {
                        val store = reset()
                        val saved = store.save(profile, firstSecret)
                        var models = ModelPreferences(isolated)
                        models.save(saved, ModelSelection("old-model", "low"))
                        models.migrate(listOf(saved))
                        check(models.read(saved) == ModelSelection() && models.initialChoicePending(saved))
                        // Read-only restoration and a failed reconnect cannot consume a pending choice.
                        repeat(2) { check(models.read(saved) == ModelSelection()) }
                        check(runCatching { store.loadCredential(saved.copy(credentialRevision = "stale")) }.isFailure)
                        models = ModelPreferences(isolated)
                        models.migrate(store.list())
                        check(models.initialChoicePending(saved) && models.read(saved) == ModelSelection())
                        models.initialChoiceUsed(saved)
                        check(!ModelPreferences(isolated).initialChoicePending(saved))
                        models.save(saved, ModelSelection("user-model", "high"))
                        models.migrate(store.list())
                        check(models.read(saved) == ModelSelection("user-model", "high") && !models.initialChoicePending(saved))
                    }
                    test("legacy_workspace_moves_only_to_previously_active_connection") {
                        val store = reset()
                        val first = store.save(profile, firstSecret)
                        val second = store.save(profile, secondSecret)
                        store.setActive(first.id)
                        check(prefs.edit().putString("workspace", "/legacy/first").commit())
                        store.setActive(second.id)
                        check(store.workspace.isEmpty() && store.workspaceFor(first) == "/legacy/first")
                        store.setWorkspace(second, "/second")
                        store.setActive(first.id)
                        check(store.workspace == "/legacy/first" && store.workspaceFor(second) == "/second")
                        check(!prefs.contains("workspace"))
                    }
                    test("legacy_workspace_does_not_follow_same_id_credential_replacement") {
                        val store = reset()
                        val first = store.save(profile, firstSecret)
                        check(prefs.edit().putString("workspace", "/legacy/first").commit())
                        val replacement = store.save(profile, secondSecret, first.id)
                        check(store.workspaceFor(first) == "/legacy/first")
                        check(store.workspaceFor(replacement).isEmpty())
                    }
                    test("orphan_legacy_workspace_is_not_assigned_to_new_connection") {
                        val store = reset()
                        check(prefs.edit().putString("workspace", "/unknown-owner").commit())
                        val saved = store.save(profile, firstSecret)
                        check(store.workspaceFor(saved).isEmpty())
                        check(prefs.getString("legacy-workspace-unassigned", null) == "/unknown-owner")
                        check(!prefs.contains("workspace"))
                    }
                    test("bootstrap_commits_workspace_to_new_connection_scope") {
                        val store = reset()
                        val invite = "fixture-invitation-not-a-real-grant"
                        store.savePendingBootstrap(invite, firstSecret)!!.close()
                        val saved = store.finishBootstrap(invite, profile, "/bootstrap/workspace")!!
                        check(saved.credentialRevision.isNotEmpty())
                        check(store.workspaceFor(saved) == "/bootstrap/workspace" && store.workspace == "/bootstrap/workspace")
                        check(!prefs.contains("workspace"))
                    }
                }
            }
            return passed
        } finally {
            names.forEach { name -> check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) }
        }
    }
}

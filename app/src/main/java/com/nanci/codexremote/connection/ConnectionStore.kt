package com.nanci.codexremote.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The revision is a random local identity, never a hash of credential material. */
data class SavedConnection(
    val id: String,
    val profile: ConnectionProfile,
    val hasCredential: Boolean,
    val credentialRevision: String = "",
) {
    /** Preserve the pre-revision identity for unchanged legacy connections. */
    val ownerKey: String get() = buildList {
        add(id); add(profile.host); add(profile.port.toString()); add(profile.user)
        add(profile.fingerprint.trimEnd('=')); add(profile.hostKeyAlgorithm); add(profile.codexPath)
        if (credentialRevision.isNotEmpty()) add(credentialRevision)
    }.joinToString("\u0000")

    val identityKey: String get() = MessageDigest.getInstance("SHA-256")
        .digest(ownerKey.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Pending enrollment is never returned by list() or selected as a usable connection. */
class PendingBootstrap(val inviteId: String, val connectionId: String, val credential: SshCredential) : AutoCloseable {
    override fun close() = credential.close()
}

/** Safe UI text only; never attach a provider exception containing credential data. */
class ConnectionStorageException(message: String) : Exception(message)

/** Metadata is ordinary preferences; SSH credentials are AES-GCM encrypted with a Keystore key. */
class ConnectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("connections-v2", Context.MODE_PRIVATE)
    private val alias = "codex-remote-connection-credentials-v1"

    var activeId: String?
        @Synchronized get() = prefs.getString("active", null)
        @Synchronized private set(value) { commit(prefs.edit().putString("active", value)) }

    var workspace: String
        @Synchronized get() = list().firstOrNull { it.id == activeId }?.let(::workspaceFor) ?: ""
        @Synchronized set(value) {
            val connection = list().firstOrNull { it.id == activeId }
                ?: throw ConnectionStorageException("请先选择服务器连接。")
            setWorkspace(connection, value)
        }

    @Synchronized fun workspaceFor(connection: SavedConnection): String {
        migrateLegacyWorkspace()
        return prefs.getString(workspaceKey(connection), "") ?: ""
    }

    @Synchronized fun setWorkspace(connection: SavedConnection, value: String) {
        migrateLegacyWorkspace()
        if (list().none { it.id == connection.id && it.ownerKey == connection.ownerKey })
            throw ConnectionStorageException("连接已改变，请重新选择工作目录。")
        commit(prefs.edit().putString(workspaceKey(connection), checkedWorkspace(value)))
    }

    @Synchronized fun list(): List<SavedConnection> = guarded("无法读取连接设置；原有数据已保留。") {
        val rows = JSONArray(prefs.getString("profiles", "[]"))
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            SavedConnection(id, ConnectionProfile(
                host = row.getString("host"), port = row.optInt("port", 22),
                user = row.optString("user", "codex"), fingerprint = row.getString("fingerprint"),
                name = row.optString("name", row.getString("host")),
                hostKeyAlgorithm = row.optString("hostKeyAlgorithm", "ecdsa-sha2-nistp256"),
                codexPath = row.optString("codexPath", "~/.local/bin/codex"),
            ), prefs.contains("secret:$id"), row.optString("credentialRevision", ""))
        }
    }

    @Synchronized fun setActive(id: String) {
        migrateLegacyWorkspace()
        if (list().none { it.id == id }) throw ConnectionStorageException("这条连接已不存在。")
        activeId = id
    }

    /** A null credential preserves the saved secret. Save is atomic with profile metadata. */
    @Synchronized fun save(profile: ConnectionProfile, credential: SshCredential? = null, id: String? = null): SavedConnection {
        if (profile.host.isBlank() || profile.port !in 1..65535 || profile.user.isBlank() ||
            !profile.fingerprint.startsWith("SHA256:")) throw ConnectionStorageException("连接信息不完整。")
        val existing = list().toMutableList()
        // Claim the old global directory for its original owner before changing active/id/key.
        migrateLegacyWorkspace()
        val targetId = id ?: UUID.randomUUID().toString()
        if (id != null && existing.none { it.id == id }) throw ConnectionStorageException("这条连接已不存在，请重新添加。")
        val previous = existing.firstOrNull { it.id == targetId }
        val secret = credential?.let { encrypt(targetId, it) }
        val ownerChanged = previous != null && previous.copy(profile = profile).ownerKey != previous.ownerKey
        val revision = if (previous == null || credential != null || ownerChanged) UUID.randomUUID().toString()
            else previous.credentialRevision
        val saved = SavedConnection(targetId, profile, secret != null || prefs.contains("secret:$targetId"), revision)
        val index = existing.indexOfFirst { it.id == targetId }
        if (index >= 0) existing[index] = saved else existing += saved
        val edit = prefs.edit().putString("profiles", serialize(existing)).putString("active", targetId)
        if (secret != null) edit.putString("secret:$targetId", secret)
        commit(edit)
        return saved
    }

    @Synchronized fun loadCredential(id: String): SshCredential? {
        val encoded = prefs.getString("secret:$id", null) ?: return null
        return decrypt(id, encoded)
    }

    /** Reject a credential replaced between an async caller's snapshot and this read. */
    @Synchronized fun loadCredential(connection: SavedConnection): SshCredential? {
        fun current() = list().any { it.id == connection.id && it.ownerKey == connection.ownerKey }
        if (!current()) throw ConnectionStorageException("连接身份已改变，请重新连接。")
        val credential = loadCredential(connection.id)
        if (!current()) {
            credential?.close()
            throw ConnectionStorageException("连接身份已改变，请重新连接。")
        }
        return credential
    }

    private fun decrypt(id: String, encoded: String): SshCredential =
        guarded("无法解密这条连接的凭据。请重新导入密钥或输入密码；原有数据已保留。") {
            val envelope = JSONObject(encoded)
            require(envelope.getInt("v") == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
            require(iv.size == 12)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            cipher.updateAAD(id.toByteArray(StandardCharsets.UTF_8))
            val plain = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP))
            try { decodeCredential(plain) } finally { plain.fill(0) }
        }

    /** Persist a terminal decision so deleting a connection cannot restart enrollment. */
    @Synchronized fun canBootstrap(): Boolean {
        if (prefs.getString("bootstrap:state", null) in setOf("completed", "cancelled", "existing")) return false
        if (list().isNotEmpty()) {
            commitBootstrap(prefs.edit().putString("bootstrap:state", "existing").remove("bootstrap:pending"),
                "bootstrap:state", "bootstrap:pending")
            return false
        }
        return true
    }

    /** A replacement invitation may recover an unfinished first install. Never reset an
     * established connection or a completed enrollment, and retain the key for same-invite retries. */
    @Synchronized fun prepareBootstrap(inviteId: String): Boolean =
        guarded("无法读取首次连接资料；原有数据已保留。") {
            require(inviteId.matches(Regex("[A-Za-z0-9_-]{16,80}")))
            val state = prefs.getString("bootstrap:state", null)
            if (state in setOf("completed", "existing")) return@guarded false
            if (list().isNotEmpty()) {
                canBootstrap()
                return@guarded false
            }
            // Older APKs recorded the invitation only inside pending. Read it before
            // deciding whether a cancellation belongs to this package's invitation.
            val pendingId = prefs.getString("bootstrap:pending", null)?.let {
                JSONObject(it).getString("inviteId")
            }
            val previousId = prefs.getString("bootstrap:invite", null) ?: pendingId
            if (previousId != null && previousId != inviteId) {
                commitBootstrap(prefs.edit().putString("bootstrap:invite", inviteId)
                    .putString("bootstrap:state", "ready").remove("bootstrap:pending").remove("bootstrap:durable"),
                    "bootstrap:invite", "bootstrap:state", "bootstrap:pending", "bootstrap:durable")
            } else {
                // A legacy cancellation with no recorded invitation cannot safely be
                // identified as a replacement. Preserve that explicit cancellation.
                if (state == "cancelled") return@guarded false
                if (previousId != inviteId || !prefs.contains("bootstrap:invite")) {
                    commitBootstrap(prefs.edit().putString("bootstrap:invite", inviteId), "bootstrap:invite")
                }
            }
            canBootstrap()
        }

    @Synchronized fun loadPendingBootstrap(inviteId: String): PendingBootstrap? {
        val encoded = prefs.getString("bootstrap:pending", null) ?: return null
        return guarded("无法读取首次连接资料；原有数据已保留。") {
            val record = JSONObject(encoded)
            require(record.getString("inviteId") == inviteId)
            val id = record.getString("id")
            // SharedPreferences may retain a failed commit in memory. Force a fresh disk
            // generation before this key can be sent to the server, including on retries.
            commitBootstrap(prefs.edit().putString("bootstrap:pending", encoded)
                .putString("bootstrap:durable", UUID.randomUUID().toString()), "bootstrap:pending", "bootstrap:durable")
            PendingBootstrap(inviteId, id, decrypt(id, record.getString("secret")))
        }
    }

    /** Called before any enrollment request; a retry always reuses this exact private key. */
    @Synchronized fun savePendingBootstrap(inviteId: String, credential: SshCredential): PendingBootstrap? {
        if (!canBootstrap()) return null
        loadPendingBootstrap(inviteId)?.let { return it }
        val id = UUID.randomUUID().toString()
        val record = JSONObject().put("inviteId", inviteId).put("id", id).put("secret", encrypt(id, credential))
        commitBootstrap(prefs.edit().putString("bootstrap:state", "pending").putString("bootstrap:pending", record.toString()),
            "bootstrap:state", "bootstrap:pending")
        return loadPendingBootstrap(inviteId)
    }

    /** Metadata, encrypted credential, active selection and completion marker commit together. */
    @Synchronized fun finishBootstrap(inviteId: String, profile: ConnectionProfile, workspace: String): SavedConnection? =
        guarded("无法保存首次连接；请重试，已有资料已保留。") {
            if (!canBootstrap()) return@guarded null
            val record = JSONObject(prefs.getString("bootstrap:pending", null) ?: error("pending missing"))
            require(record.getString("inviteId") == inviteId)
            val id = record.getString("id")
            migrateLegacyWorkspace()
            val saved = SavedConnection(id, profile, true, UUID.randomUUID().toString())
            val workspaceKey = workspaceKey(saved)
            commitBootstrap(prefs.edit().putString("profiles", serialize(listOf(saved))).putString("active", id)
                .putString("secret:$id", record.getString("secret")).putString(workspaceKey, checkedWorkspace(workspace))
                .putString("bootstrap:state", "completed").remove("bootstrap:pending").remove("bootstrap:durable"),
                "profiles", "active", "secret:$id", workspaceKey, "bootstrap:state", "bootstrap:pending", "bootstrap:durable")
            saved
        }

    @Synchronized fun cancelBootstrap(): SavedConnection? {
        if (prefs.getString("bootstrap:state", null) == "completed") return list().firstOrNull { it.id == activeId }
        // Retain the encrypted pending key if the server claimed it before cancellation.
        // It is never a visible connection and cancellation never retries automatically.
        commitBootstrap(prefs.edit().putString("bootstrap:state", "cancelled"), "bootstrap:state")
        return null
    }

    private fun commitBootstrap(edit: android.content.SharedPreferences.Editor, vararg keys: String) {
        val previous = keys.associateWith { prefs.getString(it, null) }
        if (edit.commit()) return
        // Restore the pre-operation memory view too: commit(false) can already have
        // changed it. A retry must not mistake a failed completion/cancellation for success.
        val rollback = prefs.edit()
        previous.forEach { (key, value) -> rollback.putString(key, value) }
        rollback.commit()
        throw ConnectionStorageException("无法保存首次连接，请检查设备存储空间后重试。")
    }

    @Synchronized fun remove(id: String) {
        migrateLegacyWorkspace()
        val remaining = list().filterNot { it.id == id }
        val edit = prefs.edit().putString("profiles", serialize(remaining)).remove("secret:$id")
        if (activeId == id) edit.putString("active", remaining.firstOrNull()?.id)
        commit(edit)
    }

    private fun serialize(profiles: List<SavedConnection>): String = JSONArray().also { rows ->
        profiles.forEach { saved -> rows.put(JSONObject().apply {
            put("id", saved.id); put("host", saved.profile.host); put("port", saved.profile.port)
            put("user", saved.profile.user); put("fingerprint", saved.profile.fingerprint)
            put("name", saved.profile.name); put("hostKeyAlgorithm", saved.profile.hostKeyAlgorithm)
            put("codexPath", saved.profile.codexPath)
            put("credentialRevision", saved.credentialRevision)
        }) }
    }.toString()

    /** A legacy global value belongs only to the connection active before the migration. */
    private fun migrateLegacyWorkspace() {
        if (!prefs.contains("workspace")) return
        val legacy = prefs.getString("workspace", "").orEmpty()
        val previousOwner = list().firstOrNull { it.id == activeId }
        val edit = prefs.edit().remove("workspace")
        if (previousOwner != null) {
            val key = workspaceKey(previousOwner)
            if (!prefs.contains(key)) edit.putString(key, legacy)
        } else if (legacy.isNotEmpty()) {
            // No reliable owner: retain it for recovery, but never assign it to a later connection.
            edit.putString("legacy-workspace-unassigned", legacy)
        }
        commit(edit)
    }

    private fun workspaceKey(connection: SavedConnection) = "workspace:${connection.identityKey}"
    private fun checkedWorkspace(value: String): String = value.trim().also {
        if ((it.isNotEmpty() && !it.startsWith('/')) || it.any { char -> char.code < 32 || char.code == 127 })
            throw ConnectionStorageException("工作目录需要是服务器上的有效绝对路径。")
    }

    private fun encrypt(id: String, credential: SshCredential): String = guarded("无法安全保存连接凭据；原有数据未改变。") {
        require(credential.privateKey?.isNotEmpty() == true || credential.password?.isNotEmpty() == true)
        val bytes = encodeCredential(credential)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(id.toByteArray(StandardCharsets.UTF_8))
            JSONObject().put("v", 1).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP)).toString()
        } finally { bytes.fill(0) }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Keystore key unavailable" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encodeCredential(value: SshCredential): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x43445831)
            out.writeSecret(value.privateKey)
            out.writeSecret(value.passphrase)
            val password = value.password?.let { chars ->
                val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
                ByteArray(encoded.remaining()).also { encoded.get(it); if (encoded.hasArray()) encoded.array().fill(0) }
            }
            try { out.writeSecret(password) } finally { password?.fill(0) }
        }
        return bytes.toByteArray()
    }

    private fun decodeCredential(bytes: ByteArray): SshCredential {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x43445831)
            val privateKey = input.readSecret()
            var passphrase: ByteArray? = null
            var password: ByteArray? = null
            try {
                passphrase = input.readSecret(); password = input.readSecret()
                require(input.available() == 0)
                val chars = password?.let {
                    val decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(it))
                    CharArray(decoded.remaining()).also { result -> decoded.get(result); if (decoded.hasArray()) decoded.array().fill('\u0000') }
                }
                return SshCredential(privateKey, passphrase, chars)
            } catch (error: Exception) {
                privateKey?.fill(0); passphrase?.fill(0); throw error
            } finally { password?.fill(0) }
        }
    }

    private fun DataOutputStream.writeSecret(bytes: ByteArray?) {
        if (bytes == null) { writeInt(-1); return }
        require(bytes.size <= MAX_SECRET)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.readSecret(): ByteArray? {
        val size = readInt()
        if (size == -1) return null
        require(size in 0..MAX_SECRET)
        return ByteArray(size).also { readFully(it) }
    }
    private fun commit(edit: android.content.SharedPreferences.Editor) {
        if (!edit.commit()) throw ConnectionStorageException("无法保存连接设置，请检查设备存储空间。")
    }
    private inline fun <T> guarded(message: String, operation: () -> T): T = try { operation() }
    catch (error: ConnectionStorageException) { throw error }
    catch (_: Exception) { throw ConnectionStorageException(message) }

    companion object { const val MAX_SECRET = 64 * 1024 }
}

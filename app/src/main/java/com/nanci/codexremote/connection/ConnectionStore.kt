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
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedConnection(val id: String, val profile: ConnectionProfile, val hasCredential: Boolean)

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
        @Synchronized get() = prefs.getString("workspace", "") ?: ""
        @Synchronized set(value) { commit(prefs.edit().putString("workspace", value.trim())) }

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
            ), prefs.contains("secret:$id"))
        }
    }

    @Synchronized fun setActive(id: String) {
        if (list().none { it.id == id }) throw ConnectionStorageException("这条连接已不存在。")
        activeId = id
    }

    /** A null credential preserves the saved secret. Save is atomic with profile metadata. */
    @Synchronized fun save(profile: ConnectionProfile, credential: SshCredential? = null, id: String? = null): SavedConnection {
        if (profile.host.isBlank() || profile.port !in 1..65535 || profile.user.isBlank() ||
            !profile.fingerprint.startsWith("SHA256:")) throw ConnectionStorageException("连接信息不完整。")
        val existing = list().toMutableList()
        val targetId = id ?: UUID.randomUUID().toString()
        if (id != null && existing.none { it.id == id }) throw ConnectionStorageException("这条连接已不存在，请重新添加。")
        val secret = credential?.let { encrypt(targetId, it) }
        val saved = SavedConnection(targetId, profile, secret != null || prefs.contains("secret:$targetId"))
        val index = existing.indexOfFirst { it.id == targetId }
        if (index >= 0) existing[index] = saved else existing += saved
        val edit = prefs.edit().putString("profiles", serialize(existing)).putString("active", targetId)
        if (secret != null) edit.putString("secret:$targetId", secret)
        commit(edit)
        return saved
    }

    @Synchronized fun loadCredential(id: String): SshCredential? {
        val encoded = prefs.getString("secret:$id", null) ?: return null
        return guarded("无法解密这条连接的凭据。请重新导入密钥或输入密码；原有数据已保留。") {
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
    }

    @Synchronized fun remove(id: String) {
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
        }) }
    }.toString()

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

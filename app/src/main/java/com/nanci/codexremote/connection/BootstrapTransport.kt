package com.nanci.codexremote.connection

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.nanci.codexremote.core.SshCredential
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Public to permit target-device instrumentation; no exported Activity or credential endpoint. */
object BootstrapKeys {
    fun generate(): SshCredential {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        return try {
            val out = ByteArrayOutputStream()
            pair.writePrivateKey(out)
            SshCredential(privateKey = out.toByteArray())
        } finally { pair.dispose() }
    }

    fun publicKey(credential: SshCredential): String {
        val bytes = credential.privateKey ?: throw BootstrapException("首次连接的设备密钥不可用。")
        val pair = KeyPair.load(JSch(), bytes, null)
        return try {
            require(pair.keyType == KeyPair.RSA && pair.keySize >= 3072)
            val out = ByteArrayOutputStream()
            pair.writePublicKey(out, "")
            val parts = out.toString("US-ASCII").trim().split(Regex("\\s+"))
            require(parts.size == 2 && parts[0] == "ssh-rsa" && parts[1].length <= 8192)
            parts.joinToString(" ")
        } finally { pair.dispose() }
    }
}

/** One bounded, host-pinned password session. The only submitted payload is a public key. */
class BootstrapTransport : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val session = AtomicReference<Session?>()

    fun enroll(spec: BootstrapSpec, publicKey: String) {
        val pin = EnrollmentHostKeys(spec.enrollment.fingerprint)
        val ssh = JSch().apply { setHostKeyRepository(pin) }
        var channel: ChannelExec? = null
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ close() }, 45, TimeUnit.SECONDS)
        try {
            checkRunning()
            val profile = spec.enrollment
            val connected = ssh.getSession(profile.user, profile.host, profile.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("server_host_key", profile.hostKeyAlgorithm)
                setConfig("PreferredAuthentications", "password")
                setPassword(String(spec.enrollmentPassword))
                setServerAliveInterval(10_000); setServerAliveCountMax(2)
                this.timeout = 20_000
            }
            session.set(connected)
            checkRunning()
            connected.connect(15_000)
            checkRunning()
            channel = connected.openChannel("exec") as ChannelExec
            channel.setCommand("enroll")
            channel.setInputStream(null)
            channel.setErrStream(object : OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = Unit
            })
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(10_000)
            val payload = (JSONObject().put("schemaVersion", 1).put("inviteId", spec.inviteId)
                .put("publicKey", publicKey).toString() + "\n").toByteArray(Charsets.UTF_8)
            require(payload.size <= 16 * 1024)
            output.use { it.write(payload); it.flush() } // EOF is part of the enrollment protocol.
            val response = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                checkRunning()
                val count = input.read(buffer)
                if (count < 0) break
                require(response.size() + count <= 16 * 1024)
                response.write(buffer, 0, count)
            }
            checkRunning()
            val result = JSONObject(response.toString("UTF-8"))
            if (result.opt("ok") == true && result.opt("inviteId") == spec.inviteId) return
            throw BootstrapException(when (result.optString("error")) {
                "claimed" -> "这份安装包已在另一台设备激活，请获取新的安装包。"
                "expired" -> "这份安装包的激活期限已过，请获取新的安装包。"
                "revoked" -> "这份安装包的连接授权已撤回，请联系分享者。"
                "invalid" -> "安装包中的连接资料无效，请获取新的安装包。"
                else -> "暂时无法完成首次连接，请稍后重试。"
            })
        } catch (error: BootstrapException) { throw error }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (stopped.get()) throw CancellationException()
            throw BootstrapException(if (pin.mismatched) "服务器身份校验未通过，已停止连接。请联系分享者。"
                else "首次连接未完成，请检查网络后重试。")
        } finally {
            timeout.cancel(false); timer.shutdownNow()
            runCatching { channel?.disconnect() }
            runCatching { session.getAndSet(null)?.disconnect() }
            ssh.removeAllIdentity()
        }
    }

    private fun checkRunning() { if (stopped.get()) throw CancellationException() }
    override fun close() {
        stopped.set(true)
        // JSch's disconnect can write to the socket; never make the UI wait for it.
        session.getAndSet(null)?.let { connected ->
            Thread({ runCatching { connected.disconnect() } }, "codex-bootstrap-close").apply { isDaemon = true; start() }
        }
    }

    private class EnrollmentHostKeys(private val fingerprint: String) : HostKeyRepository {
        @Volatile var mismatched = false
        override fun check(host: String?, key: ByteArray): Int {
            val actual = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
            val same = MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), fingerprint.trimEnd('=').toByteArray(Charsets.US_ASCII))
            if (!same) mismatched = true
            return if (same) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "codex-bootstrap-pinned-host"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}

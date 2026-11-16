package com.nanci.codexremote.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** SSH authenticates the byte stream; the remote daemon survives an Android disconnect. */
internal class SshTransport(private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onMessage(generation: Long, message: JSONObject)
        fun onDisconnected(generation: Long, reason: String)
    }
    private data class Pending(
        val generation: Long,
        val future: CompletableFuture<JSONObject>,
        @Volatile var written: Boolean = false,
        var timer: ScheduledFuture<*>? = null,
    )
    private val lifecycle = Any()
    private val outputLock = Any()
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "codex-ssh").apply { isDaemon = true } }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "codex-write").apply { isDaemon = true } }
    private val clock = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "codex-timeout").apply { isDaemon = true } }
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val random = SecureRandom()
    private var session: Session? = null
    private var channel: ChannelExec? = null
    private var outgoing: OutputStream? = null
    @Volatile var generation: Long = 0; private set
    @Volatile private var connected = false
    @Volatile private var disposed = false
    val isOpen: Boolean get() = connected && !disposed

    fun connect(profile: ConnectionProfile, credential: SshCredential): CompletableFuture<Long> {
        val secret = credential.copySecret()
        disconnect("连接已切换")
        val future = CompletableFuture<Long>()
        val g = synchronized(lifecycle) { ++generation }
        if (disposed) { secret.close(); future.completeExceptionally(CodexException("连接器已关闭")); return future }
        val deadline = clock.schedule({
            if (future.completeExceptionally(CodexException("建立连接超时（45 秒），请返回前台重试")))
                fail(g, "建立连接超时（45 秒）")
        }, 45, TimeUnit.SECONDS)
        future.whenComplete { _, _ -> deadline.cancel(false) }
        io.execute {
            var localSession: Session? = null
            var localChannel: ChannelExec? = null
            var ssh: JSch? = null
            var stage = "配置"
            try {
                require(profile.host.isNotBlank() && profile.user.isNotBlank() && profile.port in 1..65535)
                require(profile.fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?")))
                require(profile.codexPath.isNotBlank() && !profile.codexPath.contains('\u0000'))
                ssh = JSch().apply { setHostKeyRepository(PinnedHostKeys(profile.fingerprint)) }
                if (secret.privateKey != null) ssh.addIdentity("codex-mobile", secret.privateKey, null, secret.passphrase)
                localSession = ssh.getSession(profile.user, profile.host, profile.port).apply {
                    setConfig("StrictHostKeyChecking", "yes")
                    if (profile.hostKeyAlgorithm.isNotBlank()) setConfig("server_host_key", profile.hostKeyAlgorithm)
                    setConfig("PreferredAuthentications", if (secret.privateKey != null) "publickey" else "password")
                    if (secret.privateKey == null) {
                        require(secret.password != null && secret.password.isNotEmpty())
                        setPassword(String(secret.password!!))
                    }
                    setServerAliveInterval(15_000)
                    setServerAliveCountMax(3)
                }
                synchronized(lifecycle) {
                    check(generation == g && !disposed)
                    session = localSession
                }
                stage = "SSH"
                localSession.connect(15_000)
                secret.close()
                ssh.removeAllIdentity()
                stage = "代理通道"
                localChannel = localSession.openChannel("exec") as ChannelExec
                val executable = if (profile.codexPath.startsWith("~/"))
                    "\"\$HOME\"/" + shellQuote(profile.codexPath.removePrefix("~/")) else shellQuote(profile.codexPath)
                localChannel.setCommand("$executable app-server daemon start >/dev/null && exec $executable app-server proxy")
                localChannel.setInputStream(null)
                localChannel.setErrStream(object : OutputStream() {
                    override fun write(b: Int) = Unit
                    override fun write(b: ByteArray, off: Int, len: Int) = Unit
                })
                val input = localChannel.inputStream
                val output = localChannel.outputStream
                synchronized(lifecycle) { check(generation == g && !disposed); channel = localChannel }
                localChannel.connect(15_000)
                stage = "代理握手"
                val handshakeTimeout = clock.schedule({ fail(g, "服务器握手超时") }, 20, TimeUnit.SECONDS)
                try { handshake(input, output) } finally { handshakeTimeout.cancel(false) }
                synchronized(lifecycle) {
                    check(generation == g && session === localSession && localSession.isConnected)
                    outgoing = output
                    connected = true
                }
                future.complete(g)
                readLoop(input, g)
            } catch (error: Exception) {
                val reason = connectionFailure(stage, error, localChannel?.exitStatus)
                localChannel?.disconnect()
                localSession?.disconnect()
                future.completeExceptionally(CodexException(reason))
                fail(g, reason)
            } finally {
                secret.close()
                ssh?.removeAllIdentity()
            }
        }
        return future
    }

    fun request(method: String, params: JSONObject, timeoutMillis: Long = 60_000): CompletableFuture<JSONObject> {
        val future = CompletableFuture<JSONObject>()
        val g = generation
        if (!connected) { future.completeExceptionally(CodexException("尚未连接服务器")); return future }
        val id = ids.getAndIncrement()
        val entry = Pending(g, future)
        pending[id] = entry
        val message = jsonObject("id" to id, "method" to method, "params" to params.copyJson()).toString().toByteArray(Charsets.UTF_8)
        if (message.size > MAX_MESSAGE) {
            pending.remove(id); future.completeExceptionally(CodexException("请求内容过大")); return future
        }
        entry.timer = clock.schedule({
            if (pending.remove(id, entry)) future.completeExceptionally(
                CodexException(if (method == "initialize") "Codex 初始化超时（15 秒），连接将重新建立" else
                    "请求超时；请先刷新状态，避免重复提交", outcomeUnknown = entry.written))
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        future.whenComplete { _, _ -> entry.timer?.cancel(false); pending.remove(id, entry) }
        writer.execute {
            if (pending[id] === entry) {
                // A partial write also makes the outcome unknown: never retry a mutation automatically.
                entry.written = true
                try { writeFrame(1, message, g) }
                catch (_: Exception) { fail(g, "发送失败，连接已断开") }
            }
        }
        return future
    }

    fun notification(method: String, params: JSONObject = JSONObject()): CompletableFuture<Unit> =
        send(jsonObject("method" to method, "params" to params))

    fun response(id: Any, result: JSONObject): CompletableFuture<Unit> = send(jsonObject("id" to id, "result" to result))
    fun errorResponse(id: Any, code: Int, message: String): CompletableFuture<Unit> =
        send(jsonObject("id" to id, "error" to jsonObject("code" to code, "message" to message)))

    private fun send(message: JSONObject): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        val g = generation
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (!connected || bytes.size > MAX_MESSAGE) {
            future.completeExceptionally(CodexException(if (!connected) "尚未连接服务器" else "请求内容过大")); return future
        }
        writer.execute {
            try { writeFrame(1, bytes, g); future.complete(Unit) }
            catch (_: Exception) {
                future.completeExceptionally(CodexException("回复发送中断，请刷新状态", outcomeUnknown = true))
                fail(g, "发送失败，连接已断开")
            }
        }
        return future
    }

    private fun readLoop(input: InputStream, g: Long) {
        var fragmented: ByteArrayOutputStream? = null
        var reason = "Codex 服务器关闭了连接"
        try {
            while (generation == g && connected) {
                val first = readByte(input)
                val second = readByte(input)
                val final = first and 0x80 != 0
                val opcode = first and 15
                require(first and 0x70 == 0 && second and 0x80 == 0)
                var length = (second and 127).toLong()
                if (length == 126L) length = ((readByte(input) shl 8) or readByte(input)).toLong()
                else if (length == 127L) {
                    length = 0
                    repeat(8) { length = (length shl 8) or readByte(input).toLong() }
                }
                require(length in 0..MAX_MESSAGE.toLong())
                if (opcode >= 8) require(final && length <= 125)
                val payload = ByteArray(length.toInt())
                readFully(input, payload)
                when (opcode) {
                    8 -> break
                    9 -> writer.execute { try { writeFrame(10, payload, g) } catch (_: Exception) { fail(g, "连接已断开") } }
                    10 -> Unit
                    1 -> {
                        require(fragmented == null)
                        if (final) dispatch(payload, g)
                        else fragmented = ByteArrayOutputStream().apply { write(payload) }
                    }
                    0 -> {
                        val buffer = requireNotNull(fragmented)
                        require(buffer.size() + payload.size <= MAX_MESSAGE)
                        buffer.write(payload)
                        if (final) { dispatch(buffer.toByteArray(), g); fragmented = null }
                    }
                    else -> throw IllegalArgumentException("Unsupported frame")
                }
            }
        } catch (error: Exception) {
            reason = "Codex 数据流已断开（${error.javaClass.simpleName}）"
        } finally { fail(g, reason) }
    }

    private fun dispatch(bytes: ByteArray, g: Long) {
        if (g != generation || !connected) return
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        val message = JSONObject(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        if (message.has("method")) { listener.onMessage(g, message); return }
        val id = message.opt("id") as? Number ?: return
        val entry = pending.remove(id.toLong()) ?: return
        if (entry.generation != g) return
        val error = message.optJSONObject("error")
        if (error != null) {
            // Protocol errors are user-facing. Bound size; never print transport exception text.
            entry.future.completeExceptionally(CodexException(
                error.optString("message", "服务器拒绝了请求").take(1200), error.optInt("code")))
        } else entry.future.complete(message.optJSONObject("result") ?: jsonObject("value" to message.opt("result")))
    }

    private fun handshake(input: InputStream, output: OutputStream) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        output.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.flush()
        val header = ByteArrayOutputStream()
        var last = 0
        while (header.size() < 16_384) {
            val b = readByte(input); header.write(b); last = (last shl 8) or b
            if (last == 0x0d0a0d0a) break
        }
        require(last == 0x0d0a0d0a)
        val lines = header.toString("US-ASCII").split("\r\n")
        require(lines.first().matches(Regex("HTTP/1\\.[01] 101(?: .*)?")))
        val headers = lines.drop(1).filter { it.contains(':') }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        require(headers["upgrade"].equals("websocket", true))
        require(headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", true) } == true)
        val wanted = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        require(MessageDigest.isEqual(wanted.toByteArray(), headers["sec-websocket-accept"].orEmpty().toByteArray()))
    }

    private fun writeFrame(opcode: Int, payload: ByteArray, g: Long) {
        synchronized(outputLock) {
            val output = synchronized(lifecycle) {
                check(g == generation && connected); requireNotNull(outgoing)
            }
            output.write(0x80 or opcode)
            val n = payload.size
            when {
                n < 126 -> output.write(0x80 or n)
                n <= 65535 -> { output.write(0x80 or 126); output.write(n ushr 8); output.write(n) }
                else -> { output.write(0x80 or 127); for (i in 7 downTo 0) output.write((n.toLong() ushr (8 * i)).toInt()) }
            }
            val mask = ByteArray(4).also { random.nextBytes(it) }
            output.write(mask)
            val buffer = ByteArray(minOf(8192, maxOf(1, n)))
            var offset = 0
            while (offset < n) {
                val count = minOf(buffer.size, n - offset)
                repeat(count) { buffer[it] = (payload[offset + it].toInt() xor mask[(offset + it) and 3].toInt()).toByte() }
                output.write(buffer, 0, count); offset += count
            }
            output.flush()
        }
    }

    fun listDirectory(path: String): CompletableFuture<List<RemoteEntry>> = sftp { channel ->
        require(path.startsWith('/'))
        channel.ls(path).filterIsInstance<ChannelSftp.LsEntry>().filter { it.filename != "." && it.filename != ".." }
            .map { RemoteEntry(it.filename, path.trimEnd('/') + "/" + it.filename, it.attrs.isDir, it.attrs.size) }
            .sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun readFile(path: String, maxBytes: Int): CompletableFuture<ByteArray> = sftp { channel ->
        require(path.startsWith('/') && maxBytes in 1..MAX_UPLOAD)
        val attr = channel.stat(path)
        if (attr.isDir || attr.size > maxBytes) throw CodexException("文件超过预览大小限制")
        channel.get(path).use { input -> readBounded(input, maxBytes) }
    }

    /** The caller owns a unique temporary destination; unsuccessful downloads remove it. */
    fun downloadFile(
        absolutePath: String,
        destination: File,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): CompletableFuture<Long> {
        if (!destination.isAbsolute || destination.exists() && (!destination.isFile || destination.length() != 0L))
            return failed(CodexException("无法创建临时下载文件"))
        val owner = generation
        fun checkActive() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CodexException("文件读取已取消")
            if (generation != owner || !connected || disposed) throw CodexException("连接已切换，请重新打开文件")
        }
        return sftp { channel ->
            checkActive()
            require(absolutePath.startsWith('/') && !absolutePath.contains('\u0000'))
            val attr = channel.stat(absolutePath)
            if (!attr.isReg || attr.size < 0L) throw CodexException("此路径不是可下载的文件")
            val available = destination.parentFile?.usableSpace ?: 0L
            if (available > 0L && attr.size > available) throw CodexException("设备存储空间不足")
            var copied = 0L
            onProgress(copied, attr.size)
            channel.get(absolutePath).use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        checkActive()
                        if (count < 0) break
                        if (count == 0) continue
                        if (count.toLong() > attr.size - copied) throw CodexException("文件已发生变化，请重新下载")
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, attr.size)
                    }
                }
            }
            checkActive()
            val current = channel.stat(absolutePath)
            if (copied != attr.size || !current.isReg || current.size != attr.size || current.mTime != attr.mTime)
                throw CodexException("文件未完整读取或已发生变化，请重新下载")
            checkActive()
            copied
        }.whenComplete { _, error ->
            // Include sftp's final connection-generation check, not only failures inside the copy.
            if (error != null) runCatching { destination.delete() }
        }
    }

    fun upload(name: String, input: InputStream, size: Long): CompletableFuture<String> {
        if (size > MAX_UPLOAD) { input.close(); return failed(CodexException("附件超过 32 MB")) }
        return sftp { channel ->
            input.use {
                val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "attachment" }
                var path = channel.home.trimEnd('/')
                for (segment in listOf(".local", "share", "codex-mobile", "attachments", UUID.randomUUID().toString())) {
                    path += "/$segment"
                    try { channel.stat(path) } catch (e: com.jcraft.jsch.SftpException) {
                        if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e
                        channel.mkdir(path); channel.chmod(448, path)
                    }
                }
                val remote = "$path/$safeName"
                // Bound even when the document provider reports an unknown/incorrect size.
                val bytes = readBounded(input, MAX_UPLOAD)
                channel.put(bytes.inputStream(), remote)
                channel.chmod(384, remote)
                if (channel.stat(remote).size != bytes.size.toLong()) throw CodexException("附件上传校验失败")
                remote
            }
        }.whenComplete { _, _ -> runCatching { input.close() } }
    }

    private fun <T> sftp(block: (ChannelSftp) -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val g = generation
        io.execute {
            var channel: ChannelSftp? = null
            try {
                val active = synchronized(lifecycle) { check(connected && generation == g); requireNotNull(session) }
                channel = active.openChannel("sftp") as ChannelSftp
                channel.connect(10_000)
                val value = block(channel)
                check(generation == g && connected)
                result.complete(value)
            } catch (error: Exception) {
                result.completeExceptionally(if (error is CodexException) error else CodexException("远程文件操作失败，请检查连接与路径权限"))
            } finally { channel?.disconnect() }
        }
        return result
    }

    fun disconnect(reason: String = "连接已关闭") { fail(generation, reason, true) }
    private fun fail(g: Long, reason: String, invalidate: Boolean = false) {
        val resources = synchronized(lifecycle) {
            if (g != generation) return
            val old = Pair(channel, session)
            if (!connected && old.first == null && old.second == null && !invalidate) return
            connected = false; outgoing = null; channel = null; session = null
            if (invalidate) ++generation
            old
        }
        // Closing a stale SSH channel may write EOF to an unresponsive socket. Keep it off Android's main thread.
        if (resources.first != null || resources.second != null) Thread({
            runCatching { resources.first?.disconnect() }
            runCatching { resources.second?.disconnect() }
        }, "codex-close-$g").apply { isDaemon = true; start() }
        pending.entries.forEach { (id, entry) ->
            if (entry.generation == g && pending.remove(id, entry))
                entry.future.completeExceptionally(CodexException(reason, outcomeUnknown = entry.written))
        }
        listener.onDisconnected(g, reason)
    }

    override fun close() {
        disposed = true; disconnect(); writer.shutdownNow(); clock.shutdownNow(); io.shutdownNow()
    }

    private class PinnedHostKeys(private val fingerprint: String) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val digest = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
            return if (MessageDigest.isEqual(digest.toByteArray(), fingerprint.trimEnd('=').toByteArray())) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "codex-mobile-pinned-key"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
    companion object {
        private const val MAX_MESSAGE = 16 * 1024 * 1024
        private const val MAX_UPLOAD = 32 * 1024 * 1024
        private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        private fun connectionFailure(stage: String, error: Exception, exitCode: Int?): String {
            val code = error.javaClass.simpleName
            val exit = exitCode?.takeIf { it >= 0 }?.let { ", exit=$it" }.orEmpty()
            return when (stage) {
                "配置" -> "连接配置或密钥格式无效（配置/$code）"
                "SSH" -> "SSH 连接或认证失败（SSH/$code）"
                "代理通道" -> "SSH 已认证，但 Codex 代理通道未建立（代理/$code$exit）"
                else -> "SSH 已连接，但 Codex 代理握手失败（握手/$code$exit）"
            }
        }
        private fun readByte(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException() }
        private fun readFully(input: InputStream, data: ByteArray) {
            var offset = 0
            while (offset < data.size) { val n = input.read(data, offset, data.size - offset); if (n < 0) throw EOFException(); offset += n }
        }
        private fun readBounded(input: InputStream, max: Int): ByteArray {
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                if (output.size() + n > max) throw CodexException("文件超过大小限制")
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }
        private fun <T> failed(error: Exception) = CompletableFuture<T>().apply { completeExceptionally(error) }
    }
}

package com.nanci.codexremote.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import com.nanci.codexremote.BuildConfig
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Independent updater; the public build uses only GitHub HTTPS, the private build uses SFTP. */
class UpdateRepository internal constructor(
    private val cacheDirectory: File,
    private val verifier: UpdateApkVerifier,
    private val dispatch: (() -> Unit) -> Unit,
    private val sourceFactory: () -> UpdateSource = { SftpUpdateSource() },
    private val publicRelease: Boolean = false,
) : AutoCloseable {
    constructor(context: Context) : this(File(context.applicationContext.cacheDir, "updates"),
        AndroidApkVerifier(context), { action -> Handler(Looper.getMainLooper()).post(action) },
        { if (BuildConfig.PUBLIC_RELEASE) GitHubUpdateSource() else SftpUpdateSource() }, BuildConfig.PUBLIC_RELEASE)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "codex-update").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "codex-update-timeout").apply { isDaemon = true } }
    private val active = AtomicReference<Operation<*>?>()
    private val closed = AtomicBoolean(false)

    /** Public updates work before any SSH connection exists and never load its credentials. */
    fun checkPublic(): CompletableFuture<UpdateCheck> =
        if (publicRelease) check(GitHubUpdateSource.PROFILE, SshCredential())
        else publicChannelDisabled()

    fun downloadPublic(release: UpdateRelease,
        onProgress: (UpdateProgress) -> Unit = {}): CompletableFuture<VerifiedApk> =
        if (publicRelease) download(release, SshCredential(), onProgress)
        else publicChannelDisabled()

    private fun <T> publicChannelDisabled(): CompletableFuture<T> = CompletableFuture<T>().also {
        it.completeExceptionally(UpdateException("此安装包未启用 GitHub 更新"))
    }

    fun check(profile: ConnectionProfile, credential: SshCredential): CompletableFuture<UpdateCheck> =
        start(if (publicRelease) SshCredential() else credential, 45_000) { operation ->
            val origin = if (publicRelease) GitHubUpdateSource.PROFILE else profile
            val source = connect(operation, origin)
            val release = UpdateManifest.parse(source.metadata(), origin)
            operation.ensureActive()
            val installed = verifier.installed()
            if (installed.packageName != UpdateManifest.PACKAGE_NAME) throw UpdateException("当前应用包名无法匹配更新")
            if (release.minSdk > verifier.deviceSdk) throw UpdateException("新版本需要更高版本的 Android 系统")
            UpdateCheck(installed.versionCode, release, release.versionCode.toLong() > installed.versionCode)
        }

    fun download(release: UpdateRelease, credential: SshCredential,
        onProgress: (UpdateProgress) -> Unit = {}): CompletableFuture<VerifiedApk> = start(if (publicRelease) SshCredential() else credential, 10 * 60_000) { operation ->
        if (publicRelease && release.source != GitHubUpdateSource.PROFILE)
            throw UpdateException("更新来源与此公开安装包不匹配")
        UpdateManifest.validate(release)
        if (release.versionCode.toLong() <= verifier.installed().versionCode) throw UpdateException("不能下载相同版本或降级版本")
        if (release.minSdk > verifier.deviceSdk) throw UpdateException("此更新需要更高版本的 Android 系统")
        val source = connect(operation, release.source)
        // Bind the offer to the same manifest and immutable origin, including after a delayed install prompt.
        val current = UpdateManifest.parse(source.metadata(), release.source)
        if (current != release) throw UpdateException("服务器的更新版本已变化，请重新检查更新")
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) throw UpdateException("无法创建更新下载目录")
        val directory = cacheDirectory.canonicalFile
        val temporary = File.createTempFile("download-", ".part", directory)
        operation.temporary = temporary
        var total = 0L
        var lastProgressAt = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        source.apk(release).use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    operation.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > release.sizeBytes || total > UpdateManifest.APK_LIMIT) throw UpdateException("更新 APK 超过索引声明的大小")
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                    val now = System.nanoTime()
                    if (now - lastProgressAt > TimeUnit.MILLISECONDS.toNanos(100) || total == release.sizeBytes) {
                        lastProgressAt = now
                        val progress = UpdateProgress(total, release.sizeBytes)
                        dispatch { if (operation.isActive()) onProgress(progress) }
                    }
                }
                output.fd.sync()
            }
        }
        operation.ensureActive()
        if (total != release.sizeBytes) throw UpdateException("更新 APK 下载不完整，文件长度不匹配")
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(actualHash.toByteArray(Charsets.US_ASCII), release.sha256.lowercase().toByteArray(Charsets.US_ASCII)))
            throw UpdateException("更新 APK 的 SHA-256 校验失败")
        val candidate = verifier.archive(temporary)
        UpdateManifest.requireInstallable(release, verifier.installed(), candidate, verifier.deviceSdk)
        operation.ensureActive()
        val ready = File(directory, "codex-${release.versionCode}-${UUID.randomUUID()}.apk")
        operation.ready = ready
        try { Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { throw UpdateException("无法安全保存已验证的更新 APK") }
        operation.temporary = null
        operation.ensureActive()
        VerifiedApk(release, ready)
    }

    /** Cancels only this updater's in-flight operation; valid previously downloaded APKs are retained. */
    fun cancel() { active.getAndSet(null)?.abort() }

    override fun close() {
        if (closed.compareAndSet(false, true)) { cancel(); io.shutdownNow(); timer.shutdownNow() }
    }

    private fun connect(operation: Operation<*>, profile: ConnectionProfile): UpdateSource {
        operation.ensureActive()
        val source = sourceFactory()
        operation.source.set(source)
        operation.ensureActive()
        source.connect(profile, operation.secret)
        operation.secret.close()
        operation.ensureActive()
        return source
    }

    private fun <T> start(credential: SshCredential, timeoutMillis: Long, work: (Operation<T>) -> T): CompletableFuture<T> {
        // Copy before returning: the Keystore caller may erase its plaintext immediately.
        val operation = Operation<T>(credential.copySecret())
        if (closed.get()) { operation.secret.close(); dispatch { operation.future.completeExceptionally(UpdateException("更新服务已关闭")) }; return operation.future }
        active.getAndSet(operation)?.abort()
        operation.future.whenComplete { _, _ -> if (operation.future.isCancelled) operation.abort() }
        val timeout = try {
            timer.schedule({ operation.abort(UpdateException("更新服务器响应超时，请稍后重试")) }, timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            operation.abort(UpdateException("更新服务已关闭"))
            return operation.future
        }
        try {
            io.execute {
                var successQueued = false
                try {
                    operation.ensureActive()
                    val result = work(operation)
                    successQueued = true
                    dispatch {
                        if (operation.isActive()) {
                            operation.ready = null // Ownership passes to the installer coordinator.
                            operation.future.complete(result)
                        } else { operation.removeReady(); operation.future.cancel(false) }
                        active.compareAndSet(operation, null)
                    }
                } catch (error: Exception) {
                    val safe = when (error) {
                        is UpdateException -> error
                        is CancellationException -> null
                        is SftpException -> UpdateException(if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) "服务器尚未发布更新文件" else "无法读取服务器更新文件")
                        else -> UpdateException("更新检查或下载失败，请检查连接和存储空间后重试")
                    }
                    dispatch {
                        if (!operation.future.isDone) {
                            if (safe == null || !operation.isActive()) operation.future.cancel(false)
                            else operation.future.completeExceptionally(safe)
                        }
                        active.compareAndSet(operation, null)
                    }
                } finally {
                    timeout.cancel(false)
                    operation.cleanup(removeReady = !successQueued)
                }
            }
        } catch (_: Exception) { timeout.cancel(false); operation.abort(UpdateException("无法启动更新任务")) }
        return operation.future
    }

    private inner class Operation<T>(val secret: SshCredential) {
        val future = CompletableFuture<T>()
        val source = AtomicReference<UpdateSource?>()
        @Volatile var temporary: File? = null
        @Volatile var ready: File? = null
        private val cancelled = AtomicBoolean(false)
        fun isActive(): Boolean = !cancelled.get() && !closed.get() && active.get() === this && !future.isCancelled
        fun ensureActive() { if (!isActive()) throw CancellationException() }
        fun abort(error: UpdateException? = null) {
            cancelled.set(true); cleanup()
            dispatch { if (error == null) future.cancel(false) else future.completeExceptionally(error) }
        }
        fun removeReady() { ready?.let { runCatching { it.delete() } }; ready = null }
        fun cleanup(removeReady: Boolean = true) {
            runCatching { source.getAndSet(null)?.close() }
            secret.close()
            temporary?.let { runCatching { it.delete() } }; temporary = null
            if (removeReady) this.removeReady()
        }
    }
}

/** Injectable boundary for bounded-stream and cancellation tests; real transport is read-only SFTP. */
internal interface UpdateSource : AutoCloseable {
    fun connect(profile: ConnectionProfile, credential: SshCredential)
    fun metadata(): ByteArray
    fun apk(release: UpdateRelease): InputStream
}

internal class SftpUpdateSource : UpdateSource {
    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelSftp? = null
    private val stopped = AtomicBoolean(false)
    private var root: String? = null

    override fun connect(profile: ConnectionProfile, credential: SshCredential) {
        if (profile.host.isBlank() || profile.user.isBlank() || profile.port !in 1..65535 ||
            !profile.fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?")) ||
            (profile.hostKeyAlgorithm.isNotBlank() && !profile.hostKeyAlgorithm.matches(Regex("[A-Za-z0-9@._+-]+"))))
            throw UpdateException("更新连接的主机信息或指纹无效")
        val pin = PinnedUpdateHostKeys(profile.fingerprint)
        val ssh = JSch().apply { setHostKeyRepository(pin) }
        var opening: Session? = null
        var openingChannel: ChannelSftp? = null
        try {
            if (credential.privateKey != null) ssh.addIdentity("codex-update", credential.privateKey, null, credential.passphrase)
            val connected = ssh.getSession(profile.user, profile.host, profile.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                if (profile.hostKeyAlgorithm.isNotBlank()) setConfig("server_host_key", profile.hostKeyAlgorithm)
                setConfig("PreferredAuthentications", if (credential.privateKey != null) "publickey" else "password")
                if (credential.privateKey == null) {
                    val password = credential.password?.takeIf { it.isNotEmpty() } ?: throw UpdateException("更新连接缺少 SSH 凭据")
                    setPassword(String(password))
                }
                timeout = 20_000
                setServerAliveInterval(10_000); setServerAliveCountMax(2)
            }
            session = connected
            opening = connected
            if (stopped.get()) throw CancellationException()
            connected.connect(15_000)
            ssh.removeAllIdentity(); credential.close()
            if (stopped.get()) throw CancellationException()
            val sftp = connected.openChannel("sftp") as ChannelSftp
            openingChannel = sftp
            channel = sftp
            if (stopped.get()) throw CancellationException()
            sftp.connect(15_000)
            val home = sftp.realpath(".").trimEnd('/')
            if (!home.startsWith('/') || home.isEmpty() || '\u0000' in home) throw UpdateException("无法确定更新服务器的用户目录")
            var parent = home
            for (component in UpdateManifest.REMOTE_DIRECTORY.split('/')) {
                parent += "/$component"
                requirePath(sftp, parent, directory = true)
            }
            root = parent
        } catch (error: UpdateException) { throw error }
        catch (error: CancellationException) { throw error }
        catch (error: SftpException) { throw error }
        catch (_: Exception) { throw UpdateException(if (pin.mismatched) "更新服务器的主机指纹不匹配，已拒绝连接" else "无法连接更新服务器，请检查网络和 SSH 认证") }
        finally {
            ssh.removeAllIdentity(); credential.close()
            // Cancellation may race with connect() after close() cleared the shared fields.
            if (stopped.get()) { openingChannel?.disconnect(); opening?.disconnect() }
        }
    }

    override fun metadata(): ByteArray {
        val sftp = requireChannel()
        val path = requireNotNull(root) + "/latest.json"
        requirePath(sftp, path, directory = false)
        if (sftp.lstat(path).size !in 1..UpdateManifest.METADATA_LIMIT.toLong()) throw UpdateException("更新索引超过 64 KiB 限制或内容为空")
        return sftp.get(path).use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                if (output.size() + n > UpdateManifest.METADATA_LIMIT) throw UpdateException("更新索引超过 64 KiB 限制")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }

    override fun apk(release: UpdateRelease): InputStream {
        UpdateManifest.validate(release)
        val sftp = requireChannel(); val root = requireNotNull(root)
        requirePath(sftp, "$root/releases", directory = true)
        val path = "$root/${release.apkFile}"
        requirePath(sftp, path, directory = false)
        if (sftp.lstat(path).size != release.sizeBytes) throw UpdateException("服务器 APK 长度与更新索引不一致")
        return sftp.get(path)
    }

    private fun requirePath(sftp: ChannelSftp, path: String, directory: Boolean) {
        val attributes = sftp.lstat(path)
        if (attributes.isLink || (if (directory) !attributes.isDir else !attributes.isReg) || sftp.realpath(path) != path)
            throw UpdateException("更新目录包含不受支持的链接或文件类型")
    }

    private fun requireChannel(): ChannelSftp = channel?.takeIf { it.isConnected && !stopped.get() } ?: throw CancellationException()
    override fun close() { stopped.set(true); channel?.disconnect(); session?.disconnect(); channel = null; session = null }
}

internal class PinnedUpdateHostKeys(private val fingerprint: String) : HostKeyRepository {
    @Volatile var mismatched = false; private set
    override fun check(host: String?, key: ByteArray): Int {
        val actual = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val same = MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), fingerprint.trimEnd('=').toByteArray(Charsets.US_ASCII))
        if (!same) mismatched = true
        return if (same) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "codex-update-pinned-host"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

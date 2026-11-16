package com.nanci.codexremote.test

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import com.nanci.codexremote.BuildConfig
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import com.nanci.codexremote.update.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Run only in the separate com.nanci.codexremote.githubqa application.
 * The installed identity alone is adapted to model an older production version; the downloaded
 * public APK is parsed by Android's real PackageManager and checked against the established signer.
 * No installer is launched, no owner app is touched, and no SSH credentials are read.
 */
class GitHubUpdateInstrumentation : Instrumentation() {
    private val report = Bundle()
    private val main = Handler(Looper.getMainLooper())
    private val models = ViewModelStore()
    private val repositories = mutableListOf<UpdateRepository>()
    private var stage = "setup"
    private lateinit var scratch: File
    private lateinit var verifier: UpdateApkVerifier
    private val capturedMetadata = AtomicReference<ByteArray>()
    private var arguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        this.arguments = Bundle(arguments ?: Bundle())
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var result = Activity.RESULT_CANCELED
        try {
            requireCheck(targetContext.packageName == "com.nanci.codexremote.githubqa", "qa_application_required")
            requireCheck(BuildConfig.PUBLIC_RELEASE, "public_build_required")
            val resource = targetContext.resources.getIdentifier("public_update_channel", "string", targetContext.packageName)
            requireCheck(resource != 0 && targetContext.getString(resource) == "github", "public_channel_resource_missing")
            requireCheck(ConnectionStore(targetContext).list().isEmpty(), "qa_connections_must_be_empty")
            scratch = File(targetContext.cacheDir, "github-update-qa-${UUID.randomUUID()}")
            requireCheck(scratch.mkdir(), "qa_cache_creation_failed")
            val androidVerifier = AndroidApkVerifier(targetContext)
            val realInstalled = androidVerifier.installed()
            requireCheck(realInstalled.signerSha256 == setOf(SIGNER), "qa_signer_mismatch")
            verifier = object : UpdateApkVerifier {
                override val deviceSdk get() = androidVerifier.deviceSdk
                override fun installed() = realInstalled.copy(packageName = UpdateManifest.PACKAGE_NAME,
                    versionCode = 12, versionName = "0.2.9")
                override fun archive(file: File) = androidVerifier.archive(file)
            }
            checkWithoutConnection()
            val network = repository("network") {
                val delegate = GitHubUpdateSource()
                object : UpdateSource {
                    override fun connect(profile: ConnectionProfile, credential: SshCredential) {
                        requireCheck(profile == GitHubUpdateSource.PROFILE, "unexpected_network_origin")
                        requireCheck(credential.password == null && credential.privateKey == null && credential.passphrase == null,
                            "http_received_credentials")
                        delegate.connect(profile, credential)
                    }
                    override fun metadata() = delegate.metadata().also { capturedMetadata.set(it.copyOf()) }
                    override fun apk(release: UpdateRelease) = delegate.apk(release)
                    override fun close() = delegate.close()
                }
            }
            stage = "public_github_check"
            val check = onMain { network.checkPublic() }.get(60, TimeUnit.SECONDS)
            val expectedCode = arguments.getString("expectedVersionCode", "13").toInt()
            val expectedName = arguments.getString("expectedVersionName", "0.2.10")
            requireCheck(check.available && check.latest.versionCode == expectedCode && check.latest.versionName == expectedName,
                "expected_public_release_not_available")
            requireCheck(check.latest.source == GitHubUpdateSource.PROFILE, "public_origin_mismatch")
            report.putBoolean("github_check_without_ssh_credentials", true)
            report.putInt("release_version_code", check.latest.versionCode)

            stage = "public_github_download"
            val progressSeen = AtomicBoolean(false)
            val apk = onMain { network.downloadPublic(check.latest) { if (it.downloadedBytes > 0) progressSeen.set(true) } }
                .get(180, TimeUnit.SECONDS)
            requireCheck(apk.file.length() == check.latest.sizeBytes && digest(apk.file) == check.latest.sha256,
                "public_apk_size_or_sha_mismatch")
            val identity = androidVerifier.archive(apk.file)
            requireCheck(identity.packageName == UpdateManifest.PACKAGE_NAME && identity.versionCode == expectedCode.toLong() &&
                identity.signerSha256 == setOf(SIGNER) && !identity.debuggable && !identity.testOnly, "public_apk_identity_mismatch")
            requireCheck(progressSeen.get(), "download_progress_missing")
            report.putBoolean("real_github_download_sha_and_android_signature", true)
            report.putLong("downloaded_bytes", apk.file.length())

            stage = "private_origin_rejected"
            val wrongOrigin = expectFailure(onMain { network.downloadPublic(check.latest.copy(source =
                ConnectionProfile(host = "example.invalid", user = "qa", fingerprint = ""))) }, "private_origin_accepted")
            requireCheck((wrongOrigin as? UpdateException)?.safeMessage == "更新来源与此公开安装包不匹配", "origin_failed_for_wrong_reason")
            report.putBoolean("private_origin_rejected", true)

            stage = "corrupt_stream_rejected"
            val corrupt = repository("corrupt") { localSource(apk.file, corrupt = true) }
            val corruption = expectFailure(onMain { corrupt.downloadPublic(check.latest) }, "corrupt_apk_accepted")
            requireCheck((corruption as? UpdateException)?.safeMessage == "更新 APK 的 SHA-256 校验失败", "corrupt_failed_before_hash_check")
            waitUntil(5_000, "corrupt_partial_retained") { File(scratch, "corrupt").listFiles().orEmpty().isEmpty() }
            report.putBoolean("corrupt_stream_sha_rejected_and_removed", true)

            stage = "cancel_download"
            val cancelled = repository("cancelled") { localSource(apk.file, slow = true) }
            val cancelledAfterBytes = AtomicBoolean(false)
            val cancelledFuture = onMain {
                cancelled.downloadPublic(check.latest) {
                    if (it.downloadedBytes > 0 && cancelledAfterBytes.compareAndSet(false, true)) cancelled.cancel()
                }
            }
            expectFailure(cancelledFuture, "cancelled_download_succeeded")
            requireCheck(cancelledAfterBytes.get() && cancelledFuture.isCancelled, "cancel_did_not_reach_stream")
            waitUntil(5_000, "cancelled_partial_retained") { File(scratch, "cancelled").listFiles().orEmpty().isEmpty() }
            report.putBoolean("cancelled_partial_removed", true)
            requireCheck(ConnectionStore(targetContext).list().isEmpty(), "qa_connection_store_changed")
            report.putBoolean("connection_store_remained_empty", true)
            report.putString("status", "passed")
            result = Activity.RESULT_OK
        } catch (failure: CheckFailure) {
            report.putString("status", "failed"); report.putString("failure", failure.code)
        } catch (failure: Throwable) {
            report.putString("status", "failed"); report.putString("failure", "operation_failed")
            report.putString("error_type", failure.javaClass.simpleName)
            val cause = generateSequence(failure) { it.cause }.last()
            if (cause is UpdateException) report.putString("safe_error", cause.safeMessage)
        } finally {
            report.putString("stage", stage)
            repositories.forEach { it.close() }
            onMain { models.clear() }
            val cleaned = !::scratch.isInitialized || scratch.deleteRecursively()
            report.putBoolean("test_resources_removed", cleaned)
            if (!cleaned) { report.putString("status", "failed"); report.putString("failure", "qa_cleanup_failed"); result = Activity.RESULT_CANCELED }
            report.putString("stream", "\nGitHub updater: ${report.getString("status")} (${report.getString("stage")})\n")
            finish(result, report)
        }
    }

    private fun checkWithoutConnection() {
        stage = "foreground_without_connection"
        onMain {
            val coordinator = UpdateCoordinator(targetContext.applicationContext as Application)
            models.put("github-update-qa", coordinator)
            coordinator.onForeground(null)
            requireCheck(coordinator.automaticCheckCount == 1, "foreground_check_requires_ssh_connection")
            coordinator.onConnectionAvailable(null)
            requireCheck(coordinator.automaticCheckCount == 1, "duplicate_foreground_check")
            // The QA application has a separate package ID, so its actual installed identity
            // intentionally cannot install the production APK. Network success is checked below
            // with only that installed identity adapted; this assertion proves the UI trigger.
            coordinator.dismiss()
            models.clear()
        }
        report.putBoolean("foreground_check_triggered_without_connection", true)
    }

    private fun repository(name: String, source: () -> UpdateSource): UpdateRepository =
        UpdateRepository(File(scratch, name), verifier, { action -> main.post(action) }, source, true)
            .also { repositories += it }

    /** Use the actual downloaded payload for deterministic interruption and corruption checks,
     * avoiding repeated GitHub downloads and making cancellation happen after a written block. */
    private fun localSource(file: File, corrupt: Boolean = false, slow: Boolean = false): UpdateSource = object : UpdateSource {
        private val stopped = AtomicBoolean(false)
        private var input: InputStream? = null
        override fun connect(profile: ConnectionProfile, credential: SshCredential) {
            requireCheck(profile == GitHubUpdateSource.PROFILE, "fixture_origin_changed")
        }
        override fun metadata(): ByteArray = requireNotNull(capturedMetadata.get()).copyOf()
        override fun apk(release: UpdateRelease): InputStream {
            return object : FilterInputStream(file.inputStream()) {
                private var changed = false
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (stopped.get()) throw CancellationException()
                    if (slow) SystemClock.sleep(3)
                    val count = super.read(buffer, offset, minOf(length, 16 * 1024))
                    if (corrupt && !changed && count > 0) { buffer[offset] = (buffer[offset].toInt() xor 1).toByte(); changed = true }
                    return count
                }
            }.also { input = it }
        }
        override fun close() { stopped.set(true); runCatching { input?.close() } }
    }

    private fun <T> expectFailure(future: CompletableFuture<T>, code: String): Throwable {
        try { future.get(30, TimeUnit.SECONDS) }
        catch (failure: ExecutionException) { return generateSequence(failure) { it.cause }.last() }
        catch (failure: CancellationException) { return failure }
        throw CheckFailure(code)
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; if (n > 0) hash.update(bytes, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun waitUntil(timeout: Long, code: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { if (SystemClock.elapsedRealtime() >= end) throw CheckFailure(code); SystemClock.sleep(25) }
    }
    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun requireCheck(value: Boolean, code: String) { if (!value) throw CheckFailure(code) }
    private class CheckFailure(val code: String) : Exception()
    companion object { private const val SIGNER = "12c9858dc73f6bfab725bb10ec341e3a203344f9bd8f1cea0b3874106ba25d3e" }
}

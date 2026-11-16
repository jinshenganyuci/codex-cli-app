package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.ViewModelProvider
import com.nanci.codexremote.MainActivity
import com.nanci.codexremote.RemoteFileCoordinator
import com.nanci.codexremote.RemoteFilePhase
import com.nanci.codexremote.RemoteViewModel
import com.nanci.codexremote.remoteFileMimeType
import com.nanci.codexremote.remoteFileOpenIntent
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.connection.SavedConnection
import com.nanci.codexremote.core.CodexRepository
import com.nanci.codexremote.ui.content.inlineMarkdown
import com.nanci.codexremote.ui.content.markdownInlineSmokeChecks
import com.nanci.codexremote.ui.content.userMessageWrapperSmokeChecks
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Device-only tests against task-owned remote fixtures. No connection or chat mutations.
 * Build the separate test APK with -PcodexTestRunner set to this class. Supply fixturePath
 * and fixtureManifest (base64 UTF-8 JSON). Test output never includes connection secrets.
 */
class FileDownloadInstrumentation : Instrumentation() {
    private data class Fixture(val name: String, val mime: String, val size: Long, val sha256: String)
    private var arguments = Bundle()
    private val report = Bundle()
    private var stage = "setup"
    private var repo: CodexRepository? = null
    private var coordinator: RemoteFileCoordinator? = null
    private var activity: MainActivity? = null
    private lateinit var scratch: File
    private lateinit var fixtureRoot: String
    private lateinit var fixtures: List<Fixture>
    private var baseline: String? = null
    private val savedUris = mutableListOf<Uri>()

    override fun onCreate(arguments: Bundle?) {
        this.arguments = Bundle(arguments ?: Bundle())
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var result = Activity.RESULT_CANCELED
        try {
            requireCheck(targetContext.packageName == "com.nanci.codexremote", "unexpected_target")
            readFixtures()
            baseline = connectionDigest()
            scratch = File(targetContext.cacheDir, "file-download-qa-${UUID.randomUUID()}")
            requireCheck(scratch.mkdir(), "scratch_creation_failed")
            markdownChecks()
            if (arguments.getString("mode", "verify") == "ui") {
                uiPreview()
            } else {
                val store = ConnectionStore(targetContext)
                val saved = store.list().firstOrNull { it.id == store.activeId && it.hasCredential }
                    ?: throw CheckFailure("saved_active_connection_required")
                onMain { repo = CodexRepository() }
                connect(store, saved)
                streamingChecks()
                cancellationAndErrorChecks()
                disconnectedDownloadCheck()
                connect(store, saved)
                coordinatorChecks()
                lateResultChecks()
            }
            requireCheck(connectionDigest() == baseline, "connection_store_changed")
            report.putBoolean("connection_store_preserved", true)
            report.putString("status", "passed")
            result = Activity.RESULT_OK
        } catch (failure: CheckFailure) {
            report.putString("status", "failed")
            report.putString("failure", failure.code)
        } catch (failure: Throwable) {
            report.putString("status", "failed")
            report.putString("failure", "operation_failed")
            report.putString("error_type", failure.javaClass.simpleName)
        } finally {
            report.putString("stage", stage)
            val cleaned = runCatching { cleanup() }.getOrDefault(false)
            report.putBoolean("test_resources_removed", cleaned)
            if (!cleaned) {
                report.putString("status", "failed")
                report.putString("failure", "cleanup_failed")
                result = Activity.RESULT_CANCELED
            }
            report.putString("stream", "\nFile download: ${report.getString("status")} (${report.getString("stage")})\n")
            finish(result, report)
        }
    }

    private fun readFixtures() {
        fixtureRoot = arguments.getString("fixturePath").orEmpty().trimEnd('/')
        requireCheck(fixtureRoot.startsWith("/home/codex/.local/share/codex-mobile/file-download-qa-") &&
            !fixtureRoot.contains("..") && !fixtureRoot.contains('\u0000'), "invalid_fixture_root")
        val encoded = arguments.getString("fixtureManifest").orEmpty()
        requireCheck(encoded.length in 1..16_384, "fixture_manifest_required")
        val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        val rows = json.getJSONArray("files")
        fixtures = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            Fixture(row.getString("name"), row.getString("mime"), row.getLong("size"), row.getString("sha256"))
        }
        requireCheck(fixtures.size in 4..12 && fixtures.all {
            it.name.startsWith("qa-") && !it.name.contains('/') && !it.name.contains('\\') &&
                !it.name.contains('\u0000') && it.size in 0..128L * 1024 * 1024 &&
                it.sha256.matches(Regex("[0-9a-f]{64}"))
        }, "invalid_fixture_manifest")
        requireCheck(fixtures.any { it.size > 32L * 1024 * 1024 } && fixtures.any { it.size == 0L } &&
            fixtures.any { it.mime == "application/pdf" } && fixtures.any { it.name.endsWith(".docx") },
            "missing_required_fixture")
    }

    private fun connect(store: ConnectionStore, saved: SavedConnection) {
        stage = "connect_read_only"
        val request = (store.loadCredential(saved.id) ?: throw CheckFailure("credential_missing")).use { credential ->
            onMain { repo!!.connect(saved.profile, credential) }
        }
        request.get(75, TimeUnit.SECONDS)
        requireCheck(onMain { repo!!.state.connected }, "connection_not_ready")
    }

    private fun markdownChecks() {
        stage = "markdown_file_links"
        val cases = listOf(
            "[网页](https://example.com/doc?q=1)" to "https://example.com/doc?q=1",
            "[下载](/home/codex/qa-下载 (中文).pdf)" to "/home/codex/qa-下载 (中文).pdf",
            "[下载](</home/codex/qa-下载 (中文).pdf>)" to "/home/codex/qa-下载 (中文).pdf",
            "[下载](/home/codex/qa-下载 \\(中文\\).pdf)" to "/home/codex/qa-下载 (中文).pdf",
        )
        for ((source, expected) in cases) {
            val parsed = inlineMarkdown(source)
            val links = parsed.getStringAnnotations("url", 0, parsed.length)
            requireCheck(links.size == 1 && links.single().item == expected, "markdown_destination_mismatch")
        }
        val malformed = "[下载](</home/codex/未闭合.pdf)"
        val preserved = inlineMarkdown(malformed)
        requireCheck(preserved.text == malformed && preserved.getStringAnnotations("url", 0, preserved.length).isEmpty(),
            "malformed_markdown_not_preserved")
        requireCheck(markdownInlineSmokeChecks().isEmpty(), "markdown_smoke_failed")
        requireCheck(userMessageWrapperSmokeChecks().isEmpty(), "message_wrapper_smoke_failed")
        report.putBoolean("markdown_destinations_and_smoke", true)
    }

    private fun streamingChecks() {
        stage = "stream_all_fixtures"
        for ((index, fixture) in fixtures.withIndex()) {
            val file = File(scratch, "stream-$index")
            val progressValid = AtomicBoolean(true)
            val progressCount = AtomicLong(0)
            val previous = AtomicLong(-1)
            val future = onMain { repo!!.downloadFile(remote(fixture), file, { false }) { done, total ->
                if (total != fixture.size || done < previous.getAndSet(done) || done !in 0..total)
                    progressValid.set(false)
                progressCount.incrementAndGet()
            } }
            val size = future.get(180, TimeUnit.SECONDS)
            requireCheck(size == fixture.size && file.length() == fixture.size, "stream_size_mismatch")
            requireCheck(file.inputStream().use(::hash) == fixture.sha256, "stream_sha_mismatch")
            requireCheck(progressValid.get() && progressCount.get() > 0 && previous.get() == fixture.size,
                "stream_progress_invalid")
            requireCheck(file.delete(), "stream_fixture_cleanup_failed")
        }
        report.putInt("streamed_fixture_count", fixtures.size)
        report.putLong("largest_fixture_bytes", fixtures.maxOf { it.size })
        report.putBoolean("all_stream_sha256_match", true)
        report.putBoolean("empty_file_supported", true)
        report.putBoolean("progress_monotonic", true)
    }

    private fun cancellationAndErrorChecks() {
        stage = "cancel_partial_download"
        val partial = File(scratch, "cancelled-partial")
        val cancel = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val large = fixtures.maxBy { it.size }
        val future = onMain { repo!!.downloadFile(remote(large), partial, cancel::get) { done, _ ->
            if (done > 0) { started.set(true); cancel.set(true) }
        } }
        expectFailure(future, "cancelled_download_succeeded")
        requireCheck(started.get() && !partial.exists(), "cancelled_partial_retained")
        report.putBoolean("cancelled_partial_removed", true)

        stage = "reject_missing_and_directory"
        for ((index, path) in listOf("$fixtureRoot/qa-does-not-exist-${UUID.randomUUID()}", "$fixtureRoot/qa-folder").withIndex()) {
            val destination = File(scratch, "invalid-$index")
            val invalid = onMain { repo!!.downloadFile(path, destination, { false }) { _, _ -> } }
            expectFailure(invalid, "invalid_remote_path_succeeded")
            requireCheck(!destination.exists(), "invalid_partial_retained")
        }
        report.putBoolean("missing_file_rejected", true)
        report.putBoolean("directory_rejected", true)
    }

    private fun disconnectedDownloadCheck() {
        stage = "disconnect_during_download"
        val partial = File(scratch, "disconnected-partial")
        val disconnected = AtomicBoolean(false)
        val future = onMain { repo!!.downloadFile(remote(fixtures.maxBy { it.size }), partial, { false }) { done, _ ->
            if (done > 0 && disconnected.compareAndSet(false, true)) onMain { repo!!.disconnect() }
        } }
        expectFailure(future, "disconnected_download_succeeded")
        requireCheck(disconnected.get() && !partial.exists(), "disconnected_partial_retained")
        report.putBoolean("disconnect_invalidates_download", true)
    }

    private fun coordinatorChecks() {
        stage = "preview_and_media_store_save"
        requireCheck(android.os.Build.VERSION.SDK_INT >= 29, "media_store_test_requires_android_q")
        val errors = mutableListOf<String>()
        onMain {
            coordinator = RemoteFileCoordinator(targetContext, { "download-qa-owner" },
                { path, destination, cancelled, progress -> repo!!.downloadFile(path, destination, cancelled, progress) },
                { errors += it })
        }
        val files = requireNotNull(coordinator)
        for (fixture in fixtures.filter { it.mime == "application/pdf" || it.name.endsWith(".docx") }) {
            requireCheck(remoteFileMimeType(fixture.name) == fixture.mime, "document_mime_mismatch")
            onMain {
                files.open(remote(fixture))
                requireCheck(files.state?.phase == RemoteFilePhase.Loading, "preview_loading_not_immediate")
            }
            waitUntil(30_000, "document_preview_timeout") { onMain { files.state?.phase == RemoteFilePhase.Ready } }
            onMain {
                val state = requireNotNull(files.state)
                requireCheck(state.text == null, "binary_document_decoded_as_text")
                requireCheck(state.size == fixture.size && state.displayName == fixture.name, "document_metadata_mismatch")
                files.saveToDownloads()
                files.saveToDownloads() // Rapid repeated taps must not create two saved files.
            }
            waitUntil(30_000, "media_store_save_timeout") { onMain { files.notices.isNotEmpty() } }
            val notices = onMain { files.notices.toList() }
            savedUris += notices.map { it.file.uri }.filter { it !in savedUris }
            val notice = notices.singleOrNull() ?: throw CheckFailure("duplicate_save_notice")
            requireCheck(notice.file.mimeType == fixture.mime, "saved_mime_mismatch")
            requireCheck(targetContext.contentResolver.getType(notice.file.uri) == fixture.mime, "media_store_mime_mismatch")
            requireCheck(targetContext.contentResolver.openInputStream(notice.file.uri)?.use(::hash) == fixture.sha256,
                "saved_content_sha_mismatch")
            val chooser = remoteFileOpenIntent(notice.file)
            @Suppress("DEPRECATION")
            val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            requireCheck(chooser.action == Intent.ACTION_CHOOSER && view?.action == Intent.ACTION_VIEW &&
                view.type == fixture.mime && view.data == notice.file.uri &&
                view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                view.clipData?.getItemAt(0)?.uri == notice.file.uri, "external_open_intent_invalid")
            requireCheck(onMain { files.state == null }, "saved_preview_not_dismissed")
            onMain { files.consumeNotice(notice.id); files.open(remote(fixture)) }
            waitUntil(15_000, "saved_open_action_not_restored") { onMain { files.state?.saved?.uri == notice.file.uri } }
            onMain { files.dismiss() }
        }
        report.putBoolean("pdf_docx_mime_and_binary_preview", true)
        report.putBoolean("media_store_sha256_match", true)
        report.putBoolean("duplicate_save_guard", true)
        report.putBoolean("saved_file_open_record_restored", true)
        report.putBoolean("external_open_chooser_read_grant", true)
        report.putBoolean("save_closes_preview", true)

        stage = "extensionless_image_preview"
        val image = fixtures.firstOrNull { it.name == "qa-image" && it.mime == "image/png" }
            ?: throw CheckFailure("extensionless_image_fixture_required")
        onMain { files.open(remote(image)) }
        waitUntil(15_000, "extensionless_image_preview_timeout") { onMain { files.state?.phase == RemoteFilePhase.Ready } }
        requireCheck(onMain { files.state?.image != null && files.state?.mimeType == "image/png" && files.state?.text == null },
            "extensionless_image_not_recognized")
        onMain { files.dismiss() }
        report.putBoolean("extensionless_image_preview_preserved", true)

        stage = "text_and_error_preview"
        for (fixture in fixtures.filter { it.mime == "text/plain" }) {
            onMain { files.open(remote(fixture)) }
            waitUntil(15_000, "text_preview_timeout") { onMain { files.state?.phase == RemoteFilePhase.Ready } }
            requireCheck(onMain { files.state?.text != null }, "text_preview_missing")
            if (fixture.size == 0L) requireCheck(onMain { files.state?.text == "" }, "empty_text_preview_not_empty")
            onMain { files.dismiss() }
        }
        onMain { files.open("$fixtureRoot/qa-preview-does-not-exist") }
        waitUntil(15_000, "error_preview_timeout") { onMain { files.state?.phase == RemoteFilePhase.Error } }
        requireCheck(onMain { !files.state?.error.isNullOrBlank() }, "error_preview_message_missing")
        onMain { files.dismiss() }
        requireCheck(onMain { errors.isEmpty() }, "unexpected_save_error")
        report.putBoolean("text_empty_and_error_states", true)
        onMain { files.close(); coordinator = null }
    }

    private data class PendingRead(
        val destination: File,
        val cancelled: () -> Boolean,
        val future: CompletableFuture<Long> = CompletableFuture(),
    )

    private fun lateResultChecks() {
        stage = "late_result_and_owner_isolation"
        var owner = "qa-owner-a"
        val pending = mutableListOf<PendingRead>()
        val unexpectedError = AtomicBoolean(false)
        onMain {
            coordinator = RemoteFileCoordinator(targetContext, { owner }, { _, destination, cancelled, _ ->
                PendingRead(destination, cancelled).also { pending += it }.future
            }, { unexpectedError.set(true) })
        }
        val files = requireNotNull(coordinator)
        onMain {
            val longName = "qa-" + "document".repeat(27) + ".pdf"
            files.open("$fixtureRoot/$longName")
            requireCheck(files.state?.displayName == longName &&
                remoteFileMimeType(requireNotNull(files.state).displayName) == "application/pdf", "long_filename_extension_lost")
            files.open("$fixtureRoot/qa-current.txt")
            requireCheck(pending.size == 2 && pending[0].cancelled(), "superseded_request_not_cancelled")
        }
        completeRead(pending[0], "stale")
        waitUntil(5_000, "stale_cache_not_removed") { !pending[0].destination.exists() }
        requireCheck(onMain { files.state?.path == "$fixtureRoot/qa-current.txt" && files.state?.phase == RemoteFilePhase.Loading },
            "stale_result_replaced_current_file")
        completeRead(pending[1], "current")
        waitUntil(5_000, "current_fixture_not_ready") { onMain { files.state?.phase == RemoteFilePhase.Ready } }
        requireCheck(onMain { files.state?.text == "current" }, "current_text_mismatch")
        onMain { owner = "qa-owner-b"; files.onOwnerChanged() }
        requireCheck(onMain { files.state == null && files.notices.isEmpty() }, "owner_change_retained_preview")
        requireCheck(!pending[1].destination.exists(), "owner_change_retained_cache")
        onMain { files.open("$fixtureRoot/qa-dismissed.txt"); files.dismiss() }
        requireCheck(pending[2].cancelled(), "dismiss_did_not_cancel_request")
        completeRead(pending[2], "dismissed")
        waitUntil(5_000, "dismissed_cache_not_removed") { !pending[2].destination.exists() }
        requireCheck(onMain { files.state == null && files.notices.isEmpty() }, "late_result_reopened_preview")
        onMain { files.open("$fixtureRoot/qa-previous-owner.txt"); owner = "qa-owner-c"; files.onOwnerChanged() }
        requireCheck(pending[3].cancelled(), "owner_change_did_not_cancel_request")
        completeRead(pending[3], "previous owner")
        waitUntil(5_000, "previous_owner_cache_not_removed") { !pending[3].destination.exists() }
        requireCheck(onMain { files.state == null && files.notices.isEmpty() }, "previous_owner_result_reopened_preview")
        requireCheck(!unexpectedError.get(), "unexpected_race_error")
        onMain { files.close(); coordinator = null }
        report.putBoolean("late_results_ignored", true)
        report.putBoolean("dismiss_cancels_and_removes_cache", true)
        report.putBoolean("owner_change_clears_preview", true)
        report.putBoolean("long_filename_extension_preserved", true)
    }

    private fun completeRead(read: PendingRead, text: String) {
        // Deliberately emulate an adapter which completes after cancellation. The coordinator
        // must still reject this result and remove its unique cache file.
        read.destination.parentFile?.mkdirs()
        read.destination.writeText(text)
        read.future.complete(read.destination.length())
    }

    private fun uiPreview() {
        stage = "ui_preview"
        val path = arguments.getString("uiPath") ?: remote(fixtures.first { it.mime == "application/pdf" })
        requireCheck(path.startsWith("$fixtureRoot/"), "ui_path_outside_fixture_root")
        val hold = arguments.getString("holdMs", "30000").toLong().coerceIn(1_000, 60_000)
        activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val vm = onMain { ViewModelProvider(requireNotNull(activity))[RemoteViewModel::class.java] }
        waitUntil(75_000, "ui_connection_timeout") { onMain { vm.state.connected } }
        onMain { vm.files.open(path) }
        report.putString("ui_path_kind", path.substringAfterLast('.'))
        val deadline = SystemClock.elapsedRealtime() + hold
        while (SystemClock.elapsedRealtime() < deadline) {
            val uris = onMain { vm.files.notices.map { it.file.uri } }
            savedUris += uris.filter { it !in savedUris }
            SystemClock.sleep(100)
        }
        report.putString("ui_phase", onMain { vm.files.state?.phase?.name ?: "Dismissed" })
        onMain { vm.files.dismiss() }
        report.putBoolean("ui_preview_shown", true)
    }

    private fun cleanup(): Boolean {
        val pendingUris = onMain {
            val uris = coordinator?.notices?.map { it.file.uri }.orEmpty()
            coordinator?.close(); coordinator = null; repo?.close(); repo = null; activity?.finish(); activity = null
            uris
        }
        savedUris += pendingUris.filter { it !in savedUris }
        var clean = true
        for (uri in savedUris) {
            clean = runCatching { targetContext.contentResolver.delete(uri, null, null) == 1 }.getOrDefault(false) && clean
        }
        if (::scratch.isInitialized) clean = scratch.deleteRecursively() && clean
        if (baseline != null) clean = connectionDigest() == baseline && clean
        return clean
    }

    private fun connectionDigest(): String {
        val preferences = targetContext.getSharedPreferences("connections-v2", Context.MODE_PRIVATE).all.toSortedMap()
        return hash(preferences.entries.joinToString("\u0000") { (key, value) -> "$key\u0001$value" }.byteInputStream())
    }

    private fun remote(fixture: Fixture) = "$fixtureRoot/${fixture.name}"

    private fun <T> expectFailure(future: CompletableFuture<T>, failure: String) {
        var failed = false
        try { future.get(90, TimeUnit.SECONDS) } catch (_: java.util.concurrent.ExecutionException) { failed = true }
        requireCheck(failed, failure)
    }

    private fun waitUntil(timeout: Long, failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw CheckFailure(failure)
            SystemClock.sleep(50)
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        runOnMainSync { value.set(runCatching(action)) }
        return value.get().getOrThrow()
    }

    private fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireCheck(value: Boolean, code: String) { if (!value) throw CheckFailure(code) }
    private class CheckFailure(val code: String) : Exception()
}

package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import com.nanci.codexremote.ImagePipeline
import com.nanci.codexremote.ui.content.ImageLoadState
import com.nanci.codexremote.ui.content.ImageSource
import com.nanci.codexremote.ui.content.ImageSourceKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Audits the shipped ImagePipeline without server/account changes or production cache access.
 * A mutable byte fixture represents a file overwritten at the same remote path. Expected bugs
 * are recorded as reproduced, rather than making the instrumentation itself fail.
 */
class ImageCacheAuditInstrumentation : Instrumentation() {
    private val result = Bundle()
    private val pipelines = mutableListOf<ImagePipeline>()
    private var scratch: File? = null
    private var stage = "setup"

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var outcome = Activity.RESULT_CANCELED
        try {
            check(targetContext.packageName == "com.nanci.codexremote")
            scratch = File(targetContext.cacheDir, "image-cache-audit-${UUID.randomUUID()}").also { check(it.mkdir()) }
            val pngA = png(16, 8, 0xffff0000.toInt())
            val pngB = png(32, 12, 0xff0000ff.toInt())
            audit("png", pngA, pngB, "image/png")
            val svgA = "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='8'><rect width='16' height='8' fill='red'/></svg>".toByteArray()
            val svgB = "<svg xmlns='http://www.w3.org/2000/svg' width='32' height='12'><rect width='32' height='12' fill='blue'/></svg>".toByteArray()
            audit("svg", svgA, svgB, "image/svg+xml")
            result.putBoolean("production_cache_untouched", true)
            result.putBoolean("server_and_connections_untouched", true)
            result.putString("status", "passed")
            outcome = Activity.RESULT_OK
        } catch (failure: Throwable) {
            result.putString("status", "failed")
            result.putString("error_type", failure.javaClass.simpleName)
        } finally {
            pipelines.forEach { runCatching { it.close() } }
            val cleaned = scratch?.deleteRecursively() ?: true
            result.putBoolean("temporary_resources_removed", cleaned)
            if (!cleaned) { result.putString("status", "failed"); outcome = Activity.RESULT_CANCELED }
            result.putString("stage", stage)
            result.putString("stream", "\nImage cache audit: ${result.getString("status")} ($stage); bug_* fields record reproductions.\n")
            finish(outcome, result)
        }
    }

    private fun audit(format: String, bytesA: ByteArray, bytesB: ByteArray, mime: String) {
        stage = "${format}_initial_load"
        val cacheRoot = File(requireNotNull(scratch), format).also { check(it.mkdir()) }
        val isolatedContext = object : ContextWrapper(targetContext) {
            override fun getCacheDir(): File = cacheRoot
        }
        val currentBytes = AtomicReference(bytesA)
        val reads = AtomicInteger()
        fun newPipeline() = ImagePipeline(isolatedContext) { _, _, _ ->
            reads.incrementAndGet()
            CompletableFuture.completedFuture(currentBytes.get())
        }.also { pipelines += it }
        val path = "/audit-fixtures/same-path.$format"
        val sourceA = ImageSource(ImageSourceKind.RemotePath, path, mime, "message-before-edit")
        val sourceB = ImageSource(ImageSourceKind.RemotePath, path, mime, "message-after-edit")
        var pipeline = newPipeline()
        val first = awaitImage(pipeline, "owner-A", sourceA, 1024)
        val dimensionsA = dimensions(first)
        check(pipeline.original("owner-A", sourceA).get(15, TimeUnit.SECONDS).contentEquals(bytesA))
        val clipboardA = pipeline.clipboardFile("owner-A", sourceA).get(15, TimeUnit.SECONDS).readBytes()
        check(reads.get() == 1)

        stage = "${format}_same_path_after_edit"
        currentBytes.set(bytesB)
        val sameSize = awaitImage(pipeline, "owner-A", sourceB, 1024)
        val fullScreen = awaitImage(pipeline, "owner-A", sourceB, 2048)
        result.putBoolean("bug_${format}_memory_old_after_edit", sameSize.bitmap === first.bitmap || dimensions(sameSize) == dimensionsA)
        result.putBoolean("bug_${format}_ui_cache_key_does_not_refresh", reads.get() == 1)
        result.putBoolean("bug_${format}_viewer_old_after_edit", ratio(fullScreen) == ratio(first))
        result.putBoolean("bug_${format}_save_original_old_after_edit", pipeline.original("owner-A", sourceB).get(15, TimeUnit.SECONDS).contentEquals(bytesA))
        result.putInt("${format}_remote_reads_after_edit", reads.get())
        pipeline.close()

        stage = "${format}_new_pipeline_same_disk"
        pipeline = newPipeline()
        val reopened = awaitImage(pipeline, "owner-A", sourceB, 1024)
        result.putBoolean("bug_${format}_disk_old_after_pipeline_restart", dimensions(reopened) == dimensionsA && reads.get() == 1)
        pipeline.close()

        stage = "${format}_clipboard_after_byte_cache_eviction"
        // Only task-owned byte cache is evicted. Existing task-owned clipboard export stays.
        check(File(cacheRoot, "image-content-v1").deleteRecursively())
        pipeline = newPipeline()
        check(pipeline.original("owner-A", sourceB).get(15, TimeUnit.SECONDS).contentEquals(bytesB))
        val fresh = awaitImage(pipeline, "owner-A", sourceB, 1024)
        check(ratio(fresh) != ratio(first))
        val clipboardAfterRefresh = pipeline.clipboardFile("owner-A", sourceB).get(15, TimeUnit.SECONDS).readBytes()
        result.putBoolean("bug_${format}_clipboard_old_after_content_refresh", clipboardAfterRefresh.contentEquals(clipboardA))
        result.putString("${format}_original_A_sha256", hash(bytesA))
        result.putString("${format}_original_B_sha256", hash(bytesB))
        result.putString("${format}_clipboard_before_sha256", hash(clipboardA))
        result.putString("${format}_clipboard_after_sha256", hash(clipboardAfterRefresh))

        stage = "${format}_controls"
        val nextPath = ImageSource(ImageSourceKind.RemotePath, "/audit-fixtures/new-path.$format", mime, "new-path")
        check(pipeline.original("owner-A", nextPath).get(15, TimeUnit.SECONDS).contentEquals(bytesB))
        check(ratio(awaitImage(pipeline, "owner-A", nextPath, 1024)) == ratio(fresh))
        currentBytes.set(bytesA)
        check(pipeline.original("owner-B", sourceB).get(15, TimeUnit.SECONDS).contentEquals(bytesA))
        check(pipeline.original("owner-A", sourceB).get(15, TimeUnit.SECONDS).contentEquals(bytesB))
        fun data(bytes: ByteArray) = ImageSource(ImageSourceKind.DataUri, "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), mime, "same-ui-key")
        val inlineA = awaitImage(pipeline, "owner-A", data(bytesA), 1024)
        val inlineB = awaitImage(pipeline, "owner-A", data(bytesB), 1024)
        check(ratio(inlineA) != ratio(inlineB))
        result.putBoolean("control_${format}_new_path_refreshes", true)
        result.putBoolean("control_${format}_owners_are_isolated", true)
        result.putBoolean("control_${format}_inline_changed_bytes_refresh", true)
        result.putString("${format}_dimensions_A", dimensionsA)
        result.putString("${format}_dimensions_B", dimensions(fresh))
        pipeline.close()
    }

    private fun awaitImage(pipeline: ImagePipeline, owner: String, source: ImageSource, edge: Int): ImageLoadState.Ready {
        val ready = CompletableFuture<ImageLoadState>()
        runOnMainSync { pipeline.scoped(owner).load(source, edge) { ready.complete(it) } }
        return ready.get(20, TimeUnit.SECONDS) as? ImageLoadState.Ready ?: error("image_load_failed")
    }

    private fun dimensions(state: ImageLoadState.Ready) = "${state.bitmap.width}x${state.bitmap.height}"
    private fun ratio(state: ImageLoadState.Ready) = state.bitmap.width.toDouble() / state.bitmap.height
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray() }
        } finally { bitmap.recycle() }
    }
}

package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.ImagePipeline
import com.nanci.codexremote.ImageViewer
import com.nanci.codexremote.MainActivity
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.core.CodexRepository
import com.nanci.codexremote.ui.content.*
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses task-owned server fixtures and the production SFTP, decoder, Markdown and image view.
 * No remote chat is created/modified. Run with fixturePath, optional svgName/pngName and holdSeconds.
 * All temporary bitmap/disk caches belong to a dedicated ContextWrapper cache directory.
 */
class InlineImageInstrumentation : Instrumentation() {
    private var args = Bundle()
    private val result = Bundle()
    private var stage = "setup"
    private var repo: CodexRepository? = null
    private var pipeline: ImagePipeline? = null
    private var activity: MainActivity? = null
    private var scratch: File? = null
    private var baseline: String? = null
    private val runId = UUID.randomUUID().toString()
    private lateinit var root: String
    private lateinit var svgPath: String
    private lateinit var pngPath: String
    private lateinit var svg: ByteArray
    private lateinit var png: ByteArray
    private val seenImages = ConcurrentHashMap<String, String>()
    private val unexpectedSources = ConcurrentHashMap.newKeySet<String>()
    private val openedByClick = AtomicReference<String?>()
    private val saveButtonName = AtomicReference<String?>()

    override fun onCreate(arguments: Bundle?) {
        args = Bundle(arguments ?: Bundle())
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var outcome = Activity.RESULT_CANCELED
        try {
            check(targetContext.packageName == "com.nanci.codexremote")
            baseline = connectionDigest()
            root = args.getString("fixturePath").orEmpty().trimEnd('/')
            check(root.startsWith("/home/codex/.local/share/codex-mobile/inline-image-qa-") &&
                ".." !in root && root.none { it == '\u0000' || it == '\n' || it == '\r' })
            fun fixtureName(key: String, fallback: String): String = args.getString(key, fallback).orEmpty().also {
                check(it.startsWith("qa-") && it.length < 180 && it.none { c -> c in "/\\\u0000\n\r" })
            }
            svgPath = "$root/${fixtureName("svgName", "qa-log.svg")}"
            pngPath = "$root/${fixtureName("pngName", "qa-log.png")}"
            scratch = File(targetContext.cacheDir, "inline-image-qa-$runId").also { check(it.mkdir()) }
            val cacheContext = object : ContextWrapper(targetContext) {
                override fun getCacheDir(): File = requireNotNull(scratch)
            }
            val store = ConnectionStore(targetContext)
            val connection = store.list().firstOrNull { it.id == store.activeId && it.hasCredential }
                ?: error("saved_connection_required")
            stage = "connect_and_read_fixtures"
            onMain { repo = CodexRepository() }
            val connecting = requireNotNull(store.loadCredential(connection.id)).use {
                onMain { requireNotNull(repo).connect(connection.profile, it) }
            }
            connecting.get(75, TimeUnit.SECONDS)
            svg = onMain { requireNotNull(repo).readFile(svgPath, ImagePipeline.MAX_IMAGE_BYTES) }.get(45, TimeUnit.SECONDS)
            png = onMain { requireNotNull(repo).readFile(pngPath, ImagePipeline.MAX_IMAGE_BYTES) }.get(45, TimeUnit.SECONDS)
            check(svg.isNotEmpty() && png.isNotEmpty())
            args.getString("svgSha256")?.let { check(hash(svg) == it) }
            args.getString("pngSha256")?.let { check(hash(png) == it) }
            result.putString("svg_sha256", hash(svg)); result.putString("png_sha256", hash(png))
            pipeline = ImagePipeline(cacheContext) { owner, path, limit ->
                when (owner) {
                    "fixture-cache-A:$runId" -> CompletableFuture.completedFuture(svg)
                    "fixture-cache-B:$runId" -> CompletableFuture.completedFuture(png)
                    else -> {
                        check(path == svgPath || path == pngPath) { "unexpected_fixture_path" }
                        onMain { requireNotNull(repo).readFile(path, limit) }
                    }
                }
            }
            decoderChecks()
            markdownChecks()
            uiChecks()
            check(connectionDigest() == baseline) { "connection_store_changed" }
            result.putBoolean("connection_store_preserved", true)
            result.putBoolean("remote_chat_mutations", false)
            result.putString("status", "passed")
            outcome = Activity.RESULT_OK
        } catch (failure: Throwable) {
            result.putString("status", "failed")
            result.putString("error_type", failure.javaClass.simpleName)
            // Do not print arbitrary protocol messages or credentials from underlying exceptions.
        } finally {
            result.putString("stage", stage)
            val cleaned = runCatching {
                onMain { activity?.finish(); activity = null; repo?.close(); repo = null }
                pipeline?.close(); pipeline = null
                val removed = scratch?.deleteRecursively() ?: true
                removed && (baseline == null || connectionDigest() == baseline)
            }.getOrDefault(false)
            result.putBoolean("temporary_resources_removed", cleaned)
            if (!cleaned) { result.putString("status", "failed"); outcome = Activity.RESULT_CANCELED }
            result.putString("stream", "\nInline SVG/PNG: ${result.getString("status")} ($stage)\n")
            finish(outcome, result)
        }
    }

    private fun decoderChecks() {
        stage = "svg_png_decode_and_safety"
        check(ImagePipeline.extension(svg) == "svg")
        check(ImagePipeline.extension(png) == "png")
        for (bytes in listOf(svg, png)) {
            val bitmap = ImagePipeline.decode(bytes, 1024)
            check(bitmap.width in 1..1024 && bitmap.height in 1..1024)
            bitmap.recycle()
        }
        val b64 = ImageSource(ImageSourceKind.DataUri,
            "data:image/svg+xml;base64," + Base64.encodeToString(svg, Base64.NO_WRAP), "image/svg+xml", "b64-$runId")
        val encoded = URLEncoder.encode(String(svg, Charsets.UTF_8), "UTF-8").replace("+", "%20")
        val percent = ImageSource(ImageSourceKind.DataUri, "data:image/svg+xml,$encoded", "image/svg+xml", "percent-$runId")
        check(awaitImage(b64) is ImageLoadState.Ready)
        check(awaitImage(percent) is ImageLoadState.Ready)
        check(requireNotNull(pipeline).original("qa:$runId", b64).get(20, TimeUnit.SECONDS).contentEquals(svg))
        val exported = requireNotNull(pipeline).clipboardFile("qa:$runId", b64).get(20, TimeUnit.SECONDS)
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(exported.absolutePath, header)
        check(exported.extension == "png" && header.outMimeType == "image/png" && header.outWidth > 0)
        val escapedText = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 40'><text x='2' y='25'>&#8722; &#x20; &amp;</text></svg>"
        ImagePipeline.decode(escapedText.toByteArray(), 256).also { check(it.width > 0 && it.height > 0); it.recycle() }
        val rejected = listOf(
            "<!DOCTYPE svg [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><svg xmlns='http://www.w3.org/2000/svg' width='20' height='20'><text>&xxe;</text></svg>",
            "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20'><image href='https://example.invalid/private.png' width='20' height='20'/></svg>",
        )
        rejected.forEach { document ->
            var failed = false
            try { ImagePipeline.decode(document.toByteArray(), 128) } catch (_: Exception) { failed = true }
            check(failed) { "unsafe_svg_accepted" }
        }
        val shared = ImageSource(ImageSourceKind.RemotePath, "$root/qa-owner-cache.img", cacheKey = "same-$runId")
        check(awaitImage(shared, "fixture-cache-A:$runId") is ImageLoadState.Ready)
        check(awaitImage(shared, "fixture-cache-B:$runId") is ImageLoadState.Ready)
        check(requireNotNull(pipeline).original("fixture-cache-A:$runId", shared).get(20, TimeUnit.SECONDS).contentEquals(svg))
        check(requireNotNull(pipeline).original("fixture-cache-B:$runId", shared).get(20, TimeUnit.SECONDS).contentEquals(png))
        result.putBoolean("svg_png_and_both_data_uri_encodings", true)
        result.putBoolean("unsafe_svg_rejected", true)
        result.putBoolean("svg_original_and_png_clipboard_export", true)
        result.putBoolean("image_cache_owner_isolation", true)
    }

    private fun markdownChecks() {
        stage = "markdown_destinations"
        val expected = "$root/qa-image (space).png"
        val paths = listOf(
            "./qa-image (space).png",
            "sandbox:$root/qa-image%20(space).png",
            "file://$root/qa-image%20(space).png",
        )
        paths.forEach { check(resolveMarkdownImageSource(it, "qa", root)?.value == expected) }
        check(resolveMarkdownImageSource("https://example.com/image.png", "qa", root)?.kind == ImageSourceKind.HttpUrl)
        check(resolveMarkdownImageSource("javascript:alert(1)", "qa", root) == null)
        check(resolveMarkdownImageSource("file://other-host/private.png", "qa", root) == null)
        check(markdownInlineSmokeChecks().isEmpty())
        result.putBoolean("markdown_paths_and_code_guard", true)
    }

    private fun uiChecks() {
        stage = "production_inline_image_ui"
        val loader = object : ImageLoader {
            private val delegate = requireNotNull(pipeline).scoped("fixture-server:$runId")
            override fun load(source: ImageSource, maxEdge: Int, onResult: (ImageLoadState) -> Unit): () -> Unit {
                if (source.value != svgPath && source.value != pngPath) unexpectedSources += source.value
                return delegate.load(source, maxEdge) { state ->
                    if (state is ImageLoadState.Ready) seenImages[source.value] = "${state.bitmap.width}x${state.bitmap.height}"
                    if (state is ImageLoadState.Error) seenImages[source.value] = "ERROR"
                    onResult(state)
                }
            }
        }
        val message = JSONObject().put("id", "inline-image-check-$runId").put("type", "agentMessage").put("text",
            "SVG 对数函数图：\n\n![SVG 对数函数图](<$svgPath>)\n\nPNG 对数函数图：\n\n![PNG 对数函数图](<./${pngPath.substringAfterLast('/')}>)\n\n" +
                "[下载图片](<$pngPath>)\n\n`![代码示例]($root/qa-must-not-read.png)`\n\n```text\n![代码块]($root/qa-must-not-read.svg)\n```")
        activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        onMain {
            requireNotNull(activity).setContent {
                MaterialTheme(colorScheme = lightColorScheme(background = Color(0xFFFAFAFA))) {
                    var opened by remember { mutableStateOf<ImageSource?>(null) }
                    Column(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).systemBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(20.dp)) {
                        MessageContent(message, imageLoader = loader, onOpenImage = {
                            opened = it; openedByClick.set(it.value)
                        }, imageBaseDirectory = root)
                    }
                    opened?.let { source -> ImageViewer(source, loader, onClose = {
                        opened = null; openedByClick.set(null)
                    }, onSave = { saveButtonName.set(it) }) }
                }
            }
        }
        waitUntil(40_000) { seenImages.keys.containsAll(listOf(svgPath, pngPath)) }
        check(seenImages[svgPath] != "ERROR" && seenImages[pngPath] != "ERROR")
        check(unexpectedSources.isEmpty()) { "code_or_plain_link_loaded_as_image" }
        result.putString("svg_dimensions", seenImages[svgPath]); result.putString("png_dimensions", seenImages[pngPath])
        result.putBoolean("production_markdown_images_ready", true)
        val screenshots = File(requireNotNull(targetContext.getExternalFilesDir(null)), "inline-sftp-$runId")
            .also { check(it.mkdir()) { "screenshot_directory_failed" } }
        result.putString("screenshots_directory", screenshots.absolutePath)
        result.putString("chat_screenshot", saveScreenshot(screenshots, "chat.png").absolutePath)
        viewerClickCheck("svg", "SVG 对数函数图", svgPath, screenshots)
        viewerClickCheck("png", "PNG 对数函数图", pngPath, screenshots)
        stage = "production_inline_image_ui_verified"
        sendStatus(1, Bundle().apply { putString("stream", "\nINLINE_IMAGE_UI_READY\n") })
        val hold = args.getString("holdSeconds", "0").orEmpty().toLong().coerceIn(0, 60)
        val deadline = SystemClock.elapsedRealtime() + hold * 1000
        while (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(unexpectedSources.isEmpty())
    }

    /** Exercise the actual Compose click route; no direct assignment opens the viewer. */
    private fun viewerClickCheck(format: String, label: String, path: String, screenshots: File) {
        stage = "${format}_click_opens_production_viewer"
        saveButtonName.set(null)
        clickImageWithScroll(label)
        waitUntil(30_000) {
            openedByClick.get() == path && findUiNode {
                it.contentDescription?.toString() == "保存图片" && it.isEnabled && it.isVisibleToUser
            } != null
        }
        check(findUiNode { it.text?.toString() == "关闭" && it.isVisibleToUser } != null) { "viewer_close_button_missing" }
        result.putString("${format}_viewer_screenshot", saveScreenshot(screenshots, "$format-viewer.png").absolutePath)
        val saveButton = requireNotNull(findUiNode { it.contentDescription?.toString() == "保存图片" && it.isEnabled })
        check(clickUiNode(saveButton)) { "viewer_save_click_failed" }
        waitUntil(5_000) { saveButtonName.get() != null }
        check(saveButtonName.get()?.substringAfterLast('.') == format) { "viewer_save_filename_wrong_format" }
        result.putString("${format}_save_button_filename", saveButtonName.get())
        val closeButton = requireNotNull(findUiNode { it.text?.toString() == "关闭" && it.isVisibleToUser })
        check(clickUiNode(closeButton)) { "viewer_close_click_failed" }
        waitUntil(5_000) {
            openedByClick.get() == null && findUiNode { it.contentDescription?.toString() == "保存图片" } == null
        }
        result.putBoolean("${format}_actual_click_viewer_save_close", true)
        // Saving is a fixture callback; this test does not create user Downloads or launch SAF.
        result.putBoolean("${format}_save_callback_verified_without_writing_downloads", true)
    }

    private fun clickImageWithScroll(label: String) {
        repeat(12) {
            waitForIdleSync()
            val image = findUiNode { it.contentDescription?.toString() == label && it.isVisibleToUser }
            if (image != null) {
                val bounds = Rect().also(image::getBoundsInScreen)
                val metrics = targetContext.resources.displayMetrics
                if (bounds.width() > 0 && bounds.height() > 0 && bounds.centerX() in 0 until metrics.widthPixels &&
                    bounds.centerY() in 0 until metrics.heightPixels && clickUiNode(image)) return
                image.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            }
            val scroll = findUiNode { it.isScrollable && it.isVisibleToUser }
            check(scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) { "inline_image_not_reachable" }
            SystemClock.sleep(180)
        }
        error("inline_image_click_timeout")
    }

    private fun findUiNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> walk(child)?.let { return it } }
            return null
        }
        return uiAutomation.rootInActiveWindow?.let(::walk)
    }

    private fun clickUiNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isEnabled && candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return false
    }

    private fun saveScreenshot(directory: File, name: String): File {
        waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = requireNotNull(uiAutomation.takeScreenshot()) { "screenshot_unavailable" }
        val file = File(directory, name)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        check(file.isFile && file.length() > 0) { "screenshot_empty" }
        return file
    }

    private fun awaitImage(source: ImageSource, owner: String = "qa:$runId"): ImageLoadState {
        val future = CompletableFuture<ImageLoadState>()
        val cancel = onMain { requireNotNull(pipeline).scoped(owner).load(source, 1024) { future.complete(it) } }
        return try { future.get(30, TimeUnit.SECONDS) } finally { onMain(cancel) }
    }

    private fun connectionDigest(): String = hash(targetContext.getSharedPreferences("connections-v2", Context.MODE_PRIVATE)
        .all.toSortedMap().entries.joinToString("\u0000") { "${it.key}\u0001${it.value}" }.toByteArray())
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "timeout" }; SystemClock.sleep(50) }
    }
    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        runOnMainSync { value.set(runCatching(action)) }
        return value.get().getOrThrow()
    }
}

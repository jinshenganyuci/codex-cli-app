package com.nanci.codexremote

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nanci.codexremote.ui.content.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Debug only. The two real image fixtures are supplied to filesDir after installation. */
class ImageChecksActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val checksWorker = Executors.newSingleThreadExecutor()
    private val fileWorkers = Executors.newFixedThreadPool(2)
    private val disposed = AtomicBoolean(false)
    private val runId = UUID.randomUUID().toString()
    private val owner = "debug-image-owner-A:$runId"
    private val secondOwner = "debug-image-owner-B:$runId"
    private val prefix = "/__debug_images/$runId"
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val remoteOnMain = AtomicBoolean(false)
    private val cancelledReadStarted = CountDownLatch(1)
    private val cancelledRead = CompletableFuture<ByteArray>()
    private val results = CopyOnWriteArrayList<JSONObject>()
    private val interactions = CopyOnWriteArrayList<JSONObject>()
    private lateinit var pipeline: ImagePipeline
    private var fixtures by mutableStateOf<Fixtures?>(null)
    private var status by mutableStateOf("正在后台读取实际图片并执行检查…")
    private var feedback by mutableStateOf("")
    private var pendingExport: ImageSource? = null
    private var reportStatus = "running"

    private data class Fixtures(val generated: JSONObject, val photo: JSONObject, val placeholder: JSONObject, val retry: JSONObject)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pipeline = ImagePipeline(this, ::readFixture)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0D0D0D), background = Color(0xFFFAFAFA))) {
                CheckScreen()
            }
        }
        checksWorker.execute { runChecks() }
    }

    private fun fixture(name: String) = File(filesDir, "image-fixtures/$name")
    private fun source(path: String) = ImageSource(ImageSourceKind.RemotePath, "$prefix/$path", cacheKey = "$prefix/$path")

    /** Production pipeline calls this adapter on its worker, never on Compose. No network is mocked as success. */
    private fun readFixture(requestOwner: String, path: String, maxBytes: Int): CompletableFuture<ByteArray> {
        if (Looper.myLooper() == Looper.getMainLooper()) remoteOnMain.set(true)
        val attempt = counters.computeIfAbsent("$requestOwner:$path") { AtomicInteger() }.incrementAndGet()
        if (path == "$prefix/cancel.jpg") { cancelledReadStarted.countDown(); return cancelledRead }
        return CompletableFuture.supplyAsync({
            check(Looper.myLooper() != Looper.getMainLooper())
            require(path.startsWith("$prefix/"))
            when (path.substringAfterLast('/')) {
                "oversize.jpg" -> ByteArray(maxBytes + 1)
                "repair.jpg" -> if (attempt == 1) "not an image yet".toByteArray() else fixture("photo.jpg").readBytes()
                "network-retry.jpg", "ui-retry.jpg" -> if (attempt == 1) throw IOException("Debug fixture: first read unavailable") else fixture("photo.jpg").readBytes()
                "same-path.img" -> fixture(if (requestOwner == secondOwner) "photo.jpg" else "generated.png").readBytes()
                else -> fixture("photo.jpg").readBytes()
            }
        }, fileWorkers)
    }

    private fun runChecks() {
        try {
            val png = fixture("generated.png").readBytes()
            val jpeg = fixture("photo.jpg").readBytes()
            require(png.isNotEmpty() && jpeg.isNotEmpty())
            val encoded = Base64.encodeToString(png, Base64.NO_WRAP)
            val raw = ImageSource(ImageSourceKind.GeneratedBase64, encoded, "image/png", "generated:$runId")
            val display = Fixtures(
                JSONObject().put("id", "fixture-generated").put("type", "imageGeneration").put("status", "completed").put("result", encoded),
                JSONObject().put("id", "fixture-user-photo").put("type", "userMessage").put("content", JSONArray().put(JSONObject().put("type", "localImage").put("path", "$prefix/ui-photo.jpg"))),
                JSONObject().put("id", "fixture-generating").put("type", "imageGeneration").put("status", "inProgress"),
                JSONObject().put("id", "fixture-retry").put("type", "imageView").put("path", "$prefix/ui-retry.jpg"),
            )
            main.post { if (!disposed.get()) fixtures = display }

            checkCase("raw_png_base64_and_main_callback") {
                val loaded = awaitLoad(raw)
                val bitmap = requireReady(loaded)
                require(bitmap.width > 0 && bitmap.height > 0 && maxOf(bitmap.width, bitmap.height) <= 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                val expectedLongEdge = minOf(1024, maxOf(bounds.outWidth, bounds.outHeight))
                require(maxOf(bitmap.width, bitmap.height) == expectedLongEdge)
                require(kotlin.math.abs(bitmap.width.toDouble() / bitmap.height - bounds.outWidth.toDouble() / bounds.outHeight) < .01)
                "Actual PNG decoded from raw base64; ${bitmap.width}x${bitmap.height}; callback on main"
            }
            checkCase("remote_jpeg_background_read") {
                val bitmap = requireReady(awaitLoad(source("remote.jpg")))
                require(!remoteOnMain.get())
                "Actual JPEG via asynchronous remote adapter; ${bitmap.width}x${bitmap.height}; adapter and file IO off main"
            }
            checkCase("cache_isolated_between_owners") {
                val shared = source("same-path.img")
                requireReady(awaitLoad(shared, owner))
                requireReady(awaitLoad(shared, secondOwner))
                val first = pipeline.original(owner, shared).get(10, TimeUnit.SECONDS)
                val second = pipeline.original(secondOwner, shared).get(10, TimeUnit.SECONDS)
                require(first.contentEquals(png) && second.contentEquals(jpeg) && !first.contentEquals(second))
                "Identical remote path under two owners retained distinct original fixtures"
            }
            checkCase("exif_rotate_90_and_horizontal_flip") { checkExif(jpeg) }
            checkCase("oversize_returns_error_on_main") {
                require(awaitLoad(source("oversize.jpg")) is ImageLoadState.Error)
                "Remote payload exceeding 20 MiB rejected without a Ready callback"
            }
            checkCase("cancel_suppresses_callback") {
                val callbacks = AtomicInteger()
                val cancel = AtomicReference<(() -> Unit)?>(null)
                val launched = CountDownLatch(1)
                main.post { cancel.set(pipeline.scoped(owner).load(source("cancel.jpg"), 512) { callbacks.incrementAndGet() }); launched.countDown() }
                require(launched.await(3, TimeUnit.SECONDS) && cancelledReadStarted.await(5, TimeUnit.SECONDS))
                val cancelled = CountDownLatch(1)
                main.post { cancel.get()?.invoke(); cancelled.countDown() }
                require(cancelled.await(3, TimeUnit.SECONDS))
                cancelledRead.complete(jpeg)
                // A second task must complete on the same two-worker production pool, then allow main delivery.
                pipeline.original(owner, source("remote.jpg")).get(10, TimeUnit.SECONDS)
                val quiet = CountDownLatch(1)
                main.postDelayed({ quiet.countDown() }, 600)
                require(quiet.await(3, TimeUnit.SECONDS) && callbacks.get() == 0)
                "Remote completion after cancellation did not call the UI callback"
            }
            checkCase("failed_read_then_retry") {
                val value = source("network-retry.jpg")
                require(awaitLoad(value) is ImageLoadState.Error)
                requireReady(awaitLoad(value))
                "First read failed; second read decoded the actual JPEG"
            }
            checkCase("invalid_cached_bytes_then_repair_retry") {
                val value = source("repair.jpg")
                require(awaitLoad(value) is ImageLoadState.Error)
                requireReady(awaitLoad(value))
                "Invalid first response did not poison retry after remote content became valid"
            }
            checkCase("original_and_clipboard_file_preserve_bytes") {
                val original = pipeline.original(owner, raw).get(10, TimeUnit.SECONDS)
                val file = pipeline.clipboardFile(owner, raw).get(10, TimeUnit.SECONDS)
                require(original.contentEquals(png) && file.readBytes().contentEquals(png))
                require(file.parentFile?.canonicalFile == File(cacheDir, "exports").canonicalFile)
                "Original bytes and FileProvider export file match fixture SHA256 ${sha256(png)}"
            }
            checkCase("main_thread_decode_rejected") {
                val error = AtomicReference<Throwable?>(null)
                val done = CountDownLatch(1)
                main.post { try { ImagePipeline.decode(png, 128) } catch (failure: Throwable) { error.set(failure) }; done.countDown() }
                require(done.await(3, TimeUnit.SECONDS) && error.get() is IllegalArgumentException)
                "Accidental Bitmap decode on main is explicitly rejected"
            }
            reportStatus = if (results.any { it.optString("status") == "fail" }) "failed" else "passed"
            writeReport(png, jpeg)
            main.post { if (!disposed.get()) status = "${results.count { it.optString("status") == "pass" }}/${results.size} 项 Android 图片检查通过 · $reportStatus" }
        } catch (error: Throwable) {
            reportStatus = "fixture_or_harness_error"
            results += JSONObject().put("id", "fixture_setup").put("status", "fail").put("detail", error.javaClass.simpleName + ": " + error.message.orEmpty().take(150))
            runCatching { writeReport() }
            main.post { if (!disposed.get()) status = "无法运行图片检查，请确认两个真实 fixture 文件已放入应用私有目录。" }
        }
    }

    private fun awaitLoad(value: ImageSource, imageOwner: String = owner): ImageLoadState {
        val result = AtomicReference<ImageLoadState?>()
        val callbackOnMain = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val cancel = AtomicReference<(() -> Unit)?>(null)
        main.post { cancel.set(pipeline.scoped(imageOwner).load(value, 1024) {
            callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper()); result.set(it); done.countDown()
        }) }
        if (!done.await(40, TimeUnit.SECONDS)) { cancel.get()?.invoke(); error("Image callback timed out") }
        require(callbackOnMain.get()) { "Image callback did not run on main" }
        return requireNotNull(result.get())
    }

    private fun requireReady(state: ImageLoadState) = (state as? ImageLoadState.Ready)?.bitmap
        ?: error("Expected Ready, got ${if (state is ImageLoadState.Error) state.reason else "Loading"}")

    private fun checkCase(id: String, block: () -> String) {
        val started = android.os.SystemClock.elapsedRealtime()
        try { results += JSONObject().put("id", id).put("status", "pass").put("detail", block()).put("durationMs", android.os.SystemClock.elapsedRealtime() - started) }
        catch (error: Throwable) { results += JSONObject().put("id", id).put("status", "fail").put("detail", error.javaClass.simpleName + ": " + error.message.orEmpty().take(180)).put("durationMs", android.os.SystemClock.elapsedRealtime() - started) }
    }

    private fun checkExif(jpeg: ByteArray): String {
        val file = File(cacheDir, "debug-exif-$runId.jpg")
        var base: Bitmap? = null; var rotated: Bitmap? = null; var flipped: Bitmap? = null
        try {
            file.writeBytes(jpeg)
            fun orientation(value: Int): ByteArray {
                ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, value.toString()); saveAttributes() }
                return file.readBytes()
            }
            base = ImagePipeline.decode(orientation(ExifInterface.ORIENTATION_NORMAL), 512)
            rotated = ImagePipeline.decode(orientation(ExifInterface.ORIENTATION_ROTATE_90), 512)
            require(rotated.width == base.height && rotated.height == base.width)
            require(rotated.getPixel(0, 0) == base.getPixel(0, base.height - 1))
            require(rotated.getPixel(rotated.width - 1, 0) == base.getPixel(0, 0))
            flipped = ImagePipeline.decode(orientation(ExifInterface.ORIENTATION_FLIP_HORIZONTAL), 512)
            require(flipped.width == base.width && flipped.height == base.height)
            require(flipped.getPixel(0, 0) == base.getPixel(base.width - 1, 0))
            return "Actual JPEG copy: EXIF=6 swaps dimensions/corners; EXIF=2 mirrors corner pixels; fixture unchanged"
        } finally { base?.recycle(); rotated?.recycle(); flipped?.recycle(); file.delete() }
    }

    @Synchronized private fun writeReport(png: ByteArray? = null, jpeg: ByteArray? = null) {
        val previous = File(filesDir, "image-checks.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val record = JSONObject().put("runId", runId).put("status", reportStatus).put("device", android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE).put("tests", JSONArray(results.toList())).put("interactions", JSONArray(interactions.toList()))
            .put("scope", "Debug activity, actual local fixtures, production ImagePipeline/MessageContent/ImageViewer; no real SSH or server generation")
        if (png != null && jpeg != null) record.put("fixtures", JSONObject().put("generatedPngBytes", png.size).put("generatedPngSha256", sha256(png)).put("photoJpegBytes", jpeg.size).put("photoJpegSha256", sha256(jpeg)))
        else previous?.optJSONObject("fixtures")?.let { record.put("fixtures", it) }
        val temporary = File(filesDir, "image-checks.json.tmp")
        temporary.writeText(record.toString(2))
        require(temporary.renameTo(File(filesDir, "image-checks.json")))
    }

    private fun recordInteraction(action: String, success: Boolean, detail: String) {
        interactions += JSONObject().put("action", action).put("success", success).put("detail", detail)
        fileWorkers.execute { runCatching { writeReport() } }
    }

    private fun copyImage(source: ImageSource) {
        pipeline.clipboardFile(owner, source).whenComplete { file, error ->
            if (error != null) { feedback = "图片复制失败"; recordInteraction("copy", false, "pipeline export failed") }
            else runCatching {
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, "图片", uri))
            }.onSuccess { feedback = "已复制图片"; recordInteraction("copy", true, "Real image content URI placed on system clipboard") }
                .onFailure { feedback = "图片复制失败"; recordInteraction("copy", false, "clipboard failed") }
        }
    }

    private fun saveImage(uri: Uri, source: ImageSource) {
        pipeline.original(owner, source).whenComplete { bytes, error ->
            if (error != null) { feedback = "图片保存失败"; recordInteraction("save", false, "original read failed") }
            else fileWorkers.execute {
                val saved = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("No output stream") }.isSuccess
                recordInteraction("save", saved, if (saved) "SAF wrote ${bytes.size} original bytes" else "SAF write failed")
                main.post { feedback = if (saved) "已保存原图" else "图片保存失败" }
            }
        }
    }

    @Composable private fun CheckScreen() {
        val loader = remember { pipeline.scoped(owner) }
        var opened by remember { mutableStateOf<ImageSource?>(null) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
            val source = pendingExport; pendingExport = null
            if (uri != null && source != null) saveImage(uri, source)
        }
        Column(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("图片组件核验", fontSize = 20.sp)
            Text(status, fontSize = 12.sp)
            if (feedback.isNotBlank()) Text(feedback, fontSize = 13.sp)
            Text("以下为真实本地图片经生产渲染链呈现；不代表服务器已完成图片生成。", fontSize = 12.sp, color = Color(0xFF5D5D5D))
            fixtures?.let { data ->
                listOf("生成结果 · raw base64 PNG" to data.generated, "用户图片 · RemotePath JPEG" to data.photo,
                    "生成中 · 灰色占位" to data.placeholder, "读取失败 · 点按后重试" to data.retry).forEach { (label, item) ->
                    Text(label, fontSize = 13.sp, color = Color(0xFF5D5D5D))
                    MessageContent(item, imageLoader = loader, onOpenImage = { opened = it; recordInteraction("open", true, "Production ImageViewer requested") }, onCopyImage = ::copyImage)
                }
            }
            TextButton(onClick = { finish() }) { Text("关闭检查") }
        }
        opened?.let { source -> ImageViewer(source, loader, onClose = { opened = null }, onSave = {
            pendingExport = source; export.launch(imageFileName(source))
        }) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun onDestroy() {
        disposed.set(true); cancelledRead.completeExceptionally(IllegalStateException("Activity closed"))
        pipeline.close(); checksWorker.shutdownNow(); fileWorkers.shutdown(); super.onDestroy()
    }
}

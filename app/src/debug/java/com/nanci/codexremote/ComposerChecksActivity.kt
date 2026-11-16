package com.nanci.codexremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nanci.codexremote.core.*
import com.nanci.codexremote.ui.content.*
import com.nanci.codexremote.ui.shell.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Debug-only, production Timeline + Composer. No repository, SSH, model call, or credentials. */
class ComposerChecksActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val imageIO = Executors.newSingleThreadExecutor()
    private val imageFiles = ConcurrentHashMap<String, Pair<String, File>>()
    private lateinit var imagePipeline: ImagePipeline
    private var pendingImageExport: Pair<String, ImageSource>? = null
    private var imageCount = 0
    private var imageMetadata = JSONArray()
    private val events = mutableListOf<JSONObject>()
    private var runId = UUID.randomUUID().toString()
    private var scenario by mutableStateOf("long")
    private var chat by mutableStateOf(RepositoryState())
    private var draft by mutableStateOf("")
    private var model by mutableStateOf("debug-codex-a")
    private var effort by mutableStateOf("medium")
    private var permission by mutableStateOf(ShellPermission.Default)
    private var plan by mutableStateOf(false)
    private var fast by mutableStateOf(false)
    private var attachmentVisible by mutableStateOf(false)
    private val motionThumbnail by lazy {
        Bitmap.createBitmap(88, 126, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(120, 162, 195)) }
    }
    private var latestRequest by mutableIntStateOf(0)
    private var focusRequest by mutableIntStateOf(0)
    private var feedback by mutableStateOf("")
    private var imeBottomPx = 0
    private var composerBounds = JSONObject()
    private var timelineBounds = JSONObject()
    private var sendCount = 0
    private var appendCount = 0
    private var pageCount = 0
    private var paging = false
    private var streaming = false
    private var destroyed = false
    private val models = listOf(
        ShellModel("debug-codex-a", "Codex A · 测试", listOf("low", "medium", "high", "xhigh"), "仅用于检查模型及推理菜单"),
        ShellModel("debug-codex-b", "Codex B · 测试", listOf("low", "medium", "high"), "切换后更新本地状态，不发模型请求"),
        ShellModel("debug-codex-c", "Codex C · 测试", listOf("minimal", "low", "medium"), "其他模型子菜单检查"),
        ShellModel("debug-codex-d", "Codex D · 测试", listOf("medium", "high"), "其他模型子菜单检查"),
    )
    private val persist = Runnable { writeSnapshot() }
    private val streamTick = object : Runnable {
        override fun run() {
            if (streaming && !destroyed) { appendStream(); main.postDelayed(this, 2400) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        imagePipeline = ImagePipeline(this) { owner, path, maxBytes ->
            CompletableFuture.supplyAsync({
                check(Looper.myLooper() != Looper.getMainLooper())
                val registered = imageFiles[path] ?: error("Debug 图片尚未生成")
                require(registered.first == owner) { "Debug 图片所属场景已改变" }
                require(registered.second.length() <= maxBytes)
                registered.second.readBytes()
            }, imageIO)
        }
        reset(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0D0D0D), onPrimary = Color.White,
                secondary = Color(0xFF5D5D5D), background = Color(0xFFFAFAFA), surface = Color.White,
                onSurface = Color(0xFF0D0D0D), surfaceVariant = Color(0xFFF3F3F3))) { Screen() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("scenario") && !intent.hasExtra("action")) { reset(intent); return }
        when (intent.getStringExtra("action")) {
            "append" -> appendStream(intent.getIntExtra("paragraphs", 1).coerceIn(1, 12))
            "prepend" -> loadEarlier()
            "fill" -> {
                draft = intent.getStringExtra("draft") ?: multilineDraft()
                if (intent.getBooleanExtra("focus", true)) focusRequest++
                record("intent_fill", JSONObject().put("length", draft.length).put("lines", draft.lines().size))
            }
            "focus" -> { focusRequest++; record("intent_focus") }
            "send" -> { if (draft.isBlank()) draft = "本地发送检查 ${sendCount + 1}"; send() }
            "stop" -> stopStream()
            "dump" -> { record("intent_dump"); writeSnapshot() }
            "reset" -> reset(intent)
        }
    }

    private fun reset(input: Intent) {
        main.removeCallbacks(streamTick)
        events.clear(); runId = UUID.randomUUID().toString()
        scenario = input.getStringExtra("scenario")?.takeIf { it in setOf("long", "history", "stream", "multiline", "images") || it.matches(Regex("images[2-6]")) } ?: "long"
        imageCount = if (scenario.startsWith("images")) (scenario.removePrefix("images").toIntOrNull() ?: input.getIntExtra("count", 2)).coerceIn(2, 6) else 0
        imageMetadata = JSONArray()
        draft = input.getStringExtra("draft") ?: if (scenario == "multiline") multilineDraft() else ""
        attachmentVisible = input.getBooleanExtra("attachment", false)
        model = models.first().id; effort = "medium"; permission = ShellPermission.Default; plan = false; fast = false
        sendCount = 0; appendCount = 0; pageCount = 0; paging = false; latestRequest = 0; focusRequest = 0
        val count = if (scenario == "history") 26 else 10
        val turns = (1..count).map { n -> makeTurn("history-$n", listOf(
            "userMessage" to "请查看这一条历史记录 HISTORY-${n.toString().padStart(2, '0')}。",
            "agentMessage" to "历史记录 HISTORY-${n.toString().padStart(2, '0')}：这里保留稳定消息 ID。向上阅读时，后续流式内容不应把阅读位置拉回最底部。\n\n这段文字用于识别当前阅读锚点。",
        )) }.toMutableList()
        val longText = buildString {
            append("这是一条超过一屏的长回复，用来核对输入框和聊天末尾的相对位置。\n\n")
            repeat(18) { index -> append("第 ${index + 1} 段：打开键盘、输入多行文字或展开模型菜单时，聊天可见区域会改变。正在阅读末尾时，最后一行应留在输入栏上方；已经向上阅读历史时，新增内容应尊重当前阅读位置。\n\n") }
            append("聊天末尾 IME-END")
        }
        streaming = scenario == "stream"
        turns += makeTurn("tail", listOf("agentMessage" to longText), if (streaming) "inProgress" else "completed")
        val summary = ThreadSummary("composer-check-$runId", "输入与滚动核验", "", "/debug/composer", System.currentTimeMillis() / 1000,
            "idle", model, "debug-only", true, JSONObject().put("id", "composer-check-$runId"))
        chat = RepositoryState(connection = ConnectionState(ConnectionStatus.CONNECTED, "本地 Debug", "本地状态，无网络", 1),
            selectedThread = summary, threads = listOf(summary), turns = if (imageCount > 0) emptyList() else turns,
            loadingThread = imageCount > 0, nextTurnsCursor = if (imageCount > 0) null else "older-1")
        if (input.getBooleanExtra("focus", scenario == "multiline")) focusRequest++
        feedback = "本地状态，无服务器调用"
        record("scenario_started", JSONObject().put("scenario", scenario))
        if (imageCount > 0) generateImages(imageCount, runId)
        if (streaming) main.postDelayed(streamTick, input.getLongExtra("delayMs", 10_000).coerceIn(1000, 60_000))
    }

    private fun generateImages(count: Int, requestedRun: String) {
        imageIO.execute {
            try {
                val groupingFailures = userImageGroupingSmokeChecks()
                val owner = "composer:$requestedRun"
                val directory = File(cacheDir, "composer-fixtures/$requestedRun").apply { mkdirs() }
                val dimensions = listOf(900 to 600, 500 to 900, 800 to 800, 1100 to 500, 600 to 1000, 900 to 700)
                val colors = intArrayOf(0xFF2673BA.toInt(), 0xFFC14748.toInt(), 0xFF3D8F66.toInt(), 0xFF9B66B6.toInt(), 0xFFD28C26.toInt(), 0xFF358B94.toInt())
                val content = JSONArray()
                val metadata = JSONArray()
                repeat(count) { index ->
                    val (width, height) = dimensions[index]
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(colors[index])
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER }
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 12f
                    canvas.drawRect(24f, 24f, width - 24f, height - 24f, paint)
                    paint.style = Paint.Style.FILL
                    paint.textSize = minOf(width, height) * .33f
                    canvas.drawText("${index + 1}", width / 2f, height * .54f, paint)
                    paint.textSize = minOf(width, height) * .055f
                    canvas.drawText("IMAGE ${index + 1} / $count", width / 2f, height * .69f, paint)
                    canvas.drawText("${width} x $height", width / 2f, height * .79f, paint)
                    paint.textSize = 28f
                    canvas.drawText("TL", 60f, 65f, paint); canvas.drawText("TR", width - 60f, 65f, paint)
                    canvas.drawText("BL", 60f, height - 45f, paint); canvas.drawText("BR", width - 60f, height - 45f, paint)
                    val file = File(directory, "number-${index + 1}.jpg")
                    try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
                    finally { bitmap.recycle() }
                    val path = "/debug-composer/$requestedRun/number-${index + 1}.jpg"
                    imageFiles[path] = owner to file
                    content.put(JSONObject().put("type", "localImage").put("path", path))
                    metadata.put(JSONObject().put("number", index + 1).put("width", width).put("height", height).put("path", path).put("bytes", file.length()))
                }
                content.put(JSONObject().put("type", "text").put("text", "这里有 $count 张不同原始比例的编号图片。点击后应打开对应编号的完整原图。IMAGES-END-$count"))
                val itemId = "images-$count-user"
                val raw = JSONObject().put("id", itemId).put("type", "userMessage").put("content", content)
                val imageTurn = ChatTurn("images-$count", "completed", listOf(ChatItem(itemId, "images-$count", "userMessage", "", "completed", raw)), raw = JSONObject().put("id", "images-$count"))
                main.post {
                    if (destroyed || requestedRun != runId) return@post
                    imageMetadata = metadata
                    chat = chat.copy(loadingThread = false, turns = listOf(imageTurn))
                    record("image_grouping_smoke", JSONObject().put("passed", groupingFailures.isEmpty()).put("failures", JSONArray(groupingFailures)))
                    record("images_ready", JSONObject().put("count", count).put("backgroundGenerated", true))
                }
            } catch (error: Exception) {
                main.post {
                    if (destroyed || requestedRun != runId) return@post
                    chat = chat.copy(loadingThread = false)
                    feedback = "Debug 图片生成失败"
                    record("images_failed", JSONObject().put("errorType", error.javaClass.simpleName))
                }
            }
        }
    }

    private fun makeTurn(id: String, messages: List<Pair<String, String>>, status: String = "completed"): ChatTurn {
        val items = messages.mapIndexed { index, (type, text) ->
            val raw = JSONObject().put("id", "$id-item-$index").put("type", type).put("status", "completed")
            if (type == "userMessage") raw.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))) else raw.put("text", text)
            ChatItem("$id-item-$index", id, type, text, "completed", raw)
        }
        return ChatTurn(id, status, items, raw = JSONObject().put("id", id).put("status", status))
    }

    private fun send() {
        if (draft.isBlank()) return
        if (streaming) stopStream()
        val text = draft
        sendCount++
        val marker = "发送后末尾 SEND-END-$sendCount"
        chat = chat.copy(turns = chat.turns + makeTurn("sent-$sendCount", listOf("userMessage" to text,
            "agentMessage" to "已接收本地测试消息。这是 Debug 数据变更，未调用模型。\n\n$marker")))
        draft = ""; latestRequest++
        feedback = marker
        record("send", JSONObject().put("inputLength", text.length).put("marker", marker).put("latestRequest", latestRequest))
    }

    private fun appendStream(paragraphs: Int = 1) {
        val tail = chat.turns.lastOrNull() ?: return
        val last = tail.items.lastOrNull() ?: return
        appendCount++
        val marker = "流式末尾 STREAM-END-$appendCount"
        val text = last.text + "\n\n" + (1..paragraphs).joinToString("\n\n") { "流式追加第 $appendCount 轮：保留已有消息 ID，检查在末尾时随内容增长、阅读历史时保持位置。" } + "\n\n" + marker
        val updated = last.copy(text = text, raw = JSONObject(last.raw.toString()).put("text", text))
        chat = chat.copy(turns = chat.turns.dropLast(1) + tail.copy(items = tail.items.dropLast(1) + updated))
        feedback = marker
        record("stream_append", JSONObject().put("marker", marker).put("latestRequestUnchanged", latestRequest))
    }

    private fun stopStream() {
        streaming = false; main.removeCallbacks(streamTick)
        chat = chat.copy(turns = chat.turns.map { if (it.status == "inProgress") it.copy(status = "completed", raw = JSONObject(it.raw.toString()).put("status", "completed")) else it })
        record("stop")
    }

    private fun loadEarlier() {
        if (paging || pageCount >= 3) return
        val requestedRun = runId
        paging = true
        record("history_page_requested", JSONObject().put("page", pageCount + 1))
        main.postDelayed({
            if (destroyed || requestedRun != runId) return@postDelayed
            pageCount++
            val earlier = (1..5).map { n -> makeTurn("older-$pageCount-$n", listOf("agentMessage" to
                "前置历史 HISTORY-OLDER-$pageCount-$n\n\n本批消息插入列表前面，原有消息 ID 不变；当前阅读锚点应保持。")) }
            chat = chat.copy(turns = earlier + chat.turns, nextTurnsCursor = if (pageCount < 3) "older-${pageCount + 1}" else null)
            paging = false
            record("history_page_prepended", JSONObject().put("count", earlier.size).put("latestRequestUnchanged", latestRequest))
        }, 350)
    }

    @Composable private fun Screen() {
        val density = LocalDensity.current
        val ime = WindowInsets.ime.getBottom(density)
        LaunchedEffect(ime > 0) { imeBottomPx = ime; record("ime_visibility", JSONObject().put("visible", ime > 0).put("bottomPx", ime)) }
        SideEffect { imeBottomPx = ime }
        val owner = "composer:$runId"
        val loader = remember(owner) { imagePipeline.scoped(owner) }
        var openedImage by remember(runId) { mutableStateOf<ImageSource?>(null) }
        val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
            val pending = pendingImageExport; pendingImageExport = null
            if (uri != null && pending != null) saveImage(uri, pending.first, pending.second)
        }
        val shell = ShellState(title = "输入与滚动核验", subtitle = "Debug · $scenario", connected = true)
        val actions = ShellActions(onBack = { finish() }, onNewChat = { record("new_chat_control") }, onCopyId = { copy(chat.selectedThread?.id.orEmpty()) })
        Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Text("DBG $scenario · ${model.removePrefix("debug-")} / $effort · ${permission.name} · ${if (ime > 0) "IME" else "no IME"}",
                    Modifier.fillMaxWidth().height(21.dp).background(Color(0xFFECECEC)).padding(horizontal = 10.dp),
                    fontSize = 10.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).onGloballyPositioned { coordinates ->
                    val r = coordinates.boundsInWindow(); val next = rectangle(r.left, r.top, r.right, r.bottom)
                    if (next.toString() != timelineBounds.toString()) { timelineBounds = next; scheduleWrite() }
                }.semantics { contentDescription = "Debug 生产聊天时间线" }) {
                    ChatTimelineContent(state = chat, latestRequest = latestRequest, onLoadEarlier = ::loadEarlier,
                        onCopy = ::copy, onPlanOpen = { record("plan_open_not_in_fixture") }, onDownload = { record("download_not_in_fixture") },
                        onOpenLink = { record("link_not_in_fixture") }, imageLoader = loader,
                        onOpenImage = { source -> openedImage = source; record("image_open", JSONObject().put("key", source.cacheKey).put("path", source.value)) },
                        onCopyImage = { copyImage(owner, it) }, modifier = Modifier.fillMaxSize())
                    ChatChrome(shell, actions, Modifier.align(Alignment.TopCenter))
                }
                Composer(ComposerState(text = draft, enabled = true, running = chat.activeTurnId != null, planMode = plan,
                    permission = permission, permissions = listOf(ShellPermission.Default, ShellPermission.AutoReview, ShellPermission.ReadOnly, ShellPermission.FullAccess),
                    models = models, modelId = model, reasoning = effort, focusRequest = focusRequest,
                    attachments = if (attachmentVisible) listOf(ShellAttachment("motion-thumbnail", "motion-check.png", motionThumbnail)) else emptyList(),
                    allowCamera = false, allowPhotos = false, allowFiles = false, supportsFast = true, fast = fast),
                    ComposerActions(onTextChange = { draft = it; record("draft_changed", JSONObject().put("length", it.length).put("lines", it.lines().size)) },
                        onSend = ::send, onStop = ::stopStream, onPlanMode = { plan = it; record("plan_selected", JSONObject().put("enabled", it)) },
                        onPermission = { permission = it; record("permission_selected", JSONObject().put("permission", it.name)) },
                        onModel = { chosen -> model = chosen; effort = models.first { it.id == chosen }.reasoning.let { if ("medium" in it) "medium" else it.firstOrNull().orEmpty() }; record("model_selected", JSONObject().put("model", model).put("effort", effort)) },
                        onReasoning = { effort = it; record("effort_selected", JSONObject().put("effort", it)) },
                        onFast = { fast = it; record("fast_selected", JSONObject().put("enabled", it)) },
                        onRemoveAttachment = { attachmentVisible = false; record("attachment_removed") },
                        onFocusRequestHandled = { if (focusRequest == it) focusRequest = 0 },
                        onFocus = { record("composer_focused") }),
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).onGloballyPositioned { coordinates ->
                        val r = coordinates.boundsInWindow(); val next = rectangle(r.left, r.top, r.right, r.bottom)
                        if (next.toString() != composerBounds.toString()) { composerBounds = next; scheduleWrite() }
                    }.semantics { contentDescription = "Debug 生产输入栏" })
            }
        }
        openedImage?.let { source -> ImageViewer(source, loader, onClose = {
            record("image_close", JSONObject().put("key", source.cacheKey)); openedImage = null
        }, onSave = { pendingImageExport = owner to source; saveLauncher.launch(imageFileName(source)) }) }
    }

    private fun copyImage(owner: String, source: ImageSource) {
        imagePipeline.clipboardFile(owner, source).whenComplete { file, error ->
            val success = error == null && runCatching {
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, "Debug image", uri))
            }.isSuccess
            record("image_copy", JSONObject().put("key", source.cacheKey).put("success", success))
        }
    }
    private fun saveImage(uri: Uri, owner: String, source: ImageSource) {
        imagePipeline.original(owner, source).whenComplete { bytes, error ->
            if (error != null) record("image_save", JSONObject().put("key", source.cacheKey).put("success", false))
            else imageIO.execute {
                val success = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException("No output stream") }.isSuccess
                main.post { if (!destroyed) record("image_save", JSONObject().put("key", source.cacheKey).put("success", success).put("bytes", bytes.size)) }
            }
        }
    }

    private fun copy(value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Codex Debug", value))
        record("copy_text", JSONObject().put("length", value.length))
    }
    private fun multilineDraft() = (1..7).joinToString("\n") { "输入栏第 $it 行：展开后聊天末尾应上抬。" }
    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float) = JSONObject().put("left", left.toInt()).put("top", top.toInt()).put("right", right.toInt()).put("bottom", bottom.toInt())
    private fun record(name: String, detail: JSONObject = JSONObject()) {
        events += JSONObject().put("event", name).put("elapsedMs", SystemClock.elapsedRealtime()).put("imeBottomPx", imeBottomPx).put("detail", detail)
        if (events.size > 500) events.removeAt(0)
        scheduleWrite()
    }
    private fun scheduleWrite() { main.removeCallbacks(persist); if (!destroyed) main.postDelayed(persist, 180) }
    private fun writeSnapshot() {
        val snapshot = JSONObject().put("runId", runId).put("scenario", scenario).put("device", android.os.Build.MODEL)
            .put("scope", "Debug production Timeline/Composer and real IME; local messages/options only, no server capability validation")
            .put("current", JSONObject().put("model", model).put("effort", effort).put("permission", permission.name).put("plan", plan).put("fast", fast)
                .put("draftLength", draft.length).put("draftLines", draft.lines().size).put("imeBottomPx", imeBottomPx)
                .put("latestRequest", latestRequest).put("sendCount", sendCount).put("appendCount", appendCount).put("pageCount", pageCount)
                .put("itemCount", chat.items.size).put("firstItemId", chat.items.firstOrNull()?.id).put("lastItemId", chat.items.lastOrNull()?.id)
                .put("imageCount", imageCount).put("imageFixtures", imageMetadata)
                .put("tailMarker", chat.items.lastOrNull()?.text?.substringAfterLast("\n")).put("composerBoundsPx", composerBounds).put("timelineBoundsPx", timelineBounds))
            .put("events", JSONArray(events.toList())).put("visualChecks", JSONArray(listOf("末尾 IME-END 在输入框上方", "模型/推理子菜单时输入法保留", "读 HISTORY-n 时追加不跳底", "前置分页保持已有 HISTORY-n 阅读位置", "发送后跳到 SEND-END-n")))
            .put("visualChecksAutomaticallyPassed", false).toString(2)
        writer.execute {
            runCatching { val target = File(filesDir, "composer-checks.json"); val temp = File(filesDir, "composer-checks.json.tmp"); temp.writeText(snapshot); check(temp.renameTo(target)) }
        }
    }
    override fun onStop() { main.removeCallbacks(persist); writeSnapshot(); super.onStop() }
    override fun onDestroy() {
        main.removeCallbacks(streamTick); main.removeCallbacks(persist); writeSnapshot(); destroyed = true
        imagePipeline.close(); imageIO.shutdown(); writer.shutdown(); super.onDestroy()
    }
}

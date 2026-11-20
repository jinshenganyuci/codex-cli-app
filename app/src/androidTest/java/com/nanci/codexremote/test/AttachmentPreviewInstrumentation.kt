package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.nanci.codexremote.ImagePipeline
import com.nanci.codexremote.ImageViewer
import com.nanci.codexremote.MainActivity
import com.nanci.codexremote.ui.content.ImageLoadState
import com.nanci.codexremote.ui.content.ImageLoader
import com.nanci.codexremote.ui.content.ImageSource
import com.nanci.codexremote.ui.content.ImageSourceKind
import com.nanci.codexremote.ui.shell.Composer
import com.nanci.codexremote.ui.shell.ComposerActions
import com.nanci.codexremote.ui.shell.ComposerState
import com.nanci.codexremote.ui.shell.ShellAttachment
import com.nanci.codexremote.ui.theme.NativeAppTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Uses real thumbnail taps and private content URIs without sending a message. */
class AttachmentPreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        val runId = "attachment-preview-${UUID.randomUUID()}"
        val directory = File(targetContext.cacheDir, "camera/$runId")
        val thumbnails = mutableListOf<Bitmap>()
        var activity: MainActivity? = null
        var pipeline: ImagePipeline? = null
        var stage = "fixtures"
        var passed = false
        try {
            check(directory.mkdirs())
            val first = fixture(directory, "first", 360, 480, Color.RED, thumbnails)
            val second = fixture(directory, "second", 480, 360, Color.BLUE, thumbnails)
            val cacheContext = object : ContextWrapper(targetContext) {
                override fun getCacheDir(): File = directory
            }
            val remoteReads = AtomicInteger()
            val imagePipeline = ImagePipeline(cacheContext) { _, _, _ ->
                remoteReads.incrementAndGet()
                error("Unexpected remote preview read")
            }.also { pipeline = it }
            val requested = AtomicReference<ImageSource?>()
            val decoded = AtomicReference<Pair<Int, Int>?>()
            val openedCount = AtomicInteger()
            val removed = AtomicReference<String?>()
            val delegate = imagePipeline.scoped(runId)
            val loader = object : ImageLoader {
                override fun load(source: ImageSource, maxEdge: Int, onResult: (ImageLoadState) -> Unit): () -> Unit {
                    requested.set(source)
                    decoded.set(null)
                    return delegate.load(source, maxEdge) { state ->
                        if (state is ImageLoadState.Ready) decoded.set(state.bitmap.width to state.bitmap.height)
                        onResult(state)
                    }
                }
            }
            val fixtureActivity = startActivitySync(
                Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = fixtureActivity
            runOnMainSync {
                fixtureActivity.setContent {
                    NativeAppTheme {
                        var attachments by remember { mutableStateOf(listOf(first, second)) }
                        var opened by remember { mutableStateOf<ImageSource?>(null) }
                        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                            Composer(
                                ComposerState(attachments = attachments),
                                ComposerActions(
                                    onPreviewAttachment = { openedCount.incrementAndGet(); opened = it },
                                    onRemoveAttachment = { id ->
                                        removed.set(id)
                                        attachments = attachments.filterNot { it.id == id }
                                    },
                                ),
                            )
                        }
                        opened?.let { ImageViewer(it, loader, onClose = { opened = null }, onSave = {}) }
                    }
                }
            }

            for ((attachment, size) in listOf(first to (360 to 480), second to (480 to 360))) {
                stage = "preview_${attachment.id}"
                tap { it.contentDescription?.toString() == attachment.label }
                await("Correct full-size image is visible") {
                    requested.get() == attachment.imagePreview && decoded.get() == size &&
                        visible { it.contentDescription?.toString() == "图片预览" } != null
                }
                if (attachment == first) {
                    val viewer = requireNotNull(visible { it.contentDescription?.toString() == "图片预览" })
                    pinchOut(Rect().also(viewer::getBoundsInScreen))
                    await("Pinch exposes the zoom reset control") { visible { it.text?.toString() == "重置" } != null }
                    report.putBoolean("pinch_zoom", true)
                }
                tap { it.text?.toString() == "关闭" }
                await("Closing preview keeps the attachment") {
                    visible { it.contentDescription?.toString() == attachment.label } != null
                }
            }
            report.putInt("correct_previews", openedCount.get())
            check(openedCount.get() == 2 && remoteReads.get() == 0)

            stage = "remove"
            tap { it.contentDescription?.toString() == "移除 ${first.label}" }
            await("Removal keeps the second attachment") {
                removed.get() == first.id &&
                    visible { it.contentDescription?.toString() == first.label } == null &&
                    visible { it.contentDescription?.toString() == second.label } != null
            }
            check(openedCount.get() == 2)
            report.putBoolean("separate_remove", true)
            passed = true
        } catch (failure: Throwable) {
            report.putString("failure", failure.message ?: failure.javaClass.simpleName)
        } finally {
            val cleaned = runCatching {
                runOnMainSync { activity?.finish() }
                waitForIdleSync()
                pipeline?.close()
                thumbnails.forEach(Bitmap::recycle)
                !directory.exists() || directory.deleteRecursively()
            }.getOrDefault(false)
            report.putString("stage", stage)
            report.putBoolean("fixtures_removed", cleaned)
            report.putString("status", if (passed && cleaned) "passed" else "failed")
            finish(if (passed && cleaned) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
        }
    }

    private fun fixture(directory: File, id: String, width: Int, height: Int, color: Int,
        thumbnails: MutableList<Bitmap>): ShellAttachment {
        val file = File(directory, "$id.png")
        val original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            original.eraseColor(color)
            file.outputStream().use { check(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            original.recycle()
        }
        val thumbnail = Bitmap.createBitmap(44, 63, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        thumbnails += thumbnail
        val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.files", file)
        return ShellAttachment(id, file.name, thumbnail,
            imagePreview = ImageSource(ImageSourceKind.LocalUri, uri.toString(), "image/png", "${directory.name}:$id", file.name))
    }

    private fun visible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }
        return uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun tap(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var node: AccessibilityNodeInfo? = null
        await("Tap target is visible") { visible(predicate)?.also { node = it } != null }
        val bounds = Rect().also { requireNotNull(node).getBoundsInScreen(it) }
        check(!bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun pinchOut(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        val pointers = Array(2) { index -> PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        fun dispatch(action: Int, fingers: Int, span: Float) {
            val positions = Array(fingers) { index -> PointerCoords().apply {
                x = bounds.exactCenterX() + span * if (index == 0) -1f else 1f
                y = bounds.exactCenterY()
                pressure = 1f
                size = .06f
            } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, fingers,
                pointers, positions, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { check(uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        val pointerIndex = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val start = bounds.width() * .08f
        val end = bounds.width() * .27f
        dispatch(MotionEvent.ACTION_DOWN, 1, start)
        dispatch(MotionEvent.ACTION_POINTER_DOWN or pointerIndex, 2, start)
        for (step in 1..8) {
            SystemClock.sleep(25)
            dispatch(MotionEvent.ACTION_MOVE, 2, start + (end - start) * step / 8)
        }
        dispatch(MotionEvent.ACTION_POINTER_UP or pointerIndex, 2, end)
        dispatch(MotionEvent.ACTION_UP, 1, end)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(80)
        }
    }
}

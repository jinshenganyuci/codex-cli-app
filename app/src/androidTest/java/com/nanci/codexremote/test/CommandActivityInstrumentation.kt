package com.nanci.codexremote.test

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.MainActivity
import com.nanci.codexremote.core.ProtocolParser
import com.nanci.codexremote.core.RepositoryState
import com.nanci.codexremote.core.jsonArray
import com.nanci.codexremote.core.jsonObject
import com.nanci.codexremote.ui.content.ChatTimelineContent
import com.nanci.codexremote.ui.content.ImageLoadState
import com.nanci.codexremote.ui.content.ImageLoader
import com.nanci.codexremote.ui.content.ImageSource
import com.nanci.codexremote.ui.content.TimelineActivityChecks
import com.nanci.codexremote.ui.theme.NativeAppTheme
import com.nanci.codexremote.ui.theme.NativeTheme
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Exercises the release chat timeline using deterministic synthetic events. */
@OptIn(ExperimentalComposeUiApi::class)
class CommandActivityInstrumentation : Instrumentation() {
    private val report = Bundle()
    private var stage = "launch"
    private var screenshotDirectory: File? = null
    private var takeScreenshots = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        takeScreenshots = arguments?.getString("captureScreenshots") == "true"
        start()
    }

    override fun onStart() {
        var activity: MainActivity? = null
        val result = runCatching {
            stage = "timeline_data"
            TimelineActivityChecks.run()
            report.putBoolean("timeline_data_checks", true)
            stage = "launch"
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val fixtureActivity = startActivitySync(
                Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = fixtureActivity
            verify(fixtureActivity)
        }
        if (result.isFailure) {
            capture("command-activity-test-failure.png")
            runCatching { report.putString("timeline_labels", accessibleLabels().joinToString("\n").take(2000)) }
        }
        runOnMainSync { activity?.finish() }
        report.putString("stage", stage)
        report.putString("status", if (result.isSuccess) "passed" else "failed")
        result.exceptionOrNull()?.let { report.putString("failure", it.stackTraceToString()) }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }

    private fun verify(activity: MainActivity) {
        val fixture = mutableStateOf(timelineFixture("live-thread", "inProgress"))
        val noImages = object : ImageLoader {
            override fun load(source: ImageSource, maxEdge: Int, onResult: (ImageLoadState) -> Unit): () -> Unit = {}
        }
        runOnMainSync {
            activity.setContent {
                NativeAppTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().systemBarsPadding().background(NativeTheme.colors.background)
                        .testTag("command-timeline-fixture").semantics { testTagsAsResourceId = true }) {
                        ChatTimelineContent(
                            state = fixture.value,
                            onLoadEarlier = {},
                            onCopy = {},
                            onPlanOpen = {},
                            onDownload = {},
                            onOpenLink = {},
                            imageLoader = noImages,
                            onOpenImage = {},
                            onCopyImage = {},
                            topInset = 16.dp,
                        )
                    }
                }
            }
        }

        stage = "running_summary"
        awaitVisible(readFilename)
        awaitVisible(commandLabel)
        awaitVisible(commentary)
        awaitDisclosure("已展开")
        assertHidden(commandLine)
        assertHidden(commandOutput)
        report.putBoolean("compact_running_commands", true)
        capture("command-activity-running.png")

        stage = "automatically_collapses"
        runOnMainSync { fixture.value = timelineFixture("live-thread", "completed") }
        awaitVisible(finalResponse)
        val group = awaitDisclosure("已收起")
        await("Completed activity hides command and commentary rows") {
            visibleLabel(readFilename) == null && visibleLabel(commandLabel) == null && visibleLabel(commentary) == null
        }
        report.putBoolean("completed_auto_collapse", true)
        capture("command-activity-completed.png")

        stage = "reopen_and_inspect"
        click(group)
        awaitVisible(readFilename)
        awaitVisible(commentary)
        awaitVisible(finalResponse)
        awaitDisclosure("已展开")
        assertHidden(commandLine)
        click(awaitVisible(commandLabel))
        awaitVisible(commandLine)
        awaitVisible(commandOutput)
        awaitTextContaining("执行失败")
        awaitTextContaining("退出码 3")
        report.putBoolean("command_and_failed_exit_details_retained", true)
        capture("command-activity-details.png")

        stage = "manual_collapse"
        click(awaitVisible(groupLabel))
        awaitVisible(finalResponse)
        awaitDisclosure("已收起")
        await("Collapsing the group hides command details") { visibleLabel(commandOutput) == null && visibleLabel(commandLabel) == null }
        report.putBoolean("manual_expand_and_collapse", true)

        stage = "historical_failed_turn"
        // The fixture repeats turn/item IDs to catch expansion leaking when the selected chat changes.
        click(awaitVisible(groupLabel))
        awaitVisible(commandLabel)
        runOnMainSync { fixture.value = timelineFixture("history-thread", "failed") }
        awaitVisible(historyResponse)
        awaitDisclosure("已收起")
        assertHidden(commandLabel)
        assertHidden(commentary)
        report.putBoolean("history_collapsed_by_default", true)
    }

    private fun timelineFixture(threadId: String, turnStatus: String): RepositoryState {
        val running = turnStatus == "inProgress"
        val items = buildList {
            add(jsonObject("id" to "qa-input", "type" to "userMessage", "content" to jsonArray(listOf(
                jsonObject("type" to "text", "text" to "检查测试文件"),
            ))))
            add(jsonObject("id" to "qa-progress", "type" to "agentMessage", "phase" to "commentary", "text" to commentary))
            add(jsonObject("id" to "qa-read", "type" to "commandExecution", "status" to "completed",
                "command" to "cat /tmp/$readFilename", "exitCode" to 0,
                "commandActions" to jsonArray(listOf(jsonObject("type" to "read", "name" to readFilename, "path" to "/tmp/$readFilename")))))
            add(jsonObject("id" to "qa-run", "type" to "commandExecution", "status" to if (running) "inProgress" else "failed",
                "command" to commandLine, "aggregatedOutput" to commandOutput, "exitCode" to if (running) JSONObject.NULL else 3,
                "commandActions" to jsonArray(listOf(jsonObject("type" to "unknown", "command" to commandLine)))))
            if (!running) add(jsonObject("id" to "qa-final", "type" to "agentMessage", "text" to
                if (turnStatus == "completed") finalResponse else historyResponse))
        }
        val thread = ProtocolParser.thread(jsonObject("id" to threadId, "cwd" to "/tmp"))
        val turn = ProtocolParser.turn(jsonObject("id" to "qa-turn", "status" to turnStatus, "items" to jsonArray(items)))
        return RepositoryState(selectedThread = thread, selectedThreadReady = true, turns = listOf(turn))
    }

    private fun visibleLabel(label: String) = visible { node ->
        node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label
    }

    private fun assertHidden(label: String) = check(visibleLabel(label) == null) { "Unexpected visible label $label" }

    private fun awaitVisible(label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("Visible label $label") { visibleLabel(label)?.also { found = it } != null }
        return requireNotNull(found)
    }

    private fun awaitTextContaining(text: String) = await("Visible text contains $text") {
        visible { it.text?.contains(text) == true } != null
    }

    private fun awaitDisclosure(state: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("Activity group is $state") {
            visible { it.viewIdResourceName == "activity:qa-turn:qa-read" && it.stateDescription?.toString() == state }
                ?.also { found = it } != null
        }
        return requireNotNull(found)
    }

    private fun visible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        windows().firstNotNullOfOrNull { root -> nodes(root).firstOrNull { it.isVisibleToUser && predicate(it) } }

    private fun windows() = (listOfNotNull(uiAutomation.rootInActiveWindow) + uiAutomation.windows.mapNotNull { it.root })
        .filter { it.packageName == targetContext.packageName }
        .distinctBy { it.windowId }

    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        repeat(root.childCount) { index -> root.getChild(index)?.let { yieldAll(nodes(it)) } }
    }

    private fun accessibleLabels() = windows().flatMap { root ->
        nodes(root).filter { it.isVisibleToUser }.mapNotNull { (it.text ?: it.contentDescription)?.toString() }.toList()
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val target = current ?: error("Label has no clickable ancestor")
            if (target.isClickable && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = target.parent
        }
        error("Could not click label ${node.text}")
    }

    private fun capture(name: String) {
        if (!takeScreenshots) return
        runCatching {
            val node = visible { it.viewIdResourceName == "command-timeline-fixture" } ?: return
            val bounds = Rect().also(node::getBoundsInScreen)
            val screenshot = requireNotNull(uiAutomation.takeScreenshot())
            try {
                check(bounds.intersect(0, 0, screenshot.width, screenshot.height))
                val cropped = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val directory = screenshotDirectory ?: File(requireNotNull(targetContext.getExternalFilesDir(null)),
                        "command-activity-qa-${UUID.randomUUID()}").also {
                        check(it.mkdir())
                        screenshotDirectory = it
                    }
                    val output = File(directory, name)
                    output.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    report.putString(name, output.absolutePath)
                } finally {
                    if (cropped !== screenshot) cropped.recycle()
                }
            } finally {
                screenshot.recycle()
            }
        }.onFailure { report.putString("capture_failure", it.javaClass.simpleName) }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(60)
        }
    }

    private companion object {
        const val groupLabel = "命令记录 · 2 条"
        const val commandLabel = "命令执行"
        const val readFilename = "fixture-read.txt"
        const val commentary = "我先检查测试文件。"
        const val finalResponse = "测试文件已检查。"
        const val historyResponse = "历史任务结束。"
        const val commandLine = "printf 'QA command'; exit 3"
        const val commandOutput = "QA command output"
    }
}

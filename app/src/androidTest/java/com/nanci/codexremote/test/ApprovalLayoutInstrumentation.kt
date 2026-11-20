package com.nanci.codexremote.test

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.MainActivity
import com.nanci.codexremote.ui.content.ApprovalCard
import com.nanci.codexremote.ui.theme.NativeAppTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Drives the release approval card with fixture replies that never reach the server. */
@OptIn(ExperimentalComposeUiApi::class)
class ApprovalLayoutInstrumentation : Instrumentation() {
    private val report = Bundle()
    private var stage = "launch"
    private var captureScreenshots = false
    private var screenshotDirectory: File? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        captureScreenshots = arguments?.getString("captureScreenshots") == "true"
        start()
    }

    override fun onStart() {
        var activity: MainActivity? = null
        val result = runCatching {
            stage = "permission_defaults"
            report.putInt("core_checks", CoreStateFixChecks.run().size)
            report.putBoolean("full_access_defaults_and_turn_payloads", true)
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
        if (result.isFailure && activity != null) {
            capture("approval-layout-failure.png")
            runCatching { report.putString("layout_diagnostics", layoutDiagnostics()) }
        }
        runOnMainSync { activity?.finish() }
        report.putString("stage", stage)
        report.putString("status", if (result.isSuccess) "passed" else "failed")
        result.exceptionOrNull()?.let { report.putString("failure", it.stackTraceToString()) }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }

    private fun verify(activity: MainActivity) {
        val commandLastLine = "QA_COMMAND_END_200"
        val ruleLastArgument = "QA_RULE_END_200"
        val command = buildString {
            appendLine("printf '%s\\n' '")
            repeat(200) { appendLine("QA fixture line $it with spaces and quoted characters") }
            appendLine(commandLastLine)
            append("'")
        }
        report.putInt("command_bytes", command.toByteArray().size)
        val commandPrefix = JSONArray().put("printf").put("%s\\n").put(command).put(ruleLastArgument)
        val rule = JSONObject().put("acceptWithExecpolicyAmendment",
            JSONObject().put("execpolicy_amendment", commandPrefix))
        val params = JSONObject()
            .put("command", command)
            .put("reason", "请检查测试用的长命令。")
            .put("cwd", "/tmp/codex-mobile-approval-qa")
            .put("availableDecisions", JSONArray().put("accept").put(rule)
                .put("acceptForSession").put("decline").put("cancel"))
        val reply = AtomicReference<JSONObject?>()
        val calls = AtomicInteger()
        val variant = mutableIntStateOf(0)
        runOnMainSync {
            activity.setContent {
                val mode = variant.intValue
                val deviceDensity = LocalDensity.current
                val density = Density(deviceDensity.density, if (mode > 0) 1.4f else 1f)
                CompositionLocalProvider(LocalDensity provides density) {
                    NativeAppTheme {
                        Box(Modifier.fillMaxSize().systemBarsPadding().testTag("approval-test-viewport-$mode")
                            .semantics { testTagsAsResourceId = true },
                            contentAlignment = Alignment.BottomCenter) {
                            Column(Modifier.heightIn(max = if (mode > 0) 420.dp else 720.dp)
                                .padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                                Spacer(Modifier.height(68.dp))
                                Box(Modifier.weight(1f)) { Text("审批布局测试 $mode") }
                                key(mode) {
                                    ApprovalCard("item/commandExecution/requestApproval", params, onReply = { value, _ ->
                                        reply.set(value)
                                        calls.incrementAndGet()
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }

        stage = "initial_actions"
        val before = fixedActions()
        capture("approval-fixed-footer.png")
        tap(node("批准"))
        await("Clicking approve returns the one-time approval") { reply.get()?.optString("decision") == "accept" }
        tap(node("取消本次请求"))
        await("Clicking cancel returns the cancel decision") { reply.get()?.optString("decision") == "cancel" }
        check(calls.get() == 2)
        check(fixedActions() == before) { "Footer moved in the unchanged layout" }
        report.putBoolean("normal_viewport_approve_and_cancel", true)

        stage = "small_large_text_viewport"
        runOnMainSync { variant.intValue++ }
        tag("approval-test-viewport-1")
        val compactActions = fixedActions()
        stage = "expand_long_command"
        clickInBody("查看完整命令")
        verifyViewer(commandLastLine, screenshot = "approval-command-viewer.png")
        check(calls.get() == 2) { "Reviewing a command sent an approval" }
        check(fixedActions() == compactActions) { "Closing the command viewer moved the footer" }
        report.putBoolean("full_command_review", true)

        stage = "expand_long_rule"
        clickInBody("查看完整规则")
        verifyViewer(ruleLastArgument)
        check(calls.get() == 2) { "Reviewing a rule sent an approval" }
        check(fixedActions() == compactActions) { "Scrolling approval details moved the footer" }
        report.putBoolean("small_viewport_large_text_fixed_footer", true)

        stage = "exact_rule_decision"
        clickInBody("批准并记住此命令规则", scrollUp = true)
        await("Clicking the rule returns its exact JSON payload") {
            reply.get()?.optJSONObject("decision")?.toString() == rule.toString()
        }
        check(calls.get() == 3)
        report.putBoolean("full_rule_review_and_exact_decision", true)
        tap(node("取消本次请求"))
        await("Cancel is reachable after reviewing the long rule") { reply.get()?.optString("decision") == "cancel" }
        check(calls.get() == 4)
        report.putBoolean("cancel_reachable_after_scrolling", true)
    }

    private fun fixedActions(): List<Rect> = listOf(bounds(node("批准")), bounds(node("取消本次请求"))).also { actions ->
        val footer = bounds(tag("approval-actions"))
        check(actions.all { footer.contains(it) }) { "Approve and cancel are outside the fixed footer" }
    }

    private fun verifyViewer(lastLine: String, screenshot: String? = null) {
        val dialogWindow = node("关闭").windowId
        await("Full details contain the last fixture line") {
            visible { it.windowId == dialogWindow &&
                (it.text?.contains(lastLine) == true || it.contentDescription?.contains(lastLine) == true) } != null
        }
        var scroll: AccessibilityNodeInfo? = null
        await("Full details can scroll in their own window") {
            visible { it.windowId == dialogWindow && it.isScrollable }?.also { scroll = it } != null
        }
        screenshot?.let(::capture)
        check(requireNotNull(scroll).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            "Full details cannot be scrolled"
        }
        check(click(node("关闭"))) { "The full details close control could not be clicked" }
        await("Closing details returns to the same request") {
            visible { hasLabel(it, "关闭") } == null && visible { hasLabel(it, "取消本次请求") } != null
        }
    }

    private fun clickInBody(label: String, scrollUp: Boolean = false) {
        var direction = if (scrollUp) -1 else 1
        var previousBounds: Rect? = null
        repeat(10) {
            val button = visible { hasLabel(it, label) }
            if (button != null && click(button)) return
            val bodyNode = tag("approval-scroll")
            val body = bounds(bodyNode)
            val top = body.top + body.height() * .15f
            val bottom = body.top + body.height() * .85f
            val labelBounds = button?.let(::bounds)
            if (labelBounds != null && labelBounds == previousBounds) direction *= -1
            previousBounds = labelBounds
            val x = body.exactCenterX()
            val start = if (direction < 0) top else bottom
            val end = if (direction < 0) bottom else top
            val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, x, start)
            for (step in 1..8) {
                SystemClock.sleep(24)
                touch(down, MotionEvent.ACTION_MOVE, x, start + (end - start) * step / 8)
            }
            touch(down, MotionEvent.ACTION_UP, x, end)
            SystemClock.sleep(250)
        }
        error("No visible approval action labelled $label after scrolling the details")
    }

    private fun node(label: String) = requireVisible(label) { hasLabel(it, label) }

    private fun tag(tag: String) = requireVisible(tag) { it.viewIdResourceName == tag }

    private fun hasLabel(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label

    private fun requireVisible(name: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await("Visible node: $name") { visible(predicate)?.also { result = it } != null }
        return requireNotNull(result)
    }

    private fun visible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }
        return accessibilityRoots().firstNotNullOfOrNull(::find)
    }

    private fun accessibilityRoots() = listOfNotNull(uiAutomation.rootInActiveWindow) +
        uiAutomation.windows.mapNotNull { it.root }

    private fun layoutDiagnostics(): String = buildString {
        val labels = listOf("批准", "取消本次请求", "查看完整命令", "查看完整规则", "完整命令", "完整规则", "关闭")
        fun visit(node: AccessibilityNodeInfo) {
            val label = labels.firstOrNull { hasLabel(node, it) }
            val tag = node.viewIdResourceName?.takeIf { it.startsWith("approval-") }
            if (label != null || tag != null || node.isScrollable) {
                val rect = Rect().also(node::getBoundsInScreen)
                appendLine("${label ?: tag ?: "scroll"} $rect visible=${node.isVisibleToUser} clickable=${node.isClickable}")
            }
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        accessibilityRoots().distinctBy { it.windowId }.forEach { root ->
            appendLine("window=${root.windowId} class=${root.className}")
            visit(root)
        }
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also {
        node.getBoundsInScreen(it)
        check(!it.isEmpty)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = bounds(node)
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
        touch(down, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        repeat(5) {
            val current = candidate ?: return false
            if (current.isClickable && current.isEnabled && current.isVisibleToUser &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            candidate = current.parent
        }
        return false
    }

    private fun capture(name: String) {
        if (!captureScreenshots) return
        runCatching {
            val directory = screenshotDirectory ?: File(requireNotNull(targetContext.getExternalFilesDir(null)),
                "approval-layout-qa-${UUID.randomUUID()}").also {
                check(it.mkdir())
                screenshotDirectory = it
            }
            val screenshot = requireNotNull(uiAutomation.takeScreenshot())
            val output = File(directory, name)
            try {
                output.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                screenshot.recycle()
            }
            report.putString(name, output.absolutePath)
        }.onFailure { report.putString("capture_failure", it.javaClass.simpleName) }
    }

    private fun touch(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(70)
        }
    }
}

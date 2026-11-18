package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.MainActivity
import com.nanci.codexremote.ui.content.MessageContent
import com.nanci.codexremote.ui.content.markdownInlineSmokeChecks
import com.nanci.codexremote.ui.theme.NativeAppTheme
import com.nanci.codexremote.ui.theme.NativeTheme
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real reply renderer in an isolated QA installation. */
class MathInstrumentation : Instrumentation() {
    private var activity: MainActivity? = null
    private var output: File? = null
    private var stage = "setup"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = JSONObject()
        try {
            check(targetContext.packageName.startsWith("com.nanci.codexremote.")) {
                "Math fixture requires an isolated application ID"
            }
            output = requireNotNull(targetContext.getExternalFilesDir("math-regression"))
            stage = "parser"
            val parserFailures = markdownInlineSmokeChecks()
            check(parserFailures.isEmpty()) { parserFailures.joinToString() }
            report.put("markdown_parser_regression", true)
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity

            stage = "light"
            val openedLink = AtomicReference<String>()
            render(reply, dark = false, onLink = openedLink::set)
            checkFormulas()
            capture("math-light.png")
            val link = findNode("ordinary link") { it.text?.toString() == "普通链接" }
            click(Rect().also(link::getBoundsInScreen))
            waitFor("ordinary link callback") { openedLink.get() == "https://example.invalid/math" }
            report.put("ordinary_link_callback", true)
            clickMixedLink()
            waitFor("link callback in math paragraph") { openedLink.get() == "https://example.invalid/verify" }
            report.put("link_callback_in_math_paragraph", true)

            stage = "dark"
            render(reply.replace("a=33", "a=30+3"), dark = true)
            checkFormulas(firstFormula = "a=30+3")
            capture("math-dark.png")
            report.put("formulas_visible_in_both_themes", true)

            stage = "fallback"
            render(fallbackReply, dark = false)
            findNode("unsupported formula source") { it.text?.contains(unsupportedFormula) == true }
            findNode("unfinished formula body") { it.text?.contains("x+") == true }
            findNode("literal code") { it.text?.toString() == codeLiteral }
            check(findNodeOrNull { it.contentDescription?.contains("code_literal") == true } == null) {
                "Formula inside the code fence was rendered as math"
            }
            capture("math-fallback.png")
            report.put("unsupported_unfinished_and_fenced_source_visible", true)

            stage = "inline_formatting"
            render(formattingReply, dark = false)
            findNode("strikethrough without delimiters") {
                it.isVisibleToUser && it.text?.toString() == "Keep obsolete and current."
            }
            findNode("bold italic without delimiters") {
                it.isVisibleToUser && it.text?.toString() == "This is combined and italic."
            }
            capture("markdown-formatting.png")
            report.put("inline_formatting_delimiters_hidden", true)
            report.put("status", "passed")
        } catch (failure: Exception) {
            report.put("status", "failed").put("error", failure.toString())
            runCatching { capture("math-failure.png") }
        } finally {
            runCatching { runOnMainSync { activity?.finish() } }
        }
        report.put("stage", stage)
        output?.let { File(it, "results.json").writeText(report.toString(2)) }
        finish(if (report.optString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("report", report.toString())
                putString("output", output?.absolutePath.orEmpty())
            })
    }

    private fun render(source: String, dark: Boolean, onLink: (String) -> Unit = {}) {
        val message = JSONObject().put("id", "math-fixture").put("type", "agentMessage").put("text", source)
        runOnMainSync {
            requireNotNull(activity).setContent {
                NativeAppTheme(darkTheme = dark, highContrast = false) {
                    Column(Modifier.fillMaxSize().background(NativeTheme.colors.background).systemBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(20.dp)) {
                        MessageContent(message, onOpenLink = onLink)
                    }
                }
            }
        }
        waitForIdleSync()
    }

    private fun checkFormulas(firstFormula: String = "a=33") {
        // Check a theme-specific expression first so accessibility cannot return the preceding screen.
        for (formula in listOf(firstFormula) + formulas) {
            findNode("formula $formula") { node ->
                node.isVisibleToUser && node.contentDescription?.contains(formula) == true &&
                    !Rect().also(node::getBoundsInScreen).isEmpty
            }
        }
    }

    private fun capture(name: String) {
        val folder = requireNotNull(output)
        waitForIdleSync()
        // Accessibility can observe Compose's latest layout before the compositor presents it.
        SystemClock.sleep(200)
        val bitmap = requireNotNull(uiAutomation.takeScreenshot())
        try {
            File(folder, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun clickMixedLink() {
        val paragraph = findNode("paragraph with math and a link") {
            it.text?.toString()?.startsWith("验算说明与等式") == true
        }
        val paragraphBounds = Rect().also(paragraph::getBoundsInScreen)
        val linkBounds = Rect()
        val link = findNodeOrNull {
            it.getBoundsInScreen(linkBounds)
            it.isVisibleToUser && it.isClickable && !linkBounds.isEmpty &&
                Rect.intersects(paragraphBounds, linkBounds) && linkBounds.width() < paragraphBounds.width()
        }
        if (link != null) {
            click(linkBounds)
            return
        }
        val firstCharacter = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
        }
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        val characterBounds = if (paragraph.refreshWithExtraData(key, firstCharacter)) {
            paragraph.extras.getParcelableArray(key)?.firstOrNull() as? RectF
        } else null
        // The fixture starts with the link and fits on one line, including the inline equation.
        click(characterBounds?.let { Rect().also(it::roundOut) } ?: Rect(paragraphBounds).apply {
            right = minOf(right, left + height())
        })
    }

    private fun click(bounds: Rect) {
        check(!bounds.isEmpty)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            check(uiAutomation.injectInputEvent(down, true))
            check(uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun findNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitFor(label) { findNodeOrNull(predicate).also { found = it } != null }
        return requireNotNull(found)
    }

    private fun findNodeOrNull(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return uiAutomation.rootInActiveWindow?.let(::walk)
    }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $label")
    }

    private companion object {
        val formulas = listOf("a+d=10(a-d)", "11d=297, \\qquad d=27",
            "\\boxed{\\text{小明 6 岁，爸爸 33 岁，爷爷 60 岁}}", "\\frac{297}{11}=27", "60=10\\times6")
        val reply = """
            所以爸爸是 \(a=33\) 岁。

            由爷爷的年龄是小明的 10 倍，得：
            \[
            a+d=10(a-d)
            \]
            \[
            11d=297, \qquad d=27
            \]

            因此：
            \[
            \boxed{\text{小明 6 岁，爸爸 33 岁，爷爷 60 岁}}
            \]
            \[\frac{297}{11}=27\]

            [验算说明](https://example.invalid/verify)与等式 \(60=10\times6\)。

            [普通链接](https://example.invalid/math)
        """.trimIndent()
        const val unsupportedFormula = "\\(\\unrecognizedcmd{x}\\)"
        const val codeLiteral = "\\[code_literal\\]"
        val fallbackReply = """
            未知公式 $unsupportedFormula

            ```text
            $codeLiteral
            ```

            尚在生成 \(x+
        """.trimIndent()
        val formattingReply = """
            Keep ~~obsolete~~ and **current**.

            This is ***combined*** and *italic*.
        """.trimIndent()
    }
}

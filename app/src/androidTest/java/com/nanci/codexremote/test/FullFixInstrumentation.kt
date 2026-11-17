package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.*
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.core.RemoteEntry
import com.nanci.codexremote.ui.content.*
import com.nanci.codexremote.ui.shell.*
import com.nanci.codexremote.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated regression fixtures using the actual signed application's classes and Compose UI.
 * No model turns, credential replacements, real chat mutations, or system setting changes.
 */
class FullFixInstrumentation : Instrumentation() {
    private val report=JSONObject()
    private var stage="setup"
    private var activity:MainActivity?=null
    private var pipeline:ImagePipeline?=null
    private var scratch:File?=null
    private lateinit var output:File
    private var originalConnections=""

    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        var success=false
        try {
            check(targetContext.packageName=="com.nanci.codexremote")
            originalConnections=connectionIdentity()
            output=File(targetContext.getExternalFilesDir(null),"fix-regression-${UUID.randomUUID()}").also {check(it.mkdirs())}
            step("image-cache")
            val images=ImageCacheFixChecks.run(this);report.put("images",images);check(images.optString("status")=="passed")
            step("core-options")
            report.put("core",JSONArray(CoreStateFixChecks.run()))
            step("connection-identities")
            report.put("identities",JSONArray(ConnectionIdentityChecks.run(targetContext)))
            step("content-parsers")
            val contentFailures=ContentFixChecks.run();report.put("content_failures",JSONArray(contentFailures));check(contentFailures.isEmpty())
            report.put("shell",JSONArray(ShellFixChecks.run()))
            step("key-import")
            report.put("private_key_import",PrivateKeyImportChecks.run(this))
            workspaceChecks()
            themeChecks()
            uiChecks()
            check(connectionIdentity()==originalConnections)
            report.put("connections_preserved",true)
            report.put("remote_chat_mutations",false)
            success=true
        } catch(failure:Throwable) {
            report.put("failure_type",failure.javaClass.simpleName)
            report.put("failure_location",failure.stackTrace.filter {it.className.startsWith("com.nanci") }.joinToString("\n"))
            if(stage=="compose-ui" && ::output.isInitialized)runCatching {capture("failure-ui.png")}
        } finally {
            onMain {activity?.finish();activity=null}
            pipeline?.close()
            val removed=scratch?.deleteRecursively() ?: true
            if(!removed)success=false
            report.put("temporary_cache_removed",removed).put("status",if(success)"passed" else "failed").put("stage",stage)
            if(::output.isInitialized)File(output,"results.json").writeText(report.toString(2))
            finish(if(success)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
                putString("status",report.optString("status"));putString("stage",stage)
                putString("output",if(::output.isInitialized)output.absolutePath else "")
                putString("report",report.toString())
            })
        }
    }

    private fun workspaceChecks() {
        step("workspace-request-order")
        val calls=mutableMapOf<String,CompletableFuture<List<RemoteEntry>>>()
        lateinit var state:WorkspaceBrowserState
        onMain {
            state=WorkspaceBrowserState("/A") {path->CompletableFuture<List<RemoteEntry>>().also {calls[path]=it}}
            state.load("/A");state.load("/B")
            calls.getValue("/B").complete(listOf(RemoteEntry("B","/B/child",true,0)))
            calls.getValue("/A").complete(listOf(RemoteEntry("A","/A/child",true,0)))
            check(state.path=="/B" && state.entries.single().name=="B" && state.canSelect)
            state.edit("/typed");check(!state.canSelect && state.entries.isEmpty())
            state.load();calls.getValue("/typed").completeExceptionally(IllegalStateException())
            check(!state.canSelect && state.error!=null && state.entries.isEmpty())
            state.load("/C");state.close();calls.getValue("/C").complete(emptyList())
            check(!state.canSelect && state.loadedPath==null)
        }
        report.put("workspace_latest_request_and_close",true)
    }

    private fun themeChecks() {
        check(nativeColors(false,false).ink==androidx.compose.ui.graphics.Color(0xFF0D0D0D))
        check(nativeColors(true,false).ink==androidx.compose.ui.graphics.Color.White)
        check(nativeColors(false,true).muted==androidx.compose.ui.graphics.Color(0xFF414141))
        check(nativeColors(true,true).field==androidx.compose.ui.graphics.Color(0xFF212121))
        report.put("native_light_dark_high_contrast_tokens",true)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun uiChecks() {
        step("compose-ui")
        uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        scratch=File(targetContext.cacheDir,"full-fix-ui-${UUID.randomUUID()}").also {check(it.mkdir())}
        val isolated=object:ContextWrapper(targetContext) {override fun getCacheDir():File=requireNotNull(scratch)}
        val png=AtomicReference(png(120,60,0xffff4040.toInt()))
        val svg=AtomicReference(svg(120,60,"#e69922"))
        pipeline=ImagePipeline(isolated) {_,path,_->CompletableFuture.completedFuture(if(path.endsWith(".svg"))svg.get() else png.get())}
        val seen=ConcurrentHashMap<String,String>()
        val version=mutableIntStateOf(0)
        val sendCount=AtomicInteger();val stopCount=AtomicInteger();val savedName=AtomicReference("")
        val renameCount=AtomicInteger();val archiveCount=AtomicInteger()
        var dark=false;var high=false;var fontScale=1f
        val message=JSONObject().put("id","ui-fixture").put("type","agentMessage").put("text",
            "`行内代码` 与 [链接](https://example.invalid/qa)\n\n![PNG 同路径](/qa/cache.png)\n\n![SVG 引用图片][diagram]\n\n[diagram]: /qa/cache.svg")
        activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun render()=onMain {
            requireNotNull(activity).setContent {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
                    NativeAppTheme(darkTheme=dark,highContrast=high) {
                        val loader=remember(version.intValue) {
                            val delegate=requireNotNull(pipeline).scoped("ui-fixture")
                            object:ImageLoader {
                                override fun load(source:ImageSource,maxEdge:Int,onResult:(ImageLoadState)->Unit):()->Unit =
                                    delegate.load(source,maxEdge) {state->
                                        if(state is ImageLoadState.Ready)seen[source.value]="${state.bitmap.width}x${state.bitmap.height}"
                                        onResult(state)
                                    }
                            }
                        }
                        var opened by remember {mutableStateOf<ImageSource?>(null)}
                        Column(Modifier.fillMaxSize().background(NativeTheme.colors.background).systemBarsPadding()
                            .semantics {testTagsAsResourceId=true}) {
                            ChatChrome(ShellState(title="图片更新与大字体验证",subtitle="独立测试目录",hostName="测试",connected=true),
                                ShellActions(onRename={renameCount.incrementAndGet()},onArchive={archiveCount.incrementAndGet()}))
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
                                MessageContent(message,imageLoader=loader,onOpenImage={opened=it},imageBaseDirectory="/qa")
                            }
                            Composer(ComposerState(running=true,attachments=listOf(ShellAttachment("fixture","仅附件.png"))),
                                ComposerActions(onSend={sendCount.incrementAndGet()},onStop={stopCount.incrementAndGet()}))
                        }
                        opened?.let {source->ImageViewer(source,loader,onClose={opened=null},onSave={savedName.set(it)})}
                    }
                }
            }
        }
        render()
        waitFor {seen["/qa/cache.png"]=="120x60" && seen["/qa/cache.svg"]=="1024x512"}
        val baseHeight=bounds(findNode {it.viewIdResourceName=="chat-title-pill"}).height()
        capture("01-light-original.png")
        clickNode {it.contentDescription?.toString()=="更多"}
        waitFor {findNodeOrNull {it.text?.toString()=="重命名"}!=null}
        capture("01b-disabled-menu.png")
        fun disabledBySelfOrAncestor(node:AccessibilityNodeInfo):Boolean {
            var current:AccessibilityNodeInfo?=node
            while(current!=null) {if(!current.isEnabled)return true;current=current.parent}
            return false
        }
        check(disabledBySelfOrAncestor(findNode {it.text?.toString()=="重命名"}))
        check(disabledBySelfOrAncestor(findNode {it.text?.toString()=="归档"}))
        clickNode {it.text?.toString()=="重命名"};clickNode {it.text?.toString()=="归档"}
        SystemClock.sleep(200)
        check(renameCount.get()==0 && archiveCount.get()==0)
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitFor {findNodeOrNull {it.text?.toString()=="重命名"}==null}
        report.put("more_menu_disabled_actions",true)
        clickNode {it.contentDescription?.toString()=="发送" || it.viewIdResourceName=="composer-primary-send"}
        waitFor {sendCount.get()==1};check(stopCount.get()==0)
        report.put("running_attachment_only_send_clicked",true)
        png.set(png(120,45,0xff2277ff.toInt()));svg.set(svg(120,45,"#38a358"))
        onMain {version.intValue++}
        waitFor {seen["/qa/cache.png"]=="120x45" && seen["/qa/cache.svg"]=="1024x384"}
        capture("02-light-same-path-updated.png")
        clickNode {it.contentDescription?.toString()=="PNG 同路径"}
        waitFor {findNodeOrNull {it.contentDescription?.toString()=="保存图片"}!=null}
        clickNode {it.contentDescription?.toString()=="保存图片"}
        waitFor {savedName.get().endsWith(".png")}
        capture("03-updated-png-viewer.png")
        clickNode {it.text?.toString()=="关闭"}
        report.put("same_path_png_svg_ui_updates_and_viewer",true)
        dark=true;high=false;fontScale=1.5f;render()
        waitFor {findNodeOrNull {it.viewIdResourceName=="chat-title-pill"}!=null}
        SystemClock.sleep(500)
        val largeHeight=bounds(findNode {it.viewIdResourceName=="chat-title-pill"}).height()
        check(largeHeight>baseHeight)
        report.put("title_pill_height_normal_px",baseHeight).put("title_pill_height_large_px",largeHeight)
        capture("04-dark-large-font.png")
        dark=false;high=true;fontScale=1f;render();SystemClock.sleep(500)
        capture("05-high-contrast.png")
        val openedLink=AtomicReference("")
        onMain {
            requireNotNull(activity).setContent {NativeAppTheme {Box(Modifier.fillMaxSize().systemBarsPadding()) {
                PlanReader("[验证计划链接](https://example.invalid/audit)",onClose={},onCopy={},onDownload={},onOpenLink={openedLink.set(it)})
            }}}
        }
        waitFor {findNodeOrNull {it.text?.toString()=="验证计划链接"}!=null}
        clickNode {it.text?.toString()=="验证计划链接"}
        waitFor {openedLink.get()=="https://example.invalid/audit"}
        report.put("plan_link_ui_callback_clicked",true)
        capture("06-plan-link.png")
        val selectedReason=AtomicReference("")
        val draft=mutableStateOf("")
        onMain {requireNotNull(activity).setContent {NativeAppTheme {
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(),contentAlignment=androidx.compose.ui.Alignment.BottomCenter) {
                Composer(ComposerState(text=draft.value,focusRequest=1,modelId="gpt-6.1-terra",reasoning="xhigh",
                    models=listOf(ShellModel("gpt-6.1-terra","GPT-6.1 Terra",listOf("high","xhigh")))),
                    ComposerActions(onTextChange={draft.value=it},onReasoning={selectedReason.set(it)}))
            }
        }}}
        fun imeVisible():Boolean {var shown=false;onMain {shown=requireNotNull(activity).window.decorView.rootWindowInsets
            ?.isVisible(android.view.WindowInsets.Type.ime())==true};return shown}
        waitFor {imeVisible()}
        clickNode {it.text?.toString()?.contains("6.1 Terra")==true}
        waitFor {findNodeOrNull {it.text?.toString()=="极高"}!=null}
        check(imeVisible())
        waitForIdleSync();SystemClock.sleep(250)
        var imeTop=0
        onMain {val view=requireNotNull(activity).window.decorView;val location=IntArray(2);view.getLocationOnScreen(location)
            imeTop=location[1]+view.height-view.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom}
        check(bounds(findNode {it.text?.toString()=="GPT-6.1 Terra"}).bottom<=imeTop)
        report.put("composer_menu_above_keyboard",true)
        capture("06b-menu-keeps-keyboard.png")
        clickNode {it.text?.toString()=="极高"}
        waitFor {selectedReason.get()=="xhigh"}
        check(imeVisible())
        clickNode {it.text?.toString()?.contains("6.1 Terra")==true}
        waitFor {findNodeOrNull {it.text?.toString()=="极高"}!=null}
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitFor {findNodeOrNull {it.text?.toString()=="极高"}==null}
        check(imeVisible())
        clickNode {it.text?.toString()?.contains("6.1 Terra")==true}
        waitFor {findNodeOrNull {it.text?.toString()=="极高"}!=null}
        clickPoint(30f,200f)
        waitFor {findNodeOrNull {it.text?.toString()=="极高"}==null}
        check(imeVisible())
        report.put("composer_menu_outside_dismiss_keeps_keyboard",true)
        val editor=findNode {it.isEditable}
        check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"菜单关闭后继续输入")
        }))
        waitFor {draft.value=="菜单关闭后继续输入"}
        report.put("composer_menu_preserves_keyboard_and_back_and_input",true)
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        onMain { requireNotNull(activity).setContent {NativeAppTheme {Box(Modifier.fillMaxSize().systemBarsPadding()) {
            com.nanci.codexremote.connection.ConnectionScreen(onSave={_,_->},onCancel={},connected=false)
        }}}}
        waitFor {findNodeOrNull {it.contentDescription?.toString()=="返回"}!=null}
        report.put("connection_back_accessibility_label",true)
        capture("07-connection-form.png")
    }

    private fun connectionIdentity():String {
        val store=ConnectionStore(targetContext)
        return store.activeId+":"+store.list().joinToString {it.id+":"+it.profile.toString()+":"+it.hasCredential}
    }
    private fun capture(name:String) {
        waitForIdleSync();SystemClock.sleep(150)
        val bitmap=requireNotNull(uiAutomation.takeScreenshot())
        File(output,name).outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    private fun step(value:String) {stage=value;sendStatus(1,Bundle().apply {putString("stream","\nFIX_CHECK $value\n")})}
    private fun onMain(action:()->Unit) {runOnMainSync {action()}}
    private fun waitFor(timeout:Long=20_000,predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<until) {if(predicate())return;SystemClock.sleep(100)}
        error("fixture condition timed out")
    }
    private fun findNodeOrNull(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        fun walk(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(predicate(node))return node
            for(i in 0 until node.childCount)node.getChild(i)?.let {walk(it)?.let {found->return found}}
            return null
        }
        uiAutomation.rootInActiveWindow?.let(::walk)?.let {return it}
        for(window in uiAutomation.windows)window.root?.let(::walk)?.let {return it}
        return null
    }
    private fun findNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        var found:AccessibilityNodeInfo?=null
        waitFor {found=findNodeOrNull(predicate);found!=null}
        return requireNotNull(found)
    }
    private fun bounds(node:AccessibilityNodeInfo)=Rect().also(node::getBoundsInScreen)
    private fun clickNode(predicate:(AccessibilityNodeInfo)->Boolean) {
        val rect=bounds(findNode(predicate));clickPoint(rect.exactCenterX(),rect.exactCenterY())
    }
    private fun clickPoint(x:Float,y:Float) {
        val now=SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0)
        val up=MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,x,y,0)
        try {check(uiAutomation.injectInputEvent(down,true));check(uiAutomation.injectInputEvent(up,true))}
        finally {down.recycle();up.recycle()}
    }
    private fun png(w:Int,h:Int,color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color)
        return try {ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()}} finally {bitmap.recycle()}
    }
    private fun svg(w:Int,h:Int,color:String)="<svg xmlns='http://www.w3.org/2000/svg' width='$w' height='$h'><rect width='$w' height='$h' fill='$color'/></svg>".toByteArray()
}

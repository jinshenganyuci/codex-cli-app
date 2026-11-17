package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.nanci.codexremote.ImagePipeline
import com.nanci.codexremote.boundedImageOffset
import com.nanci.codexremote.imageFileName
import com.nanci.codexremote.ui.content.ImageLoadState
import com.nanci.codexremote.ui.content.ImageSource
import com.nanci.codexremote.ui.content.ImageSourceKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Actual production cache/decoder regression checks, with task-owned files and a mutable remote
 * adapter. No accounts, chats, server files or real app caches are modified. Every fix is asserted.
 */
class ImageCacheFixInstrumentation : Instrumentation() {
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        val report=ImageCacheFixChecks.run(this)
        val result=Bundle().apply {
            putString("status",report.optString("status"))
            putString("report",report.toString())
            putString("stream","\nImage fixes: ${report.optString("status")} (${report.optString("stage")})\n")
        }
        finish(if(report.optString("status")=="passed")Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}

object ImageCacheFixChecks {
    fun run(instrumentation:Instrumentation):JSONObject=ImageCacheFixSession(instrumentation).run()
}

private class ImageCacheFixSession(private val instrumentation:Instrumentation) {
    private val targetContext get()=instrumentation.targetContext
    private fun runOnMainSync(action:()->Unit)=instrumentation.runOnMainSync {action()}
    private val result=Bundle()
    private val pipelines=mutableListOf<ImagePipeline>()
    private var scratch:File?=null
    private var stage="setup"

    fun run():JSONObject {
        try {
            check(targetContext.packageName=="com.nanci.codexremote")
            scratch=File(targetContext.cacheDir,"image-cache-fix-${UUID.randomUUID()}").also {check(it.mkdir())}
            checkFormat("png",png(16,8,0xffff0000.toInt()),png(32,12,0xff0000ff.toInt()),"image/png")
            checkFormat("svg",svg(16,8,"red"),svg(32,12,"blue"),"image/svg+xml")
            filenameChecks()
            panChecks()
            delayedReadChecks()
            shutdownChecks()
            result.putBoolean("production_cache_untouched",true)
            result.putBoolean("server_and_connections_untouched",true)
            result.putString("status","passed")
        } catch(failure:Throwable) {
            result.putString("status","failed")
            result.putString("error_type",failure.javaClass.simpleName)
            result.putString("assertion",failure.message?.takeIf {it.startsWith("check_")} ?: "check_failed")
        } finally {
            pipelines.forEach {runCatching {it.close()}}
            val cleaned=scratch?.deleteRecursively() ?: true
            result.putBoolean("temporary_resources_removed",cleaned)
            if(!cleaned)result.putString("status","failed")
            result.putString("stage",stage)
            result.putString("stream","\nImage fixes: ${result.getString("status")} ($stage)\n")
        }
        return JSONObject().also {json -> result.keySet().forEach {json.put(it,result.get(it))}}
    }

    private fun checkFormat(format:String,a:ByteArray,b:ByteArray,mime:String) {
        stage="${format}_same_path_freshness"
        val root=File(requireNotNull(scratch),format).also {check(it.mkdir())}
        val isolated=object:ContextWrapper(targetContext){override fun getCacheDir()=root}
        val contents=ConcurrentHashMap<String,ByteArray>().apply {put("owner-A",a);put("owner-B",a)}
        val reads=AtomicInteger()
        fun newPipeline()=ImagePipeline(isolated){owner,_,_ ->
            reads.incrementAndGet()
            CompletableFuture.completedFuture(requireNotNull(contents[owner]))
        }.also {pipelines+=it}
        var pipeline=newPipeline()
        // Reuse the exact source object and UI key: freshness must not depend on a new message ID.
        val source=ImageSource(ImageSourceKind.RemotePath,"/audit-fixtures/modified.$format",mime,"unchanged-ui-key")
        val initial=awaitReady(pipeline,"owner-A",source,1024)
        check(initial.extension==format) {"check_${format}_detected_format"}
        val clipboardA=pipeline.clipboardFile("owner-A",source).get(15,TimeUnit.SECONDS)
        val originalClipboardA=clipboardA.readBytes()
        contents["owner-A"]=b
        val updated=awaitReady(pipeline,"owner-A",source,1024)
        check(ratio(initial)!=ratio(updated)) {"check_${format}_memory_fresh"}
        check(pipeline.original("owner-A",source).get(15,TimeUnit.SECONDS).contentEquals(b)) {"check_${format}_original_fresh"}
        val viewer=awaitReady(pipeline,"owner-A",source,2048)
        check(ratio(viewer)==ratio(updated)) {"check_${format}_viewer_fresh"}
        val clipboardB=pipeline.clipboardFile("owner-A",source).get(15,TimeUnit.SECONDS)
        check(clipboardB!=clipboardA && !clipboardB.readBytes().contentEquals(originalClipboardA)) {"check_${format}_clipboard_fresh"}
        check(clipboardA.readBytes().contentEquals(originalClipboardA)) {"check_${format}_old_clipboard_uri_not_overwritten"}
        val clipboardBitmap=BitmapFactory.decodeFile(clipboardB.absolutePath) ?: error("check_${format}_clipboard_decodable")
        try {check(clipboardBitmap.width.toDouble()/clipboardBitmap.height==ratio(updated)) {"check_${format}_clipboard_represents_B"}}
        finally {clipboardBitmap.recycle()}
        pipeline.close()
        pipeline=newPipeline()
        check(ratio(awaitReady(pipeline,"owner-A",source,1024))==ratio(updated)) {"check_${format}_reopen_fresh"}
        result.putBoolean("${format}_same_path_same_ui_key_refreshes",true)
        result.putBoolean("${format}_viewer_original_clipboard_agree",true)
        result.putBoolean("${format}_old_clipboard_uri_immutable",true)
        result.putBoolean("${format}_new_pipeline_current_content",true)

        stage="${format}_owner_and_immutable_controls"
        check(ratio(awaitReady(pipeline,"owner-B",source,1024))==ratio(initial)) {"check_${format}_owner_B"}
        check(ratio(awaitReady(pipeline,"owner-A",source,1024))==ratio(updated)) {"check_${format}_owner_A"}
        fun inline(bytes:ByteArray)=ImageSource(ImageSourceKind.DataUri,"data:$mime;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP),mime,"same-inline-key")
        val countBefore=reads.get()
        val inlineA=awaitReady(pipeline,"owner-A",inline(a),1024)
        val inlineB=awaitReady(pipeline,"owner-A",inline(b),1024)
        check(ratio(inlineA)!=ratio(inlineB) && reads.get()==countBefore) {"check_${format}_inline_versions"}
        result.putBoolean("${format}_owner_isolation",true)
        result.putBoolean("${format}_inline_content_versions",true)

        stage="${format}_repaired_file"
        contents["owner-A"]="not an image".toByteArray()
        check(awaitState(pipeline,"owner-A",source,1024) is ImageLoadState.Error) {"check_${format}_invalid_replaces_old_success"}
        contents["owner-A"]=b
        check(ratio(awaitReady(pipeline,"owner-A",source,1024))==ratio(updated)) {"check_${format}_repair_retry"}
        result.putBoolean("${format}_invalid_file_error_and_repair",true)
        pipeline.close()
    }

    private fun filenameChecks() {
        stage="filename_detected_content"
        fun web(path:String)=ImageSource(ImageSourceKind.HttpUrl,"https://example.invalid/$path",cacheKey="filename")
        check(imageFileName(web("cat.svg?token=ignored"))=="cat.svg") {"check_svg_url_suffix"}
        check(imageFileName(web("photo.jpg"))=="photo.jpg") {"check_jpeg_url_suffix"}
        check(imageFileName(web("download"),"svg")=="download.svg") {"check_extensionless_svg"}
        check(imageFileName(web("wrong.png"),"svg")=="wrong.svg") {"check_detected_format_wins"}
        check(imageFileName(web("cat%20one+two.svg"),"svg")=="cat one+two.svg") {"check_url_decode_filename"}
        result.putBoolean("http_names_and_detected_format",true)
    }

    private fun panChecks() {
        stage="viewer_pan_bounds"
        val viewport=IntSize(100,100)
        // Offset packs Float bits: +0 and -0 can compare unequal even though positions coincide.
        fun at(actual:Offset,x:Float,y:Float)=kotlin.math.abs(actual.x-x)<.001f && kotlin.math.abs(actual.y-y)<.001f
        check(at(boundedImageOffset(200,100,viewport,1f,Offset(1000f,-1000f)),0f,0f)) {"check_fit_pan_zero"}
        check(at(boundedImageOffset(200,100,viewport,2f,Offset(1000f,-1000f)),50f,0f)) {"check_landscape_pan_bounds"}
        check(at(boundedImageOffset(100,200,viewport,2f,Offset(1000f,-1000f)),0f,-50f)) {"check_portrait_pan_bounds"}
        check(at(boundedImageOffset(100,100,viewport,3f,Offset(-200f,200f)),-100f,100f)) {"check_square_pan_bounds"}
        result.putBoolean("viewer_pan_keeps_image_in_viewport",true)
    }

    private fun delayedReadChecks() {
        stage="refresh_while_old_read_pending"
        val root=File(requireNotNull(scratch),"delayed").also {check(it.mkdir())}
        val isolated=object:ContextWrapper(targetContext){override fun getCacheDir()=root}
        val a=png(16,8,0xffff0000.toInt())
        val b=png(32,12,0xff0000ff.toInt())
        val oldBytes=CompletableFuture<ByteArray>()
        val oldEntered=CountDownLatch(1)
        val reads=AtomicInteger()
        val pipeline=ImagePipeline(isolated){_,_,_ ->
            if(reads.incrementAndGet()==1){oldEntered.countDown();oldBytes}
            else CompletableFuture.completedFuture(b)
        }.also {pipelines+=it}
        val source=ImageSource(ImageSourceKind.RemotePath,"/audit-fixtures/delayed.png","image/png","unchanged-message")
        val oldResult=CompletableFuture<ImageLoadState>()
        runOnMainSync {pipeline.scoped("owner").load(source,1024){oldResult.complete(it)}}
        check(oldEntered.await(10,TimeUnit.SECONDS)) {"check_old_read_started"}
        try {
            val newLoader=pipeline.scoped("owner")
            val newResult=CompletableFuture<ImageLoadState>()
            runOnMainSync {newLoader.load(source,1024){newResult.complete(it)}}
            val fresh=newResult.get(10,TimeUnit.SECONDS) as? ImageLoadState.Ready ?: error("check_new_loader_ready")
            check(fresh.bitmap.width==32 && fresh.bitmap.height==12 && !oldBytes.isDone) {"check_new_loader_does_not_join_old"}
            check(pipeline.original("owner",source).get(10,TimeUnit.SECONDS).contentEquals(b)) {"check_original_does_not_join_old"}
            check(pipeline.clipboardFile("owner",source).get(10,TimeUnit.SECONDS).readBytes().contentEquals(b)) {"check_clipboard_does_not_join_old"}
            oldBytes.complete(a)
            val old=oldResult.get(10,TimeUnit.SECONDS) as? ImageLoadState.Ready ?: error("check_old_request_snapshot")
            check(old.bitmap.width==16 && old.bitmap.height==8) {"check_old_request_snapshot_A"}
            val afterOld=CompletableFuture<ImageLoadState>()
            runOnMainSync {newLoader.load(source,1024){afterOld.complete(it)}}
            val final=afterOld.get(10,TimeUnit.SECONDS) as? ImageLoadState.Ready ?: error("check_after_old_ready")
            check(final.bitmap.width==32 && final.bitmap.height==12) {"check_late_A_cannot_poison_B"}
            result.putBoolean("refresh_epoch_does_not_join_old_read",true)
            result.putBoolean("save_copy_do_not_join_pre_action_read",true)
            result.putBoolean("late_old_read_cannot_poison_new_content",true)
        } finally {oldBytes.complete(a);pipeline.close()}
    }

    private fun shutdownChecks() {
        stage="close_completes_active_and_queued_futures"
        val root=File(requireNotNull(scratch),"shutdown").also {check(it.mkdir())}
        val isolated=object:ContextWrapper(targetContext){override fun getCacheDir()=root}
        val entered=CountDownLatch(2)
        val heldBytes=CompletableFuture<ByteArray>()
        val pipeline=ImagePipeline(isolated){_,_,_ -> entered.countDown();heldBytes}.also {pipelines+=it}
        fun source(index:Int)=ImageSource(ImageSourceKind.RemotePath,"/audit-fixtures/held-$index.png","image/png","held-$index")
        val activeOriginal=pipeline.original("owner",source(1))
        val activeCopy=pipeline.clipboardFile("owner",source(2))
        try {
            check(entered.await(10,TimeUnit.SECONDS)) {"check_both_workers_held"}
            val queuedOriginal=pipeline.original("owner",source(3))
            val queuedCopy=pipeline.clipboardFile("owner",source(4))
            check(!queuedOriginal.isDone && !queuedCopy.isDone) {"check_requests_queued"}
            pipeline.close()
            listOf(activeOriginal,activeCopy,queuedOriginal,queuedCopy).forEach {
                check(it.isDone && it.isCompletedExceptionally) {"check_close_completes_all_requests"}
            }
            check(pipeline.original("owner",source(5)).isCompletedExceptionally) {"check_after_close_original"}
            check(pipeline.clipboardFile("owner",source(6)).isCompletedExceptionally) {"check_after_close_clipboard"}
            result.putBoolean("close_completes_active_and_queued_requests",true)
            result.putBoolean("post_close_requests_fail_without_throwing",true)
        } finally {heldBytes.complete(png(16,8,0xffff0000.toInt()));pipeline.close()}
    }

    private fun awaitState(pipeline:ImagePipeline,owner:String,source:ImageSource,edge:Int):ImageLoadState {
        val ready=CompletableFuture<ImageLoadState>()
        runOnMainSync {pipeline.scoped(owner).load(source,edge){ready.complete(it)}}
        return ready.get(20,TimeUnit.SECONDS)
    }
    private fun awaitReady(pipeline:ImagePipeline,owner:String,source:ImageSource,edge:Int)=
        awaitState(pipeline,owner,source,edge) as? ImageLoadState.Ready ?: error("check_image_load_failed")
    private fun ratio(state:ImageLoadState.Ready)=state.bitmap.width.toDouble()/state.bitmap.height
    private fun svg(width:Int,height:Int,color:String)="<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='$height'><rect width='$width' height='$height' fill='$color'/></svg>".toByteArray()
    private fun png(width:Int,height:Int,color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        return try {bitmap.eraseColor(color);ByteArrayOutputStream().use {out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}}
        finally {bitmap.recycle()}
    }
}

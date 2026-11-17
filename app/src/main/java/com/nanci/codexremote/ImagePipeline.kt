package com.nanci.codexremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import com.nanci.codexremote.ui.content.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Fetch, decode, EXIF correction and caching never run on the Compose/main thread. */
class ImagePipeline(
    context: Context,
    private val readRemote: (String, String, Int) -> CompletableFuture<ByteArray>,
) : Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val workers=Executors.newFixedThreadPool(2)
    private val closed=AtomicBoolean(false)
    private val directory=File(context.cacheDir,"image-content-v2")
    private val exports=File(context.cacheDir,"exports/images-v2")
    private val reads=ConcurrentHashMap<String,CompletableFuture<ByteArray>>()
    private val pending=ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val revisions=AtomicLong()
    private val bitmaps=object:LruCache<String,Bitmap>(24*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }

    fun scoped(owner:String):ImageLoader {
        // A UI revalidation must not join an earlier loader's still-running pre-edit read.
        val revision=revisions.incrementAndGet()
        return object:ImageLoader {
        override fun load(source:ImageSource,maxEdge:Int,onResult:(ImageLoadState)->Unit):()->Unit {
            val cancelled=AtomicBoolean(false)
            if(!closed.get())try {workers.execute {
                if(cancelled.get() || closed.get())return@execute
                val result=try {
                    // A path/URL is mutable. Fetch its current version before consulting decoded
                    // bitmaps; changing a Compose key alone cannot validate remote content.
                    val data=bytes(owner,source,revision)
                    val edge=maxEdge.coerceIn(128,2048)
                    val key="${contentKey(owner,data)}:$edge"
                    val bitmap=bitmaps.get(key) ?: decode(data,edge).also {bitmaps.put(key,it)}
                    ImageLoadState.Ready(bitmap.asImageBitmap(),extension(data))
                } catch(_:OutOfMemoryError) {
                    bitmaps.evictAll();ImageLoadState.Error("图片过大，暂时无法预览")
                } catch(error:Exception) {
                    ImageLoadState.Error(safeImageError(error))
                }
                if(!cancelled.get() && !closed.get())main.post {if(!cancelled.get() && !closed.get())onResult(result)}
            }} catch(_:RejectedExecutionException) {cancelled.set(true)}
            return {cancelled.set(true)}
        }
        }
    }

    fun original(owner:String,source:ImageSource):CompletableFuture<ByteArray> = async {bytes(owner,source)}

    fun clipboardFile(owner:String,source:ImageSource):CompletableFuture<File> = async {
        val original=bytes(owner,source)
        // Clipboard consumers expect a bitmap; preserve the original SVG for explicit saving.
        val bytes=if(SvgImageDecoder.isSvg(original)) {
            val bitmap=decode(original,2048)
            try {ByteArrayOutputStream().use {out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) {"图片复制失败"}
                out.toByteArray()
            }} finally {bitmap.recycle()}
        } else original
        // Name the exported representation by its actual content. A refreshed source must never
        // resolve to the previous version's still-existing clipboard file.
        saveIn(exports,"image-${contentKey(owner,bytes)}.${extension(bytes)}",bytes)
    }

    /** Warm decoded content after upload; never make a mutable path an immortal byte-cache key. */
    fun seedRemote(owner:String,path:String,bytes:ByteArray) {
        if(closed.get() || bytes.size>MAX_IMAGE_BYTES)return
        try {workers.execute {
            runCatching {
                require(path.startsWith('/'))
                validate(bytes)
                val key="${contentKey(owner,bytes)}:1024"
                if(bitmaps.get(key)==null)bitmaps.put(key,decode(bytes,1024))
            }
        }} catch(_:RejectedExecutionException) { /* Closing the optional warm-up is harmless. */ }
    }

    private fun bytes(owner:String,source:ImageSource,revision:Long=revisions.incrementAndGet()):ByteArray {
        val key=digest(owner,source)
        if(source.kind==ImageSourceKind.RemotePath || source.kind==ImageSourceKind.HttpUrl) {
            // Only the same loader's concurrent request for the same message can share a fetch.
            // Save/copy use their own revision; they never join a pre-click preview read.
            val readKey="$key:$revision:${source.cacheKey}"
            val pending=CompletableFuture<ByteArray>()
            val existing=reads.putIfAbsent(readKey,pending)
            if(existing!=null)return existing.get(100,TimeUnit.SECONDS)
            try {
                val current=if(source.kind==ImageSourceKind.RemotePath)
                    readRemote(owner,source.value,MAX_IMAGE_BYTES).get(90,TimeUnit.SECONDS)
                else download(source.value)
                validate(current)
                pending.complete(current)
                return current
            } catch(error:Throwable) {
                pending.completeExceptionally(error)
                throw error
            } finally {reads.remove(readKey,pending)}
        }
        // Inline data is immutable: its entire value participates in the digest.
        val cached=File(directory,key)
        if(cached.isFile && cached.length()<=MAX_IMAGE_BYTES) {
            runCatching {cached.inputStream().use {readBounded(it)}.also(::validate)}.getOrNull()?.let {
                cached.setLastModified(System.currentTimeMillis());return it
            }
            cached.delete()
        }
        val data=when(source.kind) {
            ImageSourceKind.RemotePath,ImageSourceKind.HttpUrl -> error("Unexpected mutable image source")
            ImageSourceKind.DataUri -> {
                val comma=source.value.indexOf(',')
                require(comma in 1..256) {"图片编码暂不支持"}
                val header=source.value.substring(0,comma)
                if(header.endsWith(";base64",true))decodeBase64(source.value.substring(comma+1))
                else {
                    require(header.substringBefore(';').equals("data:image/svg+xml",true) &&
                        source.value.length-comma<=SvgImageDecoder.MAX_SVG_BYTES*3) {"图片编码暂不支持"}
                    // URLDecoder treats '+' as form whitespace; data URLs must keep literal pluses.
                    java.net.URLDecoder.decode(source.value.substring(comma+1).replace("+","%2B"),"UTF-8").toByteArray(Charsets.UTF_8)
                }
            }
            ImageSourceKind.GeneratedBase64 -> decodeBase64(source.value)
        }
        validate(data)
        // A cache write failure must not make otherwise valid inline images unreadable.
        runCatching {saveIn(directory,key,data)}
        return data
    }

    private fun validate(data:ByteArray) {
        require(data.isNotEmpty() && data.size<=MAX_IMAGE_BYTES) {"图片超过 20 MB 或内容为空"}
        if(SvgImageDecoder.isSvg(data))SvgImageDecoder.validate(data)
        else {
            val header=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(data,0,data.size,header)
            require(header.outWidth>0 && header.outHeight>0) {"图片格式无法识别"}
        }
    }

    private fun download(address:String):ByteArray {
        var url=URL(address)
        repeat(5) {
            require(url.protocol in setOf("https","http")) {"图片链接暂不支持"}
            val connection=url.openConnection() as HttpURLConnection
            connection.connectTimeout=12_000;connection.readTimeout=20_000;connection.instanceFollowRedirects=false
            connection.useCaches=false
            connection.setRequestProperty("Cache-Control","no-cache")
            try {
                if(connection.responseCode in listOf(301,302,303,307,308)) {
                    val location=connection.getHeaderField("Location") ?: error("图片地址无效")
                    url=URL(url,location)
                } else {
                    require(connection.responseCode in 200..299) {"图片下载失败"}
                    require(connection.contentLengthLong<=MAX_IMAGE_BYTES) {"图片超过 20 MB"}
                    return connection.inputStream.use {readBounded(it)}
                }
            } finally {connection.disconnect()}
        }
        error("图片地址跳转次数过多")
    }

    private fun decodeBase64(value:String):ByteArray {
        require(value.length<=MAX_IMAGE_BYTES*4/3+8192) {"图片超过 20 MB"}
        return try {Base64.decode(value,Base64.DEFAULT)} catch(_:IllegalArgumentException) {error("图片编码无法识别")}
    }

    private fun readBounded(input:InputStream):ByteArray {
        val output=ByteArrayOutputStream();val buffer=ByteArray(16*1024)
        while(true) {
            val n=input.read(buffer);if(n<0)break
            require(output.size()+n<=MAX_IMAGE_BYTES) {"图片超过 20 MB"}
            output.write(buffer,0,n)
        }
        return output.toByteArray()
    }

    @Synchronized private fun saveIn(folder:File,key:String,data:ByteArray):File {
        check(folder.isDirectory || folder.mkdirs()) {"图片缓存不可用"}
        val target=File(folder,key)
        if(!target.exists()) {
            val temp=File.createTempFile("image-",".tmp",folder)
            try {temp.writeBytes(data);check(temp.renameTo(target)) {"图片缓存写入失败"}} finally {temp.delete()}
        }
        target.setLastModified(System.currentTimeMillis())
        val entries=folder.listFiles()?.filter {it.isFile && !it.name.endsWith(".tmp")}?.sortedBy {it.lastModified()}.orEmpty()
        var total=entries.sumOf {it.length()}
        for(file in entries)if(total>96L*1024*1024 && file.name!=key) {val size=file.length();if(file.delete())total-=size}
        return target
    }

    private fun contentKey(owner:String,data:ByteArray):String {
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update(owner.toByteArray(Charsets.UTF_8));digest.update(0.toByte());digest.update(data)
        return digest.digest().joinToString("") {"%02x".format(it)}
    }

    private fun digest(owner:String,source:ImageSource):String {
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update((owner+"\u0000"+source.kind.name+"\u0000").toByteArray())
        // Chunking avoids a second multi-megabyte byte array just to obtain a cache key.
        var offset=0
        while(offset<source.value.length) {
            val end=minOf(offset+16*1024,source.value.length)
            digest.update(source.value.substring(offset,end).toByteArray(Charsets.UTF_8));offset=end
        }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }

    private fun <T> async(block:()->T):CompletableFuture<T> {
        val result=CompletableFuture<T>()
        pending.add(result)
        result.whenComplete {_,_ -> pending.remove(result)}
        if(closed.get())return result.apply {completeExceptionally(IllegalStateException("图片服务已关闭"))}
        try {workers.execute {
            if(closed.get() || result.isDone) {
                result.completeExceptionally(IllegalStateException("图片服务已关闭"))
                return@execute
            }
            try {val value=block();main.post {result.complete(value)}}
            catch(_:OutOfMemoryError) {
                bitmaps.evictAll();main.post {result.completeExceptionally(IllegalStateException("图片过大，暂时无法处理"))}
            }
            catch(error:Exception) {main.post {result.completeExceptionally(IllegalStateException(safeImageError(error)))}}
        }} catch(_:RejectedExecutionException) {result.completeExceptionally(IllegalStateException("图片服务已关闭"))}
        return result
    }

    override fun close() {
        closed.set(true)
        pending.forEach {it.completeExceptionally(IllegalStateException("图片服务已关闭"))}
        pending.clear()
        reads.values.forEach {it.completeExceptionally(IllegalStateException("图片服务已关闭"))}
        reads.clear();workers.shutdownNow();bitmaps.evictAll()
    }

    companion object {
        const val MAX_IMAGE_BYTES=20*1024*1024
        fun decode(bytes:ByteArray,maxEdge:Int):Bitmap {
            require(Looper.myLooper()!=Looper.getMainLooper()) {"图片必须在后台解码"}
            require(bytes.size<=MAX_IMAGE_BYTES) {"图片超过 20 MB"}
            if(SvgImageDecoder.isSvg(bytes))return SvgImageDecoder.decode(bytes,maxEdge)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..100000 && bounds.outHeight in 1..100000) {"图片格式无法识别"}
            var sample=1
            while(bounds.outWidth/(sample*2)>=maxEdge || bounds.outHeight/(sample*2)>=maxEdge)sample*=2
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
                ?: error("图片无法解码")
            val largest=maxOf(decoded.width,decoded.height)
            val bitmap=if(largest>maxEdge)Bitmap.createScaledBitmap(decoded,
                (decoded.width.toLong()*maxEdge/largest).toInt().coerceAtLeast(1),
                (decoded.height.toLong()*maxEdge/largest).toInt().coerceAtLeast(1),true).also {if(it!==decoded)decoded.recycle()} else decoded
            val orientation=runCatching {ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix()
            when(orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {matrix.setRotate(90f);matrix.postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {matrix.setRotate(-90f);matrix.postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also {if(it!==bitmap)bitmap.recycle()}
        }
        fun extension(bytes:ByteArray):String {
            if(SvgImageDecoder.isSvg(bytes))return "svg"
            val header=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,header)
            return when(header.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                "image/avif" -> "avif"
                "image/bmp" -> "bmp"
                else -> "img"
            }
        }
        private fun safeImageError(error:Throwable):String {
            val cause=generateSequence(error) {it.cause}.last()
            return when {
                cause is java.util.concurrent.TimeoutException || cause is java.net.SocketTimeoutException -> "加载超时，请点按重试"
                cause is com.nanci.codexremote.core.CodexException -> cause.safeMessage.take(100)
                cause is IllegalArgumentException || cause is IllegalStateException -> cause.message?.take(100) ?: "图片暂时无法读取"
                else -> "图片暂时无法读取，请检查连接后重试"
            }
        }
    }
}

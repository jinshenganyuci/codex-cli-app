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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fetch, decode, EXIF correction and caching never run on the Compose/main thread. */
class ImagePipeline(
    context: Context,
    private val readRemote: (String, String, Int) -> CompletableFuture<ByteArray>,
) : Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val workers=Executors.newFixedThreadPool(2)
    private val closed=AtomicBoolean(false)
    private val directory=File(context.cacheDir,"image-content-v1")
    private val exports=File(context.cacheDir,"exports")
    private val bitmaps=object:LruCache<String,Bitmap>(24*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }

    fun scoped(owner:String):ImageLoader=object:ImageLoader {
        override fun load(source:ImageSource,maxEdge:Int,onResult:(ImageLoadState)->Unit):()->Unit {
            val cancelled=AtomicBoolean(false)
            if(!closed.get())workers.execute {
                if(cancelled.get() || closed.get())return@execute
                var attemptedKey:String?=null
                val result=try {
                    val digest=digest(owner,source)
                    attemptedKey=digest
                    val edge=maxEdge.coerceIn(128,2048)
                    val key="$digest:$edge"
                    val bitmap=bitmaps.get(key) ?: decode(bytes(owner,source,digest),edge).also {bitmaps.put(key,it)}
                    ImageLoadState.Ready(bitmap.asImageBitmap())
                } catch(_:OutOfMemoryError) {
                    bitmaps.evictAll();ImageLoadState.Error("图片过大，暂时无法预览")
                } catch(error:Exception) {
                    // A non-image response must not poison retries after the server file is repaired.
                    attemptedKey?.let(::invalidate)
                    ImageLoadState.Error(safeImageError(error))
                }
                if(!cancelled.get() && !closed.get())main.post {if(!cancelled.get() && !closed.get())onResult(result)}
            }
            return {cancelled.set(true)}
        }
    }

    fun original(owner:String,source:ImageSource):CompletableFuture<ByteArray> = async {bytes(owner,source,digest(owner,source))}

    fun clipboardFile(owner:String,source:ImageSource):CompletableFuture<File> = async {
        val hash=digest(owner,source);val bytes=bytes(owner,source,hash)
        exports.mkdirs()
        File(exports,"image-${hash.take(24)}.${extension(bytes)}").also {if(!it.exists())it.writeBytes(bytes)}
    }

    /** Keep the original bytes after upload so the sent photo appears without downloading it again. */
    fun seedRemote(owner:String,path:String,bytes:ByteArray) {
        if(closed.get() || bytes.size>MAX_IMAGE_BYTES)return
        workers.execute {
            runCatching {save(digest(owner,ImageSource(ImageSourceKind.RemotePath,path,cacheKey=path)),bytes)}
        }
    }

    private fun bytes(owner:String,source:ImageSource,key:String):ByteArray {
        val cached=File(directory,key)
        if(cached.isFile && cached.length()<=MAX_IMAGE_BYTES) {
            runCatching {cached.inputStream().use {readBounded(it)}}.getOrNull()?.let {
                cached.setLastModified(System.currentTimeMillis());return it
            }
        }
        val data=when(source.kind) {
            ImageSourceKind.RemotePath -> readRemote(owner,source.value,MAX_IMAGE_BYTES).get(90,TimeUnit.SECONDS)
            ImageSourceKind.HttpUrl -> download(source.value)
            ImageSourceKind.DataUri -> {
                val comma=source.value.indexOf(',')
                require(comma in 1..256 && source.value.substring(0,comma).endsWith(";base64",true)) {"图片编码暂不支持"}
                decodeBase64(source.value.substring(comma+1))
            }
            ImageSourceKind.GeneratedBase64 -> decodeBase64(source.value)
        }
        require(data.isNotEmpty() && data.size<=MAX_IMAGE_BYTES) {"图片超过 20 MB 或内容为空"}
        val header=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(data,0,data.size,header)
        require(header.outWidth>0 && header.outHeight>0) {"图片格式无法识别"}
        save(key,data)
        return data
    }

    private fun download(address:String):ByteArray {
        var url=URL(address)
        repeat(5) {
            require(url.protocol in setOf("https","http")) {"图片链接暂不支持"}
            val connection=url.openConnection() as HttpURLConnection
            connection.connectTimeout=12_000;connection.readTimeout=20_000;connection.instanceFollowRedirects=false
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

    @Synchronized private fun save(key:String,data:ByteArray) {
        directory.mkdirs()
        val target=File(directory,key)
        if(!target.exists()) {
            val temp=File.createTempFile("image-",".tmp",directory)
            try {temp.writeBytes(data);if(!temp.renameTo(target))temp.delete()} finally {temp.delete()}
        }
        val entries=directory.listFiles()?.filter {it.isFile && !it.name.endsWith(".tmp")}?.sortedBy {it.lastModified()}.orEmpty()
        var total=entries.sumOf {it.length()}
        for(file in entries)if(total>96L*1024*1024 && file.name!=key) {val size=file.length();if(file.delete())total-=size}
    }

    @Synchronized private fun invalidate(key:String) {
        File(directory,key).delete()
        bitmaps.snapshot().keys.filter {it.startsWith("$key:")}.forEach {bitmaps.remove(it)}
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
        if(closed.get())return result.apply {completeExceptionally(IllegalStateException("图片服务已关闭"))}
        workers.execute {
            try {val value=block();main.post {result.complete(value)}}
            catch(error:Exception) {main.post {result.completeExceptionally(IllegalStateException(safeImageError(error)))}}
        }
        return result
    }

    override fun close() {closed.set(true);workers.shutdownNow();bitmaps.evictAll()}

    companion object {
        const val MAX_IMAGE_BYTES=20*1024*1024
        fun decode(bytes:ByteArray,maxEdge:Int):Bitmap {
            require(Looper.myLooper()!=Looper.getMainLooper()) {"图片必须在后台解码"}
            require(bytes.size<=MAX_IMAGE_BYTES) {"图片超过 20 MB"}
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

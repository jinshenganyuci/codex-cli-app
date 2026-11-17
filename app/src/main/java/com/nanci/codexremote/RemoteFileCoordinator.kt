package com.nanci.codexremote

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nanci.codexremote.core.CodexException
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class RemoteFilePhase { Loading, Ready, Error }

data class SavedRemoteFile(val owner: String, val path: String, val uri: Uri, val mimeType: String, val displayName: String,
    val savedToDownloads: Boolean = true)
data class FileDownloadNotice(val id: Long, val file: SavedRemoteFile)
data class RemoteFileDocumentSave(val id: Long, val intent: Intent)
data class RemoteFileState(
    val requestId: Long,
    val owner: String,
    val path: String,
    val displayName: String,
    val phase: RemoteFilePhase = RemoteFilePhase.Loading,
    val size: Long? = null,
    val text: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val saved: SavedRemoteFile? = null,
    val saveError: String? = null,
    val image: Bitmap? = null,
    val mimeType: String = remoteFileMimeType(displayName),
)

/** Owns the temporary download and save leases independently of the sheet's composition. */
class RemoteFileCoordinator(
    context: Context,
    private val owner: () -> String,
    private val download: (String, File, () -> Boolean, (Long, Long) -> Unit) -> CompletableFuture<Long>,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private var sequence = 0L
    private var readsInFlight = 0
    private var closed = false
    private var active: Request? = null
    private val savedFiles = mutableMapOf<Pair<String, String>, SavedRemoteFile>()
    private val saves = mutableMapOf<Pair<String, String>, Save>()
    private val documentSaves = mutableMapOf<Long, Save>()
    private var pendingDocumentId: Long? = null
    var state by mutableStateOf<RemoteFileState?>(null); private set
    var notices by mutableStateOf<List<FileDownloadNotice>>(emptyList()); private set

    private class Request(val id: Long, val owner: String, val path: String, val name: String, val file: File) {
        val cancelled = AtomicBoolean(false)
        val key get() = owner to path
        var reading = true
        var retired = false
        var saving = false
        var mimeType = remoteFileMimeType(name)
    }
    private class Save(val id: Long, val request: Request) {
        val cancelled = AtomicBoolean(false)
        var writing = false
    }

    fun open(path: String) {
        if (closed) return
        dismiss()
        val request = Request(++sequence, owner(), path, remoteFileName(path),
            File(app.cacheDir, "exports/remote-files/${UUID.randomUUID()}.part"))
        active = request
        state = RemoteFileState(request.id, request.owner, path, request.name,
            saving = saves.containsKey(request.key))
        if (!path.startsWith('/') || path.indexOf('\u0000') >= 0) {
            request.reading = false
            state = state?.copy(phase = RemoteFilePhase.Error, error = "无法读取此文件路径。")
            return
        }
        savedFiles[request.key]?.let { saved ->
            io.execute {
                val exists = runCatching { app.contentResolver.openFileDescriptor(saved.uri, "r")?.use { true } ?: false }.getOrDefault(false)
                main.post {
                    // A prior URI probe must not overwrite a save that finished meanwhile.
                    if (savedFiles[request.key] == saved) {
                        if (!exists) savedFiles.remove(request.key)
                        if (isCurrent(request)) state = state?.copy(saved = saved.takeIf { exists })
                    }
                }
            }
        }
        // Progress deliberately remains the native indeterminate loading state. The transport
        // streams into this unique file, so neither large files nor progress flood Compose.
        readsInFlight++
        val future = try {
            val directory = request.file.parentFile!!
            check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare file cache" }
            download(path, request.file, request.cancelled::get) { _, _ -> }
        }
        catch (error: Exception) { CompletableFuture<Long>().also { it.completeExceptionally(error) } }
        future.whenComplete { size, error ->
            main.post {
                if (error != null) finishRead(request, null, null, error)
                else if (request.cancelled.get()) finishRead(request, null, null, CancellationException())
                else io.execute {
                    val preview = runCatching { remoteFilePreview(request.file, request.name) }.getOrNull()
                    main.post { finishRead(request, size, preview, null) }
                }
            }
        }
    }

    private fun finishRead(request: Request, size: Long?, preview: FilePreview?, error: Throwable?) {
        request.reading = false
        readsInFlight--
        if (isCurrent(request)) {
            preview?.mimeType?.let { request.mimeType = it }
            state = if (error == null) state?.copy(phase = RemoteFilePhase.Ready, size = size,
                text = preview?.text, image = preview?.image, mimeType = request.mimeType)
            else state?.copy(phase = RemoteFilePhase.Error, error = fileReadError(error))
        } else preview?.image?.recycle()
        cleanup(request)
        if (error != null && !request.saving) request.file.delete()
        shutdownIfFinished()
    }

    fun dismiss() {
        active?.let { request ->
            request.retired = true
            request.cancelled.set(true)
            cleanup(request)
        }
        active = null
        state = null
    }

    fun onOwnerChanged() {
        val currentOwner = owner()
        if (active?.owner != currentOwner) dismiss()
        saves.values.filter { it.request.owner != currentOwner }.toList().forEach { save ->
            save.cancelled.set(true)
            if (pendingDocumentId == save.id) pendingDocumentId = null
            if (!save.writing) finishSave(save, null, CancellationException())
        }
        notices = notices.filter { it.file.owner == currentOwner }
    }

    /** API 29+ uses scoped public Downloads, with no storage permission or location picker. */
    fun saveToDownloads() {
        if (Build.VERSION.SDK_INT < 29) return
        val save = beginSave() ?: return
        save.writing = true
        io.execute {
            val result = runCatching { saveToMediaStore(save) }
            main.post { finishSave(save, result.getOrNull(), result.exceptionOrNull()) }
        }
    }

    /** Compatibility path for API 26–28. The result belongs to this save, even after dismissal. */
    fun beginDocumentSave(): RemoteFileDocumentSave? {
        if (pendingDocumentId != null) return null
        val save = beginSave() ?: return null
        pendingDocumentId = save.id
        documentSaves[save.id] = save
        return RemoteFileDocumentSave(save.id, Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(save.request.mimeType).putExtra(Intent.EXTRA_TITLE, save.request.name))
    }

    fun completeDocumentSave(id: Long?, uri: Uri?) {
        val save = documentSaves.remove(id) ?: return
        if (pendingDocumentId == id) pendingDocumentId = null
        if (uri == null) { finishSave(save, null, null); return }
        save.writing = true
        io.execute {
            val result = runCatching {
                copyToUri(save, uri)
                checkSave(save)
                SavedRemoteFile(save.request.owner, save.request.path, uri,
                    save.request.mimeType, save.request.name, savedToDownloads = false)
            }
            if (result.isFailure) runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
            main.post { finishSave(save, result.getOrNull(), result.exceptionOrNull()) }
        }
    }

    private fun beginSave(): Save? {
        val request = active ?: return null
        if (closed || !isCurrent(request) || state?.phase != RemoteFilePhase.Ready || saves.containsKey(request.key)) return null
        val save = Save(++sequence, request)
        request.saving = true
        saves[request.key] = save
        state = state?.copy(saving = true, saveError = null)
        return save
    }

    @android.annotation.TargetApi(29)
    private fun saveToMediaStore(save: Save): SavedRemoteFile {
        checkSave(save)
        val request = save.request
        val mime = request.mimeType
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, request.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create download")
        try {
            copyToUri(save, uri)
            checkSave(save)
            val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, publish, null, null) > 0) { "Cannot publish download" }
            checkSave(save)
            return SavedRemoteFile(request.owner, request.path, uri, mime, request.name)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun copyToUri(save: Save, uri: Uri) {
        checkSave(save)
        save.request.file.inputStream().use { input ->
            val output = app.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Cannot open download")
            output.use {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkSave(save)
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
    }

    private fun checkSave(save: Save) {
        if (save.cancelled.get()) throw CancellationException("File save cancelled")
    }

    private fun finishSave(save: Save, saved: SavedRemoteFile?, error: Throwable?) {
        val request = save.request
        if (saves[request.key] === save) saves.remove(request.key)
        request.saving = false
        val sameOwner = !closed && request.owner == owner()
        if (saved != null && (!sameOwner || save.cancelled.get())) {
            // This URI was just created by this operation and has not been delivered.
            // Never remove a previously delivered saved-file record on an owner switch.
            io.execute { runCatching {
                if (Build.VERSION.SDK_INT >= 29) app.contentResolver.delete(saved.uri, null, null)
                else DocumentsContract.deleteDocument(app.contentResolver, saved.uri)
            } }
        }
        if (saved != null && sameOwner && !save.cancelled.get()) {
            savedFiles[request.key] = saved
            notices = notices + FileDownloadNotice(save.id, saved)
            // Saving an earlier preview must never close a newly opened sheet, even for
            // the same path. The downloaded URI remains available independently.
            if (active?.id == request.id) dismiss()
            else if (active?.key == request.key) state = state?.copy(saved = saved, saving = false)
        } else if (active?.key == request.key && sameOwner) {
            val message = if (error != null && !save.cancelled.get()) "文件保存失败，请重试。" else null
            state = state?.copy(saving = false, saveError = message)
        } else if (error != null && sameOwner && !save.cancelled.get()) onError("文件保存失败，请重试。")
        cleanup(request)
        shutdownIfFinished()
    }

    fun consumeNotice(id: Long) { notices = notices.filterNot { it.id == id } }

    fun openSaved(context: Context, saved: SavedRemoteFile) {
        if (closed || saved.owner != owner()) return
        runCatching { context.startActivity(remoteFileOpenIntent(saved)) }
            .onFailure { onError("无法打开文件，请安装可打开此类型文件的应用。") }
    }

    private fun isCurrent(request: Request) = !closed && active === request && request.owner == owner() && !request.cancelled.get()

    private fun cleanup(request: Request) {
        if (request.retired && !request.reading && !request.saving) request.file.delete()
    }

    override fun close() {
        if (closed) return
        closed = true
        dismiss()
        saves.values.toList().forEach {
            it.cancelled.set(true)
            if (!it.writing) finishSave(it, null, CancellationException())
        }
        pendingDocumentId = null
        documentSaves.clear()
        notices = emptyList()
        savedFiles.clear()
        // Running I/O owns its cache lease until its completion callback cleans up.
        shutdownIfFinished()
    }

    private fun shutdownIfFinished() {
        if (closed && readsInFlight == 0 && saves.isEmpty()) io.shutdown()
    }
}

fun remoteFileOpenIntent(saved: SavedRemoteFile): Intent {
    val view = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT)
        .setDataAndType(saved.uri, saved.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    view.clipData = ClipData.newRawUri(saved.displayName, saved.uri)
    return Intent.createChooser(view, null)
}

private fun remoteFileName(path: String): String = path.substringAfterLast('/').replace(Regex("[\\p{Cntrl}\\\\]"), "_")
    .ifBlank { "文件" }

private data class FilePreview(val text: String? = null, val image: Bitmap? = null, val mimeType: String? = null)

/** Header-only detection preserves extensionless images without reading arbitrary binaries into RAM. */
private fun remoteFilePreview(file: File, name: String): FilePreview {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth in 1..100000 && bounds.outHeight in 1..100000 && file.length() <= ImagePipeline.MAX_IMAGE_BYTES) {
        val image = runCatching { ImagePipeline.decode(file.readBytes(), 2048) }.getOrNull()
        if (image != null) return FilePreview(image = image, mimeType = bounds.outMimeType?.takeIf { it.startsWith("image/") })
    }
    if (file.length() in 1..SvgImageDecoder.MAX_SVG_BYTES.toLong()) {
        val header=file.inputStream().use { input -> ByteArray(1024).let { bytes -> bytes.copyOf(input.read(bytes).coerceAtLeast(0)) } }
        if (name.endsWith(".svg",ignoreCase=true) || SvgImageDecoder.isSvg(header)) {
            val image=runCatching { ImagePipeline.decode(file.readBytes(),2048) }.getOrNull()
            if(image!=null)return FilePreview(image=image,mimeType="image/svg+xml")
        }
    }
    return FilePreview(text = remoteFileTextPreview(file, name))
}

private val textExtensions = setOf("txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "yaml", "yml", "xml", "log",
    "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "css", "html", "htm", "sh", "bash", "zsh", "toml", "ini",
    "cfg", "conf", "sql", "c", "h", "cpp", "hpp", "go", "rs", "swift", "rb", "php", "vue", "svelte", "properties", "gradle")

fun remoteFileMimeType(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "json", "jsonl" -> "application/json"
        "md", "markdown" -> "text/markdown"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (extension in textExtensions) "text/plain" else "application/octet-stream"
    }
}

/** Decode only known text, with a bounded preview; binary files remain downloadable intact. */
private fun remoteFileTextPreview(file: File, name: String): String? {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in textExtensions && name.lowercase(Locale.ROOT) !in setOf("dockerfile", "makefile", ".gitignore")) return null
    val limit = 48 * 1024
    val bytes = file.inputStream().use { input ->
        val buffer = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = input.read(buffer, count, limit - count)
            if (read < 0) break
            count += read
        }
        buffer.copyOf(count)
    }
    val truncated = file.length() > limit
    for (trim in 0..if (truncated) 3 else 0) {
        val text = runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, bytes.size - trim)).toString() }.getOrNull() ?: continue
        if (text.any { it < ' ' && it !in "\n\r\t" }) return null
        return text + if (truncated) "\n\n仅预览前 48 KB，下载可查看完整文件。" else ""
    }
    return null
}

private fun fileReadError(error: Throwable): String {
    val cause = generateSequence(error) { it.cause }.last()
    return (cause as? CodexException)?.safeMessage ?: "无法读取此文件，请检查连接后重新打开。"
}

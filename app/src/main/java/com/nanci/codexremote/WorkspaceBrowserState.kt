package com.nanci.codexremote

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import com.nanci.codexremote.core.RemoteEntry
import java.util.concurrent.CompletableFuture

/** Owns a single directory picker. Older requests and dismissed pickers never update its state. */
class WorkspaceBrowserState(
    initialPath: String,
    private val readDirectory: (String) -> CompletableFuture<List<RemoteEntry>>,
) : AutoCloseable {
    var path by mutableStateOf(initialPath); private set
    var entries by mutableStateOf(emptyList<RemoteEntry>()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var loadedPath by mutableStateOf<String?>(null); private set
    val canSelect: Boolean get() = !closed && !loading && error == null && loadedPath == path && path.startsWith('/')
    private var revision = 0L
    private var closed = false
    private val main = Handler(Looper.getMainLooper())

    fun edit(value: String) {
        if (closed) return
        revision++
        path = value; entries = emptyList(); loadedPath = null; loading = false; error = null
    }

    fun load(value: String = path) {
        if (closed) return
        val ticket = ++revision
        path = value; entries = emptyList(); loadedPath = null; error = null
        if (!value.startsWith('/') || value.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            loading = false; error = "请输入有效的服务器绝对路径"; return
        }
        loading = true
        val future = try { readDirectory(value) } catch (_: Exception) {
            loading = false; error = "无法读取此目录"; return
        }
        future.whenComplete { list, failure ->
            val apply = {
                if (!closed && ticket == revision) {
                    loading = false
                    if (failure != null) error = "无法读取此目录"
                    else { entries = list.orEmpty().filter { it.directory }; loadedPath = value }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post { apply() }
        }
    }

    override fun close() { closed = true; revision++; loading = false }
}

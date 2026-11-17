package com.nanci.codexremote.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

enum class BootstrapPhase { Hidden, Preparing, Failed, Cancelling }
data class BootstrapUiState(val phase: BootstrapPhase = BootstrapPhase.Hidden, val message: String? = null)

/** Own for the ViewModel lifetime, not the Activity lifetime; all credential/network I/O is off-main. */
class BootstrapCoordinator(
    context: Context,
    private val store: ConnectionStore,
    private val onReady: (SavedConnection, String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val gate = Any()
    private val transport = AtomicReference<BootstrapTransport?>()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private var started = false
    var state by mutableStateOf(BootstrapUiState()); private set

    fun start() {
        if (closed || started) return
        started = true
        launch()
    }

    fun retry() {
        if (closed || state.phase != BootstrapPhase.Failed) return
        state = BootstrapUiState(BootstrapPhase.Preparing)
        launch()
    }

    private fun launch() {
        val token = synchronized(gate) { ++generation }
        io.execute {
            try {
                if (!current(token)) return@execute
                val spec = readSpec() ?: run { post(token) { state = BootstrapUiState() }; return@execute }
                spec.use {
                    val allowed = synchronized(gate) { current(token) && store.prepareBootstrap(spec.inviteId) }
                    if (!allowed) { post(token) { state = BootstrapUiState() }; return@execute }
                    post(token) { state = BootstrapUiState(BootstrapPhase.Preparing) }
                    val pending = store.loadPendingBootstrap(spec.inviteId) ?: BootstrapKeys.generate().use { secret ->
                        synchronized(gate) {
                            if (!current(token)) throw CancellationException()
                            store.savePendingBootstrap(spec.inviteId, secret)
                        }
                    }
                    if (pending == null) { post(token) { state = BootstrapUiState() }; return@execute }
                    pending.use {
                        if (!current(token)) throw CancellationException()
                        val attempt = BootstrapTransport()
                        transport.set(attempt)
                        try {
                            if (!current(token)) throw CancellationException()
                            attempt.enroll(spec, BootstrapKeys.publicKey(pending.credential))
                            val saved = synchronized(gate) {
                                if (!current(token)) throw CancellationException()
                                store.finishBootstrap(spec.inviteId, spec.connection, spec.workspace)
                            }
                            post(token) {
                                state = BootstrapUiState()
                                if (saved != null) onReady(saved, spec.workspace)
                            }
                        } finally { transport.compareAndSet(attempt, null); attempt.close() }
                    }
                }
            } catch (error: Exception) {
                val message = when (error) {
                    is BootstrapException -> error.safeMessage
                    is ConnectionStorageException -> error.message ?: "无法保存首次连接，请重试。"
                    else -> "首次连接未完成，请检查网络后重试。"
                }
                post(token) { state = BootstrapUiState(BootstrapPhase.Failed, message) }
            }
        }
    }

    /** Explicit cancellation is durable; a later app launch or deletion does not retry enrollment. */
    fun cancel(onCancelled: () -> Unit = {}) {
        if (closed || state.phase == BootstrapPhase.Cancelling) return
        val token = synchronized(gate) { ++generation }
        transport.getAndSet(null)?.close()
        state = BootstrapUiState(BootstrapPhase.Cancelling)
        io.execute {
            try {
                val completed = store.cancelBootstrap()
                val workspace = if (completed != null) store.workspace else ""
                post(token) {
                    state = BootstrapUiState()
                    // Completion can have committed just before the cancel tap while its
                    // main-thread callback was queued. Keep the ViewModel in sync in that case.
                    if (completed != null) onReady(completed, workspace) else onCancelled()
                }
            } catch (_: Exception) {
                post(token) { state = BootstrapUiState(BootstrapPhase.Failed, "无法保存取消状态，请检查设备存储空间后重试。") }
            }
        }
    }

    private fun readSpec(): BootstrapSpec? {
        val input = try { app.assets.open(BootstrapSpec.ASSET_NAME) } catch (_: FileNotFoundException) { return null }
        val bytes = input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val size = it.read(buffer)
                if (size < 0) break
                if (out.size() + size > BootstrapSpec.MAX_BYTES) throw BootstrapException("安装包中的连接资料无效，请获取新的安装包。")
                out.write(buffer, 0, size)
            }
            out.toByteArray()
        }
        return try { BootstrapSpec.parse(bytes) } finally { bytes.fill(0) }
    }

    private fun current(token: Long) = !closed && generation == token
    private fun post(token: Long, action: () -> Unit) { main.post { if (current(token)) action() } }
    override fun close() {
        synchronized(gate) { closed = true; generation++ }
        transport.getAndSet(null)?.close()
        io.shutdown()
    }
}

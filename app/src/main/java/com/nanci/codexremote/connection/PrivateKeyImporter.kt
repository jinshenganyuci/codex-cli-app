package com.nanci.codexremote.connection

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads document providers away from the UI thread. Each screen owns one importer;
 * cancel/close invalidate queued results, including providers that ignore cancellation.
 * The successful callback takes ownership of its bytes and must erase them when done.
 * Injectable stream/dispatch boundaries also permit delayed-provider instrumentation.
 */
class PrivateKeyImporter(
    private val open: (Uri, CancellationSignal) -> InputStream,
    private val dispatch: (() -> Unit) -> Unit,
    private val maxBytes: Int = ConnectionStore.MAX_SECRET,
    private val timeoutMillis: Long = 30_000,
) : AutoCloseable {
    constructor(context: Context) : this(
        open = { uri, cancellation ->
            val descriptor = context.applicationContext.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)
                ?: throw IllegalArgumentException()
            try { descriptor.createInputStream() }
            catch (error: Exception) { descriptor.close(); throw error }
        },
        dispatch = { action -> Handler(Looper.getMainLooper()).post(action) },
    )

    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "codex-key-import").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "codex-key-import-timeout").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Operation?>()

    init { require(maxBytes in 1..ConnectionStore.MAX_SECRET && timeoutMillis > 0) }

    fun read(uri: Uri, callback: (Result<ByteArray>) -> Unit) {
        if (closed.get()) return
        val operation = Operation(callback)
        active.getAndSet(operation)?.abort()
        if (closed.get()) { cancel(); return }
        val deadline = try {
            timer.schedule({
                if (publish(operation, Result.failure(IllegalStateException("读取文件超时，请重试或选择设备上的文件。")))) operation.abort()
            }, timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { cancel(); return }
        try {
            io.execute {
                try {
                    operation.ensureActive()
                    val input = open(uri, operation.signal)
                    operation.stream.set(input)
                    try {
                        operation.ensureActive()
                        val bytes = readBounded(input, maxBytes) { operation.ensureActive() }
                        publish(operation, Result.success(bytes))
                    } finally {
                        operation.stream.compareAndSet(input, null)
                        input.close()
                    }
                } catch (_: Exception) {
                    publish(operation, Result.failure(IllegalArgumentException("无法导入文件，请选择不超过 64 KB 的 SSH 私钥。")))
                } finally { deadline.cancel(false) }
            }
        } catch (_: Exception) { deadline.cancel(false); cancel() }
    }

    /** No provider method, including CancellationSignal listeners/stream.close, runs on the caller thread. */
    fun cancel() { active.getAndSet(null)?.abort() }

    override fun close() {
        if (closed.compareAndSet(false, true)) { cancel(); io.shutdownNow(); timer.shutdownNow() }
    }

    private fun publish(operation: Operation, result: Result<ByteArray>): Boolean {
        if (!operation.resultPosted.compareAndSet(false, true)) { result.getOrNull()?.fill(0); return false }
        dispatch {
            if (!closed.get() && active.compareAndSet(operation, null)) {
                try { operation.callback(result) }
                catch (error: Exception) { result.getOrNull()?.fill(0); throw error }
            } else result.getOrNull()?.fill(0)
        }
        return true
    }

    private inner class Operation(val callback: (Result<ByteArray>) -> Unit) {
        val signal = CancellationSignal()
        val stream = AtomicReference<InputStream?>()
        val resultPosted = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        fun ensureActive() {
            if (closed.get() || cancelled.get() || active.get() !== this || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        fun abort() {
            if (!cancelled.compareAndSet(false, true)) return
            // A provider can block in both cancel() and close(); neither may block navigation.
            Thread({ runCatching { signal.cancel() } }, "codex-key-import-cancel").apply { isDaemon = true; start() }
            stream.getAndSet(null)?.let { input ->
                Thread({ runCatching { input.close() } }, "codex-key-import-close").apply { isDaemon = true; start() }
            }
        }
    }

    companion object {
        /** Fixed-size private scratch buffer is erased even for failure/oversize/cancellation. */
        fun readBounded(input: InputStream, maxBytes: Int = ConnectionStore.MAX_SECRET, checkActive: () -> Unit = {}): ByteArray {
            require(maxBytes in 1..ConnectionStore.MAX_SECRET)
            val scratch = ByteArray(maxBytes + 1)
            try {
                var size = 0
                while (true) {
                    checkActive()
                    val count = input.read(scratch, size, scratch.size - size)
                    checkActive()
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    require(size <= maxBytes)
                }
                require(size > 0)
                return scratch.copyOf(size)
            } finally { scratch.fill(0) }
        }
    }
}

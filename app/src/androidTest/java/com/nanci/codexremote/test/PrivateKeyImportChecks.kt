package com.nanci.codexremote.test

import android.app.Instrumentation
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nanci.codexremote.connection.PrivateKeyImporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** No real key, SAF grant, account, server or disk data. Run from instrumentation's worker thread. */
object PrivateKeyImportChecks {
    fun run(instrumentation: Instrumentation): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val uri = Uri.parse("content://codex-test-delayed-provider/fixture-not-a-key")
        val main = Handler(Looper.getMainLooper())
        val dispatch: (() -> Unit) -> Unit = { action -> main.post(action) }
        val checks = mutableListOf<String>()
        val sample = "fixture-only-not-an-ssh-key".toByteArray()

        // Provider opens/reads on IO; the UI remains able to process another event while read is blocked.
        val delayed = DelayedProviderStream(sample)
        val openedOnWorker = AtomicBoolean(false)
        val finished = CompletableFuture<ByteArray>()
        PrivateKeyImporter(open = { _, _ -> openedOnWorker.set(Looper.myLooper() != Looper.getMainLooper()); delayed }, dispatch = dispatch).use { importer ->
            instrumentation.runOnMainSync {
                importer.read(uri) { result ->
                    check(Looper.myLooper() == Looper.getMainLooper())
                    result.fold(finished::complete, finished::completeExceptionally)
                }
            }
            check(delayed.started.await(3, TimeUnit.SECONDS))
            val heartbeat = AtomicBoolean(false)
            instrumentation.runOnMainSync { heartbeat.set(true) }
            check(heartbeat.get() && openedOnWorker.get() && !finished.isDone)
            delayed.release.countDown()
            val bytes = finished.get(3, TimeUnit.SECONDS)
            check(bytes.contentEquals(sample)); bytes.fill(0)
        }
        checks += "delayed_provider_read_is_off_main_and_callback_is_main"

        // Size cap and scratch zeroization use an observable fixture buffer, never a real key.
        var scratch: ByteArray? = null
        val oversized = object : ByteArrayInputStream(ByteArray(9) { 5 }) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                scratch = buffer
                return super.read(buffer, offset, length)
            }
        }
        check(runCatching { PrivateKeyImporter.readBounded(oversized, 8) }.isFailure)
        check(requireNotNull(scratch).all { it == 0.toByte() })
        check(runCatching { PrivateKeyImporter.readBounded(ByteArrayInputStream(byteArrayOf()), 8) }.isFailure)
        val exact = PrivateKeyImporter.readBounded(ByteArrayInputStream(ByteArray(8) { 7 }), 8)
        check(exact.size == 8 && exact.all { it == 7.toByte() }); exact.fill(0)
        checks += "empty_and_oversize_rejected_exact_limit_accepted_scratch_erased"

        // Deliberately slow close/cancel listeners cannot make the main-thread Cancel button wait.
        val cancelledStream = DelayedProviderStream(sample, closeDelayMillis = 300)
        val cancelledCallbacks = AtomicInteger()
        val cancellationEntered = CountDownLatch(1)
        PrivateKeyImporter(open = { _, signal ->
            signal.setOnCancelListener { cancellationEntered.countDown(); SystemClock.sleep(300) }
            cancelledStream
        }, dispatch = dispatch).use { importer ->
            instrumentation.runOnMainSync { importer.read(uri) { cancelledCallbacks.incrementAndGet() } }
            check(cancelledStream.started.await(3, TimeUnit.SECONDS))
            val elapsed = AtomicReference<Long>()
            instrumentation.runOnMainSync {
                val start = SystemClock.elapsedRealtime(); importer.cancel(); elapsed.set(SystemClock.elapsedRealtime() - start)
            }
            check(elapsed.get() < 250) { "cancel_waited_for_provider" }
            check(cancellationEntered.await(2, TimeUnit.SECONDS))
            cancelledStream.release.countDown()
            check(cancelledStream.closed.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { }
            check(cancelledCallbacks.get() == 0)
        }
        checks += "cancel_does_not_wait_for_provider_and_drops_result"

        // A successful result already queued before leaving the screen must still be discarded.
        val queued = LinkedBlockingQueue<() -> Unit>()
        val leftScreenCallbacks = AtomicInteger()
        val reader = PrivateKeyImporter(open = { _, _ -> ByteArrayInputStream(sample) }, dispatch = { queued.put(it) })
        instrumentation.runOnMainSync { reader.read(uri) { leftScreenCallbacks.incrementAndGet() } }
        val late = requireNotNull(queued.poll(3, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { reader.close(); late() }
        check(leftScreenCallbacks.get() == 0)
        checks += "screen_close_rejects_already_queued_success"

        // Deadline is injectable so production retains 30 seconds without slowing this test.
        val timeoutStream = DelayedProviderStream(sample)
        val timedOut = CompletableFuture<Boolean>()
        PrivateKeyImporter(open = { _, _ -> timeoutStream }, dispatch = dispatch, timeoutMillis = 80).use { importer ->
            instrumentation.runOnMainSync { importer.read(uri) { timedOut.complete(it.isFailure && it.exceptionOrNull()?.message?.contains("超时") == true) } }
            try { check(timedOut.get(3, TimeUnit.SECONDS)) }
            finally { timeoutStream.release.countDown() }
        }
        checks += "timeout_reports_safe_error_without_waiting_for_slow_read"

        // Replacing an in-flight read invalidates its callback before the next provider opens.
        val oldStream = DelayedProviderStream(sample)
        val opens = AtomicInteger()
        val replacedCallbacks = AtomicInteger()
        val replacement = CompletableFuture<ByteArray>()
        PrivateKeyImporter(open = { _, _ -> if (opens.getAndIncrement() == 0) oldStream else ByteArrayInputStream(sample) }, dispatch = dispatch).use { importer ->
            instrumentation.runOnMainSync { importer.read(uri) { replacedCallbacks.incrementAndGet() } }
            check(oldStream.started.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { importer.read(uri) { it.fold(replacement::complete, replacement::completeExceptionally) } }
            oldStream.release.countDown()
            val bytes = replacement.get(3, TimeUnit.SECONDS)
            check(bytes.contentEquals(sample)); bytes.fill(0)
            instrumentation.runOnMainSync { }
            check(replacedCallbacks.get() == 0)
        }
        checks += "new_import_rejects_previous_owner_result"
        sample.fill(0)
        return JSONObject().put("passed", true).put("checks", JSONArray(checks))
    }

    /** Models a document provider stream whose first read and close can be independently delayed. */
    private class DelayedProviderStream(data: ByteArray, private val closeDelayMillis: Long = 0) : InputStream() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val source = ByteArrayInputStream(data)
        private val first = AtomicBoolean(true)
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (first.compareAndSet(true, false)) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            return source.read(buffer, offset, length)
        }
        override fun close() { if (closeDelayMillis > 0) SystemClock.sleep(closeDelayMillis); closed.countDown() }
    }
}

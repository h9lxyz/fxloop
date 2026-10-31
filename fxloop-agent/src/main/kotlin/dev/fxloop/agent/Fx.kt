package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javafx.application.Platform

/** FX-thread marshaling contract (PRD 01 §4): bounded waits, stack capture on timeout, never a silent hang. */
object Fx {
    const val DEFAULT_TIMEOUT_MS: Long = 5000

    @Volatile
    private var fxThread: Thread? = null

    private const val QUEUED = 0
    private const val RUNNING = 1
    private const val ABANDONED = 2

    /** Timed-out runnables still sitting in the FX queue; while > 0 the FX thread is known to be stuck. */
    private val stuck = java.util.concurrent.atomic.AtomicInteger()

    internal fun rememberFxThread(t: Thread) {
        fxThread = t
    }

    @JvmStatic
    fun <T> call(timeoutMs: Long = DEFAULT_TIMEOUT_MS, work: () -> T): T {
        if (Platform.isFxApplicationThread()) {
            try {
                return work()
            } catch (e: RuntimeException) {
                throw e
            } catch (e: Exception) {
                throw FcpException(Fcp.Error.INTERNAL, e.toString())
            }
        }
        if (stuck.get() > 0) {
            // the FX thread has not even reached an earlier timed-out call: fail fast instead of queueing more work
            throw FcpException(Fcp.Error.FX_TIMEOUT, "FX thread is still blocked (an earlier call timed out and has not run yet)",
                Json.obj().setNode("fxThreadStack", fxThreadStack()))
        }
        val future = CompletableFuture<T>()
        val state = java.util.concurrent.atomic.AtomicInteger(QUEUED)
        Platform.runLater {
            if (!state.compareAndSet(QUEUED, RUNNING)) { // caller timed out before we started: never run stale UI work
                stuck.decrementAndGet()
                return@runLater
            }
            try {
                future.complete(work())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Only a call that never started counts as "stuck"; its runnable undoes the count when the FX thread
            // finally reaches it. A call that started but runs long is just slow — later calls queue normally.
            if (state.compareAndSet(QUEUED, ABANDONED)) stuck.incrementAndGet()
            val data = Json.obj().setNode("fxThreadStack", fxThreadStack())
            throw FcpException(
                Fcp.Error.FX_TIMEOUT,
                "FX thread did not respond within $timeoutMs ms — it is blocked or busy; see fxThreadStack",
                data,
            )
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw FcpException(Fcp.Error.INTERNAL, cause.toString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FcpException(Fcp.Error.INTERNAL, "interrupted")
        }
    }

    fun run(work: () -> Unit) {
        call(work = work)
    }

    /** Waits until all previously queued FX events have been processed. */
    @JvmStatic
    fun barrier() {
        call { }
    }

    internal fun fxThreadStack(): ArrayNode {
        val frames = Json.array()
        var t = fxThread
        if (t == null) {
            for (candidate in Thread.getAllStackTraces().keys) {
                if (candidate.name.contains("JavaFX Application Thread")) t = candidate
            }
        }
        if (t != null) {
            for (el in t.stackTrace) {
                frames.add(el.toString())
                if (frames.size() >= 40) break
            }
        }
        return frames
    }

    internal fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

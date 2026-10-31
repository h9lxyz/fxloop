package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform

/** FX-thread marshaling contract (PRD 01 §4): bounded waits, stack capture on timeout, never a silent hang. */
object Fx {
    const val DEFAULT_TIMEOUT_MS: Long = 5000

    @Volatile
    private var fxThread: Thread? = null

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
        val future = CompletableFuture<T>()
        val abandoned = AtomicBoolean()
        Platform.runLater {
            if (abandoned.get()) return@runLater // caller already timed out: never run stale UI work late
            try {
                future.complete(work())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            abandoned.set(true)
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

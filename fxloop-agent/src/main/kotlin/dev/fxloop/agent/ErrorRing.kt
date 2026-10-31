package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.util.ArrayDeque

/** Ring buffer of uncaught FX-thread exceptions with 2 s de-duplication (PRD 01 §4). */
class ErrorRing internal constructor(private val capacity: Int) {
    private val ring = ArrayDeque<ObjectNode>()
    private var seq = 0L

    @Volatile
    private var listener: (ObjectNode) -> Unit = {}

    internal fun onError(listener: (ObjectNode) -> Unit) {
        this.listener = listener
    }

    internal fun installOn(thread: Thread) {
        val prior = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, ex ->
            try {
                record(t, ex)
            } catch (ignored: Throwable) {
                // capture must never break the application's own handling
            }
            prior?.uncaughtException(t, ex)
        }
    }

    @Synchronized
    internal fun record(thread: Thread, ex: Throwable): ObjectNode {
        // FX wraps handler exceptions rarely; report the outermost but keep the cause chain in frames.
        val top = if (ex.stackTrace.isNotEmpty()) ex.stackTrace[0].toString() else ""
        val now = System.currentTimeMillis()
        val last = ring.peekLast()
        if (last != null && last.get("exceptionClass").asText() == ex.javaClass.name &&
            last.path("message").asText("") == ex.message.toString() &&
            last.path("topFrame").asText() == top && now - last.get("ts").asLong() < 2000
        ) {
            last.put("dedupCount", last.get("dedupCount").asInt() + 1)
            last.put("ts", now)
            return last
        }
        val entry = Json.obj()
        entry.put("seq", ++seq)
        entry.put("ts", now)
        entry.put("threadName", thread.name)
        entry.put("exceptionClass", ex.javaClass.name)
        entry.put("message", ex.message.toString())
        entry.put("topFrame", top)
        val frames = entry.putArray("frames")
        for (el in ex.stackTrace) {
            if (frames.size() >= 15) break
            frames.add(el.toString())
        }
        val cause = ex.cause
        if (cause != null && cause !== ex) {
            entry.put("cause", cause.javaClass.name + ": " + cause.message)
        }
        entry.put("dedupCount", 1)
        ring.addLast(entry)
        while (ring.size > capacity) {
            ring.removeFirst()
        }
        listener(entry)
        return entry
    }

    @Synchronized
    internal fun since(sinceSeq: Long): ArrayNode {
        val out = Json.array()
        for (e in ring) {
            if (e.get("seq").asLong() > sinceSeq) out.add(e.deepCopy())
        }
        return out
    }
}

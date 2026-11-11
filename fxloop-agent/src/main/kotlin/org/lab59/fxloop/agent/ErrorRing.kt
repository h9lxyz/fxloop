package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Json
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

    /**
     * Records and notifies under the lock so events leave in seq order (the listener only enqueues — it never
     * blocks); the listener gets a private copy because ring entries mutate on dedup.
     */
    internal fun record(thread: Thread, thrown: Throwable): ObjectNode = synchronized(this) {
        val (entry, fresh) = recordLocked(thread, thrown)
        if (fresh) listener(entry.deepCopy())
        entry
    }

    private fun recordLocked(thread: Thread, thrown: Throwable): Pair<ObjectNode, Boolean> {
        // FXML event handlers are invoked reflectively, so the user's exception arrives wrapped in
        // RuntimeException(InvocationTargetException(...)): report the root cause, name the wrapper.
        val ex = rootCause(thrown)
        val top = if (ex.stackTrace.isNotEmpty()) ex.stackTrace[0].toString() else ""
        val now = System.currentTimeMillis()
        val last = ring.peekLast()
        if (last != null && last.get("exceptionClass").asText() == ex.javaClass.name &&
            last.path("message").asText("") == ex.message.toString() &&
            last.path("topFrame").asText() == top && now - last.get("ts").asLong() < 2000
        ) {
            last.put("dedupCount", last.get("dedupCount").asInt() + 1)
            last.put("ts", now)
            return last to false
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
        if (ex !== thrown) {
            entry.put("wrappedBy", thrown.javaClass.name)
        }
        entry.put("dedupCount", 1)
        ring.addLast(entry)
        while (ring.size > capacity) {
            ring.removeFirst()
        }
        return entry to true
    }

    @Synchronized
    internal fun since(sinceSeq: Long): ArrayNode {
        val out = Json.array()
        for (e in ring) {
            if (e.get("seq").asLong() > sinceSeq) out.add(e.deepCopy())
        }
        return out
    }

    private fun rootCause(t: Throwable): Throwable {
        var cur = t
        var depth = 0
        while (depth++ < 10) {
            val cause = cur.cause ?: break
            if (cause === cur) break
            val wrapper = cur is java.lang.reflect.InvocationTargetException ||
                (cur.javaClass == RuntimeException::class.java && cur.message == cause.toString()) ||
                cur is java.lang.reflect.UndeclaredThrowableException
            if (!wrapper && cause !is java.lang.reflect.InvocationTargetException) break
            cur = cause
        }
        return cur
    }
}

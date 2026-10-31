package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.text.MessageFormat
import java.util.ArrayDeque
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Opt-in in-app log capture (PRD 02 §9). Hooks the JUL root logger, which also receives
 * `System.Logger` output and SLF4J when bridged via `slf4j-jdk14`/`slf4j-jdk-platform-logging`.
 * Other SLF4J backends remain visible through process stdout/stderr capture.
 */
class LogCapture internal constructor(private val capacity: Int) {
    private val ring = ArrayDeque<ObjectNode>()
    private var seq = 0L

    @Volatile
    private var listener: (ObjectNode) -> Unit = {}

    var installed: Boolean = false
        private set

    internal fun onRecord(l: (ObjectNode) -> Unit) {
        listener = l
    }

    @Synchronized
    internal fun install() {
        if (installed) return
        installed = true
        Logger.getLogger("").addHandler(object : Handler() {
            override fun publish(r: LogRecord) {
                if (r.loggerName != null && r.loggerName.startsWith("dev.fxloop")) return
                var msg: String? = r.message
                try {
                    val f = formatter
                    val params = r.parameters
                    if (f != null) {
                        msg = f.formatMessage(r)
                    } else if (params != null && msg != null) {
                        msg = MessageFormat.format(msg, *params)
                    }
                } catch (ignored: RuntimeException) {
                    // keep raw message
                }
                add(r.level.name, r.loggerName, msg, r.thrown)
            }

            override fun flush() {}

            override fun close() {}
        })
    }

    /**
     * Called on arbitrary application threads. Notifies under the lock so events leave in seq order; safe because
     * the listener only enqueues (never blocks). The listener gets a private copy.
     */
    internal fun add(level: String, logger: String?, message: String?, thrown: Throwable?) {
        synchronized(this) { listener(addLocked(level, logger, message, thrown).deepCopy()) }
    }

    private fun addLocked(level: String, logger: String?, message: String?, thrown: Throwable?): ObjectNode {
        val e = Json.obj()
        e.put("seq", ++seq)
        e.put("ts", System.currentTimeMillis())
        e.put("level", level)
        e.put("logger", logger)
        e.put("message", NodeInfo.clip(message, 2000))
        if (thrown != null) {
            e.put("thrown", thrown.toString())
        }
        ring.addLast(e)
        while (ring.size > capacity) {
            ring.removeFirst()
        }
        return e
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

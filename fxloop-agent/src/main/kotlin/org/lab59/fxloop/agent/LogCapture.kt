package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Json
import java.text.MessageFormat
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Opt-in in-app log capture (PRD 02 §9). Hooks the JUL root logger, which also receives
 * `System.Logger` output and SLF4J when bridged via `slf4j-jdk14`/`slf4j-jdk-platform-logging`.
 * Other SLF4J backends remain visible through process stdout/stderr capture.
 */
class LogCapture internal constructor() {
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
                if (r.loggerName != null && r.loggerName.startsWith("org.lab59.fxloop")) return
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
     * the listener only enqueues (never blocks).
     */
    internal fun add(level: String, logger: String?, message: String?, thrown: Throwable?) {
        synchronized(this) { listener(addLocked(level, logger, message, thrown)) }
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
        return e
    }
}

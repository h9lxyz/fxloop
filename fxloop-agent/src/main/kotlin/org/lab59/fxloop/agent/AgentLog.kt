package org.lab59.fxloop.agent

/** Minimal stderr logging for the agent; never prints the session token. */
internal object AgentLog {
    private val DEBUG = "debug".equals(System.getProperty("fxloop.log"), ignoreCase = true)

    fun info(msg: String) {
        System.err.println("[fxloop-agent] $msg")
    }

    fun debug(msg: String) {
        if (DEBUG) System.err.println("[fxloop-agent] $msg")
    }

    fun warn(msg: String, t: Throwable?) {
        System.err.println("[fxloop-agent] WARN $msg" + (if (t == null) "" else ": $t"))
    }
}

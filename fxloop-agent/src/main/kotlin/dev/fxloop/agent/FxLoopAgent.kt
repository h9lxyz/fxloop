package dev.fxloop.agent

import java.lang.instrument.Instrumentation

/**
 * `-javaagent` / attach entry point. Without a `handshake=` argument the agent is a no-op
 * (zero-footprint guarantee, PRD 01 §4).
 */
object FxLoopAgent {
    @JvmStatic
    fun premain(args: String?, inst: Instrumentation?) {
        start(args, inst, "javaagent")
    }

    @JvmStatic
    fun agentmain(args: String?, inst: Instrumentation?) {
        start(args, inst, "attach")
    }

    private fun start(args: String?, inst: Instrumentation?, mode: String) {
        val options = AgentOptions.parse(args)
        if (options.handshake == null || AgentOptions.disabledBySystemProperty()) return
        AgentRuntime.start(options, inst, mode)
    }
}

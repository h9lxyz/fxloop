package dev.fxloop.agent

import java.lang.instrument.Instrumentation
import java.nio.file.Path

/**
 * In-process start for IDE-launched apps (PRD 02 §9, F9). The agent generates its own token and writes it into the
 * owner-only handshake file; the server connects via `fx_connect_existing`.
 */
object Embedded {
    /** @return the runtime, or `null` when disabled via `-Dfxloop.enabled=false`. */
    @JvmStatic
    fun install(handshake: Path, inst: Instrumentation?): AgentRuntime? {
        if (AgentOptions.disabledBySystemProperty()) return null
        val state = System.getProperty("fxloop.state")
        val options = AgentOptions(
            handshake, null, state?.let { Path.of(it) },
            "true".equals(System.getProperty("fxloop.logs"), ignoreCase = true), inst != null,
            recordHuman = !"false".equals(System.getProperty("fxloop.recordHuman"), ignoreCase = true),
        )
        return AgentRuntime.start(options, inst, "embedded")
    }
}

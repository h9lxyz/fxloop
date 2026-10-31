package dev.fxloop.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

/**
 * In-process start for IDE-launched apps (PRD 02 §9, F9). The agent generates its own token and writes it into the
 * owner-only handshake file; the server connects via {@code fx_connect_existing}.
 */
public final class Embedded {
    private Embedded() {}

    /** @return the runtime, or {@code null} when disabled via {@code -Dfxloop.enabled=false}. */
    public static AgentRuntime install(Path handshake, Instrumentation inst) {
        if (AgentOptions.disabledBySystemProperty()) {
            return null;
        }
        String state = System.getProperty("fxloop.state");
        AgentOptions options = new AgentOptions(handshake, null, state == null ? null : Path.of(state),
                Boolean.getBoolean("fxloop.logs"), inst != null);
        return AgentRuntime.start(options, inst, "embedded");
    }
}

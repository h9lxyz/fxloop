package dev.fxloop.agent;

import java.lang.instrument.Instrumentation;

/**
 * {@code -javaagent} / attach entry point. Without a {@code handshake=} argument the agent is a no-op
 * (zero-footprint guarantee, PRD 01 §4).
 */
public final class FxLoopAgent {
    private FxLoopAgent() {}

    public static void premain(String args, Instrumentation inst) {
        start(args, inst, "javaagent");
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args, inst, "attach");
    }

    private static void start(String args, Instrumentation inst, String mode) {
        AgentOptions options = AgentOptions.parse(args);
        if (options.handshake() == null || AgentOptions.disabledBySystemProperty()) {
            return;
        }
        AgentRuntime.start(options, inst, mode);
    }
}

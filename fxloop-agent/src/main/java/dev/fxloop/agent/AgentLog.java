package dev.fxloop.agent;

/** Minimal stderr logging for the agent; never prints the session token. */
final class AgentLog {
    private static final boolean DEBUG = "debug".equalsIgnoreCase(System.getProperty("fxloop.log"));

    private AgentLog() {}

    static void info(String msg) {
        System.err.println("[fxloop-agent] " + msg);
    }

    static void debug(String msg) {
        if (DEBUG) {
            System.err.println("[fxloop-agent] " + msg);
        }
    }

    static void warn(String msg, Throwable t) {
        System.err.println("[fxloop-agent] WARN " + msg + (t == null ? "" : ": " + t));
    }
}

package dev.fxloop.agent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent arguments, {@code key=value} pairs separated by commas:
 * {@code handshake=<path>,token=<hex>,state=<path>,logs=true,fxmlHook=true}.
 */
public record AgentOptions(Path handshake, String token, Path stateFile, boolean captureLogs, boolean fxmlHook) {

    public static AgentOptions parse(String args) {
        Map<String, String> map = new HashMap<>();
        if (args != null && !args.isBlank()) {
            for (String part : args.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                }
            }
        }
        String hs = map.get("handshake");
        String state = map.get("state");
        return new AgentOptions(hs == null ? null : Path.of(hs), map.get("token"),
                state == null ? null : Path.of(state), Boolean.parseBoolean(map.getOrDefault("logs", "false")),
                Boolean.parseBoolean(map.getOrDefault("fxmlHook", "true")));
    }

    /** Production guard: {@code -Dfxloop.enabled=false} disables the agent regardless of arguments. */
    public static boolean disabledBySystemProperty() {
        return "false".equalsIgnoreCase(System.getProperty("fxloop.enabled"));
    }
}

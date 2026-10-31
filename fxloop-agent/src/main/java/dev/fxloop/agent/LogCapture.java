package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Opt-in in-app log capture (PRD 02 §9). Hooks the JUL root logger, which also receives
 * {@code System.Logger} output and SLF4J when bridged via {@code slf4j-jdk14}/{@code slf4j-jdk-platform-logging}.
 * Other SLF4J backends remain visible through process stdout/stderr capture.
 */
public final class LogCapture {
    private final int capacity;
    private final Deque<ObjectNode> ring = new ArrayDeque<>();
    private long seq;
    private volatile Consumer<ObjectNode> listener = r -> {};
    private boolean installed;

    LogCapture(int capacity) {
        this.capacity = capacity;
    }

    void onRecord(Consumer<ObjectNode> l) {
        listener = l;
    }

    synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLoggerName() != null && r.getLoggerName().startsWith("dev.fxloop")) {
                    return;
                }
                String msg = r.getMessage();
                try {
                    if (getFormatter() != null) {
                        msg = getFormatter().formatMessage(r);
                    } else if (r.getParameters() != null && msg != null) {
                        msg = java.text.MessageFormat.format(msg, r.getParameters());
                    }
                } catch (RuntimeException ignored) {
                    // keep raw message
                }
                add(r.getLevel().getName(), r.getLoggerName(), msg, r.getThrown());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    synchronized void add(String level, String logger, String message, Throwable thrown) {
        ObjectNode e = Json.object();
        e.put("seq", ++seq);
        e.put("ts", System.currentTimeMillis());
        e.put("level", level);
        e.put("logger", logger);
        e.put("message", NodeInfo.clip(message, 2000));
        if (thrown != null) {
            e.put("thrown", thrown.toString());
        }
        ring.addLast(e);
        while (ring.size() > capacity) {
            ring.removeFirst();
        }
        listener.accept(e);
    }

    synchronized ArrayNode since(long sinceSeq) {
        ArrayNode out = Json.array();
        ring.stream().filter(e -> e.get("seq").asLong() > sinceSeq).forEach(e -> out.add(e.deepCopy()));
        return out;
    }

    boolean installed() {
        return installed;
    }
}

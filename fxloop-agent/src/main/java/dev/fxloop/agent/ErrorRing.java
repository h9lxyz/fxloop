package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** Ring buffer of uncaught FX-thread exceptions with 2 s de-duplication (PRD 01 §4). */
public final class ErrorRing {
    private final int capacity;
    private final Deque<ObjectNode> ring = new ArrayDeque<>();
    private long seq;
    private volatile Consumer<ObjectNode> listener = e -> {};

    ErrorRing(int capacity) {
        this.capacity = capacity;
    }

    void onError(Consumer<ObjectNode> listener) {
        this.listener = listener;
    }

    void installOn(Thread thread) {
        Thread.UncaughtExceptionHandler prior = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((t, ex) -> {
            try {
                record(t, ex);
            } catch (Throwable ignored) {
                // capture must never break the application's own handling
            }
            if (prior != null) {
                prior.uncaughtException(t, ex);
            }
        });
    }

    synchronized ObjectNode record(Thread thread, Throwable ex) {
        Throwable root = ex;
        // FX wraps handler exceptions rarely; report the outermost but keep the cause chain in frames.
        String top = root.getStackTrace().length > 0 ? root.getStackTrace()[0].toString() : "";
        long now = System.currentTimeMillis();
        ObjectNode last = ring.peekLast();
        if (last != null && last.get("exceptionClass").asText().equals(root.getClass().getName())
                && last.path("message").asText("").equals(String.valueOf(root.getMessage()))
                && last.path("topFrame").asText().equals(top) && now - last.get("ts").asLong() < 2000) {
            last.put("dedupCount", last.get("dedupCount").asInt() + 1);
            last.put("ts", now);
            return last;
        }
        ObjectNode entry = Json.object();
        entry.put("seq", ++seq);
        entry.put("ts", now);
        entry.put("threadName", thread.getName());
        entry.put("exceptionClass", root.getClass().getName());
        entry.put("message", String.valueOf(root.getMessage()));
        entry.put("topFrame", top);
        ArrayNode frames = entry.putArray("frames");
        for (StackTraceElement el : root.getStackTrace()) {
            if (frames.size() >= 15) {
                break;
            }
            frames.add(el.toString());
        }
        Throwable cause = root.getCause();
        if (cause != null && cause != root) {
            entry.put("cause", cause.getClass().getName() + ": " + cause.getMessage());
        }
        entry.put("dedupCount", 1);
        ring.addLast(entry);
        while (ring.size() > capacity) {
            ring.removeFirst();
        }
        listener.accept(entry);
        return entry;
    }

    synchronized ArrayNode since(long sinceSeq) {
        ArrayNode out = Json.array();
        for (ObjectNode e : ring) {
            if (e.get("seq").asLong() > sinceSeq) {
                out.add(e.deepCopy());
            }
        }
        return out;
    }
}

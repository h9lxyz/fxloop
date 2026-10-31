package dev.fxloop.agent;

import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;

/** FX-thread marshaling contract (PRD 01 §4): bounded waits, stack capture on timeout, never a silent hang. */
public final class Fx {
    public static final long DEFAULT_TIMEOUT_MS = 5000;
    private static volatile Thread fxThread;

    private Fx() {}

    static void rememberFxThread(Thread t) {
        fxThread = t;
    }

    public static <T> T call(Callable<T> work) {
        return call(work, DEFAULT_TIMEOUT_MS);
    }

    public static <T> T call(Callable<T> work, long timeoutMs) {
        if (Platform.isFxApplicationThread()) {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new FcpException(Fcp.Error.INTERNAL, e.toString());
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        Platform.runLater(() -> {
            if (abandoned.get()) {
                return; // caller already timed out: never run stale UI work late
            }
            try {
                future.complete(work.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandoned.set(true);
            ObjectNode data = Json.object();
            data.set("fxThreadStack", fxThreadStack());
            throw new FcpException(Fcp.Error.FX_TIMEOUT,
                    "FX thread did not respond within " + timeoutMs + " ms — it is blocked or busy; see fxThreadStack",
                    data);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new FcpException(Fcp.Error.INTERNAL, String.valueOf(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FcpException(Fcp.Error.INTERNAL, "interrupted");
        }
    }

    public static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    /** Waits until all previously queued FX events have been processed. */
    public static void barrier() {
        run(() -> {});
    }

    static ArrayNode fxThreadStack() {
        ArrayNode frames = Json.array();
        Thread t = fxThread;
        if (t == null) {
            for (Thread candidate : Thread.getAllStackTraces().keySet()) {
                if (candidate.getName().contains("JavaFX Application Thread")) {
                    t = candidate;
                }
            }
        }
        if (t != null) {
            for (StackTraceElement el : t.getStackTrace()) {
                frames.add(el.toString());
                if (frames.size() >= 40) {
                    break;
                }
            }
        }
        return frames;
    }

    static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

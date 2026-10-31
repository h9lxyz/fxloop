package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * FXML load-registry (PRD 02 §4): {@code location → weak(root), controllerClass}. Fed by the ByteBuddy hook
 * (Plan A) and by {@code FxLoopDev.track/load} (Plan B).
 */
public final class FxmlRegistry {
    public record Entry(URL location, WeakReference<Object> root, String controllerClass, String source) {}

    private final List<Entry> entries = new ArrayList<>();

    /** Called from instrumented {@code FXMLLoader} code; must never throw. */
    public static void recordFromHook(Object location, Object root, Object controller) {
        try {
            AgentRuntime rt = AgentRuntime.get();
            if (rt != null && location instanceof URL url && root != null) {
                rt.fxml().record(url, root, controller, "hook");
            }
        } catch (Throwable ignored) {
            // never disturb FXMLLoader
        }
    }

    public synchronized void record(URL location, Object root, Object controller, String source) {
        entries.removeIf(e -> e.root().get() == null || e.root().get() == root);
        entries.add(new Entry(location, new WeakReference<>(root), controller == null ? null : controller.getClass().getName(),
                source));
    }

    public synchronized List<Entry> live() {
        entries.removeIf(e -> e.root().get() == null);
        return List.copyOf(entries);
    }

    public synchronized void replace(Entry old, Object newRoot, Object controller) {
        entries.remove(old);
        entries.add(new Entry(old.location(), new WeakReference<>(newRoot),
                controller == null ? null : controller.getClass().getName(), old.source()));
    }

    synchronized ArrayNode describe() {
        ArrayNode out = Json.array();
        for (Entry e : live()) {
            ObjectNode o = out.addObject();
            o.put("location", e.location().toString());
            o.put("controller", e.controllerClass());
            o.put("source", e.source());
            Object root = e.root().get();
            if (root instanceof javafx.scene.Node n) {
                o.put("rootPath", NodePath.of(n));
            }
        }
        return out;
    }
}

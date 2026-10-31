package dev.fxloop.agent;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import javafx.scene.Node;

/** Session-scoped integer handles for nodes, held weakly so the agent never leaks UI (PRD 01 §4). */
public final class HandleTable {
    private final Map<Integer, WeakReference<Node>> byHandle = new HashMap<>();
    private final Map<Node, Integer> byNode = new WeakHashMap<>();
    private int next = 1;

    public synchronized int handleOf(Node node) {
        Integer h = byNode.get(node);
        if (h != null) {
            return h;
        }
        int handle = next++;
        byHandle.put(handle, new WeakReference<>(node));
        byNode.put(node, handle);
        if (byHandle.size() > 10_000) {
            byHandle.values().removeIf(ref -> ref.get() == null);
        }
        return handle;
    }

    public synchronized Node resolve(int handle) {
        WeakReference<Node> ref = byHandle.get(handle);
        return ref == null ? null : ref.get();
    }

    public synchronized int size() {
        return byHandle.size();
    }
}

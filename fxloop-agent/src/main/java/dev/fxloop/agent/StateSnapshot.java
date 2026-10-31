package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import java.util.ArrayDeque;
import java.util.Deque;
import javafx.scene.Node;
import javafx.stage.Window;

/**
 * Flat, path-indexed snapshot of the watched properties of every logical node (input for the server-side
 * scene-diff engine, PRD 02 §6). FX thread only.
 */
final class StateSnapshot {
    private StateSnapshot() {}

    static ObjectNode capture(int maxNodes) {
        ObjectNode out = Json.object();
        out.set("windows", Windows.describeAll());
        ObjectNode nodes = out.putObject("nodes");
        int count = 0;
        boolean overflow = false;
        for (Window w : Windows.showing()) {
            Deque<Node> stack = new ArrayDeque<>();
            Deque<String> paths = new ArrayDeque<>();
            stack.push(w.getScene().getRoot());
            paths.push(NodePath.of(w.getScene().getRoot()));
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                String path = paths.pop();
                if (count >= maxNodes) {
                    overflow = true;
                    break;
                }
                if (path == null) {
                    continue;
                }
                count++;
                ObjectNode e = nodes.putObject(path);
                e.put("type", NodePath.typeName(n));
                String text = NodeInfo.text(n);
                if (text != null) {
                    e.put("text", NodeInfo.clip(text, 120));
                }
                Object value = NodeInfo.value(n);
                if (value != null) {
                    NodeInfo.putValue(e, "value", value);
                }
                e.put("visible", n.isVisible());
                e.put("disabled", n.isDisabled());
                e.put("focused", n.isFocused());
                Integer items = NodeInfo.itemCount(n);
                if (items != null) {
                    e.put("itemCount", items);
                }
                Integer sel = NodeInfo.selectedIndex(n);
                if (sel != null) {
                    e.put("selected", sel);
                }
                if (n instanceof javafx.scene.control.ProgressIndicator p) {
                    e.put("progress", p.getProgress());
                }
                var kids = LogicalTree.children(n);
                var segs = NodePath.childSegments(kids);
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.push(kids.get(i));
                    paths.push(path + "/" + segs.get(i));
                }
            }
        }
        out.put("overflow", overflow);
        out.put("count", count);
        return out;
    }
}

package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Cell;

/** Bounded scene-graph → JSON (PRD 01 §5). FX thread only. */
final class SceneSerializer {
    private final int maxDepth;
    private final int maxNodes;
    private int emitted;
    private boolean truncated;

    SceneSerializer(int maxDepth, int maxNodes) {
        this.maxDepth = maxDepth;
        this.maxNodes = maxNodes;
    }

    boolean truncated() {
        return truncated;
    }

    int emitted() {
        return emitted;
    }

    ObjectNode serialize(Node root) {
        return node(root, 0, NodePath.of(root));
    }

    private ObjectNode node(Node n, int depth, String path) {
        emitted++;
        ObjectNode o = Json.object();
        o.put("h", AgentRuntime.get().handles().handleOf(n));
        o.put("path", path);
        o.put("type", NodePath.typeName(n));
        if (n.getId() != null && !n.getId().isEmpty()) {
            o.put("fxId", n.getId());
        }
        if (!n.getStyleClass().isEmpty()) {
            o.put("styleClass", String.join(" ", n.getStyleClass()));
        }
        String text = NodeInfo.text(n);
        if (text != null) {
            o.put("text", NodeInfo.clip(text, 80));
        }
        Object value = NodeInfo.value(n);
        if (value != null) {
            NodeInfo.putValue(o, "value", value);
        }
        o.put("visible", n.isVisible());
        o.put("disabled", n.isDisabled());
        o.put("focused", n.isFocused());
        ObjectNode bounds = NodeInfo.screenBounds(n);
        if (bounds != null) {
            o.set("boundsScreen", bounds);
        }
        List<Node> kids = LogicalTree.children(n);
        if (LogicalTree.isVirtualized(n)) {
            o.put("itemCount", NodeInfo.itemCount(n));
            Integer sel = NodeInfo.selectedIndex(n);
            if (sel != null && sel >= 0) {
                o.put("selectedIndex", sel);
            }
            ArrayNode cells = o.putArray("visibleCells");
            for (Node c : kids) {
                if (cells.size() >= 10) {
                    break;
                }
                cells.add(NodeInfo.clip(NodeInfo.text(c), 80));
            }
        }
        o.put("kids", kids.size());
        if (kids.isEmpty() || n instanceof Cell<?> && !(n instanceof javafx.scene.control.TableRow<?>)) {
            return o;
        }
        ArrayNode children = o.putArray("children");
        if (depth + 1 > maxDepth) {
            truncated = true;
            children.add(Json.object().put("truncated", true).put("kids", kids.size()));
            return o;
        }
        List<String> segs = NodePath.childSegments(kids);
        for (int i = 0; i < kids.size(); i++) {
            if (emitted >= maxNodes) {
                truncated = true;
                children.add(Json.object().put("truncated", true).put("kids", kids.size() - i));
                break;
            }
            Node child = kids.get(i);
            children.add(node(child, depth + 1, path + "/" + segs.get(i)));
        }
        return o;
    }
}

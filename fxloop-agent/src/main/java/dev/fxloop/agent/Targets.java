package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.scene.Node;
import javafx.stage.Window;

/** Resolves the common target params {@code handle | path | query (+windowIndex)} to one node. FX thread only. */
final class Targets {
    private Targets() {}

    static boolean hasTarget(ObjectNode p) {
        return p.hasNonNull("handle") || p.hasNonNull("path") || p.hasNonNull("query");
    }

    static Node resolve(ObjectNode p) {
        if (p.hasNonNull("handle")) {
            Node n = AgentRuntime.get().handles().resolve(p.get("handle").asInt());
            if (n == null || n.getScene() == null) {
                throw new FcpException(Fcp.Error.NODE_NOT_FOUND, "handle " + p.get("handle").asInt()
                        + " is stale (node removed or collected); re-query with fx_find_nodes or use a path");
            }
            return n;
        }
        if (p.hasNonNull("path")) {
            return NodePath.resolve(p.get("path").asText());
        }
        if (p.hasNonNull("query")) {
            List<Node> hits = query(p.get("query").asText(), p.hasNonNull("windowIndex") ? p.get("windowIndex").asInt() : null);
            if (hits.isEmpty()) {
                throw new FcpException(Fcp.Error.NODE_NOT_FOUND, "CSS query '" + p.get("query").asText()
                        + "' matched nothing; check the fx:id / style class with fx_find_nodes");
            }
            if (hits.size() > 1) {
                ArrayNode c = Json.array();
                hits.stream().limit(10).forEach(n -> c.add(NodePath.of(n)));
                ObjectNode data = Json.object();
                data.set("candidates", c);
                throw new FcpException(Fcp.Error.AMBIGUOUS_PATH, "CSS query '" + p.get("query").asText() + "' matched "
                        + hits.size() + " nodes; target one by path", data);
            }
            return hits.get(0);
        }
        throw new FcpException(Fcp.Error.BAD_REQUEST, "target required: pass handle, path or query");
    }

    /** CSS lookup across windows (or one window), mapped to logical owners and de-duplicated. */
    static List<Node> query(String selector, Integer windowIndex) {
        List<Window> windows = windowIndex == null ? Windows.showing() : List.of(Windows.byIndex(windowIndex));
        Set<Node> out = new LinkedHashSet<>();
        for (Window w : windows) {
            try {
                for (Node n : w.getScene().getRoot().lookupAll(selector)) {
                    Node owner = LogicalTree.owner(n);
                    if (owner != null) {
                        out.add(owner);
                    }
                }
            } catch (RuntimeException e) {
                throw new FcpException(Fcp.Error.BAD_REQUEST, "invalid CSS selector '" + selector + "': " + e.getMessage());
            }
        }
        return new ArrayList<>(out);
    }

    static ObjectNode summary(Node n) {
        ObjectNode o = Json.object();
        o.put("h", AgentRuntime.get().handles().handleOf(n));
        o.put("path", NodePath.of(n));
        o.put("type", NodePath.typeName(n));
        if (n.getId() != null) {
            o.put("fxId", n.getId());
        }
        String text = NodeInfo.text(n);
        if (text != null) {
            o.put("text", NodeInfo.clip(text, 80));
        }
        return o;
    }
}

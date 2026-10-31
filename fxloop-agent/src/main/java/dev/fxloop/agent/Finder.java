package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Paging;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.Node;
import javafx.stage.Window;

/** {@code findNodes}: CSS query, case-insensitive text contains, or simple type name. FX thread only. */
final class Finder {
    private Finder() {}

    static ObjectNode find(ObjectNode p) {
        Integer windowIndex = p.hasNonNull("windowIndex") ? p.get("windowIndex").asInt() : null;
        int limit = Math.min(200, p.path("limit").asInt(20));
        int offset = Math.max(0, p.path("offset").asInt(0));
        List<Node> hits;
        if (p.hasNonNull("query")) {
            hits = Targets.query(p.get("query").asText(), windowIndex);
        } else if (p.hasNonNull("text") || p.hasNonNull("type")) {
            String text = p.hasNonNull("text") ? p.get("text").asText().toLowerCase(Locale.ROOT) : null;
            String type = p.hasNonNull("type") ? p.get("type").asText() : null;
            hits = new ArrayList<>();
            List<Window> windows = windowIndex == null ? Windows.showing() : List.of(Windows.byIndex(windowIndex));
            for (Window w : windows) {
                for (Node n : NodePath.descendantsInclusive(w.getScene().getRoot())) {
                    if (type != null && !typeMatches(n, type)) {
                        continue;
                    }
                    if (text != null) {
                        String t = NodeInfo.text(n);
                        if (t == null || !t.toLowerCase(Locale.ROOT).contains(text)) {
                            continue;
                        }
                    }
                    hits.add(n);
                }
            }
        } else {
            throw new FcpException(Fcp.Error.BAD_REQUEST, "pass one of query (CSS selector), text or type");
        }
        List<JsonNode> items = new ArrayList<>();
        for (Node n : hits) {
            ObjectNode s = Targets.summary(n);
            s.put("visible", n.isVisible());
            s.put("disabled", n.isDisabled());
            ObjectNode b = NodeInfo.screenBounds(n);
            if (b != null) {
                s.set("boundsScreen", b);
            }
            items.add(s);
        }
        return Paging.page(items, offset, limit);
    }

    private static boolean typeMatches(Node n, String type) {
        for (Class<?> c = n.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals(type)) {
                return true;
            }
        }
        return false;
    }
}

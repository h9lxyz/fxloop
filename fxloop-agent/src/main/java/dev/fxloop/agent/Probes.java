package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import java.lang.reflect.Method;
import java.util.List;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

/** Point probing ({@code fx_describe_at}), WebView JS bridge, test-only FX blocker. */
final class Probes {
    private Probes() {}

    static ObjectNode describeAt(ObjectNode p) {
        double x = p.path("x").asDouble();
        double y = p.path("y").asDouble();
        return Fx.call(() -> {
            List<Window> windows = new java.util.ArrayList<>(Windows.showing());
            windows.sort((a, b) -> Boolean.compare(b instanceof PopupWindow, a instanceof PopupWindow));
            for (Window w : windows) {
                Parent root = w.getScene().getRoot();
                Point2D local = root.screenToLocal(x, y);
                if (local == null || !root.contains(local)) {
                    continue;
                }
                Node hit = deepest(root, x, y);
                Node logical = LogicalTree.owner(hit);
                ObjectNode out = Json.object();
                out.set("node", Targets.summary(logical));
                out.put("pickedType", NodePath.typeName(hit));
                ObjectNode b = NodeInfo.screenBounds(logical);
                if (b != null) {
                    out.set("boundsScreen", b);
                }
                ArrayNode chain = out.putArray("ancestors");
                for (Node cur = LogicalTree.parent(logical); cur != null; cur = LogicalTree.parent(cur)) {
                    chain.add(NodePath.typeName(cur) + (cur.getId() != null ? "#" + cur.getId() : ""));
                }
                out.put("windowIndex", Windows.indexOf(w));
                return out;
            }
            throw new FcpException(Fcp.Error.NODE_NOT_FOUND, "no application window at screen point " + x + "," + y);
        });
    }

    private static Node deepest(Node n, double x, double y) {
        if (n instanceof Parent parent) {
            var kids = parent.getChildrenUnmodifiable();
            for (int i = kids.size() - 1; i >= 0; i--) {
                Node k = kids.get(i);
                if (!k.isVisible() || k.isMouseTransparent()) {
                    continue;
                }
                Point2D lp = k.screenToLocal(x, y);
                if (lp != null && k.contains(lp)) {
                    return deepest(k, x, y);
                }
            }
        }
        return n;
    }

    static ObjectNode evalJs(ObjectNode p) {
        if (!"true".equalsIgnoreCase(p.path("enabled").asText("false"))) {
            throw new FcpException(Fcp.Error.DISABLED, "fx_eval_js is disabled; start the server with FXLOOP_WEBVIEW=true");
        }
        String script = p.path("script").asText();
        long timeout = p.path("timeoutMs").asLong(3000);
        return Fx.call(() -> {
            Node node = Targets.resolve(p);
            if (!node.getClass().getName().equals("javafx.scene.web.WebView")) {
                throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, "fx_eval_js targets WebView nodes only, got "
                        + NodePath.typeName(node));
            }
            try {
                Object engine = node.getClass().getMethod("getEngine").invoke(node);
                Method exec = engine.getClass().getMethod("executeScript", String.class);
                Object json = exec.invoke(engine, "(function(){var __r=(function(){return (" + script
                        + ");})();try{return JSON.stringify(__r);}catch(e){return String(__r);}})()");
                ObjectNode out = Json.object();
                String s = json == null ? null : json.toString();
                try {
                    out.set("result", s == null || "undefined".equals(s) ? Json.object().nullNode() : Json.MAPPER.readTree(s));
                } catch (Exception notJson) {
                    out.put("result", s);
                }
                return out;
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new FcpException(Fcp.Error.BAD_REQUEST, "JavaScript error: " + e.getCause().getMessage());
            }
        }, timeout);
    }

    static ObjectNode blockFxThread(ObjectNode p) {
        if (!Boolean.getBoolean("fxloop.test")) {
            throw new FcpException(Fcp.Error.DISABLED, "blockFxThread is test-only (-Dfxloop.test=true)");
        }
        long ms = Math.min(30_000, p.path("ms").asLong(8000));
        javafx.application.Platform.runLater(() -> Fx.sleep(ms));
        return Json.object().put("blocking", ms);
    }
}

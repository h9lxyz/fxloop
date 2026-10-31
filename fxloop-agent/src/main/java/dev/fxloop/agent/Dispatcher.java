package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp.Method;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import javafx.scene.Node;
import javafx.stage.Window;

/** FCP method table. Each handler marshals to the FX thread itself, so waits/settles happen off it. */
final class Dispatcher {
    private final AgentRuntime rt;
    private final Map<String, Function<ObjectNode, JsonNode>> handlers = new HashMap<>();

    Dispatcher(AgentRuntime rt) {
        this.rt = rt;
        handlers.put(Method.PING, p -> Json.object().put("pong", System.currentTimeMillis()));
        handlers.put(Method.STATUS, p -> status());
        handlers.put(Method.GET_SCENE_GRAPH, this::sceneGraph);
        handlers.put(Method.FIND_NODES, p -> Fx.call(() -> Finder.find(p)));
        handlers.put(Method.CLICK, Interaction::click);
        handlers.put(Method.TYPE_KEYS, Interaction::typeKeys);
        handlers.put(Method.SET_TEXT, Interaction::setText);
        handlers.put(Method.GET_PROPERTY, Props::get);
        handlers.put(Method.SET_PROPERTY, Props::set);
        handlers.put(Method.NODE_DETAILS, Props::details);
        handlers.put(Method.SCREENSHOT, Snapshots::screenshot);
        handlers.put(Method.CAPTURE_STATES, Snapshots::captureStates);
        handlers.put(Method.GET_UI_ERRORS, p -> Json.object().set("errors", rt.errors().since(p.path("sinceSeq").asLong(0))));
        handlers.put(Method.GET_APP_LOGS, p -> Json.object().put("installed", rt.logs().installed())
                .set("records", rt.logs().since(p.path("sinceSeq").asLong(0))));
        handlers.put(Method.SNAPSHOT_STATE, p -> {
            if (p.path("settleMs").asLong(0) > 0) {
                Fx.sleep(p.get("settleMs").asLong());
            }
            return Fx.call(() -> StateSnapshot.capture(p.path("maxNodes").asInt(2000)));
        });
        handlers.put(Method.PATH_VISIBLE, p -> Fx.call(() -> {
            try {
                Node n = NodePath.resolve(p.path("path").asText());
                return Json.object().put("visible", n.isVisible() && n.getScene() != null && n.localToScreen(0, 0) != null);
            } catch (FcpException e) {
                return Json.object().put("visible", false).put("reason", e.getMessage());
            }
        }));
        handlers.put(Method.DESCRIBE_AT, Probes::describeAt);
        handlers.put(Method.RELOAD_CSS, Reloads::reloadCss);
        handlers.put(Method.RELOAD_FXML, Reloads::reloadFxml);
        handlers.put(Method.REDEFINE_CLASSES, Reloads::redefine);
        handlers.put(Method.LINT_VISUAL, VisualLint::lint);
        handlers.put(Method.EXPLAIN_PROPERTY, Introspect::explainProperty);
        handlers.put(Method.EXPLAIN_STYLE, Introspect::explainStyle);
        handlers.put(Method.EXPLAIN_LAYOUT, Introspect::explainLayout);
        handlers.put(Method.EVAL_JS, Probes::evalJs);
        handlers.put(Method.BLOCK_FX_THREAD, Probes::blockFxThread);
    }

    JsonNode dispatch(String method, ObjectNode params) {
        Function<ObjectNode, JsonNode> h = handlers.get(method);
        if (h == null) {
            throw new FcpException(Fcp.Error.UNKNOWN_METHOD, "unknown FCP method '" + method + "'");
        }
        return h.apply(params == null ? Json.object() : params);
    }

    private ObjectNode status() {
        return Fx.call(() -> {
            ObjectNode o = Json.object();
            o.put("mode", rt.mode());
            o.put("uptimeMs", System.currentTimeMillis() - rt.startedAt());
            o.set("windows", Windows.describeAll());
            o.set("fxml", rt.fxml().describe());
            o.put("handles", rt.handles().size());
            o.put("redefineSupported", rt.instrumentation() != null && rt.instrumentation().isRedefineClassesSupported());
            return o;
        });
    }

    private ObjectNode sceneGraph(ObjectNode p) {
        int maxDepth = Math.max(1, Math.min(64, p.path("maxDepth").asInt(12)));
        int maxNodes = Math.max(1, Math.min(5000, p.path("maxNodes").asInt(400)));
        return Fx.call(() -> {
            Node root;
            Window window;
            if (Targets.hasTarget(p) || p.hasNonNull("rootHandle")) {
                if (p.hasNonNull("rootHandle")) {
                    p.put("handle", p.get("rootHandle").asInt());
                }
                root = Targets.resolve(p);
                window = root.getScene().getWindow();
            } else {
                window = Windows.byIndex(p.hasNonNull("windowIndex") ? p.get("windowIndex").asInt() : null);
                root = window.getScene().getRoot();
            }
            SceneSerializer s = new SceneSerializer(maxDepth, maxNodes);
            ObjectNode out = Json.object();
            out.set("window", Windows.describe(window, Windows.indexOf(window)));
            out.set("root", s.serialize(root));
            out.put("truncated", s.truncated());
            out.put("nodeCount", s.emitted());
            return out;
        });
    }
}

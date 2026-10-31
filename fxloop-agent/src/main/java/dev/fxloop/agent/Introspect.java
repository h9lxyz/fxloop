package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import dev.fxloop.protocol.Paging;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.binding.Binding;
import javafx.beans.property.ReadOnlyProperty;
import javafx.beans.value.ObservableValue;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** "Why?" tools (PRD 03 §6): property bindings, style origins, layout constraints. FX thread via callers. */
final class Introspect {
    private Introspect() {}

    static ObjectNode explainProperty(ObjectNode p) {
        return Fx.call(() -> {
            Node node = Targets.resolve(p);
            String name = p.path("name").asText();
            Props.Access a = Props.access(node, name);
            ObjectNode out = Props.describe(a, node);
            ObservableValue<?> obs = a.observable();
            if (obs instanceof javafx.beans.property.Property<?> prop && prop.isBound()) {
                out.put("note", "bound via bind(); the binding object itself is not reachable through public API, "
                        + "but its dependencies are listed when it is a Binding");
                Object target = boundTarget(prop);
                if (target instanceof ObservableValue<?> src) {
                    out.set("dependencyTree", tree(src, 0, new IdentityHashMap<>()));
                }
            } else if (obs instanceof Binding<?> b) {
                out.set("dependencyTree", tree(b, 0, new IdentityHashMap<>()));
            }
            if (obs instanceof StyleableProperty<?> sp) {
                out.put("styleOrigin", String.valueOf(sp.getStyleOrigin()));
            }
            out.put("bidirectional", "unknown-peer");
            return out;
        });
    }

    /** Property implementations keep their binding in a private field named {@code observable}; read it if open. */
    private static Object boundTarget(Object prop) {
        for (Class<?> c = prop.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var f = c.getDeclaredField("observable");
                f.setAccessible(true);
                return f.get(prop);
            } catch (NoSuchFieldException e) {
                // keep looking upwards
            } catch (RuntimeException | IllegalAccessException e) {
                return null; // module encapsulation: honest gap
            }
        }
        return null;
    }

    private static ObjectNode tree(ObservableValue<?> obs, int depth, Map<Object, Boolean> seen) {
        ObjectNode o = Json.object();
        o.put("class", obs.getClass().getName());
        NodeInfo.putValue(o, "value", obs.getValue());
        if (obs instanceof ReadOnlyProperty<?> rp) {
            o.put("property", rp.getName());
            Object bean = rp.getBean();
            if (bean instanceof Node n) {
                o.put("owner", NodePath.of(n));
            } else if (bean != null) {
                o.put("owner", bean.getClass().getSimpleName());
            }
        }
        if (seen.put(obs, Boolean.TRUE) != null) {
            o.put("cycle", true);
            return o;
        }
        if (obs instanceof Binding<?> b && depth < 5) {
            ArrayNode deps = o.putArray("dependencies");
            for (Object d : b.getDependencies()) {
                if (d instanceof ObservableValue<?> ov) {
                    deps.add(tree(ov, depth + 1, seen));
                } else {
                    deps.add(Json.object().put("class", d.getClass().getName()));
                }
            }
        }
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ObjectNode explainStyle(ObjectNode p) {
        return Fx.call(() -> {
            Node node = Targets.resolve(p);
            String only = p.hasNonNull("property") ? p.get("property").asText() : null;
            List<com.fasterxml.jackson.databind.JsonNode> items = new ArrayList<>();
            for (CssMetaData<? extends Styleable, ?> meta : node.getCssMetaData()) {
                if (only != null && !meta.getProperty().equals(only)) {
                    continue;
                }
                StyleableProperty<?> sp = ((CssMetaData) meta).getStyleableProperty(node);
                if (sp == null) {
                    continue;
                }
                var origin = sp.getStyleOrigin();
                if (only == null && origin == null) {
                    continue; // untouched by CSS: omit to stay token-efficient
                }
                ObjectNode o = Json.object();
                o.put("cssProperty", meta.getProperty());
                NodeInfo.putValue(o, "value", sp.getValue());
                o.put("origin", origin == null ? "NONE(default/code)" : origin.name());
                items.add(o);
            }
            ObjectNode out = Paging.page(items, p.path("offset").asInt(0), Math.min(200, p.path("limit").asInt(80)));
            out.set("target", Targets.summary(node));
            out.put("styleClass", String.join(" ", node.getStyleClass()));
            out.put("pseudoClasses", node.getPseudoClassStates().toString());
            if (node.getStyle() != null && !node.getStyle().isBlank()) {
                out.put("inlineStyle", node.getStyle());
            }
            ArrayNode sheets = out.putArray("candidateStylesheets");
            for (Parent cur = node instanceof Parent np ? np : node.getParent(); cur != null; cur = cur.getParent()) {
                for (String s : cur.getStylesheets()) {
                    sheets.add(Json.object().put("url", s).put("on", NodePath.of(cur)));
                }
            }
            Scene scene = node.getScene();
            if (scene != null) {
                scene.getStylesheets().forEach(s -> sheets.add(Json.object().put("url", s).put("on", "Scene")));
                if (scene.getUserAgentStylesheet() != null) {
                    sheets.add(Json.object().put("url", scene.getUserAgentStylesheet()).put("on", "Scene (user agent)"));
                }
            }
            out.put("note", "rule-level source mapping (exact selector/line) is out of scope; origin + candidates only");
            return out;
        });
    }

    static ObjectNode explainLayout(ObjectNode p) {
        return Fx.call(() -> {
            Node node = Targets.resolve(p);
            ObjectNode out = Json.object();
            out.set("target", Targets.summary(node));
            out.set("layoutBounds", bounds(node.getLayoutBounds()));
            out.set("boundsInParent", bounds(node.getBoundsInParent()));
            Bounds screen = node.localToScreen(node.getBoundsInLocal());
            if (screen != null) {
                out.set("boundsInScreen", bounds(screen));
            }
            out.put("managed", node.isManaged());
            out.put("resizable", node.isResizable());
            out.put("visible", node.isVisible());
            out.put("translateX", node.getTranslateX()).put("translateY", node.getTranslateY());
            ObjectNode sizes = out.putObject("sizes");
            sizes.put("min", node.minWidth(-1) + "x" + node.minHeight(-1));
            sizes.put("pref", node.prefWidth(-1) + "x" + node.prefHeight(-1));
            sizes.put("max", node.maxWidth(-1) + "x" + node.maxHeight(-1));
            if (node instanceof Region r) {
                out.put("padding", insets(r.getPadding()));
                out.put("insets", insets(r.getInsets()));
            }
            Parent parent = node.getParent();
            ObjectNode c = out.putObject("constraints");
            if (parent != null) {
                out.put("parentType", NodePath.typeName0(parent));
            }
            if (parent instanceof HBox) {
                put(c, "hgrow", HBox.getHgrow(node));
                put(c, "margin", insets(HBox.getMargin(node)));
            } else if (parent instanceof VBox) {
                put(c, "vgrow", VBox.getVgrow(node));
                put(c, "margin", insets(VBox.getMargin(node)));
            } else if (parent instanceof GridPane) {
                put(c, "row", GridPane.getRowIndex(node));
                put(c, "column", GridPane.getColumnIndex(node));
                put(c, "hgrow", GridPane.getHgrow(node));
                put(c, "vgrow", GridPane.getVgrow(node));
                put(c, "halignment", GridPane.getHalignment(node));
                put(c, "margin", insets(GridPane.getMargin(node)));
            } else if (parent instanceof AnchorPane) {
                put(c, "top", AnchorPane.getTopAnchor(node));
                put(c, "left", AnchorPane.getLeftAnchor(node));
                put(c, "bottom", AnchorPane.getBottomAnchor(node));
                put(c, "right", AnchorPane.getRightAnchor(node));
            } else if (parent instanceof BorderPane) {
                put(c, "alignment", BorderPane.getAlignment(node));
                put(c, "margin", insets(BorderPane.getMargin(node)));
            } else if (parent instanceof StackPane) {
                put(c, "alignment", StackPane.getAlignment(node));
                put(c, "margin", insets(StackPane.getMargin(node)));
            }
            ArrayNode hints = out.putArray("hints");
            if (!node.isManaged()) hints.add("unmanaged node: ignored by parent layout, positioned by layoutX/Y only");
            if (node.getTranslateX() != 0 || node.getTranslateY() != 0)
                hints.add("translate offset applied after layout — node appears shifted relative to its slot");
            if (node.getLayoutBounds().getWidth() <= 0 || node.getLayoutBounds().getHeight() <= 0)
                hints.add("zero-size layout bounds");
            if (node instanceof Region r && r.getWidth() < r.prefWidth(-1) - 0.5)
                hints.add("width below preferred width — content may be truncated");
            return out;
        });
    }

    private static void put(ObjectNode o, String k, Object v) {
        if (v != null) {
            o.put(k, String.valueOf(v));
        }
    }

    private static String insets(Insets i) {
        return i == null ? null : i.getTop() + " " + i.getRight() + " " + i.getBottom() + " " + i.getLeft();
    }

    static ObjectNode bounds(Bounds b) {
        ObjectNode o = Json.object();
        o.put("x", round(b.getMinX())).put("y", round(b.getMinY())).put("w", round(b.getWidth())).put("h", round(b.getHeight()));
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}

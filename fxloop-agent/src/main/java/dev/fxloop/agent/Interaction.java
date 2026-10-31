package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.robot.Robot;

/** Click / key / text actions (PRD 01 §7, D6, D7). */
final class Interaction {
    private static Robot robot;

    private Interaction() {}

    private static Robot robot() {
        if (robot == null) {
            robot = new Robot();
        }
        return robot;
    }

    static ObjectNode click(ObjectNode p) {
        String mode = p.path("mode").asText("robot");
        MouseButton button = MouseButton.valueOf(p.path("button").asText("primary").toUpperCase(java.util.Locale.ROOT));
        int clickCount = Math.max(1, p.path("clickCount").asInt(1));
        ObjectNode result = Fx.call(() -> {
            ObjectNode out = Json.object();
            out.put("mode", mode);
            if (!Targets.hasTarget(p)) {
                if (!p.has("x") || !p.has("y")) {
                    throw new FcpException(Fcp.Error.BAD_REQUEST, "pass handle, path, query or screen x,y");
                }
                if (!"robot".equals(mode)) {
                    throw new FcpException(Fcp.Error.BAD_REQUEST, "x,y clicks require mode=robot");
                }
                double x = p.get("x").asDouble();
                double y = p.get("y").asDouble();
                robotClick(x, y, button, clickCount);
                out.put("x", x).put("y", y);
                return out;
            }
            Node node = Targets.resolve(p);
            if (node.isDisabled()) {
                throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, NodePath.of(node)
                        + " is disabled; inspect why with fx_explain_property {name:'disable'} before clicking");
            }
            out.set("target", Targets.summary(node));
            if ("event".equals(mode)) {
                eventClick(node, button, clickCount);
            } else {
                ensureVisible(node);
                Point2D c = center(node);
                robotClick(c.getX(), c.getY(), button, clickCount);
                out.put("x", Math.round(c.getX())).put("y", Math.round(c.getY()));
            }
            out.put("clicked", true);
            return out;
        });
        settle(p);
        return result;
    }

    private static void robotClick(double x, double y, MouseButton button, int count) {
        Robot r = robot();
        r.mouseMove(x, y);
        for (int i = 0; i < count; i++) {
            r.mouseClick(button);
        }
    }

    private static void eventClick(Node node, MouseButton button, int count) {
        if (button == MouseButton.PRIMARY && count == 1 && node instanceof ButtonBase b && !(node instanceof MenuButton)) {
            b.fire();
            return;
        }
        if (node instanceof ComboBoxBase<?> cb && button == MouseButton.PRIMARY) {
            if (cb.isShowing()) {
                cb.hide();
            } else {
                cb.show();
            }
            return;
        }
        Bounds b = node.getBoundsInLocal();
        double lx = b.getCenterX();
        double ly = b.getCenterY();
        Point2D scene = node.localToScene(lx, ly);
        Point2D screen = node.localToScreen(lx, ly);
        double sx = screen == null ? 0 : screen.getX();
        double sy = screen == null ? 0 : screen.getY();
        PickResult pick = new PickResult(node, scene.getX(), scene.getY());
        for (int i = 1; i <= count; i++) {
            for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                MouseEvent e = new MouseEvent(type, scene.getX(), scene.getY(), sx, sy, button, i, false, false, false,
                        false, button == MouseButton.PRIMARY, button == MouseButton.MIDDLE, button == MouseButton.SECONDARY,
                        true, button == MouseButton.SECONDARY, true, pick);
                Event.fireEvent(node, e);
            }
        }
    }

    static Point2D center(Node node) {
        Bounds b = node.localToScreen(node.getBoundsInLocal());
        if (b == null) {
            throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, NodePath.of(node) + " is not in a showing window");
        }
        Point2D c = new Point2D(b.getCenterX(), b.getCenterY());
        Scene scene = node.getScene();
        var w = scene.getWindow();
        double sx = w.getX() + scene.getX();
        double sy = w.getY() + scene.getY();
        if (c.getX() < sx || c.getY() < sy || c.getX() > sx + scene.getWidth() || c.getY() > sy + scene.getHeight()) {
            throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, NodePath.of(node)
                    + " is outside its window's visible area (center " + Math.round(c.getX()) + "," + Math.round(c.getY())
                    + "); scroll it into view or use mode=event");
        }
        return c;
    }

    /** Best effort (PRD 03 §9): select enclosing tabs and scroll enclosing ScrollPanes so the node is on screen. */
    static void ensureVisible(Node node) {
        List<Node> chain = new ArrayList<>();
        for (Node cur = node; cur != null; cur = LogicalTree.parent(cur)) {
            chain.add(0, cur);
        }
        boolean changed = false;
        for (int i = 0; i < chain.size() - 1; i++) {
            Node anc = chain.get(i);
            Node next = chain.get(i + 1);
            if (anc instanceof TabPane tp) {
                for (Tab t : tp.getTabs()) {
                    if (t.getContent() == next && tp.getSelectionModel().getSelectedItem() != t) {
                        tp.getSelectionModel().select(t);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            node.getScene().getRoot().applyCss();
            node.getScene().getRoot().layout();
        }
        for (int i = chain.size() - 2; i >= 0; i--) {
            if (chain.get(i) instanceof ScrollPane sp && sp.getContent() != null) {
                scrollInto(sp, node);
            }
        }
    }

    private static void scrollInto(ScrollPane sp, Node node) {
        Node content = sp.getContent();
        Bounds inContent = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        Bounds viewport = sp.getViewportBounds();
        double contentH = content.getBoundsInLocal().getHeight();
        double contentW = content.getBoundsInLocal().getWidth();
        if (contentH > viewport.getHeight()) {
            double target = (inContent.getMinY() + inContent.getHeight() / 2 - viewport.getHeight() / 2)
                    / (contentH - viewport.getHeight());
            sp.setVvalue(clamp(sp.getVmin() + target * (sp.getVmax() - sp.getVmin()), sp.getVmin(), sp.getVmax()));
        }
        if (contentW > viewport.getWidth()) {
            double target = (inContent.getMinX() + inContent.getWidth() / 2 - viewport.getWidth() / 2)
                    / (contentW - viewport.getWidth());
            sp.setHvalue(clamp(sp.getHmin() + target * (sp.getHmax() - sp.getHmin()), sp.getHmin(), sp.getHmax()));
        }
        sp.applyCss();
        sp.layout();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static ObjectNode typeKeys(ObjectNode p) {
        JsonNode keys = p.path("keys");
        if (!keys.isArray() || keys.isEmpty()) {
            throw new FcpException(Fcp.Error.BAD_REQUEST, "keys must be a non-empty array of {code, modifiers?}");
        }
        List<KeyCode[]> combos = new ArrayList<>();
        for (JsonNode k : keys) {
            List<KeyCode> combo = new ArrayList<>();
            for (JsonNode m : k.path("modifiers")) {
                combo.add(keyCode(m.asText()));
            }
            combo.add(keyCode(k.path("code").asText()));
            combos.add(combo.toArray(KeyCode[]::new));
        }
        if (Targets.hasTarget(p)) {
            Fx.run(() -> Targets.resolve(p).requestFocus());
        }
        for (KeyCode[] combo : combos) {
            Fx.run(() -> {
                Robot r = robot();
                for (KeyCode c : combo) {
                    r.keyPress(c);
                }
                for (int i = combo.length - 1; i >= 0; i--) {
                    r.keyRelease(combo[i]);
                }
            });
        }
        settle(p);
        return Fx.call(() -> {
            ObjectNode out = Json.object();
            out.put("typed", combos.size());
            Scene scene = Windows.byIndex(null).getScene();
            Node focus = scene.getFocusOwner();
            if (focus != null) {
                out.set("focusedAfter", Targets.summary(LogicalTree.owner(focus) == null ? focus : LogicalTree.owner(focus)));
            }
            return out;
        });
    }

    private static KeyCode keyCode(String name) {
        String n = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (n.equals("SHORTCUT")) {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac") ? KeyCode.COMMAND
                    : KeyCode.CONTROL;
        }
        if (n.equals("CTRL")) {
            n = "CONTROL";
        }
        if (n.equals("RETURN")) {
            n = "ENTER";
        }
        try {
            return KeyCode.valueOf(n);
        } catch (IllegalArgumentException e) {
            KeyCode byName = KeyCode.getKeyCode(name);
            if (byName != null) {
                return byName;
            }
            throw new FcpException(Fcp.Error.BAD_REQUEST, "unknown KeyCode '" + name
                    + "'; use javafx.scene.input.KeyCode names (ENTER, TAB, A, DIGIT1, SHORTCUT …). For free text use fx_set_text");
        }
    }

    static ObjectNode setText(ObjectNode p) {
        String text = p.path("text").asText("");
        boolean clearFirst = p.path("clearFirst").asBoolean(true);
        ObjectNode out = Fx.call(() -> {
            Node node = Targets.resolve(p);
            TextInputControl input = node instanceof TextInputControl t ? t
                    : node instanceof javafx.scene.control.ComboBox<?> cb && cb.isEditable() ? cb.getEditor() : null;
            if (input == null) {
                throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, NodePath.typeName(node)
                        + " is not a TextInputControl; use fx_type_keys for key presses or fx_set_property for other values");
            }
            if (input.textProperty().isBound()) {
                throw new FcpException(Fcp.Error.PROPERTY_BOUND, "text of " + NodePath.of(node)
                        + " is bound; write to the binding's source instead");
            }
            input.requestFocus();
            if (clearFirst) {
                input.setText(text);
            } else {
                input.appendText(text);
            }
            input.positionCaret(input.getLength());
            ObjectNode o = Json.object();
            o.put("set", true);
            o.set("target", Targets.summary(node));
            o.put("valueAfter", input.getText());
            return o;
        });
        settle(p);
        return out;
    }

    /** Lets robot-injected events reach the FX thread before the next read (agent-side settle). */
    static void settle(ObjectNode p) {
        Fx.sleep(p.path("settleMs").asLong(60));
        Fx.barrier();
    }
}

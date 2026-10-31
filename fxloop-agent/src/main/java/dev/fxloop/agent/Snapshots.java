package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Labeled;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Window;
import javax.imageio.ImageIO;

/**
 * Raw frame capture. The agent stays thin: it renders and encodes PNG, and reports the window origin and output
 * scale so the server can composite annotations (PRD 04 §3.1). No {@code javafx.swing} dependency.
 */
final class Snapshots {
    static final int HARD_MAX_WIDTH = 1600;

    private Snapshots() {}

    private record Frame(int w, int h, int[] argb) {}

    static ObjectNode screenshot(ObjectNode p) {
        Fx.sleep(p.path("settleMs").asLong(150));
        Fx.barrier();
        String target = p.path("target").asText(Targets.hasTarget(p) ? "node" : "window");
        int maxWidth = Math.min(HARD_MAX_WIDTH * 2, Math.max(64, p.path("maxWidth").asInt(1200)));
        Double requestedScale = p.hasNonNull("scale") ? Math.max(0.25, Math.min(3.0, p.get("scale").asDouble())) : null;
        String annotate = p.path("annotate").asText("none");
        ObjectNode meta = Json.object();
        Frame frame = Fx.call(() -> {
            Node node;
            double originX;
            double originY;
            double width;
            Window window;
            if ("node".equals(target)) {
                node = Targets.resolve(p);
                Bounds b = node.localToScreen(node.getBoundsInLocal());
                if (b == null) {
                    throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, "node is not showing; cannot snapshot");
                }
                originX = b.getMinX();
                originY = b.getMinY();
                width = b.getWidth();
                window = node.getScene().getWindow();
                meta.set("target", Targets.summary(node));
            } else {
                window = Windows.byIndex(p.hasNonNull("windowIndex") ? p.get("windowIndex").asInt() : null);
                Scene scene = window.getScene();
                node = scene.getRoot();
                originX = window.getX() + scene.getX();
                originY = window.getY() + scene.getY();
                width = scene.getWidth();
                meta.put("windowIndex", Windows.indexOf(window));
            }
            double scale = requestedScale != null ? requestedScale : 1.0;
            int cap = "node".equals(target) ? maxWidth : Math.min(maxWidth, HARD_MAX_WIDTH);
            if (width * scale > cap) {
                scale = cap / width;
            }
            Frame f = render(node, scale, window.getScene());
            meta.put("outputScale", scale);
            ObjectNode origin = meta.putObject("windowOrigin");
            origin.put("x", originX);
            origin.put("y", originY);
            if (!"none".equals(annotate)) {
                meta.set("targets", annotationTargets(window, node, annotate));
            }
            return f;
        });
        byte[] png = encode(frame);
        meta.put("w", frame.w());
        meta.put("h", frame.h());
        meta.put("pngB64", Base64.getEncoder().encodeToString(png));
        return meta;
    }

    static Frame render(Node node, double scale, Scene scene) {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(scale, scale));
        if (scene != null && scene.getRoot() == node && scene.getFill() instanceof Color fill) {
            params.setFill(fill);
        } else if (scene != null && scene.getRoot() == node) {
            params.setFill(Color.WHITE);
        }
        WritableImage img = node.snapshot(params, null);
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        int[] argb = new int[w * h];
        PixelReader reader = img.getPixelReader();
        reader.getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
        return new Frame(w, h, argb);
    }

    static byte[] encode(Frame f) {
        BufferedImage bi = new BufferedImage(f.w(), f.h(), BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, f.w(), f.h(), f.argb(), 0, f.w());
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(bi, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Set-of-Mark candidates: interactive = focus-traversable or Labeled; all = every visible node; else CSS query. */
    private static ArrayNode annotationTargets(Window window, Node scope, String filter) {
        List<Node> pool;
        if ("interactive".equals(filter) || "all".equals(filter)) {
            pool = NodePath.descendantsInclusive(scope);
        } else {
            pool = new ArrayList<>();
            for (Node n : scope.lookupAll(filter)) {
                Node owner = LogicalTree.owner(n);
                if (owner != null && !pool.contains(owner)) {
                    pool.add(owner);
                }
            }
        }
        ArrayNode out = Json.array();
        int total = 0;
        for (Node n : pool) {
            if (!n.isVisible() || n.getScene() == null || n.getScene().getWindow() != window) {
                continue;
            }
            if ("interactive".equals(filter) && !(n.isFocusTraversable() || n instanceof Labeled)) {
                continue;
            }
            Bounds b = n.localToScreen(n.getBoundsInLocal());
            if (b == null || b.getWidth() < 1 || b.getHeight() < 1) {
                continue;
            }
            total++;
            if (out.size() >= 40) {
                continue;
            }
            ObjectNode t = Targets.summary(n);
            ObjectNode bs = t.putObject("boundsScreen");
            bs.put("x", b.getMinX()).put("y", b.getMinY()).put("w", b.getWidth()).put("h", b.getHeight());
            out.add(t);
        }
        if (total > out.size()) {
            out.add(Json.object().put("overflow", true).put("total", total));
        }
        return out;
    }

    /** {@code fx_capture_states}: snapshot one node per pseudo-state, restoring the original state in finally. */
    static ObjectNode captureStates(ObjectNode p) {
        List<String> states = new ArrayList<>();
        p.path("states").forEach(s -> states.add(s.asText().toLowerCase(Locale.ROOT)));
        if (states.isEmpty()) {
            states.addAll(List.of("none", "hover", "focused", "pressed", "disabled"));
        }
        double scale = Math.max(0.25, Math.min(3.0, p.path("scale").asDouble(2.0)));
        ObjectNode out = Json.object();
        ArrayNode frames = out.putArray("frames");
        List<Frame> rendered = new ArrayList<>();
        Fx.run(() -> {
            Node node = Targets.resolve(p);
            out.set("target", Targets.summary(node));
            boolean wasDisabled = node.isDisable();
            java.util.Set<PseudoClass> original = new java.util.HashSet<>(node.getPseudoClassStates());
            try {
                for (String state : states) {
                    applyState(node, state, wasDisabled);
                    node.applyCss();
                    rendered.add(render(node, scale, null));
                    resetState(node, original, wasDisabled);
                }
            } finally {
                resetState(node, original, wasDisabled);
                node.applyCss();
            }
        });
        for (int i = 0; i < states.size(); i++) {
            Frame f = rendered.get(i);
            ObjectNode fr = frames.addObject();
            fr.put("state", states.get(i));
            fr.put("w", f.w()).put("h", f.h());
            fr.put("pngB64", Base64.getEncoder().encodeToString(encode(f)));
        }
        out.put("method", "pseudoClass");
        out.put("scale", scale);
        return out;
    }

    private static final List<String> PSEUDO = List.of("hover", "focused", "pressed", "armed", "selected", "focus-visible");

    private static void applyState(Node node, String state, boolean wasDisabled) {
        switch (state) {
            case "none" -> { }
            case "disabled" -> node.setDisable(true);
            default -> {
                node.pseudoClassStateChanged(PseudoClass.getPseudoClass(state), true);
                if ("pressed".equals(state)) {
                    node.pseudoClassStateChanged(PseudoClass.getPseudoClass("armed"), true);
                    node.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
                }
            }
        }
    }

    private static void resetState(Node node, java.util.Set<PseudoClass> original, boolean wasDisabled) {
        node.setDisable(wasDisabled);
        for (String name : PSEUDO) {
            PseudoClass pc = PseudoClass.getPseudoClass(name);
            node.pseudoClassStateChanged(pc, original.contains(pc));
        }
    }
}

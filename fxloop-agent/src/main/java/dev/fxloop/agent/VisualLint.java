package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import dev.fxloop.protocol.Paging;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;

/** Computable visual heuristics complementing screenshots (PRD 04 §3.5). All findings carry {@code heuristic:true}. */
final class VisualLint {
    static final Set<String> ALL = Set.of("textTruncated", "siblingOverlap", "zeroSizeVisible", "outOfScene", "lowContrast");

    private VisualLint() {}

    static ObjectNode lint(ObjectNode p) {
        Set<String> checks = new HashSet<>();
        p.path("checks").forEach(c -> checks.add(c.asText()));
        if (checks.isEmpty()) {
            checks.addAll(ALL);
        }
        return Fx.call(() -> {
            Node scope = Targets.hasTarget(p) ? Targets.resolve(p)
                    : Windows.byIndex(p.hasNonNull("windowIndex") ? p.get("windowIndex").asInt() : null).getScene().getRoot();
            List<JsonNode> findings = new ArrayList<>();
            List<Node> nodes = NodePath.descendantsInclusive(scope);
            Bounds sceneBounds = new javafx.geometry.BoundingBox(0, 0, scope.getScene().getWidth(), scope.getScene().getHeight());
            for (Node n : nodes) {
                if (!treeVisible(n)) {
                    continue;
                }
                if (checks.contains("textTruncated") && n instanceof Labeled l && truncated(l)) {
                    findings.add(finding("textTruncated", n, "text '" + NodeInfo.clip(l.getText(), 40) + "' needs "
                            + Math.round(textWidth(l)) + "px but has " + Math.round(available(l)) + "px", "warn",
                            "widen the control, allow wrapping (wrapText) or shorten the text"));
                }
                Bounds lb = n.getLayoutBounds();
                if (checks.contains("zeroSizeVisible") && (lb.getWidth() <= 0 || lb.getHeight() <= 0)
                        && !(n instanceof Text)) {
                    findings.add(finding("zeroSizeVisible", n, "visible but " + lb.getWidth() + "x" + lb.getHeight(), "info",
                            "set a size or hide it (setVisible(false)/setManaged(false))"));
                }
                if (checks.contains("outOfScene")) {
                    Bounds sb = n.localToScene(n.getBoundsInLocal());
                    if (sb != null && sb.getWidth() > 0 && !sb.intersects(sceneBounds)) {
                        findings.add(finding("outOfScene", n, "fully outside the scene at " + Math.round(sb.getMinX()) + ","
                                + Math.round(sb.getMinY()), "warn", "check layoutX/Y, translate and managed=false"));
                    }
                }
                if (checks.contains("lowContrast") && (n instanceof Labeled || n instanceof Text)) {
                    contrast(n, findings);
                }
            }
            if (checks.contains("siblingOverlap")) {
                for (Node n : nodes) {
                    if (n instanceof Parent parent && !(n instanceof StackPane) && !(n instanceof javafx.scene.Group)) {
                        overlap(parent, findings);
                    }
                }
            }
            ObjectNode out = Paging.page(findings, p.path("offset").asInt(0), Math.min(200, p.path("limit").asInt(50)));
            out.put("scope", NodePath.of(scope));
            return out;
        });
    }

    private static boolean treeVisible(Node n) {
        for (Node c = n; c != null; c = c.getParent()) {
            if (!c.isVisible() || c.getOpacity() == 0) {
                return false;
            }
        }
        return true;
    }

    private static ObjectNode finding(String check, Node n, String detail, String severity, String suggestion) {
        ObjectNode o = Json.object();
        o.put("check", check);
        o.put("path", NodePath.of(n));
        o.put("detail", detail);
        o.put("severity", severity);
        o.put("suggestion", suggestion);
        o.put("heuristic", true);
        return o;
    }

    private static double textWidth(Labeled l) {
        Text t = new Text(l.getText());
        t.setFont(l.getFont());
        return t.getLayoutBounds().getWidth();
    }

    private static double available(Labeled l) {
        double graphic = l.getGraphic() == null ? 0 : l.getGraphic().prefWidth(-1) + l.getGraphicTextGap();
        return l.getWidth() - l.getInsets().getLeft() - l.getInsets().getRight() - graphic;
    }

    private static boolean truncated(Labeled l) {
        if (l.getText() == null || l.getText().isEmpty() || l.isWrapText() || l.getWidth() <= 0) {
            return false;
        }
        return textWidth(l) > available(l) + 1.0;
    }

    private static void overlap(Parent parent, List<JsonNode> findings) {
        List<Node> kids = new ArrayList<>();
        for (Node k : LogicalTree.children(parent)) {
            if (k.isVisible() && !k.isMouseTransparent() && k.getParent() == parent && k.getLayoutBounds().getWidth() > 0) {
                kids.add(k);
            }
        }
        for (int i = 0; i < kids.size(); i++) {
            for (int j = i + 1; j < kids.size(); j++) {
                Bounds a = kids.get(i).getBoundsInParent();
                Bounds b = kids.get(j).getBoundsInParent();
                double ix = Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX());
                double iy = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY());
                if (ix > 1 && iy > 1) {
                    Node moved = kids.get(j).getTranslateX() != 0 || kids.get(j).getTranslateY() != 0 ? kids.get(j) : kids.get(i);
                    Node other = moved == kids.get(j) ? kids.get(i) : kids.get(j);
                    findings.add(finding("siblingOverlap", moved, "overlaps sibling " + NodePath.of(other) + " by "
                            + Math.round(ix) + "x" + Math.round(iy) + "px", "warn",
                            "check translate/margins/layoutX; inspect with fx_explain_layout"));
                }
            }
        }
    }

    private static void contrast(Node n, List<JsonNode> findings) {
        Paint fill = n instanceof Labeled l ? l.getTextFill() : ((Text) n).getFill();
        if (!(fill instanceof Color fg)) {
            return;
        }
        Color bg = null;
        for (Node c = n; c != null; c = c.getParent()) {
            if (c instanceof Region r) {
                Background b = r.getBackground();
                if (b != null && !b.getFills().isEmpty()) {
                    BackgroundFill top = b.getFills().get(b.getFills().size() - 1);
                    if (top.getFill() instanceof Color col && col.getOpacity() > 0.95) {
                        bg = col;
                        break;
                    }
                    if (!(top.getFill() instanceof Color)) {
                        findings.add(finding("lowContrast", n, "skipped:unresolvedBackground", "info",
                                "background is a gradient/image; verify contrast visually"));
                        return;
                    }
                }
            }
        }
        if (bg == null) {
            Paint sceneFill = n.getScene().getFill();
            bg = sceneFill instanceof Color sc ? sc : Color.WHITE;
        }
        double ratio = ratio(fg, bg);
        if (ratio < 3.0) {
            findings.add(finding("lowContrast", n, String.format(java.util.Locale.ROOT, "contrast %.2f:1 (%s on %s)", ratio,
                    NodeInfo.web(fg), NodeInfo.web(bg)), ratio < 1.5 ? "warn" : "info",
                    "WCAG AA needs 4.5:1 (3:1 for large text); adjust -fx-text-fill or the background"));
        }
    }

    static double ratio(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * lin(c.getRed()) + 0.7152 * lin(c.getGreen()) + 0.0722 * lin(c.getBlue());
    }

    private static double lin(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}

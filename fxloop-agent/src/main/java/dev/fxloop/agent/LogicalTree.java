package dev.fxloop.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Accordion;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Cell;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;

/**
 * The agent's view of the scene graph: layout containers expose their children, built-in controls are
 * leaves (skin internals are noise for an LLM), composite controls expose their logical content, and
 * virtualized controls expose only their visible, non-empty cells (PRD 01 §5).
 */
final class LogicalTree {
    private LogicalTree() {}

    static boolean isVirtualized(Node n) {
        return n instanceof ListView || n instanceof TableView || n instanceof TreeView || n instanceof TreeTableView;
    }

    static List<Node> children(Node node) {
        if (!(node instanceof Parent parent)) {
            return List.of();
        }
        if (node instanceof ScrollPane sp) {
            return sp.getContent() == null ? List.of() : List.of(sp.getContent());
        }
        if (node instanceof TabPane tp) {
            List<Node> out = new ArrayList<>();
            for (Tab t : tp.getTabs()) {
                if (t.getContent() != null) {
                    out.add(t.getContent());
                }
            }
            return out;
        }
        if (node instanceof SplitPane sp) {
            return List.copyOf(sp.getItems());
        }
        if (node instanceof TitledPane tp) {
            return tp.getContent() == null ? List.of() : List.of(tp.getContent());
        }
        if (node instanceof ToolBar tb) {
            return List.copyOf(tb.getItems());
        }
        if (node instanceof ButtonBar bb) {
            return List.copyOf(bb.getButtons());
        }
        if (node instanceof Accordion acc) {
            return List.copyOf(acc.getPanes());
        }
        if (isVirtualized(node)) {
            return visibleCells(parent);
        }
        if (node instanceof MenuBar) {
            return descendantsOfType(parent, javafx.scene.control.Labeled.class);
        }
        if (node instanceof Cell) {
            return node instanceof TableRow || node instanceof TreeTableRow ? rowCells(parent) : List.of();
        }
        if (node instanceof Control && node.getClass().getName().startsWith("javafx.scene.control.")) {
            return List.of();
        }
        return List.copyOf(parent.getChildrenUnmodifiable());
    }

    /** Visible, non-empty cells of a virtualized control, in index order. */
    static List<Node> visibleCells(Parent control) {
        List<Node> out = new ArrayList<>();
        Class<?> rowType = control instanceof TableView ? TableRow.class
                : control instanceof TreeTableView ? TreeTableRow.class : IndexedCell.class;
        for (Node n : control.lookupAll(".indexed-cell")) {
            if (rowType.isInstance(n) && n instanceof IndexedCell<?> c && c.isVisible() && !c.isEmpty()
                    && c.getIndex() >= 0 && owningVirtualized(c) == control) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparingInt(n -> ((IndexedCell<?>) n).getIndex()));
        return out;
    }

    private static Node owningVirtualized(Node n) {
        Node p = n.getParent();
        while (p != null && !isVirtualized(p)) {
            p = p.getParent();
        }
        return p;
    }

    private static List<Node> rowCells(Parent row) {
        List<Node> out = new ArrayList<>();
        for (Node n : row.getChildrenUnmodifiable()) {
            if (n instanceof Cell && n.isVisible()) {
                out.add(n);
            }
        }
        return out;
    }

    private static List<Node> descendantsOfType(Parent p, Class<?> type) {
        List<Node> out = new ArrayList<>();
        collect(p, type, out);
        return out;
    }

    private static void collect(Parent p, Class<?> type, List<Node> out) {
        for (Node n : p.getChildrenUnmodifiable()) {
            if (type.isInstance(n)) {
                out.add(n);
            } else if (n instanceof Parent child) {
                collect(child, type, out);
            }
        }
    }

    /** Logical parent, or {@code null} for a scene root or a node hidden inside a control skin. */
    static Node parent(Node node) {
        if (node.getScene() != null && node.getScene().getRoot() == node) {
            return null;
        }
        Node candidate = node.getParent();
        while (candidate != null) {
            if (children(candidate).contains(node)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    static boolean isLogical(Node node) {
        if (node.getScene() == null) {
            return false;
        }
        Node cur = node;
        while (cur != null) {
            if (cur.getScene().getRoot() == cur) {
                return true;
            }
            Node p = parent(cur);
            if (p == null) {
                return false;
            }
            cur = p;
        }
        return false;
    }

    /** Nearest ancestor-or-self that is addressable in the logical tree (maps skin nodes to their control). */
    static Node owner(Node node) {
        Node cur = node;
        while (cur != null && !isLogical(cur)) {
            cur = cur.getParent();
        }
        return cur;
    }
}

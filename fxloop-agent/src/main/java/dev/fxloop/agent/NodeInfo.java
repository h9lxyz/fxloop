package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Cell;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

/** Per-type text/value extraction (the small mapping table of PRD 01 §5). */
final class NodeInfo {
    private NodeInfo() {}

    static String text(Node n) {
        if (n instanceof TableRow<?> || n instanceof TreeTableRow<?>) {
            return rowText((javafx.scene.Parent) n);
        }
        if (n instanceof Labeled l) {
            return l.getText();
        }
        if (n instanceof TextInputControl t) {
            return t.getText();
        }
        if (n instanceof Text t) {
            return t.getText();
        }
        return null;
    }

    private static String rowText(javafx.scene.Parent row) {
        StringBuilder sb = new StringBuilder();
        for (Node c : row.getChildrenUnmodifiable()) {
            if (c instanceof Cell<?> cell && cell.isVisible()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(cell.getText() == null ? "" : cell.getText());
            }
        }
        return sb.toString();
    }

    /** Type-specific value, or {@code null}. */
    static Object value(Node n) {
        if (n instanceof Toggle t) {
            return t.isSelected();
        }
        if (n instanceof javafx.scene.control.CheckBox cb) {
            return cb.isIndeterminate() ? "indeterminate" : cb.isSelected();
        }
        if (n instanceof ComboBoxBase<?> cb) {
            return oneLine(cb.getValue());
        }
        if (n instanceof ChoiceBox<?> cb) {
            return oneLine(cb.getValue());
        }
        if (n instanceof Slider s) {
            return s.getValue();
        }
        if (n instanceof ScrollBar s) {
            return s.getValue();
        }
        if (n instanceof ProgressIndicator p) {
            return p.getProgress();
        }
        if (n instanceof Spinner<?> s) {
            return oneLine(s.getValue());
        }
        if (n instanceof TabPane tp) {
            Tab sel = tp.getSelectionModel().getSelectedItem();
            return sel == null ? null : sel.getText();
        }
        return null;
    }

    static Integer itemCount(Node n) {
        if (n instanceof ListView<?> l) {
            return l.getItems() == null ? 0 : l.getItems().size();
        }
        if (n instanceof TableView<?> t) {
            return t.getItems() == null ? 0 : t.getItems().size();
        }
        if (n instanceof TreeView<?> t) {
            return t.getExpandedItemCount();
        }
        if (n instanceof TreeTableView<?> t) {
            return t.getExpandedItemCount();
        }
        if (n instanceof ComboBoxBase<?> && n instanceof javafx.scene.control.ComboBox<?> cb) {
            return cb.getItems().size();
        }
        return null;
    }

    static Integer selectedIndex(Node n) {
        if (n instanceof ListView<?> l) {
            return l.getSelectionModel().getSelectedIndex();
        }
        if (n instanceof TableView<?> t) {
            return t.getSelectionModel().getSelectedIndex();
        }
        if (n instanceof TreeView<?> t) {
            return t.getSelectionModel().getSelectedIndex();
        }
        if (n instanceof IndexedCell<?> c) {
            return c.isSelected() ? c.getIndex() : null;
        }
        return null;
    }

    static ObjectNode screenBounds(Node n) {
        Bounds b = n.localToScreen(n.getBoundsInLocal());
        if (b == null) {
            return null;
        }
        ObjectNode o = Json.object();
        o.put("x", Math.round(b.getMinX()));
        o.put("y", Math.round(b.getMinY()));
        o.put("w", Math.round(b.getWidth()));
        o.put("h", Math.round(b.getHeight()));
        return o;
    }

    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Compact, JSON-friendly rendering of an arbitrary property value. */
    static Object oneLine(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Number || v instanceof String) {
            return v instanceof String s ? clip(s, 200) : v;
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof Color c) {
            return web(c);
        }
        if (v instanceof Node n) {
            String path = NodePath.of(n);
            return NodePath.typeName(n) + (path == null ? "" : " @ " + path);
        }
        if (v instanceof Collection<?> c) {
            return "[" + c.size() + " items]";
        }
        if (v instanceof Map<?, ?> m) {
            return "{" + m.size() + " entries}";
        }
        return clip(String.valueOf(v), 200);
    }

    static String web(Color c) {
        return String.format("#%02x%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255), Math.round(c.getOpacity() * 255));
    }

    static void putValue(ObjectNode o, String key, Object v) {
        Object x = oneLine(v);
        if (x == null) {
            o.putNull(key);
        } else if (x instanceof Boolean b) {
            o.put(key, b);
        } else if (x instanceof Integer i) {
            o.put(key, i);
        } else if (x instanceof Long l) {
            o.put(key, l);
        } else if (x instanceof Number num) {
            o.put(key, num.doubleValue());
        } else {
            o.put(key, x.toString());
        }
    }

    static ArrayNode strings(List<String> values) {
        ArrayNode a = Json.array();
        values.forEach(a::add);
        return a;
    }
}

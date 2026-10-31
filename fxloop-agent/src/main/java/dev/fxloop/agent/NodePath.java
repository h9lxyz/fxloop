package dev.fxloop.agent;

import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.Node;
import javafx.stage.Window;

/**
 * Stable structural addresses (PRD 02 §7). Grammar:
 * <pre>
 * path    := window ('/Scene')? (sep segment)*
 * window  := ('Stage' | 'Popup' | 'Window') '[' int ']'
 * sep     := '/' (child step) | '//' (descendant search)
 * segment := Type? ('#' fxId)? ('[' int ']')?      -- at least one of Type / #fxId
 * </pre>
 * The first segment after {@code Scene} matches the scene root. {@code Type[i]} is the i-th logical child of
 * that type; {@code #id} matches by id; {@code //seg} searches all logical descendants and must be unique
 * unless an index picks the i-th match.
 */
final class NodePath {
    private static final Pattern WINDOW = Pattern.compile("^(Stage|Popup|Window)\\[(\\d+)]");
    private static final Pattern SEGMENT = Pattern.compile("^([A-Za-z_$][\\w$]*)?(?:#([^\\[/]+))?(?:\\[(\\d+)])?$");

    private NodePath() {}

    static String typeName(Node n) {
        return typeName0(n);
    }

    static String typeName0(Object n) {
        Class<?> c = n.getClass();
        while (c.getSimpleName().isEmpty()) {
            c = c.getSuperclass();
        }
        return c.getSimpleName();
    }

    /** Path of {@code node}; skin-internal nodes are mapped to their owning logical node first. */
    static String of(Node node) {
        Node n = LogicalTree.owner(node);
        if (n == null || n.getScene() == null || n.getScene().getWindow() == null) {
            return null;
        }
        List<String> segs = new ArrayList<>();
        Node cur = n;
        while (cur != null) {
            Node parent = LogicalTree.parent(cur);
            segs.add(0, parent == null ? rootSegment(cur) : childSegment(parent, cur));
            cur = parent;
        }
        Window w = n.getScene().getWindow();
        return Windows.segment(w) + "/Scene/" + String.join("/", segs);
    }

    private static String rootSegment(Node n) {
        return n.getId() != null && !n.getId().isEmpty() ? typeName(n) + "#" + n.getId() : typeName(n);
    }

    private static String childSegment(Node parent, Node child) {
        String type = typeName(child);
        String id = child.getId();
        if (id != null && !id.isEmpty()) {
            return type + "#" + id;
        }
        int i = 0;
        for (Node sibling : LogicalTree.children(parent)) {
            if (sibling == child) {
                break;
            }
            if (typeName(sibling).equals(type)) {
                i++;
            }
        }
        return type + "[" + i + "]";
    }

    /** Segments for all {@code kids} of one parent in a single pass (O(n), used by bulk traversals). */
    static List<String> childSegments(List<Node> kids) {
        java.util.Map<String, Integer> counters = new java.util.HashMap<>();
        List<String> out = new ArrayList<>(kids.size());
        for (Node k : kids) {
            String type = typeName(k);
            int i = counters.merge(type, 1, Integer::sum) - 1;
            String id = k.getId();
            out.add(id != null && !id.isEmpty() ? type + "#" + id : type + "[" + i + "]");
        }
        return out;
    }

    static Node resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new FcpException(Fcp.Error.BAD_REQUEST, "empty path");
        }
        Matcher wm = WINDOW.matcher(path);
        if (!wm.find()) {
            throw new FcpException(Fcp.Error.BAD_REQUEST,
                    "path must start with Stage[i], Popup[i] or Window[i]: " + path);
        }
        Window window = wm.group(1).equals("Window") ? Windows.byIndex(Integer.parseInt(wm.group(2)))
                : Windows.bySegment(wm.group(1), Integer.parseInt(wm.group(2)));
        String rest = path.substring(wm.end());
        if (rest.startsWith("/Scene")) {
            rest = rest.substring("/Scene".length());
        }
        Node root = window.getScene().getRoot();
        List<Step> steps = parseSteps(rest, path);
        if (steps.isEmpty()) {
            return root;
        }
        List<Node> current = List.of();
        boolean first = true;
        for (Step step : steps) {
            List<Node> pool;
            if (first) {
                pool = step.descendant ? descendantsInclusive(root) : List.of(root);
                first = false;
            } else {
                if (current.size() != 1) {
                    break;
                }
                pool = step.descendant ? descendants(current.get(0)) : LogicalTree.children(current.get(0));
            }
            List<Node> matches = new ArrayList<>();
            for (Node n : pool) {
                if (step.matches(n)) {
                    matches.add(n);
                }
            }
            if (step.index != null) {
                matches = step.index < matches.size() ? List.of(matches.get(step.index)) : List.of();
            }
            if (matches.isEmpty()) {
                throw notFound(path, step);
            }
            if (matches.size() > 1) {
                throw ambiguous(path, matches);
            }
            current = matches;
        }
        return current.get(0);
    }

    private static FcpException notFound(String path, Step step) {
        return new FcpException(Fcp.Error.NODE_NOT_FOUND, "no node matches segment '" + step.raw + "' of " + path
                + " — the UI may have changed; re-run fx_find_nodes or fx_get_scene_graph");
    }

    private static FcpException ambiguous(String path, List<Node> matches) {
        ArrayNode candidates = Json.array();
        for (Node n : matches) {
            if (candidates.size() >= 10) {
                break;
            }
            candidates.add(of(n));
        }
        ObjectNode data = Json.object();
        data.set("candidates", candidates);
        return new FcpException(Fcp.Error.AMBIGUOUS_PATH,
                matches.size() + " nodes match " + path + "; add [i] or use a full path from candidates", data);
    }

    static List<Node> descendants(Node n) {
        List<Node> out = new ArrayList<>();
        for (Node c : LogicalTree.children(n)) {
            out.addAll(descendantsInclusive(c));
        }
        return out;
    }

    static List<Node> descendantsInclusive(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        out.addAll(descendants(n));
        return out;
    }

    private static List<Step> parseSteps(String rest, String full) {
        List<Step> steps = new ArrayList<>();
        int i = 0;
        while (i < rest.length()) {
            if (rest.charAt(i) != '/') {
                throw new FcpException(Fcp.Error.BAD_REQUEST, "malformed path near '" + rest.substring(i) + "' in " + full);
            }
            boolean desc = rest.startsWith("//", i);
            i += desc ? 2 : 1;
            int end = i;
            int depth = 0;
            while (end < rest.length() && (rest.charAt(end) != '/' || depth > 0)) {
                if (rest.charAt(end) == '[') depth++;
                if (rest.charAt(end) == ']') depth--;
                end++;
            }
            String raw = rest.substring(i, end);
            Matcher m = SEGMENT.matcher(raw);
            if (raw.isEmpty() || !m.matches() || m.group(1) == null && m.group(2) == null) {
                throw new FcpException(Fcp.Error.BAD_REQUEST, "malformed segment '" + raw + "' in " + full);
            }
            steps.add(new Step(raw, desc, m.group(1), m.group(2), m.group(3) == null ? null : Integer.valueOf(m.group(3))));
            i = end;
        }
        return steps;
    }

    private record Step(String raw, boolean descendant, String type, String id, Integer index) {
        boolean matches(Node n) {
            if (id != null && !id.equals(n.getId())) {
                return false;
            }
            if (type != null && !(descendant ? typeMatches(n.getClass(), type) : typeName(n).equals(type))) {
                return false;
            }
            return true;
        }
    }

    private static boolean typeMatches(Class<?> c, String type) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getSimpleName().equals(type)) {
                return true;
            }
        }
        return false;
    }
}

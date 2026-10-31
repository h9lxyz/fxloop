package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.Window;

/** Agent side of the reload tiers: CSS re-apply, FXML swap, class redefinition (PRD 02 §3–4). */
final class Reloads {
    private static final AtomicLong CSS_GENERATION = new AtomicLong();

    private Reloads() {}

    /**
     * Params {@code files:[relPath…]} (resource-root-relative, '/' separated). Every stylesheet URL on a Scene or
     * Parent that ends with one of them is replaced by a cache-busted variant so the StyleManager reparses it.
     */
    static ObjectNode reloadCss(ObjectNode p) {
        List<String> rel = new ArrayList<>();
        p.path("files").forEach(f -> rel.add(f.asText().replace('\\', '/')));
        long gen = CSS_GENERATION.incrementAndGet();
        return Fx.call(() -> {
            int replaced = 0;
            ArrayNode hits = Json.array();
            for (Window w : Windows.showing()) {
                Scene scene = w.getScene();
                replaced += bust(scene.getStylesheets(), rel, gen, hits);
                for (Node n : allNodes(scene.getRoot())) {
                    if (n instanceof Parent parent && !parent.getStylesheets().isEmpty()) {
                        replaced += bust(parent.getStylesheets(), rel, gen, hits);
                    }
                }
            }
            ObjectNode out = Json.object();
            out.put("replaced", replaced);
            out.set("stylesheets", hits);
            if (replaced == 0) {
                out.put("hint", "no live Scene/Parent references these stylesheets; the change applies when they are next added");
            }
            return out;
        });
    }

    private static int bust(ObservableList<String> sheets, List<String> rel, long gen, ArrayNode hits) {
        int n = 0;
        for (int i = 0; i < sheets.size(); i++) {
            String url = sheets.get(i);
            String bare = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            for (String r : rel) {
                if (bare.endsWith("/" + r) || bare.endsWith(r)) {
                    sheets.set(i, bare + "?fxloop=" + gen);
                    hits.add(bare);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    private static List<Node> allNodes(Parent root) {
        List<Node> out = new ArrayList<>();
        java.util.ArrayDeque<Node> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.poll();
            out.add(n);
            if (n instanceof Parent p) {
                q.addAll(p.getChildrenUnmodifiable());
            }
        }
        return out;
    }

    /** Params {@code files:[relPath…]} or {@code path} (one document). Re-runs FXMLLoader and swaps roots in place. */
    static ObjectNode reloadFxml(ObjectNode p) {
        List<String> rel = new ArrayList<>();
        p.path("files").forEach(f -> rel.add(f.asText().replace('\\', '/')));
        if (p.hasNonNull("file")) {
            rel.add(p.get("file").asText().replace('\\', '/'));
        }
        return Fx.call(() -> {
            FxmlRegistry registry = AgentRuntime.get().fxml();
            ArrayNode swapped = Json.array();
            ArrayNode unregistered = Json.array();
            for (String r : rel) {
                boolean any = false;
                for (FxmlRegistry.Entry e : registry.live()) {
                    if (!e.location().toString().endsWith(r)) {
                        continue;
                    }
                    Object old = e.root().get();
                    if (!(old instanceof Node oldNode) || oldNode.getScene() == null) {
                        continue;
                    }
                    any = true;
                    FXMLLoader loader = new FXMLLoader(fresh(e.location()));
                    Object fresh;
                    try {
                        fresh = loader.load();
                    } catch (Exception ex) {
                        throw new FcpException(Fcp.Error.BAD_REQUEST, "FXML " + r + " failed to load: " + rootCause(ex)
                                + " — fix the document (fx_lint_fxml) and reload again");
                    }
                    String where = NodePath.of(oldNode);
                    swap(oldNode, (Node) fresh);
                    registry.replace(e, fresh, loader.getController());
                    swapped.add(Json.object().put("file", r).put("at", where)
                            .put("controller", loader.getController() == null ? null : loader.getController().getClass().getName()));
                }
                if (!any) {
                    unregistered.add(r);
                }
            }
            ObjectNode out = Json.object();
            out.set("swapped", swapped);
            if (!unregistered.isEmpty()) {
                out.set("unregistered", unregistered);
                out.put("hint", "documents not in the load registry cannot be swapped live; load them via FXMLLoader "
                        + "(agent hook) or FxLoopDev.load(...), or restart");
            }
            return out;
        });
    }

    private static URL fresh(URL location) {
        return location; // file: URLs re-read from outputDir; the server copies the edited resource first
    }

    private static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.toString();
    }

    static void swap(Node old, Node fresh) {
        Scene scene = old.getScene();
        if (scene.getRoot() == old) {
            scene.setRoot((Parent) fresh);
            return;
        }
        Parent parent = old.getParent();
        Node logicalParent = LogicalTree.parent(old);
        if (logicalParent instanceof ScrollPane sp && sp.getContent() == old) {
            sp.setContent(fresh);
        } else if (logicalParent instanceof TabPane tp) {
            for (Tab t : tp.getTabs()) {
                if (t.getContent() == old) {
                    t.setContent(fresh);
                }
            }
        } else if (logicalParent instanceof TitledPane tp && tp.getContent() == old) {
            tp.setContent(fresh);
        } else if (logicalParent instanceof SplitPane sp) {
            sp.getItems().set(sp.getItems().indexOf(old), fresh);
        } else if (parent instanceof BorderPane bp) {
            if (bp.getCenter() == old) bp.setCenter(fresh);
            else if (bp.getTop() == old) bp.setTop(fresh);
            else if (bp.getBottom() == old) bp.setBottom(fresh);
            else if (bp.getLeft() == old) bp.setLeft(fresh);
            else if (bp.getRight() == old) bp.setRight(fresh);
        } else if (parent instanceof Pane pane) {
            pane.getChildren().set(pane.getChildren().indexOf(old), fresh);
        } else if (parent instanceof javafx.scene.Group g) {
            g.getChildren().set(g.getChildren().indexOf(old), fresh);
        } else {
            throw new FcpException(Fcp.Error.UNSUPPORTED_TARGET, "cannot swap a root inside "
                    + NodePath.typeName0(parent) + "; restart instead");
        }
    }

    /** Params {@code classes:[{name, bytesB64}]}. Classes not loaded yet are skipped (they load fresh from disk). */
    static ObjectNode redefine(ObjectNode p) {
        Instrumentation inst = AgentRuntime.get().instrumentation();
        if (inst == null || !inst.isRedefineClassesSupported()) {
            throw new FcpException(Fcp.Error.DISABLED, "class redefinition unavailable (agent loaded without "
                    + "Instrumentation, e.g. embedded mode); use restart");
        }
        Map<String, List<Class<?>>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            loaded.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
        }
        List<ClassDefinition> defs = new ArrayList<>();
        ArrayNode redefined = Json.array();
        ArrayNode notLoaded = Json.array();
        for (JsonNode c : p.path("classes")) {
            String name = c.path("name").asText();
            byte[] bytes = Base64.getDecoder().decode(c.path("bytesB64").asText());
            List<Class<?>> classes = loaded.get(name);
            if (classes == null) {
                notLoaded.add(name);
                continue;
            }
            for (Class<?> k : classes) {
                defs.add(new ClassDefinition(k, bytes));
            }
            redefined.add(name);
        }
        if (!defs.isEmpty()) {
            try {
                inst.redefineClasses(defs.toArray(ClassDefinition[]::new));
            } catch (UnsupportedOperationException | ClassNotFoundException | java.lang.instrument.UnmodifiableClassException | LinkageError e) {
                ObjectNode data = Json.object();
                data.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
                data.set("classes", redefined);
                throw new FcpException(Fcp.Error.REDEFINE_REJECTED, "JVM rejected redefinition — "
                        + classify(e) + "; a restart is required", data);
            }
        }
        ObjectNode out = Json.object();
        out.set("redefined", redefined);
        out.set("notLoaded", notLoaded);
        return out;
    }

    private static String classify(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("add a method") || m.contains("delete a method")) return "methods added or removed";
        if (m.contains("schema") || m.contains("field")) return "fields added, removed or changed";
        if (m.contains("hierarchy")) return "class hierarchy changed";
        if (m.contains("modifiers")) return "modifiers changed";
        return "structural change";
    }
}

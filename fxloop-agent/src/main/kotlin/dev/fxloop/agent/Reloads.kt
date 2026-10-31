package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.lang.instrument.ClassDefinition
import java.lang.instrument.UnmodifiableClassException
import java.net.URL
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javafx.collections.ObservableList
import javafx.fxml.FXMLLoader
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.control.TabPane
import javafx.scene.control.TitledPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Pane

/** Agent side of the reload tiers: CSS re-apply, FXML swap, class redefinition (PRD 02 §3–4). */
internal object Reloads {
    private val CSS_GENERATION = AtomicLong()

    /**
     * Params `files:[relPath…]` (resource-root-relative, '/' separated). Every stylesheet URL on a Scene or
     * Parent that ends with one of them is replaced by a cache-busted variant so the StyleManager reparses it.
     */
    fun reloadCss(p: ObjectNode): ObjectNode {
        dev.fxloop.agent.css.Sheets.invalidate()
        val rel = p.path("files").map { it.asText().replace('\\', '/') }
        val gen = CSS_GENERATION.incrementAndGet()
        return Fx.call {
            var replaced = 0
            val hits = Json.array()
            for (w in Windows.showing()) {
                val scene = w.scene
                replaced += bust(scene.stylesheets, rel, gen, hits)
                for (n in allNodes(scene.root)) {
                    if (n is Parent && n.stylesheets.isNotEmpty()) {
                        replaced += bust(n.stylesheets, rel, gen, hits)
                    }
                }
            }
            dev.fxloop.agent.css.Scratch.reassert()
            val out = Json.obj()
            out.put("replaced", replaced)
            out.setNode("stylesheets", hits)
            dev.fxloop.agent.css.Scratch.ruleCount().takeIf { it > 0 }?.let { out.put("scratchRules", it) }
            if (replaced == 0) {
                out.put("hint", "no live Scene/Parent references these stylesheets; the change applies when they are next added")
            }
            out
        }
    }

    private fun bust(sheets: ObservableList<String>, rel: List<String>, gen: Long, hits: ArrayNode): Int {
        var n = 0
        for (i in sheets.indices) {
            val url = sheets[i]
            val bare = if (url.contains("?")) url.substring(0, url.indexOf('?')) else url
            for (r in rel) {
                if (bare.endsWith("/$r") || bare.endsWith(r)) {
                    sheets[i] = "$bare?fxloop=$gen"
                    hits.add(bare)
                    n++
                    break
                }
            }
        }
        return n
    }

    private fun allNodes(root: Parent): List<Node> {
        val out = ArrayList<Node>()
        val q = ArrayDeque<Node>()
        q.add(root)
        while (!q.isEmpty()) {
            val n = q.poll()
            out.add(n)
            if (n is Parent) q.addAll(n.childrenUnmodifiable)
        }
        return out
    }

    /** Params `files:[relPath…]` or `path` (one document). Re-runs FXMLLoader and swaps roots in place. */
    fun reloadFxml(p: ObjectNode): ObjectNode {
        val rel = ArrayList<String>()
        p.path("files").forEach { rel.add(it.asText().replace('\\', '/')) }
        if (p.hasNonNull("file")) rel.add(p.get("file").asText().replace('\\', '/'))
        val carry = if (p.has("carryState")) p.get("carryState").asBoolean(true) else true
        return Fx.call {
            val registry = AgentRuntime.current().fxml
            val stateCarried = ViewState.empty()
            val swapped = Json.array()
            val unregistered = Json.array()
            for (r in rel) {
                var any = false
                for (e in registry.live()) {
                    if (!e.location.toString().endsWith(r)) continue
                    val old = e.root.get()
                    if (old !is Node || old.scene == null) continue
                    any = true
                    val loader = e.loader?.newLoader(freshUrl(e.location)) ?: FXMLLoader(freshUrl(e.location))
                    val fresh: Any = try {
                        loader.load<Any>()
                    } catch (ex: Exception) {
                        throw FcpException(
                            Fcp.Error.BAD_REQUEST,
                            "FXML $r failed to load: ${rootCause(ex)} — fix the document (fx_lint_fxml) and reload again",
                        )
                    }
                    val where = NodePath.of(old)
                    val snapshot = if (carry) runCatching { ViewState.capture(old) }.getOrNull() else null
                    swap(old, fresh as Node)
                    val controller = loader.getController<Any?>()
                    registry.replace(e, fresh, controller)
                    val carried = if (snapshot == null) ViewState.empty() else try {
                        ViewState.restore(snapshot, fresh)
                    } catch (ex: Exception) {
                        ViewState.empty()
                    }
                    ViewState.merge(stateCarried, carried)
                    val entry = Json.obj().put("file", r).put("at", where).put("controller", controller?.javaClass?.name)
                    entry.set<ObjectNode>("stateCarried", carried)
                    swapped.add(entry)
                }
                if (!any) unregistered.add(r)
            }
            val out = Json.obj()
            out.setNode("swapped", swapped)
            out.setNode("stateCarried", stateCarried)
            if (unregistered.size() > 0) {
                out.setNode("unregistered", unregistered)
                out.put(
                    "hint", "documents not in the load registry cannot be swapped live; load them via FXMLLoader " +
                        "(agent hook) or FxLoopDev.load(...), or restart",
                )
            }
            out
        }
    }

    private fun freshUrl(location: URL): URL = location // file: URLs re-read from outputDir; the server copies the edited resource first

    private fun rootCause(t: Throwable): String {
        var c = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c.toString()
    }

    fun swap(old: Node, fresh: Node) {
        val scene = old.scene
        if (scene.root === old) {
            scene.root = fresh as Parent
            return
        }
        val parent = old.parent
        val logicalParent = LogicalTree.parent(old)
        if (logicalParent is ScrollPane && logicalParent.content === old) {
            logicalParent.content = fresh
        } else if (logicalParent is TabPane) {
            for (t in logicalParent.tabs) {
                if (t.content === old) t.content = fresh
            }
        } else if (logicalParent is TitledPane && logicalParent.content === old) {
            logicalParent.content = fresh
        } else if (logicalParent is SplitPane) {
            logicalParent.items[logicalParent.items.indexOf(old)] = fresh
        } else if (parent is BorderPane) {
            when {
                parent.center === old -> parent.center = fresh
                parent.top === old -> parent.top = fresh
                parent.bottom === old -> parent.bottom = fresh
                parent.left === old -> parent.left = fresh
                parent.right === old -> parent.right = fresh
            }
        } else if (parent is Pane) {
            parent.children[parent.children.indexOf(old)] = fresh
        } else if (parent is Group) {
            parent.children[parent.children.indexOf(old)] = fresh
        } else {
            throw FcpException(
                Fcp.Error.UNSUPPORTED_TARGET,
                "cannot swap a root inside ${parent?.let { NodePath.typeName0(it) }}; restart instead",
            )
        }
    }

    /** Params `classes:[{name, bytesB64}]`. Classes not loaded yet are skipped (they load fresh from disk). */
    fun redefine(p: ObjectNode): ObjectNode {
        val inst = AgentRuntime.current().instrumentation
        if (inst == null || !inst.isRedefineClassesSupported) {
            throw FcpException(
                Fcp.Error.DISABLED,
                "class redefinition unavailable (agent loaded without Instrumentation, e.g. embedded mode); use restart",
            )
        }
        val loaded = HashMap<String, MutableList<Class<*>>>()
        for (c in inst.allLoadedClasses) {
            loaded.getOrPut(c.name) { ArrayList() }.add(c)
        }
        val defs = ArrayList<ClassDefinition>()
        val redefined = Json.array()
        val notLoaded = Json.array()
        for (c in p.path("classes")) {
            val name = c.path("name").asText()
            val bytes = Base64.getDecoder().decode(c.path("bytesB64").asText())
            val classes = loaded[name]
            if (classes == null) {
                notLoaded.add(name)
                continue
            }
            for (k in classes) defs.add(ClassDefinition(k, bytes))
            redefined.add(name)
        }
        if (defs.isNotEmpty()) {
            try {
                inst.redefineClasses(*defs.toTypedArray())
            } catch (e: Throwable) {
                if (e !is UnsupportedOperationException && e !is ClassNotFoundException &&
                    e !is UnmodifiableClassException && e !is LinkageError
                ) {
                    throw e
                }
                val data = Json.obj()
                data.put("reason", e.javaClass.simpleName + ": " + e.message)
                data.setNode("classes", redefined)
                throw FcpException(
                    Fcp.Error.REDEFINE_REJECTED,
                    "JVM rejected redefinition — ${classify(e)}; a restart is required", data,
                )
            }
        }
        val out = Json.obj()
        out.setNode("redefined", redefined)
        out.setNode("notLoaded", notLoaded)
        return out
    }

    private fun classify(e: Throwable): String {
        val m = e.message.toString()
        return when {
            m.contains("add a method") || m.contains("delete a method") -> "methods added or removed"
            m.contains("schema") || m.contains("field") -> "fields added, removed or changed"
            m.contains("hierarchy") -> "class hierarchy changed"
            m.contains("modifiers") -> "modifiers changed"
            else -> "structural change"
        }
    }
}

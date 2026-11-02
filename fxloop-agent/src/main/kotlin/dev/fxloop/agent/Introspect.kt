package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.css.CssInspect
import dev.fxloop.agent.css.SourceRoots
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.Paging
import java.util.IdentityHashMap
import javafx.beans.binding.Binding
import javafx.beans.property.Property
import javafx.beans.property.ReadOnlyProperty
import javafx.beans.value.ObservableValue
import javafx.css.CssMetaData
import javafx.css.Styleable
import javafx.css.StyleableProperty
import javafx.geometry.Bounds
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** "Why?" tools (PRD 03 §6): property bindings, style origins, layout constraints. FX thread via callers. */
internal object Introspect {
    fun explainProperty(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val name = p.path("name").asText()
        val a = Props.access(node, name)
        val out = Props.describe(a, node)
        val obs = a.observable
        if (obs is Property<*> && obs.isBound) {
            out.put(
                "note", "bound via bind(); the binding object itself is not reachable through public API, " +
                    "but its dependencies are listed when it is a Binding",
            )
            val target = boundTarget(obs)
            if (target is ObservableValue<*>) {
                out.setNode("dependencyTree", tree(target, 0, IdentityHashMap()))
            }
        } else if (obs is Binding<*>) {
            out.setNode("dependencyTree", tree(obs, 0, IdentityHashMap()))
        }
        if (obs is StyleableProperty<*>) {
            out.put("styleOrigin", obs.styleOrigin.toString())
        }
        out.put("bidirectional", "unknown-peer")
        out
    }

    /** Property implementations keep their binding in a private field named `observable`; read it if open. */
    private fun boundTarget(prop: Any): Any? {
        var c: Class<*>? = prop.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("observable")
                f.setAccessible(true)
                return f.get(prop)
            } catch (e: NoSuchFieldException) {
                // keep looking upwards
            } catch (e: RuntimeException) {
                return null // module encapsulation: honest gap
            } catch (e: IllegalAccessException) {
                return null
            }
            c = c.superclass
        }
        return null
    }

    private fun tree(obs: ObservableValue<*>, depth: Int, seen: MutableMap<Any, Boolean>): ObjectNode {
        val o = Json.obj()
        o.put("class", obs.javaClass.name)
        NodeInfo.putValue(o, "value", obs.value)
        if (obs is ReadOnlyProperty<*>) {
            o.put("property", obs.name)
            val bean = obs.bean
            if (bean is Node) {
                o.put("owner", NodePath.of(bean))
            } else if (bean != null) {
                o.put("owner", bean.javaClass.simpleName)
            }
        }
        if (seen.put(obs, true) != null) {
            o.put("cycle", true)
            return o
        }
        if (obs is Binding<*> && depth < 5) {
            val deps = o.putArray("dependencies")
            for (d in obs.dependencies) {
                if (d is ObservableValue<*>) {
                    deps.add(tree(d, depth + 1, seen))
                } else {
                    deps.add(Json.obj().put("class", d.javaClass.name))
                }
            }
        }
        return o
    }

    /** Rule-level cascade by default (PRD 08 §3.4); `rules:false` restores the 1.0 origin-only output exactly. */
    fun explainStyle(p: ObjectNode): ObjectNode {
        if (p.path("rules").asBoolean(true)) return CssInspect.explainStyle(p, ::legacyFields)
        return Fx.call { originOnly(p) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun originOnly(p: ObjectNode): ObjectNode {
        val node = Targets.resolve(p)
        val only = if (p.hasNonNull("property")) p.get("property").asText() else null
        val items = ArrayList<JsonNode>()
        for (meta in node.cssMetaData) {
            if (only != null && meta.property != only) continue
            val sp = (meta as CssMetaData<Styleable, *>).getStyleableProperty(node) ?: continue
            val origin = sp.styleOrigin
            if (only == null && origin == null) continue // untouched by CSS: omit to stay token-efficient
            val o = Json.obj()
            o.put("cssProperty", meta.property)
            NodeInfo.putValue(o, "value", sp.value)
            o.put("origin", if (origin == null) "NONE(default/code)" else origin.name)
            items.add(o)
        }
        val out = Paging.page(items, p.path("offset").asInt(0), minOf(200, p.path("limit").asInt(80)))
        legacyFields(node, out)
        out.put("note", "rule-level source mapping (exact selector/line) is out of scope; origin + candidates only")
        return out
    }

    /** Fields shared by both modes: target, classes, pseudo-classes, inline style, candidate stylesheets. */
    private fun legacyFields(node: Node, out: ObjectNode) {
        out.setNode("target", Targets.summary(node))
        out.put("styleClass", node.styleClass.joinToString(" "))
        out.put("pseudoClasses", node.pseudoClassStates.toString())
        if (node.style != null && node.style.isNotBlank()) {
            out.put("inlineStyle", node.style)
        }
        val sheets = out.putArray("candidateStylesheets")
        var cur: Parent? = if (node is Parent) node else node.parent
        while (cur != null) {
            for (s in cur.stylesheets) {
                sheets.add(Json.obj().put("url", s).put("on", NodePath.of(cur)))
            }
            cur = cur.parent
        }
        val scene = node.scene
        if (scene != null) {
            scene.stylesheets.forEach { s -> sheets.add(Json.obj().put("url", s).put("on", "Scene")) }
            if (scene.userAgentStylesheet != null) {
                sheets.add(Json.obj().put("url", scene.userAgentStylesheet).put("on", "Scene (user agent)"))
            }
        }
    }

    fun explainLayout(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val out = Json.obj()
        out.setNode("target", Targets.summary(node))
        out.setNode("layoutBounds", bounds(node.layoutBounds))
        out.setNode("boundsInParent", bounds(node.boundsInParent))
        val screen = node.localToScreen(node.boundsInLocal)
        if (screen != null) out.setNode("boundsInScreen", bounds(screen))
        out.put("managed", node.isManaged)
        out.put("resizable", node.isResizable)
        out.put("visible", node.isVisible)
        out.put("translateX", node.translateX).put("translateY", node.translateY)
        val sizes = out.putObject("sizes")
        sizes.put("min", "${node.minWidth(-1.0)}x${node.minHeight(-1.0)}")
        sizes.put("pref", "${node.prefWidth(-1.0)}x${node.prefHeight(-1.0)}")
        sizes.put("max", "${node.maxWidth(-1.0)}x${node.maxHeight(-1.0)}")
        if (node is Region) {
            out.put("padding", insets(node.padding))
            out.put("insets", insets(node.insets))
        }
        val parent = node.parent
        val c = out.putObject("constraints")
        if (parent != null) out.put("parentType", NodePath.typeName0(parent))
        when (parent) {
            is HBox -> {
                putStr(c, "hgrow", HBox.getHgrow(node))
                putStr(c, "margin", insets(HBox.getMargin(node)))
            }
            is VBox -> {
                putStr(c, "vgrow", VBox.getVgrow(node))
                putStr(c, "margin", insets(VBox.getMargin(node)))
            }
            is GridPane -> {
                putStr(c, "row", GridPane.getRowIndex(node))
                putStr(c, "column", GridPane.getColumnIndex(node))
                putStr(c, "hgrow", GridPane.getHgrow(node))
                putStr(c, "vgrow", GridPane.getVgrow(node))
                putStr(c, "halignment", GridPane.getHalignment(node))
                putStr(c, "margin", insets(GridPane.getMargin(node)))
            }
            is AnchorPane -> {
                putStr(c, "top", AnchorPane.getTopAnchor(node))
                putStr(c, "left", AnchorPane.getLeftAnchor(node))
                putStr(c, "bottom", AnchorPane.getBottomAnchor(node))
                putStr(c, "right", AnchorPane.getRightAnchor(node))
            }
            is BorderPane -> {
                putStr(c, "alignment", BorderPane.getAlignment(node))
                putStr(c, "margin", insets(BorderPane.getMargin(node)))
            }
            is StackPane -> {
                putStr(c, "alignment", StackPane.getAlignment(node))
                putStr(c, "margin", insets(StackPane.getMargin(node)))
            }
            else -> {}
        }
        val hints = out.putArray("hints")
        NodeInfo.notShownReason(node)?.let { hints.add("not rendered ($it): the bounds above are stale") }
        if (!node.isManaged) hints.add("unmanaged node: ignored by parent layout, positioned by layoutX/Y only")
        if (node.translateX != 0.0 || node.translateY != 0.0) {
            hints.add("translate offset applied after layout — node appears shifted relative to its slot")
        }
        if (node.layoutBounds.width <= 0 || node.layoutBounds.height <= 0) hints.add("zero-size layout bounds")
        if (node is Region && node.width < node.prefWidth(-1.0) - 0.5) {
            hints.add("width below preferred width — content may be truncated")
        } else if (node is Region) {
            contentWidth(node)?.let { content ->
                if (node.width < content - 0.5) {
                    hints.add("width below content width (${fmt(node.width)} < ${fmt(content)}): prefWidth=${fmt(node.prefWidth)} caps it — content may be truncated")
                }
            }
        }
        try {
            layoutExtras(node, out, hints, SourceRoots.from(p))
        } catch (e: RuntimeException) {
            // the additions are best effort; the classic keys above always stand
        }
        out
    }

    private val SIZE_KEYS = listOf("minWidth", "prefWidth", "maxWidth", "minHeight", "prefHeight", "maxHeight")
    private val KINDS = setOf("HBox", "VBox", "GridPane", "AnchorPane", "BorderPane", "StackPane", "FlowPane", "TilePane")

    private fun computedSize(node: Node, key: String): Double = when (key) {
        "minWidth" -> node.minWidth(-1.0)
        "prefWidth" -> node.prefWidth(-1.0)
        "maxWidth" -> node.maxWidth(-1.0)
        "minHeight" -> node.minHeight(-1.0)
        "prefHeight" -> node.prefHeight(-1.0)
        else -> node.maxHeight(-1.0)
    }

    private fun source(kind: String, hit: FxmlHit?, cssProperty: String? = null): ObjectNode {
        val o = Json.obj().put("kind", kind)
        if (kind == "fxml" && hit != null) o.put("file", hit.doc.file).put("line", hit.el.line)
        if (cssProperty != null) o.put("cssProperty", cssProperty)
        return o
    }

    private fun cssName(key: String) = "-fx-" + key.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }

    /** PRD 09 §4.1: parent kind, margin/border, transforms, sized-with-sources, editable keys, FXML location, hint codes. */
    private fun layoutExtras(node: Node, out: ObjectNode, hints: com.fasterxml.jackson.databind.node.ArrayNode, roots: SourceRoots) {
        val parent = node.parent
        val kind = parent?.javaClass?.let { c -> generateSequence<Class<*>>(c) { it.superclass }.map { it.simpleName }.firstOrNull { it in KINDS } } ?: "other"
        out.put("parentKind", kind)
        val hit = try {
            FxmlLocator(roots).locate(node)
        } catch (e: FxmlMismatch) {
            null
        }
        if (hit != null) {
            val f = out.putObject("fxml")
            f.put("file", hit.doc.file)
            hit.doc.abs?.let { f.put("absFile", it.toString()) }
            f.put("line", hit.el.line).put("element", hit.el.name)
            hit.fxId?.let { f.put("fxId", it) }
            f.put("document", hit.doc.location.toString())
        }
        val constraints = Props.CONSTRAINTS[kind].orEmpty()
        constraints.firstOrNull { it.prop == "margin" }?.text(node)?.let { out.put("margin", it) }
        if (node is Region) {
            val b = node.border?.insets
            if (b != null && (b.top != 0.0 || b.right != 0.0 || b.bottom != 0.0 || b.left != 0.0)) out.put("border", insets(b))
        }
        val lb = node.layoutBounds
        val bp = node.boundsInParent
        val moved = node.translateX != 0.0 || node.translateY != 0.0 || node.scaleX != 1.0 || node.scaleY != 1.0 ||
            node.rotate != 0.0 || node.transforms.isNotEmpty()
        out.put("boundsDiffer", moved || Math.abs(lb.width - bp.width) > 0.01 || Math.abs(lb.height - bp.height) > 0.01)
        out.putObject("transforms")
            .put("translateX", node.translateX).put("translateY", node.translateY)
            .put("scaleX", node.scaleX).put("scaleY", node.scaleY).put("rotate", node.rotate)

        val sizes = out.putObject("sizes2")
        val sizeSource = HashMap<String, ObjectNode>()
        for (k in SIZE_KEYS) {
            val o = sizes.putObject(k)
            if (node is Region) {
                val prop = Props.access(node, k)
                val v = (prop.value() as? Number)?.toDouble() ?: -1.0
                o.put("value", v)
                o.put("computed", computedSize(node, k))
                val origin = (prop.observable as? StyleableProperty<*>)?.styleOrigin
                val src = when {
                    hit?.el?.attr(k) != null -> source("fxml", hit)
                    origin != null && origin != javafx.css.StyleOrigin.USER -> source("css", null, cssName(k))
                    v == -1.0 -> source("computed", null)
                    else -> source("code", null)
                }
                o.set<ObjectNode>("source", src)
                sizeSource[k] = src
            } else {
                o.put("computed", computedSize(node, k))
            }
        }

        val editable = out.putArray("editable")
        for (k in constraints) {
            val o = editable.addObject()
            o.put("key", k.key)
            o.put("value", k.text(node))
            o.put("kind", k.kind)
            if (k.kind == "enum") o.set<com.fasterxml.jackson.databind.JsonNode>("choices", Json.strings(k.choices))
            val el = hit?.el
            val inFxml = el != null && (el.attr(k.key) != null || el.children.any { it.name == k.key })
            o.set<ObjectNode>("source", source(if (inFxml) "fxml" else if (k.text(node) != null) "code" else "computed", if (inFxml) hit else null))
        }
        if (node is Region) {
            for (k in SIZE_KEYS) {
                val o = editable.addObject()
                val size = sizes.path(k)
                o.put("key", k).put("value", size.path("value").asText()).put("kind", "number")
                o.set<ObjectNode>("source", sizeSource[k])
            }
        }

        val codes = out.putArray("hintCodes")
        fun code(c: String, text: String, cause: String) {
            codes.addObject().put("code", c).put("text", text).put("cause", cause).put("section", kind)
        }
        for (h in hints) {
            val t = h.asText()
            when {
                t.startsWith("not rendered") -> code("not-rendered", t, "The node or an ancestor is invisible, unmanaged or not in a shown window, so no layout pass ran for it.")
                t.startsWith("unmanaged") -> code("unmanaged", t, "managed=false: set by a binding, a setManaged call or a visibility toggle that keeps the node's slot empty.")
                t.startsWith("translate") -> code("translated", t, "translateX/Y moves the node after layout; usually an animation or a leftover offset.")
                t.startsWith("zero-size") -> code("zero-size", t, "Empty content, a maxWidth/maxHeight of 0, or a parent that gave the node no room.")
                t.startsWith("width below content") -> code("below-pref", t, "An explicit prefWidth (FXML, code or -fx-pref-width) is smaller than the content needs; reset it to USE_COMPUTED_SIZE (-1).")
                t.startsWith("width below") -> code("below-pref", t, "The parent offers less than the preferred width: a fixed-size parent, a missing hgrow or a maxWidth cap.")
            }
        }
    }

    /**
     * The width the content asks for when an explicit prefWidth is set: measured with prefWidth reset for a moment and
     * restored with its style origin, so a CSS-set value stays a CSS value. Null without an explicit, unbound prefWidth.
     */
    @Suppress("UNCHECKED_CAST")
    private fun contentWidth(r: Region): Double? {
        val prop = r.prefWidthProperty()
        val set = r.prefWidth
        if (set < 0 || prop.isBound) return null
        val origin = (prop as javafx.css.StyleableProperty<Number>).styleOrigin
        r.prefWidth = Region.USE_COMPUTED_SIZE
        return try {
            r.prefWidth(-1.0)
        } finally {
            if (origin != null && origin != javafx.css.StyleOrigin.USER) prop.applyStyle(origin, set) else r.prefWidth = set
        }
    }

    private fun fmt(d: Double): String = if (d == Math.rint(d)) d.toLong().toString() else "%.1f".format(d)

    private fun putStr(o: ObjectNode, k: String, v: Any?) {
        if (v != null) o.put(k, v.toString())
    }

    private fun insets(i: Insets?): String? = if (i == null) null else "${i.top} ${i.right} ${i.bottom} ${i.left}"

    fun bounds(b: Bounds): ObjectNode {
        val o = Json.obj()
        o.put("x", round(b.minX)).put("y", round(b.minY)).put("w", round(b.width)).put("h", round(b.height))
        return o
    }

    private fun round(v: Double): Double = Math.round(v * 10) / 10.0
}

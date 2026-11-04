package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.stage.Popup
import javafx.stage.Window

/**
 * Node highlight for the CSS inspector (PRD 08 §4): a [NodeFrame] plus a label, drawn on request from the browser.
 * This is the transient preview: the held selection, `hold` and `release` here, is [PickMode]'s outline, as is pick
 * mode itself (PRD 07 §8). The popups are [Overlays] so no tool sees them. FX thread only.
 */
internal object Overlay {
    const val OVERLAY_KEY = Overlays.KEY
    private const val COLOR = "#3b82f6"

    private val preview by lazy { NodeFrame(COLOR, ::describe) }
    private const val MAX_HANDLES = 50
    private val multi = LinkedHashMap<Window, Popup>()

    internal fun isShown(): Boolean = preview.isShown() || multi.values.any { it.isShowing }

    /** FCP `highlight`. */
    fun highlight(p: ObjectNode): ObjectNode = Fx.call {
        if (p.path("clear").asBoolean(false)) {
            clearHighlight()
            return@call Json.obj().put("shown", false)
        }
        val pick = AgentRuntime.current().pick
        if (p.path("release").asBoolean(false)) {
            pick.release()
            return@call Json.obj().put("shown", false)
        }
        if (p.path("hold").asBoolean(false)) {
            preview.hide()
            return@call pick.hold(Targets.resolve(p))
        }
        val handles = p.path("handles")
        if (handles.isArray) return@call showMany(handles, p.path("labels").asBoolean(false))
        show(Targets.resolve(p))
    }

    /** One popup per window draws every outline; stale or unrendered handles count as not shown. */
    private fun showMany(handles: com.fasterxml.jackson.databind.JsonNode, labels: Boolean): ObjectNode {
        clearHighlight()
        val out = Json.obj()
        val items = ArrayList<Pair<Int, Node>>()
        var notShown = 0
        var index = 0
        for (h in handles) {
            if (index >= MAX_HANDLES) {
                out.put("truncated", true)
                break
            }
            index++
            val n = AgentRuntime.current().handles.resolve(h.asInt())
            if (n == null || n.scene?.window?.isShowing != true || NodeInfo.notShownReason(n) != null || n.localToScreen(n.boundsInLocal) == null) {
                notShown++
            } else {
                items.add(index to n)
            }
        }
        val byWindow = LinkedHashMap<Window, MutableList<Pair<Int, Node>>>()
        for (item in items) byWindow.getOrPut(item.second.scene.window) { ArrayList() }.add(item)
        for ((win, list) in byWindow) {
            val popup = newPopup(Pane().apply { isMouseTransparent = true })
            val pane = popup.content[0] as Pane
            pane.setPrefSize(Math.max(win.width, 1.0), Math.max(win.height, 1.0))
            for ((i, n) in list) {
                val b = n.localToScreen(n.boundsInLocal)
                val r = Region().apply {
                    isMouseTransparent = true
                    setPrefSize(Math.max(b.width, 1.0), Math.max(b.height, 1.0))
                    setMinSize(prefWidth, prefHeight)
                    setMaxSize(prefWidth, prefHeight)
                    style = "-fx-border-color: $COLOR; -fx-border-width: 2; -fx-background-color: rgba(59,130,246,0.2);"
                    layoutX = b.minX - win.x
                    layoutY = b.minY - win.y
                }
                pane.children.add(r)
                if (labels) {
                    pane.children.add(Label(i.toString()).apply {
                        isMouseTransparent = true
                        style = "-fx-background-color: $COLOR; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 0 4 0 4;"
                        layoutX = b.minX - win.x
                        layoutY = b.minY - win.y
                    })
                }
            }
            popup.show(win, win.x, win.y)
            multi[win] = popup
        }
        return out.put("shown", items.size).put("notShown", notShown)
    }

    /** The nearest ancestor-or-self whose path resolves back to itself; skin internals fall back to the node itself. */
    internal fun resolvable(start: Node): Node {
        var c: Node? = start
        while (c != null) {
            if (selfResolves(c)) return c
            c = c.parent
        }
        return start
    }

    private fun selfResolves(n: Node): Boolean = try {
        val p = NodePath.of(n)
        p != null && NodePath.resolve(p) === n
    } catch (e: RuntimeException) {
        false
    }

    private fun show(n: Node): ObjectNode {
        multi.values.forEach { it.hide() }
        multi.clear()
        // an unrendered node's bounds are stale and usually overlap the visible page: drawing them misleads
        NodeInfo.notShownReason(n)?.let {
            clearHighlight()
            return Json.obj().put("shown", false).put("reason", "not rendered: $it")
        }
        val b = n.localToScreen(n.boundsInLocal)
        val owner = n.scene?.window
        if (b == null || owner == null || !owner.isShowing) {
            clearHighlight()
            return Json.obj().put("shown", false)
        }
        // the held outline already marks this node; a second frame on it would only flash over it
        if (AgentRuntime.current().pick.isHeld(n)) preview.hide() else preview.show(owner, n)
        return Json.obj().put("shown", true).setNode("bounds", Json.obj()
            .put("x", Math.round(b.minX)).put("y", Math.round(b.minY)).put("w", Math.round(b.width)).put("h", Math.round(b.height)))
    }

    private fun describe(n: Node): String {
        val type = NodePath.typeName(n)
        val defaultClass = type.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
        val sb = StringBuilder(type)
        if (!n.id.isNullOrEmpty()) sb.append('#').append(n.id)
        for (c in n.styleClass) if (c != defaultClass) sb.append('.').append(c)
        val b = n.boundsInLocal
        return sb.append("  ").append(Math.round(b.width)).append('×').append(Math.round(b.height)).toString()
    }

    private fun clearHighlight() {
        preview.hide()
        multi.values.forEach { it.hide() }
        multi.clear()
    }

    private fun newPopup(content: Node): Popup = Popup().apply {
        isAutoFix = false
        isAutoHide = false
        consumeAutoHidingEvents = false
        Overlays.mark(this)
        this.content.add(content)
    }
}

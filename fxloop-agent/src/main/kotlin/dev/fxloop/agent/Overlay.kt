package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.stage.Popup
import javafx.stage.Window

/**
 * Node highlight for the CSS inspector (PRD 08 §4): an outline popup plus a label, drawn on request from the browser.
 * Pick mode is [PickMode]'s (PRD 07 §8). The popups are [Overlays] so no tool sees them. FX thread only.
 */
internal object Overlay {
    const val OVERLAY_KEY = Overlays.KEY
    private const val COLOR = "#3b82f6"

    private var box: Popup? = null
    private var tag: Popup? = null

    internal fun isShown(): Boolean = box?.isShowing == true

    /** FCP `highlight`. */
    fun highlight(p: ObjectNode): ObjectNode = Fx.call {
        if (p.path("clear").asBoolean(false)) {
            clearHighlight()
            return@call Json.obj().put("shown", false)
        }
        show(Targets.resolve(p))
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

    private fun place(popup: Popup, owner: Window, x: Double, y: Double) {
        if (popup.isShowing && popup.ownerWindow !== owner) popup.hide()
        if (popup.isShowing) {
            popup.anchorX = x
            popup.anchorY = y
        } else {
            popup.show(owner, x, y)
        }
    }

    private fun show(n: Node): ObjectNode {
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
        val bx = box ?: newPopup(Region()).also { box = it }
        val tg = tag ?: newPopup(Label()).also { tag = it }
        val region = bx.content[0] as Region
        val w = Math.max(b.width, 1.0)
        val h = Math.max(b.height, 1.0)
        region.setPrefSize(w, h)
        region.setMinSize(w, h)
        region.setMaxSize(w, h)
        region.isMouseTransparent = true
        region.style = "-fx-border-color: $COLOR; -fx-border-width: 2; -fx-background-color: rgba(59,130,246,0.25);"
        place(bx, owner, b.minX, b.minY)
        val label = tg.content[0] as Label
        label.text = describe(n, b.width, b.height)
        label.isMouseTransparent = true
        label.style = "-fx-background-color: $COLOR; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 1 5 1 5;"
        place(tg, owner, b.minX, if (b.minY >= 20.0) b.minY - 19.0 else b.minY + 2.0)
        return Json.obj().put("shown", true).setNode("bounds", Json.obj()
            .put("x", Math.round(b.minX)).put("y", Math.round(b.minY)).put("w", Math.round(b.width)).put("h", Math.round(b.height)))
    }

    private fun describe(n: Node, w: Double, h: Double): String {
        val type = NodePath.typeName(n)
        val defaultClass = type.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
        val sb = StringBuilder(type)
        if (!n.id.isNullOrEmpty()) sb.append('#').append(n.id)
        for (c in n.styleClass) if (c != defaultClass) sb.append('.').append(c)
        return sb.append("  ").append(Math.round(w)).append('×').append(Math.round(h)).toString()
    }

    private fun clearHighlight() {
        box?.hide()
        tag?.hide()
    }

    private fun newPopup(content: Node): Popup = Popup().apply {
        isAutoFix = false
        isAutoHide = false
        consumeAutoHidingEvents = false
        Overlays.mark(this)
        this.content.add(content)
    }
}

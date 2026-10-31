package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration

/**
 * Highlight overlay and human pick mode (PRD 07 §8, PRD 08 §4). Agent-owned popups carry [OVERLAY_KEY] so
 * [Windows.showing] never lists them. FX thread only.
 */
internal object Overlay {
    const val OVERLAY_KEY = "fxloop.overlay"
    private const val COLOR = "#3b82f6"

    private var box: Popup? = null
    private var tag: Popup? = null
    private var pick: Pick? = null
    private var finishing: Pick? = null

    /** Test hook: receives every `humanPick` payload in addition to the FCP event. */
    @Volatile
    internal var pickListener: ((ObjectNode) -> Unit)? = null

    private class Pick(val windows: List<Window>) {
        val scenes = windows.mapNotNull { it.scene }
        val mouse = EventHandler<MouseEvent> { onMouse(this, it, null) }
        val key = EventHandler<KeyEvent> { e ->
            if (e.code == KeyCode.ESCAPE) {
                e.consume()
                disarm()
            }
        }
        var done = false
    }

    internal fun isShown(): Boolean = box?.isShowing == true

    internal fun isArmed(): Boolean = pick != null

    /** FCP `highlight`. */
    fun highlight(p: ObjectNode): ObjectNode = Fx.call {
        if (p.path("clear").asBoolean(false)) {
            clearHighlight()
            return@call Json.obj().put("shown", false)
        }
        show(Targets.resolve(p))
    }

    /** FCP `pickStart`. */
    fun pickStart(p: ObjectNode): ObjectNode = Fx.call {
        if (p.path("cancel").asBoolean(false)) {
            disarm()
        } else {
            val idx = p.intOrNull("windowIndex")
            arm(if (idx != null) listOf(Windows.byIndex(idx)) else Windows.showing().filter { it is Stage })
        }
        Json.obj().put("armed", pick != null)
    }

    /** Arms pick mode on the given windows, replacing any earlier arming. */
    fun arm(windows: List<Window>) {
        disarm()
        finishing?.let { release(it) }
        val pk = Pick(windows)
        for (s in pk.scenes) {
            s.addEventFilter(MouseEvent.ANY, pk.mouse)
            s.addEventFilter(KeyEvent.KEY_PRESSED, pk.key)
        }
        pick = pk
    }

    fun disarm() {
        pick?.let { release(it) }
        pick = null
        clearHighlight()
    }

    private fun release(pk: Pick) {
        for (s in pk.scenes) {
            s.removeEventFilter(MouseEvent.ANY, pk.mouse)
            s.removeEventFilter(KeyEvent.KEY_PRESSED, pk.key)
        }
        if (finishing === pk) finishing = null
    }

    private fun onMouse(pk: Pick, e: MouseEvent, forwarded: Node?) {
        val t = e.eventType
        val primaryClick = e.button == MouseButton.PRIMARY &&
            (t == MouseEvent.MOUSE_PRESSED || t == MouseEvent.MOUSE_RELEASED || t == MouseEvent.MOUSE_CLICKED)
        if (pk.done) { // swallow the rest of the click the pick consumed so the app never sees it
            if (primaryClick) {
                e.consume()
                if (t == MouseEvent.MOUSE_CLICKED) Platform.runLater { release(pk) }
            }
            return
        }
        if (pick !== pk) return
        val node = forwarded ?: resolvable(e.pickResult?.intersectedNode ?: (e.target as? Node) ?: return)
        if (forwarded != null) e.consume()
        if (t == MouseEvent.MOUSE_MOVED) {
            show(node)
        } else if (primaryClick) {
            e.consume()
            if (t == MouseEvent.MOUSE_PRESSED) complete(pk, node)
        }
    }

    private fun complete(pk: Pick, n: Node) {
        pk.done = true
        pick = null
        finishing = pk
        for (s in pk.scenes) s.removeEventFilter(KeyEvent.KEY_PRESSED, pk.key)
        clearHighlight()
        PauseTransition(Duration.seconds(2.0)).apply { setOnFinished { release(pk) } }.play()
        val o = Json.obj()
        o.put("path", NodePath.of(n))
        o.put("h", AgentRuntime.current().handles.handleOf(n))
        o.put("window", n.scene?.window?.let { Windows.indexOf(it) } ?: -1)
        pickListener?.invoke(o)
        AgentRuntime.get()?.emit(Fcp.Event.HUMAN_PICK, o)
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

    /** Deepest visible node under a screen point; used when the highlight popup itself receives the mouse. */
    private fun deepestAt(n: Node, sx: Double, sy: Double): Node? {
        if (!n.isVisible || n.isMouseTransparent) return null
        val b = n.localToScreen(n.boundsInLocal) ?: return null
        if (!b.contains(sx, sy)) return null
        if (n is Parent) {
            val kids = n.childrenUnmodifiable
            for (i in kids.indices.reversed()) deepestAt(kids[i], sx, sy)?.let { return it }
        }
        return n
    }

    private fun forward(e: MouseEvent) {
        val pk = pick ?: return
        for (s in pk.scenes) {
            val hit = s.root?.let { deepestAt(it, e.screenX, e.screenY) } ?: continue
            onMouse(pk, e, resolvable(hit))
            return
        }
    }

    private fun newPopup(content: Node): Popup = Popup().apply {
        isAutoFix = false
        isAutoHide = false
        consumeAutoHidingEvents = false
        properties[OVERLAY_KEY] = true
        this.content.add(content)
        content.addEventFilter(MouseEvent.ANY, ::forward)
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
        val b = n.localToScreen(n.boundsInLocal)
        val owner = n.scene?.window
        if (b == null || owner == null || !owner.isShowing) {
            clearHighlight()
            return Json.obj().put("shown", false)
        }
        val bx = box ?: newPopup(Region()).also { box = it }
        val tg = tag ?: newPopup(Label()).also { tag = it }
        val transparent = pick == null
        val region = bx.content[0] as Region
        val w = Math.max(b.width, 1.0)
        val h = Math.max(b.height, 1.0)
        region.setPrefSize(w, h)
        region.setMinSize(w, h)
        region.setMaxSize(w, h)
        region.isMouseTransparent = transparent
        region.style = "-fx-border-color: $COLOR; -fx-border-width: 2; -fx-background-color: rgba(59,130,246,0.25);"
        place(bx, owner, b.minX, b.minY)
        val label = tg.content[0] as Label
        label.text = describe(n, b.width, b.height)
        label.isMouseTransparent = transparent
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
}

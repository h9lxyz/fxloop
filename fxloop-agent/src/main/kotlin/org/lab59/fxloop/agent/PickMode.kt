package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.lang.ref.WeakReference
import javafx.animation.PauseTransition
import javafx.event.EventHandler
import javafx.geometry.Bounds
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.stage.Window
import javafx.util.Duration

/**
 * E6 (PRD 07 §8): pick mode. While active, hover outlines the node under the pointer, a click is consumed and
 * selects the node (`humanPick` event), Escape leaves. The pick resolves `{"picked": true}` targets.
 *
 * The selection stays outlined afterwards, in whichever window has focus: a pick holds its node, and so does a click
 * on a CSS inspector row ([hold]). It ends when another node is held or the developer presses in an app window.
 */
class PickMode(private val rt: AgentRuntime) {
    @Volatile
    var active: Boolean = false
        private set

    /** Time source; tests move it to age a pick. */
    internal var clock: () -> Long = System::currentTimeMillis

    private var scene: Scene? = null
    private var selectedThisGesture = false
    private var pickedRef: WeakReference<Node>? = null
    private var pickedAt = 0L
    private var heldRef: WeakReference<Node>? = null
    private val outline by lazy { NodeFrame("rgba(255,64,129,0.95)", ::label, ::humanPress) }
    private val swallowTimeout by lazy { PauseTransition(Duration.millis(600.0)).apply { setOnFinished { detach() } } }

    private val onMove = EventHandler<MouseEvent> { e -> hover(e) }
    private val onMouse = EventHandler<MouseEvent> { e ->
        if (active && !selectedThisGesture &&
            (e.eventType == MouseEvent.MOUSE_PRESSED || e.eventType == MouseEvent.MOUSE_CLICKED)
        ) {
            select(e)
        }
        e.consume()
        if (e.eventType == MouseEvent.MOUSE_CLICKED && !active) detach()
    }
    private val onKey = EventHandler<KeyEvent> { e ->
        if (active && e.code == KeyCode.ESCAPE) {
            e.consume()
            leave()
        }
    }

    /** Toggles pick mode on [window] (default: the focused app window). FX thread. */
    fun toggle(window: Window?) {
        if (active) {
            leave()
            return
        }
        val w = window ?: Windows.byIndex(null)
        val s = w.scene ?: throw FcpException(Fcp.Error.WINDOW_NOT_FOUND, "window has no scene to pick from")
        detach()
        scene = s
        selectedThisGesture = false
        for (t in MOUSE) s.addEventFilter(t, onMouse)
        s.addEventFilter(MouseEvent.MOUSE_MOVED, onMove)
        s.addEventFilter(MouseEvent.MOUSE_DRAGGED, onMove)
        s.addEventFilter(KeyEvent.KEY_PRESSED, onKey)
        active = true
    }

    /** The picked node, or throws `PICK_STALE` (older than 10 min, node left the scene) / `NODE_NOT_FOUND` (none). FX thread. */
    fun picked(): Node {
        val ref = pickedRef ?: throw FcpException(
            Fcp.Error.NODE_NOT_FOUND,
            "nothing is picked yet; ask the developer to click the bar's Pick button (or press Ctrl+Shift+F12) and click a node",
        )
        val node = ref.get()
        if (clock() - pickedAt > MAX_AGE_MS || node == null || node.scene == null) {
            throw FcpException(
                Fcp.Error.PICK_STALE,
                "the picked node is gone or older than 10 minutes; pick again with the bar's Pick button or Ctrl+Shift+F12",
            )
        }
        return node
    }

    /** FCP `getPicked`. */
    internal fun get(p: ObjectNode): ObjectNode {
        val out = Fx.call {
            val node = picked()
            val o = Json.obj()
            o.setNode("target", Targets.summary(node))
            val d = o.putObject("details")
            val classes = d.putArray("styleClass")
            node.styleClass.forEach { classes.add(it) }
            val pseudo = d.putArray("pseudoClasses")
            node.pseudoClassStates.forEach { pseudo.add(it.pseudoClassName) }
            val sb = node.localToScreen(node.boundsInLocal)
            if (sb != null) d.setNode("boundsScreen", box(sb))
            d.setNode("layoutBounds", box(node.layoutBounds))
            o.put("explain", explain(node, sb))
            o.put("ageMs", clock() - pickedAt)
            o.put("handle", rt.handles.handleOf(node))
            o
        }
        val handle = out.remove("handle").asInt()
        val req = Json.obj().put("handle", handle).put("target", "node").put("settleMs", 0)
        if (p.hasNonNull("maxWidth")) req.put("maxWidth", p.get("maxWidth").asInt())
        val shot = Snapshots.screenshot(req)
        for (k in listOf("windowOrigin", "outputScale", "w", "h", "pngB64")) out.set<JsonNode>(k, shot.get(k))
        return out
    }

    /** FCP `setPickMode {on, windowIndex?}`. */
    internal fun set(p: ObjectNode): ObjectNode = Fx.call {
        val on = p.path("on").asBoolean(true)
        if (on != active) toggle(if (on) Windows.byIndex(p.intOrNull("windowIndex")) else null)
        Json.obj().put("active", active)
    }

    /** Keeps the outline on [n] until the next selection; an unrendered node ends the current one. FCP `highlight {hold}`. */
    internal fun hold(n: Node): ObjectNode {
        release()
        NodeInfo.notShownReason(n)?.let { return Json.obj().put("shown", false).put("reason", "not rendered: $it") }
        if (n.scene?.window?.isShowing != true || n.localToScreen(n.boundsInLocal) == null) return Json.obj().put("shown", false)
        heldRef = WeakReference(n)
        if (!active) restore()
        return Json.obj().put("shown", true)
    }

    /** Ends the held selection. FCP `highlight {release}`. */
    internal fun release() {
        heldRef = null
        if (!active) outline.hide()
    }

    /** The developer pressed in an app window. A press that belongs to a pick gesture is not a press on the app. */
    internal fun humanPress() {
        if (scene == null) release()
    }

    /** True while the outline is held on [n]. */
    internal fun isHeld(n: Node): Boolean = heldRef?.get() === n

    /** Puts the outline back on the held node, if any. */
    private fun restore() {
        val n = heldRef?.get()
        val w = n?.scene?.window
        if (n == null || w == null) outline.hide() else outline.show(w, n)
    }

    /** Screen rects of the pick outline popups, empty while no outline shows (tests). */
    internal fun outlineRects(): List<Rectangle2D> = outline.rects()

    private fun box(b: Bounds): ObjectNode =
        Json.obj().put("x", b.minX).put("y", b.minY).put("w", b.width).put("h", b.height)

    private fun explain(n: Node, sb: Bounds?): String {
        val at = if (sb == null) "" else " at ${Math.round(sb.minX)},${Math.round(sb.minY)}"
        val parent = n.parent?.let { NodePath.of(it) } ?: "-"
        return "${label(n)}$at, parent $parent"
    }

    private fun label(n: Node): String {
        val sb = StringBuilder(NodePath.typeName(n))
        if (!n.id.isNullOrEmpty()) sb.append('#').append(n.id)
        if (n.styleClass.isNotEmpty()) sb.append(' ').append(n.styleClass.joinToString("") { ".$it" })
        val b = n.layoutBounds
        sb.append(' ').append(Math.round(b.width)).append('×').append(Math.round(b.height))
        return sb.toString()
    }

    private fun nodeAt(e: MouseEvent): Node? {
        val hit = e.pickResult?.intersectedNode ?: (e.target as? Node) ?: return null
        return LogicalTree.owner(hit)
    }

    private fun hover(e: MouseEvent) {
        if (!active) return
        try {
            val n = nodeAt(e)
            if (n == null || n.localToScreen(n.boundsInLocal) == null) outline.hide()
            else outline.show(scene!!.window, n)
        } catch (t: RuntimeException) {
            AgentLog.warn("pick hover failed", t)
            outline.hide()
        }
    }

    private fun select(e: MouseEvent) {
        val n = nodeAt(e) ?: return
        selectedThisGesture = true
        pickedRef = WeakReference(n)
        pickedAt = clock()
        heldRef = WeakReference(n)
        val t = Targets.summary(n)
        t.remove("h")
        val o = Json.obj().setNode("target", t).put("ts", pickedAt)
        o.put("path", t.path("path").asText(null)) // the CSS inspector page selects by path (PRD 08 §4)
        o.put("window", n.scene?.window?.let { Windows.indexOf(it) } ?: -1)
        rt.emitEvent(Fcp.Event.HUMAN_PICK, o)
        leave()
    }

    /** Leaves pick mode but keeps swallowing the rest of the current click so the app never sees it. */
    private fun leave() {
        active = false
        restore()
        if (selectedThisGesture) {
            swallowTimeout.playFromStart()
        } else {
            detach()
        }
    }

    private fun detach() {
        swallowTimeout.stop()
        val s = scene ?: return
        for (t in MOUSE) s.removeEventFilter(t, onMouse)
        s.removeEventFilter(MouseEvent.MOUSE_MOVED, onMove)
        s.removeEventFilter(MouseEvent.MOUSE_DRAGGED, onMove)
        s.removeEventFilter(KeyEvent.KEY_PRESSED, onKey)
        scene = null
    }

    private companion object {
        const val MAX_AGE_MS = 10 * 60 * 1000L
        val MOUSE = listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)
    }
}

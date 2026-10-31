package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javafx.animation.PauseTransition
import javafx.collections.ListChangeListener
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventTarget
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Control
import javafx.scene.control.PasswordField
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration

/**
 * E3 (PRD 07 §5) + E5b (§7.2): observes the developer's own input with scene event filters on every app window
 * (overlay windows excluded) and turns it into journal-shaped `humanAction` events; brackets agent actions so their
 * events are not mistaken for human ones; handles the restart and pick hotkeys.
 */
class HumanInput(private val rt: AgentRuntime) {
    /**
     * Handle returned by [open]; pass it to [close]. A robot bracket may declare the screen point and key codes its
     * robot events will carry ([expectPoint], [expectKeys]); real input that matches neither counts as a conflict.
     */
    class Bracket internal constructor(
        val robot: Boolean,
        @Volatile var expectPoint: Point2D? = null,
        @Volatile var expectKeys: Set<KeyCode>? = null,
    ) {
        internal val conflicts = AtomicInteger()

        internal fun expectsMouse(e: MouseEvent): Boolean {
            if (!robot) return false
            val p = expectPoint ?: return expectKeys == null
            return Math.abs(e.screenX - p.x) <= 2.0 && Math.abs(e.screenY - p.y) <= 2.0
        }

        internal fun expectsKey(e: KeyEvent): Boolean {
            if (!robot) return false
            val k = expectKeys ?: return expectPoint == null
            return e.eventType == KeyEvent.KEY_TYPED || e.code in k
        }
    }

    private class EditState(val focusText: String, var touched: Boolean = false)

    private class Pending(val payload: ObjectNode, val path: String, val button: String)

    @Volatile
    private var recordHuman: Boolean = rt.options.recordHuman

    @Volatile
    private var restartKey: KeyCombination = KeyCombination.valueOf(DEFAULT_RESTART_KEY)

    @Volatile
    private var pickKey: KeyCombination = KeyCombination.valueOf(DEFAULT_PICK_KEY)

    private val brackets = CopyOnWriteArrayList<Bracket>()

    @Volatile
    private var lastCloseNanos: Long = 0L

    private var agentDepth = 0
    private var installed = false
    private var lastHumanNanos = -1L
    private var recorded = 0
    private var skippedHuman = 0
    private val edits = WeakHashMap<TextInputControl, EditState>()
    private var pending: Pending? = null
    private var clickTimer: PauseTransition? = null

    /** Test seam: sees every event this class emits (`humanAction`, `humanRestartRequest`). */
    @Volatile
    internal var listener: ((String, ObjectNode) -> Unit)? = null

    /** Installs the filters on existing and future windows. FX thread; idempotent. */
    fun install() {
        if (installed) return
        installed = true
        Window.getWindows().addListener(ListChangeListener<Window> { c ->
            while (c.next()) if (c.wasAdded()) c.addedSubList.toList().forEach(::attach)
        })
        Window.getWindows().toList().forEach(::attach)
    }

    private fun attach(w: Window) {
        if (w.properties.putIfAbsent(WINDOW_KEY, true) == null) {
            w.sceneProperty().addListener { _, _, s -> if (s != null) attachScene(s) }
        }
        w.scene?.let(::attachScene)
    }

    private fun attachScene(scene: Scene) {
        if (scene.properties.putIfAbsent(SCENE_KEY, true) != null) return
        scene.addEventFilter(MouseEvent.ANY, EventHandler<MouseEvent> { onMouse(scene, it) })
        scene.addEventFilter(KeyEvent.ANY, EventHandler<KeyEvent> { onKey(scene, it) })
        scene.focusOwnerProperty().addListener { _, old, new ->
            if (old is TextInputControl) commit(old, false)
            if (new is TextInputControl) edits[new] = EditState(new.text ?: "")
        }
        (scene.focusOwner as? TextInputControl)?.let { edits[it] = EditState(it.text ?: "") }
    }

    private fun isOverlay(scene: Scene): Boolean = scene.window?.let(Overlays::isOverlay) == true

    /** Applies `setBarState.config`: `{recordHuman, pickKey, restartKey}` (absent keys keep their value). */
    fun configure(config: JsonNode) {
        if (config.hasNonNull("recordHuman")) recordHuman = config.get("recordHuman").asBoolean()
        parseKey(config, "restartKey")?.let { restartKey = it }
        parseKey(config, "pickKey")?.let { pickKey = it }
    }

    private fun parseKey(config: JsonNode, name: String): KeyCombination? {
        if (!config.hasNonNull(name)) return null
        val s = config.get(name).asText()
        return try {
            KeyCombination.valueOf(s)
        } catch (e: IllegalArgumentException) {
            AgentLog.warn("invalid $name '$s'; keeping the previous key", e)
            null
        }
    }

    /** Opens an agent-action bracket (any thread). Robot brackets also make the dev bar show the driving state. */
    fun open(robot: Boolean, expectPoint: Point2D? = null, expectKeys: Set<KeyCode>? = null): Bracket {
        val b = Bracket(robot, expectPoint, expectKeys)
        brackets.add(b)
        if (robot) barDriving(true)
        return b
    }

    /** Closes [b] (the 100 ms settle window starts now); returns the human events seen while it was open (`conflicts`). */
    fun close(b: Bracket): Int {
        brackets.remove(b)
        lastCloseNanos = System.nanoTime()
        if (b.robot) barDriving(false)
        return b.conflicts.get()
    }

    private fun barDriving(on: Boolean) {
        try {
            rt.bar.agentInput(on)
        } catch (t: Throwable) {
            AgentLog.debug("dev bar unavailable: $t")
        }
    }

    /** Runs [block] (FX thread) marking every event it dispatches synchronously as the agent's own. */
    internal fun <T> asAgent(block: () -> T): T {
        agentDepth++
        try {
            return block()
        } finally {
            agentDepth--
        }
    }

    /** Whether [e] came from the developer; counts a conflict on open brackets for real input during them. */
    private fun isHuman(counts: Boolean, expected: (Bracket) -> Boolean): Boolean {
        if (agentDepth > 0) return false
        if (brackets.isEmpty()) return (System.nanoTime() - lastCloseNanos) >= SETTLE_NANOS
        if (brackets.any(expected)) return false
        if (counts) brackets.forEach { it.conflicts.incrementAndGet() }
        return false
    }

    private fun onMouse(scene: Scene, e: MouseEvent) {
        val type = e.eventType
        if (type != MouseEvent.MOUSE_PRESSED && type != MouseEvent.MOUSE_CLICKED) return
        if (isOverlay(scene)) return
        if (!isHuman(type == MouseEvent.MOUSE_PRESSED) { it.expectsMouse(e) }) return
        lastHumanNanos = System.nanoTime()
        if (type == MouseEvent.MOUSE_CLICKED && recordHuman && !pickActive()) {
            recordClick(e.target as? Node, e)
        }
    }

    private fun pickActive(): Boolean = try {
        rt.pick.active
    } catch (t: Throwable) {
        false
    }

    private fun onKey(scene: Scene, e: KeyEvent) {
        if (isOverlay(scene)) return
        val type = e.eventType
        if (type == KeyEvent.KEY_PRESSED && agentDepth == 0) {
            if (restartKey.match(e)) {
                e.consume()
                emitNamed(Fcp.Event.HUMAN_RESTART_REQUEST, Json.obj().put("via", "hotkey"))
                return
            }
            if (pickKey.match(e)) {
                e.consume()
                try {
                    rt.pick.toggle(scene.window)
                } catch (t: Throwable) {
                    AgentLog.warn("pick toggle failed", t)
                }
                return
            }
        }
        if (type != KeyEvent.KEY_PRESSED && type != KeyEvent.KEY_TYPED) return
        if (!isHuman(type == KeyEvent.KEY_PRESSED) { it.expectsKey(e) }) return
        lastHumanNanos = System.nanoTime()
        val input = e.target as? TextInputControl
        if (input != null) edits.getOrPut(input) { EditState(input.text ?: "") }.touched = true
        if (type != KeyEvent.KEY_PRESSED || !recordHuman || pickActive()) return
        if (!isSpecial(e)) return
        val focused = input ?: (scene.focusOwner as? TextInputControl)
        if (focused != null) commit(focused, e.code == KeyCode.ENTER)
        recordKeys(e)
    }

    private fun isSpecial(e: KeyEvent): Boolean {
        if (e.code in MODIFIER_KEYS) return false
        return e.isControlDown || e.isAltDown || e.isMetaDown || e.isShortcutDown ||
            e.code == KeyCode.ENTER || e.code == KeyCode.ESCAPE || e.code == KeyCode.TAB
    }

    /** Nearest addressable node: one with an fx:id, else the nearest Control (by logical owner). */
    private fun addressable(start: Node?): Node? {
        var cur = start?.let { LogicalTree.owner(it) }
        while (cur != null) {
            if (!cur.id.isNullOrEmpty() || cur is Control) return cur
            cur = LogicalTree.parent(cur)
        }
        return null
    }

    /** `{path, type, fxId?, text?}` without registering a handle; passwords never carry text. */
    private fun targetSummary(n: Node): ObjectNode? {
        val path = NodePath.of(n) ?: return null
        val o = Json.obj()
        o.put("path", path)
        o.put("type", NodePath.typeName(n))
        if (!n.id.isNullOrEmpty()) o.put("fxId", n.id)
        if (n !is PasswordField) NodeInfo.text(n)?.let { o.put("text", NodeInfo.clip(it, 80)) }
        return o
    }

    private fun recordClick(node: Node?, e: MouseEvent) {
        val target = addressable(node)?.let(::targetSummary)
        if (target == null) {
            skippedHuman++
            return
        }
        val path = target.get("path").asText()
        val button = e.button.name.lowercase(Locale.ROOT)
        val p = pending
        if (p != null && p.path == path && p.button == button && e.clickCount > 1) {
            (p.payload.get("args") as ObjectNode).put("clickCount", e.clickCount)
        } else {
            flush()
            val args = Json.obj().put("path", path).put("button", button).put("clickCount", maxOf(1, e.clickCount))
            pending = Pending(payload("fx_click_node", args, target), path, button)
        }
        val timer = clickTimer ?: PauseTransition(Duration.millis(DOUBLE_CLICK_MS)).also {
            it.setOnFinished { flush() }
            clickTimer = it
        }
        timer.playFromStart()
    }

    private fun recordKeys(e: KeyEvent) {
        val key = Json.obj().put("code", e.code.name)
        val mods = key.putArray("modifiers")
        if (e.isControlDown) mods.add("CONTROL")
        if (e.isAltDown) mods.add("ALT")
        if (e.isMetaDown) mods.add("META")
        if (e.isShiftDown) mods.add("SHIFT")
        val args = Json.obj()
        args.putArray("keys").add(key)
        val target = addressable(e.target as? Node)?.let(::targetSummary)
        if (target != null) args.put("path", target.get("path").asText())
        emitAction(payload("fx_type_keys", args, target))
    }

    private fun payload(tool: String, args: ObjectNode, target: ObjectNode?, redacted: Boolean = false): ObjectNode {
        val o = Json.obj()
        o.put("tool", tool)
        o.set<JsonNode>("args", args)
        if (target != null) o.set<JsonNode>("target", target)
        if (redacted) o.put("redacted", true)
        o.put("ts", System.currentTimeMillis())
        return o
    }

    /** Emits the click held back for double-click coalescing, if any. */
    internal fun flush() {
        val p = pending ?: return
        pending = null
        clickTimer?.stop()
        publish(p.payload)
    }

    private fun emitAction(payload: ObjectNode) {
        flush()
        publish(payload)
    }

    private fun publish(payload: ObjectNode) {
        recorded++
        emitNamed(Fcp.Event.HUMAN_ACTION, payload)
    }

    private fun emitNamed(event: String, payload: ObjectNode) {
        listener?.invoke(event, payload)
        try {
            rt.emitEvent(event, payload)
        } catch (e: RuntimeException) {
            AgentLog.warn("cannot emit $event", e)
        }
    }

    /** Emits the pending text edit of [input] (human-touched, changed since focus gain); [stillFocused] re-arms it. */
    private fun commit(input: TextInputControl, stillFocused: Boolean) {
        val st = edits.remove(input)
        val text = input.text ?: ""
        if (stillFocused) edits[input] = EditState(text)
        if (st == null || !st.touched || st.focusText == text || !recordHuman) return
        val owner = addressable(input)
        val target = owner?.let(::targetSummary)
        if (target == null) {
            skippedHuman++
            return
        }
        val secret = input is PasswordField
        val args = Json.obj().put("path", target.get("path").asText())
        if (!secret) args.put("text", text)
        emitAction(payload("fx_set_text", args, target, secret))
    }

    /** `{lastActivityMs?, focusedWindow?, recorded, skippedHuman, recordHuman}` for `status`. FX thread. */
    internal fun describe(): ObjectNode {
        val o = Json.obj()
        if (lastHumanNanos >= 0) o.put("lastActivityMs", (System.nanoTime() - lastHumanNanos) / 1_000_000)
        val showing = Windows.showing()
        if (showing.isNotEmpty()) {
            val w = Windows.focused(showing)
            val f = Json.obj().put("segment", Windows.segment(w))
            if (w is Stage && w.title != null) f.put("title", w.title)
            o.set<JsonNode>("focusedWindow", f)
        }
        o.put("recorded", recorded)
        o.put("skippedHuman", skippedHuman)
        o.put("recordHuman", recordHuman)
        return o
    }

    /** FCP `testHumanInput` (test mode only): synthetic events outside any bracket. */
    internal fun testInput(p: ObjectNode): ObjectNode = Fx.call {
        when (val kind = p.path("kind").asText("")) {
            "click" -> testClick(p)
            "text" -> testText(p)
            "keys" -> testKeys(p)
            else -> throw FcpException(Fcp.Error.BAD_REQUEST, "kind must be click, text or keys (got '$kind')")
        }
        Json.obj().put("injected", true)
    }

    private fun testClick(p: ObjectNode) {
        val node = Targets.resolve(p)
        val button = MouseButton.valueOf(p.path("button").asText("primary").uppercase(Locale.ROOT))
        val count = maxOf(1, p.path("clickCount").asInt(1))
        val b = node.boundsInLocal
        val scene = node.localToScene(b.centerX, b.centerY)
        val screen = node.localToScreen(b.centerX, b.centerY)
        val pick = PickResult(node, scene.x, scene.y)
        for (i in 1..count) {
            for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                val e = MouseEvent(
                    type, scene.x, scene.y, screen?.x ?: 0.0, screen?.y ?: 0.0, button, i, false, false, false,
                    false, button == MouseButton.PRIMARY, button == MouseButton.MIDDLE, button == MouseButton.SECONDARY,
                    true, button == MouseButton.SECONDARY, true, pick,
                )
                Event.fireEvent(node, e)
            }
        }
    }

    private fun testText(p: ObjectNode) {
        val node = Targets.resolve(p)
        val input: TextInputControl = when {
            node is TextInputControl -> node
            node is ComboBox<*> && node.isEditable -> node.editor
            else -> throw FcpException(Fcp.Error.UNSUPPORTED_TARGET, NodePath.typeName(node) + " is not a text input")
        }
        input.requestFocus()
        val st = edits.getOrPut(input) { EditState(input.text ?: "") }
        input.text = p.path("text").asText("")
        st.touched = true
        lastHumanNanos = System.nanoTime()
        if ("enter" == p.path("commit").asText("blur")) {
            commit(input, true)
        } else {
            blur(input)
            commit(input, false)
        }
    }

    private fun blur(input: TextInputControl) {
        val scene = input.scene ?: return
        val other = scene.root.lookupAll("*").firstOrNull { it !== input && it.isFocusTraversable && !it.isDisabled }
        (other ?: scene.root).requestFocus()
        if (scene.focusOwner === input) scene.root.requestFocus()
    }

    private fun testKeys(p: ObjectNode) {
        val combos = Interaction.parseCombos(p.path("keys"))
        if (Targets.hasTarget(p)) Targets.resolve(p).requestFocus()
        val scene = Windows.byIndex(p.get("windowIndex")?.asInt()).scene
        val target: EventTarget = scene.focusOwner ?: scene.root
        for (combo in combos) Interaction.fireKeys(target, combo)
    }

    companion object {
        const val DEFAULT_RESTART_KEY = "Ctrl+Shift+F5"
        const val DEFAULT_PICK_KEY = "Ctrl+Shift+F12"
        private const val SETTLE_NANOS = 100_000_000L
        private const val DOUBLE_CLICK_MS = 350.0
        private const val WINDOW_KEY = "fxloop.human.window"
        private const val SCENE_KEY = "fxloop.human.scene"
        private val MODIFIER_KEYS = setOf(
            KeyCode.CONTROL, KeyCode.SHIFT, KeyCode.ALT, KeyCode.META, KeyCode.COMMAND, KeyCode.ALT_GRAPH,
            KeyCode.WINDOWS, KeyCode.SHORTCUT,
        )
    }
}

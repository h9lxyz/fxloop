package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.Locale
import javafx.event.Event
import javafx.event.EventTarget
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.control.ButtonBase
import javafx.scene.control.ComboBox
import javafx.scene.control.ComboBoxBase
import javafx.scene.control.MenuButton
import javafx.scene.control.ScrollPane
import javafx.scene.control.TabPane
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.scene.robot.Robot

/** Click / key / text actions (PRD 01 §7, D6, D7). */
internal object Interaction {
    private var robotInstance: Robot? = null

    private fun robot(): Robot = robotInstance ?: Robot().also { robotInstance = it }

    fun click(p: ObjectNode): ObjectNode {
        val mode = p.path("mode").asText("robot")
        val button = MouseButton.valueOf(p.path("button").asText("primary").uppercase(Locale.ROOT))
        val clickCount = maxOf(1, p.path("clickCount").asInt(1))
        val human = AgentRuntime.current().human
        val bracket = human.open("event" != mode)
        val result: ObjectNode
        val conflicts: Int
        try {
            result = clickOnce(p, mode, button, clickCount, bracket)
            settle(p)
        } finally {
            conflicts = human.close(bracket)
        }
        result.put("conflicts", conflicts)
        return result
    }

    private fun clickOnce(p: ObjectNode, mode: String, button: MouseButton, clickCount: Int, bracket: HumanInput.Bracket): ObjectNode {
        return Fx.call {
            val out = Json.obj()
            out.put("mode", mode)
            if (!Targets.hasTarget(p)) {
                if (!p.has("x") || !p.has("y")) {
                    throw FcpException(Fcp.Error.BAD_REQUEST, "pass handle, path, query or screen x,y")
                }
                if ("robot" != mode) {
                    throw FcpException(Fcp.Error.BAD_REQUEST, "x,y clicks require mode=robot")
                }
                val x = p.get("x").asDouble()
                val y = p.get("y").asDouble()
                bracket.expectPoint = Point2D(x, y)
                robotClick(x, y, button, clickCount)
                out.put("x", x).put("y", y)
                return@call out
            }
            val node = Targets.resolve(p)
            if (node.isDisabled) {
                throw FcpException(
                    Fcp.Error.UNSUPPORTED_TARGET,
                    NodePath.of(node) + " is disabled; inspect why with fx_explain_property {name:'disable'} before clicking",
                )
            }
            out.setNode("target", Targets.summary(node))
            if ("event" == mode) {
                AgentRuntime.current().human.asAgent { eventClick(node, button, clickCount) }
            } else {
                ensureVisible(node)
                // its center would hit whatever is rendered there instead, e.g. the active ribbon page's control
                NodeInfo.notShownReason(node)?.let {
                    throw FcpException(
                        Fcp.Error.UNSUPPORTED_TARGET,
                        NodePath.of(node) + " is not rendered: $it; show it first (select its tab or page) or use mode=event",
                    )
                }
                val c = center(node)
                bracket.expectPoint = c
                robotClick(c.x, c.y, button, clickCount)
                out.put("x", Math.round(c.x)).put("y", Math.round(c.y))
            }
            out.put("clicked", true)
            out
        }
    }

    private fun robotClick(x: Double, y: Double, button: MouseButton, count: Int) {
        val r = robot()
        r.mouseMove(x, y)
        repeat(count) { r.mouseClick(button) }
    }

    private fun eventClick(node: Node, button: MouseButton, count: Int) {
        if (button == MouseButton.PRIMARY && count == 1 && node is ButtonBase && node !is MenuButton) {
            node.fire()
            return
        }
        if (node is ComboBoxBase<*> && button == MouseButton.PRIMARY) {
            if (node.isShowing) node.hide() else node.show()
            return
        }
        val b = node.boundsInLocal
        val lx = b.centerX
        val ly = b.centerY
        val scene = node.localToScene(lx, ly)
        val screen = node.localToScreen(lx, ly)
        val sx = screen?.x ?: 0.0
        val sy = screen?.y ?: 0.0
        val pick = PickResult(node, scene.x, scene.y)
        for (i in 1..count) {
            for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                val e = MouseEvent(
                    type, scene.x, scene.y, sx, sy, button, i, false, false, false,
                    false, button == MouseButton.PRIMARY, button == MouseButton.MIDDLE, button == MouseButton.SECONDARY,
                    true, button == MouseButton.SECONDARY, true, pick,
                )
                Event.fireEvent(node, e)
            }
        }
    }

    fun center(node: Node): Point2D {
        val b = node.localToScreen(node.boundsInLocal)
            ?: throw FcpException(Fcp.Error.UNSUPPORTED_TARGET, NodePath.of(node) + " is not in a showing window")
        val c = Point2D(b.centerX, b.centerY)
        val scene = node.scene
        val w = scene.window
        val sx = w.x + scene.x
        val sy = w.y + scene.y
        if (c.x < sx || c.y < sy || c.x > sx + scene.width || c.y > sy + scene.height) {
            throw FcpException(
                Fcp.Error.UNSUPPORTED_TARGET,
                NodePath.of(node) + " is outside its window's visible area (center " + Math.round(c.x) + "," +
                    Math.round(c.y) + "); scroll it into view or use mode=event",
            )
        }
        return c
    }

    /** Best effort (PRD 03 §9): select enclosing tabs and scroll enclosing ScrollPanes so the node is on screen. */
    fun ensureVisible(node: Node) {
        val chain = ArrayList<Node>()
        var cur: Node? = node
        while (cur != null) {
            chain.add(0, cur)
            cur = LogicalTree.parent(cur)
        }
        var changed = false
        for (i in 0 until chain.size - 1) {
            val anc = chain[i]
            val next = chain[i + 1]
            if (anc is TabPane) {
                for (t in anc.tabs) {
                    if (t.content === next && anc.selectionModel.selectedItem !== t) {
                        anc.selectionModel.select(t)
                        changed = true
                    }
                }
            }
        }
        if (changed) {
            node.scene.root.applyCss()
            node.scene.root.layout()
        }
        for (i in chain.size - 2 downTo 0) {
            val anc = chain[i]
            if (anc is ScrollPane && anc.content != null) scrollInto(anc, node)
        }
    }

    private fun scrollInto(sp: ScrollPane, node: Node) {
        val content = sp.content
        val inContent = content.sceneToLocal(node.localToScene(node.boundsInLocal))
        val viewport = sp.viewportBounds
        val contentH = content.boundsInLocal.height
        val contentW = content.boundsInLocal.width
        if (contentH > viewport.height) {
            val target = (inContent.minY + inContent.height / 2 - viewport.height / 2) / (contentH - viewport.height)
            sp.vvalue = clamp(sp.vmin + target * (sp.vmax - sp.vmin), sp.vmin, sp.vmax)
        }
        if (contentW > viewport.width) {
            val target = (inContent.minX + inContent.width / 2 - viewport.width / 2) / (contentW - viewport.width)
            sp.hvalue = clamp(sp.hmin + target * (sp.hmax - sp.hmin), sp.hmin, sp.hmax)
        }
        sp.applyCss()
        sp.layout()
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double = maxOf(lo, minOf(hi, v))

    fun typeKeys(p: ObjectNode): ObjectNode {
        val mode = p.path("mode").asText("robot")
        if (mode != "robot" && mode != "event") {
            throw FcpException(Fcp.Error.BAD_REQUEST, "mode must be 'robot' or 'event'")
        }
        val combos = parseCombos(p.path("keys"))
        val human = AgentRuntime.current().human
        val bracket = human.open("robot" == mode, expectKeys = combos.flatten().toSet())
        val out: ObjectNode
        val conflicts: Int
        try {
            if (Targets.hasTarget(p)) {
                Fx.run { Targets.resolve(p).requestFocus() }
            }
            for (combo in combos) {
                Fx.run {
                    if ("event" == mode) {
                        val scene = Windows.byIndex(null).scene
                        val target: EventTarget = if (Targets.hasTarget(p)) Targets.resolve(p) else scene.focusOwner ?: scene.root
                        human.asAgent { fireKeys(target, combo) }
                    } else {
                        val r = robot()
                        for (c in combo) r.keyPress(c)
                        for (i in combo.indices.reversed()) r.keyRelease(combo[i])
                    }
                }
            }
            settle(p)
            out = Fx.call {
                val o = Json.obj()
                o.put("typed", combos.size)
                o.put("mode", mode)
                val focus = Windows.byIndex(null).scene.focusOwner
                if (focus != null) {
                    o.setNode("focusedAfter", Targets.summary(LogicalTree.owner(focus) ?: focus))
                }
                o
            }
        } finally {
            conflicts = human.close(bracket)
        }
        out.put("conflicts", conflicts)
        return out
    }

    /** `[{code, modifiers?}]` to key chords (modifiers first, main key last). */
    internal fun parseCombos(keys: com.fasterxml.jackson.databind.JsonNode): List<List<KeyCode>> {
        if (!keys.isArray || keys.isEmpty) {
            throw FcpException(Fcp.Error.BAD_REQUEST, "keys must be a non-empty array of {code, modifiers?}")
        }
        val combos = ArrayList<List<KeyCode>>()
        for (k in keys) {
            val combo = ArrayList<KeyCode>()
            for (m in k.path("modifiers")) combo.add(keyCode(m.asText()))
            combo.add(keyCode(k.path("code").asText()))
            combos.add(combo)
        }
        return combos
    }

    /** Fires KEY_PRESSED, KEY_TYPED (printable, no ctrl/alt/meta) and KEY_RELEASED for one chord at [target]. FX thread. */
    internal fun fireKeys(target: EventTarget, combo: List<KeyCode>) {
        val code = combo.last()
        val mods = combo.dropLast(1)
        val shift = KeyCode.SHIFT in mods
        val ctrl = KeyCode.CONTROL in mods
        val alt = KeyCode.ALT in mods
        val meta = KeyCode.META in mods || KeyCode.COMMAND in mods
        fun key(type: javafx.event.EventType<KeyEvent>, ch: String, text: String, c: KeyCode) =
            Event.fireEvent(target, KeyEvent(target, target, type, ch, text, c, shift, ctrl, alt, meta))
        key(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, code.getName(), code)
        val ch = if (ctrl || alt || meta) null else typedChar(code, shift)
        if (ch != null) key(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED)
        key(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, code.getName(), code)
    }

    private fun typedChar(code: KeyCode, shift: Boolean): String? {
        val n = code.name
        return when {
            n.length == 1 && n[0] in 'A'..'Z' -> if (shift) n else n.lowercase(Locale.ROOT)
            n.startsWith("DIGIT") || n.startsWith("NUMPAD") && n.length == 7 -> n.last().toString()
            code == KeyCode.SPACE -> " "
            else -> null
        }
    }

    private fun keyCode(name: String): KeyCode {
        var n = name.trim().uppercase(Locale.ROOT)
        if (n == "SHORTCUT") {
            return if (System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("mac")) KeyCode.COMMAND
            else KeyCode.CONTROL
        }
        if (n == "CTRL") n = "CONTROL"
        if (n == "RETURN") n = "ENTER"
        try {
            return KeyCode.valueOf(n)
        } catch (e: IllegalArgumentException) {
            val byName = KeyCode.getKeyCode(name)
            if (byName != null) return byName
            throw FcpException(
                Fcp.Error.BAD_REQUEST,
                "unknown KeyCode '$name'; use javafx.scene.input.KeyCode names (ENTER, TAB, A, DIGIT1, SHORTCUT …). " +
                    "For free text use fx_set_text",
            )
        }
    }

    fun setText(p: ObjectNode): ObjectNode {
        val text = p.path("text").asText("")
        val clearFirst = p.path("clearFirst").asBoolean(true)
        val human = AgentRuntime.current().human
        val bracket = human.open(false)
        val out: ObjectNode
        val conflicts: Int
        try {
            out = setTextOnce(p, text, clearFirst)
            settle(p)
        } finally {
            conflicts = human.close(bracket)
        }
        out.put("mode", "event").put("conflicts", conflicts)
        return out
    }

    private fun setTextOnce(p: ObjectNode, text: String, clearFirst: Boolean): ObjectNode {
        return Fx.call {
            val node = Targets.resolve(p)
            val input: TextInputControl? = when {
                node is TextInputControl -> node
                node is ComboBox<*> && node.isEditable -> node.editor
                else -> null
            }
            if (input == null) {
                throw FcpException(
                    Fcp.Error.UNSUPPORTED_TARGET,
                    NodePath.typeName(node) + " is not a TextInputControl; use fx_type_keys for key presses or " +
                        "fx_set_property for other values",
                )
            }
            if (input.textProperty().isBound) {
                throw FcpException(
                    Fcp.Error.PROPERTY_BOUND,
                    "text of " + NodePath.of(node) + " is bound; write to the binding's source instead",
                )
            }
            input.requestFocus()
            if (clearFirst) input.text = text else input.appendText(text)
            input.positionCaret(input.length)
            val o = Json.obj()
            o.put("set", true)
            o.setNode("target", Targets.summary(node))
            o.put("valueAfter", input.text)
            o
        }
    }

    /** Lets robot-injected events reach the FX thread before the next read (agent-side settle). */
    fun settle(p: ObjectNode) {
        Fx.sleep(p.path("settleMs").asLong(60))
        Fx.barrier()
    }
}

package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.ListChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.stage.Popup
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.Window
import javafx.util.Duration

/**
 * E5a (PRD 07 §7): the dev bar, an agent-owned `Popup` per showing app stage carrying session, last reload and
 * action-needed segments. Rendered from the full state the server pushes with `setBarState`.
 *
 * The bar docks above the window (or over its title bar) so it never covers app controls; when neither fits it
 * collapses to a pill in the window's top-right corner.
 */
class DevBar(private val rt: AgentRuntime) {
    private class Action(val kind: String, val reason: String, val next: String, val button: String)

    private val lock = Any()
    private var state: ObjectNode = Json.obj().put("bar", "off")
    private var bar = "off"
    private var control = "agent"
    private var stateDriving = false
    private var robots = 0
    private var collapsed = false
    private var collapsedLoaded = false
    private var degraded = 0
    private var dismissedSig: String? = null
    private var lastSeq = -1L
    private var reloadVisible: String? = null
    private val history = ArrayList<String>()
    private var listening = false

    private val views = LinkedHashMap<Stage, BarView>()
    private val reloadClear by lazy { PauseTransition(Duration.seconds(3.0)).apply {
        setOnFinished {
            synchronized(lock) { reloadVisible = null }
            refresh()
        }
    } }

    /** FCP `setBarState` (the full state, see docs/decisions/live-dev-loop.md). Returns [describe]. */
    internal fun applyState(p: ObjectNode): ObjectNode = Fx.call {
        synchronized(lock) {
            state = p.deepCopy()
            bar = p.path("bar").asText("off")
            control = p.path("control").asText("agent")
            stateDriving = p.path("driving").asBoolean(false)
            degraded = 0
            if (dismissedSig != null && dismissedSig != actionSig()) dismissedSig = null
            val lr = p.path("lastReload")
            val seq = lr.path("seq").asLong(-1)
            if (lr.hasNonNull("text") && seq != lastSeq) {
                lastSeq = seq
                reloadVisible = lr.path("text").asText()
                reloadClear.playFromStart()
            } else if (!lr.hasNonNull("text")) {
                reloadVisible = null
            }
        }
        refresh()
        describe()
    }

    /** Called by [HumanInput] when a robot bracket opens/closes; shows the driving text for its duration. Any thread. */
    fun agentInput(active: Boolean) {
        synchronized(lock) {
            robots = if (active) robots + 1 else maxOf(0, robots - 1)
            if (bar != "off") recordControlText()
        }
        Platform.runLater { refresh() }
    }

    /** FCP `getBarState`: debug view of what the bar renders. FX thread. */
    internal fun describe(): ObjectNode = synchronized(lock) {
        val o = Json.obj()
        o.put("bar", bar)
        o.put("rendered", views.values.count { it.popup.isShowing })
        o.put("windows", targets().size)
        o.put("collapsed", collapsed)
        val texts = o.putObject("texts")
        texts.put("session", sessionText())
        texts.put("lastReload", reloadVisible ?: "")
        val arr = texts.putArray("actionNeeded")
        visibleActions().forEach { arr.add(it.reason) }
        val h = o.putArray("controlHistory")
        history.forEach { h.add(it) }
        o
    }

    // ---- model ------------------------------------------------------------------------------------------

    private fun driving(): Boolean = stateDriving || robots > 0

    private fun controlText(): String = if (control == "shared") {
        if (driving()) "Agent is using your mouse" else "You can interact"
    } else {
        if (driving()) "Agent is driving — hands off" else "Agent mode"
    }

    private fun sessionText(): String =
        state.path("mode").asText("dev loop") + " · " + controlText() + " · watch: " + state.path("watch").asText("css-only")

    private fun recordControlText() {
        val t = controlText()
        if (history.lastOrNull() != t) {
            history.add(t)
            if (history.size > 20) history.removeAt(0)
        }
    }

    private fun allActions(): List<Action> {
        val out = ArrayList<Action>()
        for (a in state.path("actionNeeded")) {
            out.add(Action(a.path("kind").asText(), a.path("reason").asText(), a.path("next").asText(), a.path("button").asText()))
        }
        return out
    }

    private fun actionSig(): String = allActions().joinToString("|") { it.kind + ":" + it.reason }

    private fun visibleActions(): List<Action> = if (dismissedSig != null && dismissedSig == actionSig()) emptyList() else allActions()

    // ---- rendering ----------------------------------------------------------------------------------------

    private fun refresh() {
        try {
            val effective = synchronized(lock) {
                recordControlTextIfOn()
                effectiveBar()
            }
            if (effective != "off") ensureListening()
            sync(effective)
        } catch (t: Throwable) {
            AgentLog.warn("dev bar render failed", t)
            synchronized(lock) { degraded++ }
            try {
                sync(synchronized(lock) { effectiveBar() })
            } catch (t2: Throwable) {
                AgentLog.warn("dev bar fallback failed", t2)
                synchronized(lock) { degraded = 2 }
                removeAll()
            }
        }
    }

    private fun recordControlTextIfOn() {
        if (bar != "off") recordControlText()
    }

    private fun effectiveBar(): String = when {
        bar == "off" || degraded >= 2 -> "off"
        degraded == 1 -> "pill"
        bar == "pill" -> "pill"
        else -> {
            loadCollapsed()
            if (collapsed) "pill" else "on"
        }
    }

    private fun ensureListening() {
        if (listening) return
        listening = true
        Window.getWindows().addListener(ListChangeListener<Window> { Platform.runLater { refresh() } })
    }

    private fun targets(): List<Stage> =
        Window.getWindows().filterIsInstance<Stage>().filter {
            it.isShowing && it.scene != null && !Overlays.isOverlay(it) && Windows.type(it) == "STAGE"
        }

    private fun sync(effective: String) {
        if (effective == "off") {
            removeAll()
            return
        }
        val want = targets()
        views.keys.filter { it !in want }.forEach { views.remove(it)?.dispose() }
        for (s in want) {
            val v = views.getOrPut(s) { BarView(s) }
            v.render(effective)
        }
    }

    private fun removeAll() {
        views.values.forEach { it.dispose() }
        views.clear()
    }

    // ---- actions ------------------------------------------------------------------------------------------

    private fun toggleControl() {
        val next = synchronized(lock) {
            val n = if (control == "agent") "shared" else "agent"
            control = n
            state.put("control", n)
            recordControlText()
            n
        }
        rt.emitEvent(Fcp.Event.CONTROL_MODE_CHANGED, Json.obj().put("mode", next).put("via", "bar"))
        refresh()
    }

    private fun toggleCollapsed() {
        synchronized(lock) { collapsed = !collapsed }
        saveCollapsed()
        refresh()
    }

    private fun dismissActions() {
        synchronized(lock) { dismissedSig = actionSig() }
        refresh()
    }

    private fun copy(text: String) {
        try {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
        } catch (t: Throwable) {
            AgentLog.warn("could not copy to clipboard", t)
        }
    }

    private fun prefsFile(): Path? = rt.options.stateFile?.let { it.toAbsolutePath().resolveSibling("devbar.json") }

    private fun loadCollapsed() {
        if (collapsedLoaded) return
        collapsedLoaded = true
        val f = prefsFile() ?: return
        try {
            if (Files.exists(f)) collapsed = Json.MAPPER.readTree(Files.readString(f)).path("collapsed").asBoolean(false)
        } catch (e: IOException) {
            AgentLog.warn("ignoring unreadable $f", e)
        }
    }

    private fun saveCollapsed() {
        val f = prefsFile() ?: return
        val body = Json.pretty(Json.obj().put("collapsed", collapsed))
        Thread({
            try {
                Files.createDirectories(f.parent)
                Files.writeString(f, body)
            } catch (e: IOException) {
                AgentLog.warn("could not save $f", e)
            }
        }, "fxloop-devbar-save").apply { isDaemon = true }.start()
    }

    // ---- one bar per stage ----------------------------------------------------------------------------------

    private inner class BarView(val stage: Stage) {
        val popup = Popup()
        private val root = HBox(6.0)
        private var pill = false
        private val geometry = InvalidationListener { position() }
        private val esc = javafx.event.EventHandler<KeyEvent> { e ->
            if (e.code == KeyCode.ESCAPE && root.isHover && visibleActions().isNotEmpty()) dismissActions()
        }
        private var filtered: javafx.scene.Scene? = null

        init {
            Overlays.mark(popup)
            popup.isAutoHide = false
            popup.isAutoFix = false
            root.alignment = Pos.CENTER_LEFT
            root.padding = Insets(0.0, 6.0, 0.0, 6.0)
            popup.content.add(root)
            stage.xProperty().addListener(geometry)
            stage.yProperty().addListener(geometry)
            stage.widthProperty().addListener(geometry)
            stage.heightProperty().addListener(geometry)
            stage.sceneProperty().addListener(geometry)
            filtered = stage.scene
            filtered?.addEventFilter(KeyEvent.KEY_PRESSED, esc)
        }

        fun dispose() {
            stage.xProperty().removeListener(geometry)
            stage.yProperty().removeListener(geometry)
            stage.widthProperty().removeListener(geometry)
            stage.heightProperty().removeListener(geometry)
            stage.sceneProperty().removeListener(geometry)
            filtered?.removeEventFilter(KeyEvent.KEY_PRESSED, esc)
            popup.hide()
        }

        private fun button(text: String, onClick: () -> Unit): Button = Button(text).apply {
            isFocusTraversable = false
            padding = Insets(0.0, 6.0, 0.0, 6.0)
            style = "-fx-font-size: 11px; -fx-background-radius: 3; -fx-min-height: 16; -fx-pref-height: 16; -fx-max-height: 16;"
            setOnAction { onClick() }
        }

        private fun label(text: String): Label = Label(text).apply {
            style = "-fx-text-fill: white; -fx-font-size: 11px;"
            minWidth = 0.0
        }

        fun render(effective: String) {
            if (stage.scene !== filtered) {
                filtered?.removeEventFilter(KeyEvent.KEY_PRESSED, esc)
                filtered = stage.scene
                filtered?.addEventFilter(KeyEvent.KEY_PRESSED, esc)
            }
            val actions = synchronized(lock) { visibleActions() }
            val color = when {
                actions.isNotEmpty() -> "rgba(176,42,42,0.92)"
                synchronized(lock) { driving() } -> "rgba(183,121,31,0.92)"
                else -> "rgba(40,40,40,0.82)"
            }
            root.style = "-fx-background-color: $color; -fx-background-radius: 0;"
            root.minHeight = HEIGHT
            root.prefHeight = HEIGHT
            root.maxHeight = HEIGHT
            val kids = ArrayList<Node>()
            val expandable = effective == "pill"
            if (expandable) {
                val pending = if (actions.isEmpty()) "" else " · ${actions.size} action needed"
                kids.add(label("FXLoop$pending"))
                kids.add(button("⌃") { toggleCollapsed() }.also { if (synchronized(lock) { bar } == "pill") it.isDisable = true })
            } else {
                val session = label(synchronized(lock) { sessionText() }).apply { HBox.setHgrow(this, Priority.SOMETIMES) }
                kids.add(session)
                synchronized(lock) { reloadVisible }?.let { kids.add(label("· $it")) }
                val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0 }
                kids.add(spacer)
                for (a in actions) {
                    kids.add(label(a.reason))
                    when (a.button) {
                        "restart" -> kids.add(button("Restart") {
                            rt.emitEvent(Fcp.Event.HUMAN_RESTART_REQUEST, Json.obj().put("via", "bar"))
                        })
                        "copy" -> kids.add(button("Copy reason") { copy(if (a.kind == "rebuildFailed") a.next else a.reason) })
                    }
                }
                kids.add(button("Pick") { rt.pick.toggle(stage) })
                val shared = synchronized(lock) { control == "shared" }
                kids.add(button(if (shared) "Hand back to agent" else "Let me drive") { toggleControl() })
                kids.add(button("⌄") { toggleCollapsed() })
            }
            pill = expandable
            root.children.setAll(kids)
            if (pill) {
                root.prefWidth = Region.USE_COMPUTED_SIZE
            } else {
                root.prefWidth = maxOf(120.0, stage.width)
            }
            position()
        }

        private fun position() {
            if (stage.scene == null) return
            val w = if (pill) root.prefWidth(HEIGHT).coerceAtLeast(60.0) else maxOf(120.0, stage.width)
            val aboveY = stage.y - HEIGHT
            val aboveOk = onScreen(stage.x + 1, aboveY)
            val titleOk = stage.style == StageStyle.DECORATED && stage.scene.y >= HEIGHT
            val x: Double
            val y: Double
            if (pill) {
                x = stage.x + stage.width - w
                y = if (aboveOk) aboveY else stage.y
            } else if (aboveOk) {
                x = stage.x
                y = aboveY
            } else if (titleOk) {
                x = stage.x
                y = stage.y
            } else {
                // Neither docking spot fits: collapse to the corner pill for this stage.
                pill = true
                render("pill")
                return
            }
            if (!pill) root.prefWidth = w
            if (popup.isShowing) {
                popup.x = x
                popup.y = y
            } else {
                popup.show(stage, x, y)
            }
        }

        private fun onScreen(x: Double, y: Double): Boolean = Screen.getScreens().any { it.visualBounds.contains(x, y) }
    }

    private companion object {
        const val HEIGHT = 22.0
    }
}

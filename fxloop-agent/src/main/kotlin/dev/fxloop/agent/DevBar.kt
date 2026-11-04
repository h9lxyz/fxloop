package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.ListChangeListener
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonBase
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.Labeled
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Paint
import javafx.stage.Popup
import javafx.stage.PopupWindow
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.Window
import javafx.util.Duration
import kotlin.math.abs
import kotlin.math.floor

/**
 * E5a (PRD 07 §7): the dev bar, an agent-owned bar window per showing app stage carrying session, last reload and
 * action-needed segments (see [BarWindow]). Rendered from the full state the server pushes with `setBarState`.
 *
 * The bar docks above the window (or over its title bar) so it never covers app controls; when neither fits it
 * collapses to a pill in the window's top-right corner, and expanding that pill lays the bar over the window's top
 * edge. On a narrow window segments give way in a fixed order and buttons move into a `⋯` menu, so the bar never
 * squeezes a button into "…". Dragging the bar moves the window and a double-click maximizes it, like a title bar.
 * Styles come from `devbar.css`, scoped by id so the app's own stylesheets, which every popup inherits, don't apply.
 */
class DevBar(private val rt: AgentRuntime, private val ownedStage: Boolean = LINUX) {
    private class Action(val kind: String, val reason: String, val next: String, val button: String)

    private val lock = Any()
    private val posted = AtomicReference<ObjectNode?>()
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
    private val lockExpiry by lazy { PauseTransition().apply { setOnFinished { refresh() } } }
    private val reloadClear by lazy { PauseTransition(Duration.seconds(3.0)).apply {
        setOnFinished {
            synchronized(lock) { reloadVisible = null }
            refresh()
        }
    } }

    /**
     * FCP `setBarState`: queues [applyState] on the FX thread and returns without waiting for it. The agent serves
     * requests on one worker, so waiting here on a busy or blocked FX thread would hold back the tool call queued behind
     * this push and leave it a timed-out call to wait out ([Fx.call]): a cosmetic push must never cost a tool its
     * timeout or its FX_TIMEOUT stack. Pushes made while the FX thread is busy coalesce to the latest state.
     */
    internal fun post(p: ObjectNode): ObjectNode {
        if (posted.getAndSet(p) == null) {
            Platform.runLater {
                posted.getAndSet(null)?.let { s ->
                    try {
                        applyState(s)
                    } catch (e: RuntimeException) {
                        AgentLog.warn("setBarState failed", e)
                    }
                }
            }
        }
        return Json.obj().put("queued", true)
    }

    /** Applies the full bar state (see docs/decisions/live-dev-loop.md). Returns [describe]. */
    internal fun applyState(p: ObjectNode): ObjectNode = Fx.call {
        synchronized(lock) {
            state = p.deepCopy()
            bar = p.path("bar").asText("off")
            control = p.path("control").asText("agent")
            stateDriving = p.path("driving").asBoolean(false)
            val lockMs = p.path("lock").path("remainingMs").asLong(0)
            rt.human.setLock(lockMs)
            if (lockMs > 0) {
                lockExpiry.duration = Duration.millis(lockMs.toDouble() + 50)
                lockExpiry.playFromStart()
            } else {
                lockExpiry.stop()
            }
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

    /** Bar shown while no server is connected (`fxloop:run`): the developer explores, picks, and asks an agent later. */
    internal fun standalone(bar: String) {
        applyState(Json.obj().put("bar", bar).put("standalone", true).put("control", "shared"))
    }

    private fun isStandalone(): Boolean = state.path("standalone").asBoolean(false)

    /** Connected to the `fxloop:run` inspector helper rather than an agent session. */
    private fun isSidecar(): Boolean = state.path("sidecar").asBoolean(false)

    /** The inspector's scratch stylesheet changed ([dev.fxloop.agent.css.Scratch]); re-renders its segment. Any thread. */
    fun scratchChanged() {
        Platform.runLater { refresh() }
    }

    private fun scratchText(): String? = dev.fxloop.agent.css.Scratch.ruleCount().takeIf { it > 0 }
        ?.let { "Scratch CSS active · $it rule${if (it == 1) "" else "s"}" }

    /** What the agent is doing right now (`setBarState.activity`), e.g. "clicking #save". */
    private fun activityText(): String? = state.path("activity").asText("").takeIf { it.isNotEmpty() && !isStandalone() && !isSidecar() }

    private fun locked(): Boolean = rt.human.locked

    /** Bar *Take over*: drops the agent's input lock at once and tells the server. */
    private fun takeOver() {
        rt.human.setLock(null)
        synchronized(lock) { state.remove("lock") }
        rt.emitEvent(Fcp.Event.INPUT_LOCK_RELEASED, Json.obj().put("via", "bar"))
        refresh()
    }

    /** Bar *Clear*: drops the scratch sheet and tells the server so an open inspector page refreshes. */
    private fun clearScratch() {
        val r = dev.fxloop.agent.css.Scratch.clear()
        rt.emitEvent(Fcp.Event.SCRATCH_CHANGED, r)
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
        o.put("rendered", views.values.count { it.win.window.isShowing })
        o.put("windows", targets().size)
        o.put("collapsed", collapsed)
        val texts = o.putObject("texts")
        texts.put("session", sessionText())
        texts.put("lastReload", reloadVisible ?: "")
        texts.put("scratch", scratchText() ?: "")
        texts.put("activity", activityText() ?: "")
        o.put("locked", locked())
        val arr = texts.putArray("actionNeeded")
        visibleActions().forEach { arr.add(it.reason) }
        val h = o.putArray("controlHistory")
        history.forEach { h.add(it) }
        val vs = o.putArray("views")
        views.values.filter { it.win.window.isShowing }.forEach { vs.add(it.describe()) }
        o
    }

    // ---- model ------------------------------------------------------------------------------------------

    private fun driving(): Boolean = stateDriving || robots > 0

    private fun controlText(): String = when {
        locked() -> "Agent is driving — you're watching"
        control == "shared" -> if (driving()) "Agent is using your mouse" else "You can interact"
        else -> if (driving()) "Agent is driving — hands off" else "Agent mode · you can interact"
    }

    private fun noAgent(): Boolean = isStandalone() || isSidecar()

    private fun sessionText(): String = if (noAgent()) "FXLoop · " + sessionBody() else sessionBody()

    /** [sessionText] without the brand, which the bar shows on its own. */
    private fun sessionBody(): String = if (noAgent()) "no agent connected · pick a node, then ask your agent" else
        state.path("mode").asText("dev loop") + " · " + controlText() + " · " + watchText(state.path("watch").asText("css-only"))

    /** A collapsed bar can expand: not pinned to a pill by `FXLOOP_BAR=pill` or a render failure. */
    private fun expandable(): Boolean = bar == "on" && degraded == 0

    /** What a save does in the server's watch mode, in the developer's words. */
    private fun watchText(mode: String): String = when (mode) {
        "all" -> "Saves apply CSS, FXML and code"
        "off" -> "Saves apply on reload"
        else -> "Saves apply CSS live"
    }

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
        // the bar's own tooltips and menu come and go as windows too; re-rendering for them would close them again
        Window.getWindows().addListener(ListChangeListener<Window> { c ->
            if (Overlays.appChanged(c)) Platform.runLater { refresh() }
        })
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

    /** *Open CSS inspector* (PRD 08 §4): asks the server to open the browser inspector on the last pick or the focused node. */
    private fun requestInspector(stage: Stage) {
        val o = Json.obj()
        val node = runCatching { rt.pick.picked() }.getOrNull()?.takeIf { it.scene?.window === stage }
            ?: stage.scene?.focusOwner?.let(Overlay::resolvable)
        if (node != null) {
            NodePath.of(node)?.let { o.put("path", it) }
            o.put("h", rt.handles.handleOf(node))
        }
        rt.emitEvent(Fcp.Event.INSPECTOR_REQUESTED, o)
    }

    /** Where a bar sits: above the window, over its title bar, or over the top of its content. */
    private enum class Dock { ABOVE, TITLE, INSIDE }

    /**
     * How a bar element shares a narrow window: elements get their width in [priority] order (highest first,
     * [ALWAYS] = always shown), a text may truncate down to [keep] px, and an [elastic] text asks only for [keep] and
     * takes what is left at the end. An element that gets no room is hidden; a hidden button moves into the `⋯` menu.
     */
    private class Fit(val priority: Int, val keep: Double = Double.MAX_VALUE, val elastic: Boolean = false)

    /** A press on the bar outside its buttons; dragging from it moves the window like its title bar would. */
    private class Grab(val pressX: Double, val pressY: Double, val fractionX: Double, val offsetY: Double) {
        var moving = false
    }

    /** The bar's own window, holding the bar's root node. */
    private interface BarWindow {
        val window: Window

        /** Moves the window to [x], [y], fitted to the bar, and shows it if it isn't showing yet. */
        fun place(x: Double, y: Double)
    }

    /** The lighter window, for Windows and macOS: there a popup stacks with its owner and never takes focus. */
    private class PopupBar(private val owner: Stage, root: Region) : BarWindow {
        private val popup = Popup().apply {
            isAutoHide = false
            isAutoFix = false
            content.add(root)
        }
        override val window: Window get() = popup

        override fun place(x: Double, y: Double) {
            if (popup.isShowing) {
                popup.x = x
                popup.y = y
            } else {
                popup.show(owner, x, y)
            }
        }
    }

    /**
     * For Linux (GTK), where a `Popup` maps to an override-redirect window (`GTK_WINDOW_POPUP` in glass) that the
     * window manager never stacks: the bar then floats above every other app, not just above the app it belongs to.
     * An owned `Stage` is a normal managed window, transient for the app stage, so it stays one level above the app
     * and sinks behind other apps with it.
     */
    private class StageBar(private val app: Stage, private val root: Region) : BarWindow {
        private val bar = Stage(StageStyle.TRANSPARENT).apply {
            initOwner(app)
            // a window the WM lists needs a name; JavaFX cannot ask it to skip the taskbar
            title = "FXLoop dev bar"
            scene = Scene(StackPane(root), null as Paint?)
            // a popup never takes focus, a stage does on show and on every click; keys and hotkeys belong to the app
            focusedProperty().addListener { _, _, focused ->
                if (focused) Platform.runLater { if (isShowing) app.requestFocus() }
            }
        }
        override val window: Window get() = bar

        override fun place(x: Double, y: Double) {
            // a popup re-sizes to its content on every layout pass; a stage only on request
            if (abs(bar.width - root.prefWidth(-1.0)) > 0.5 || abs(bar.height - root.prefHeight(-1.0)) > 0.5) bar.sizeToScene()
            bar.x = x
            bar.y = y
            if (!bar.isShowing) bar.show()
        }
    }

    private inner class BarView(val stage: Stage) {
        private val root = HBox()
        val win: BarWindow = if (ownedStage) StageBar(stage, root) else PopupBar(stage, root)
        private var effective = "on"
        private var pill = false
        private var dock = Dock.ABOVE

        /** The developer expanded this window's bar where no outside spot fits: it then lies over the window's top edge. */
        private var inside = false
        private var fits = LinkedHashMap<Node, Fit>()
        private var rendered: String? = null
        private var fittedWidth = -1.0
        private val more = iconButton("more", "More") { showMenu() }
        private val menu = ContextMenu().apply {
            id = "fxloop-bar-menu"
            Overlays.mark(this)
            CSS?.let { scene.root.stylesheets.add(it) }
        }
        private var grab: Grab? = null
        private val geometry = InvalidationListener { position() }
        private val esc = javafx.event.EventHandler<KeyEvent> { e ->
            if (e.code == KeyCode.ESCAPE && root.isHover && visibleActions().isNotEmpty()) dismissActions()
        }
        private var filtered: javafx.scene.Scene? = null

        init {
            Overlays.mark(win.window)
            win.window.scene.root.style = "-fx-background-color: transparent;"
            root.id = "fxloop-bar"
            root.alignment = Pos.CENTER_LEFT
            CSS?.let { root.stylesheets.add(it) }
            root.addEventHandler(MouseEvent.MOUSE_PRESSED) { e ->
                grab = if (e.button == MouseButton.PRIMARY && !onButton(e) && !locked() && !stage.isFullScreen) {
                    Grab(e.screenX, e.screenY, (e.screenX - stage.x) / stage.width, e.screenY - stage.y)
                } else {
                    null
                }
            }
            root.addEventHandler(MouseEvent.MOUSE_DRAGGED) { e -> grab?.let { drag(it, e) } }
            root.addEventHandler(MouseEvent.MOUSE_RELEASED) { grab = null }
            root.addEventHandler(MouseEvent.MOUSE_CLICKED) { e ->
                if (e.button == MouseButton.PRIMARY && e.clickCount == 2 && e.isStillSincePress && !onButton(e) && !pill &&
                    stage.isResizable && !stage.isFullScreen && !locked()
                ) {
                    stage.isMaximized = !stage.isMaximized
                }
            }
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
            menu.hide()
            win.window.hide()
        }

        /** This window's bar for [describe]: shape, dock and what a narrow window moved into the `⋯` menu. */
        fun describe(): ObjectNode {
            val o = Json.obj().put("pill", pill).put("dock", dock.name.lowercase()).put("width", root.width)
            val overflow = o.putArray("overflow")
            root.children.filter { !it.isManaged && it !== more }.filterIsInstance<Labeled>().forEach { overflow.add(it.text) }
            return o
        }

        // ---- moving the window ----

        private fun onButton(e: MouseEvent): Boolean =
            generateSequence(e.target as? Node) { it.parent }.takeWhile { it !== root }.any { it is ButtonBase }

        private fun drag(g: Grab, e: MouseEvent) {
            if (!g.moving && abs(e.screenX - g.pressX) < DRAG_SLOP && abs(e.screenY - g.pressY) < DRAG_SLOP) return
            g.moving = true
            // a maximized window restores first and keeps the pointer at the same fraction of its width
            if (stage.isMaximized) stage.isMaximized = false
            stage.x = e.screenX - g.fractionX * stage.width
            stage.y = e.screenY - g.offsetY
        }

        // ---- building blocks ----

        private fun tip(text: String): Tooltip = Tooltip(text).apply {
            Overlays.mark(this)
            showDelay = Duration.millis(400.0)
        }

        private fun button(text: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(text).apply {
            styleClass.setAll("fxl-btn")
            if (primary) styleClass.add("primary")
            isFocusTraversable = false
            isMnemonicParsing = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { onClick() }
        }

        private fun iconButton(icon: String, hint: String, onClick: () -> Unit): Button = Button().apply {
            styleClass.setAll("fxl-btn", "icon", icon)
            graphic = Region().apply { styleClass.setAll("fxl-icon") }
            tooltip = tip(hint)
            accessibleText = hint
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { onClick() }
        }

        /**
         * A text segment; [rule] puts a thin divider before it, which goes with the text when it gives way, and [hint]
         * is its tooltip (`""` = the text itself, for texts that may truncate).
         */
        private fun text(text: String, vararg classes: String, rule: Boolean = false, hint: String? = null): Label = Label(text).apply {
            styleClass.setAll("fxl-text", *classes)
            isMnemonicParsing = false
            minWidth = 0.0
            if (rule) graphic = Region().apply { styleClass.setAll("fxl-rule") }
            if (hint != null) tooltip = tip(hint.ifEmpty { text })
        }

        private fun shape(vararg classes: String): Region = Region().apply {
            styleClass.setAll(*classes)
            minWidth = Region.USE_PREF_SIZE
        }

        private fun add(node: Node, fit: Fit) {
            fits[node] = fit
        }

        // ---- rendering ----

        fun render(effective: String) {
            this.effective = effective
            build()
        }

        private fun build() {
            if (stage.scene !== filtered) {
                filtered?.removeEventFilter(KeyEvent.KEY_PRESSED, esc)
                filtered = stage.scene
                filtered?.addEventFilter(KeyEvent.KEY_PRESSED, esc)
            }
            val actions = synchronized(lock) { visibleActions() }
            val busy = locked() || synchronized(lock) { driving() }
            val tone = when {
                actions.isNotEmpty() -> "action"
                busy -> "driving"
                synchronized(lock) { noAgent() } -> "solo"
                else -> "idle"
            }
            val forced = effective == "on" && outsideDock() == null && !inside
            pill = effective == "pill" || forced
            val previous = fits
            fits = LinkedHashMap()
            if (pill) buildPill(actions, busy, forced) else buildBar(actions)
            // most pushes change nothing the bar shows; keeping its nodes keeps an open menu or tooltip open
            val signature = "$pill $forced $tone " + actions.joinToString { it.kind + ":" + it.next } + " " +
                fits.keys.joinToString("|") { n -> (if (n is Labeled) n.text else "") + n.styleClass + (if (n.isDisable) "!" else "") }
            if (signature == rendered) {
                fits = previous
                position()
                return
            }
            rendered = signature
            root.styleClass.setAll("fxl-bar", tone)
            if (pill) root.styleClass.add("pill")
            val h = if (pill) HEIGHT - 4 else HEIGHT
            root.minHeight = h
            root.prefHeight = h
            root.maxHeight = h
            root.children.setAll(fits.keys)
            root.prefWidth = Region.USE_COMPUTED_SIZE
            fittedWidth = -1.0
            position()
        }

        private fun buildPill(actions: List<Action>, busy: Boolean, forced: Boolean) {
            add(shape("fxl-dot"), Fit(ALWAYS))
            add(text("FXLoop", "brand"), Fit(ALWAYS))
            val pending = "${actions.size} action${if (actions.size == 1) "" else "s"} needed"
            when {
                actions.isNotEmpty() -> text(pending, "strong", rule = true, hint = actions.joinToString("\n") { it.reason })
                busy -> text("Agent driving", "strong", rule = true, hint = synchronized(lock) { controlText() })
                else -> null
            }?.let { add(it, Fit(ALWAYS)) }
            val expand = iconButton("expand", "Expand the dev bar") {
                if (forced) {
                    inside = true
                    build()
                } else {
                    toggleCollapsed()
                }
            }
            if (!forced && !synchronized(lock) { expandable() }) expand.isDisable = true
            add(expand, Fit(ALWAYS))
        }

        private fun buildBar(actions: List<Action>) {
            add(shape("fxl-grip").apply { Tooltip.install(this, tip("Drag to move the window")) }, Fit(ALWAYS))
            add(shape("fxl-dot"), Fit(ALWAYS))
            add(text("FXLoop", "brand"), Fit(9))
            val (session, activity, reload) = synchronized(lock) { Triple(sessionBody(), activityText(), reloadVisible) }
            add(text(session, hint = ""), Fit(8, keep = 110.0, elastic = true))
            activity?.let { add(text("Agent: $it", "activity", rule = true, hint = ""), Fit(5, keep = 110.0)) }
            reload?.let {
                val ok = synchronized(lock) { state.path("lastReload").path("ok").asBoolean(true) }
                add(text(it, if (ok) "ok" else "warn", rule = true), Fit(1))
            }
            scratchText()?.let {
                add(text(it, rule = true), Fit(2, keep = 90.0))
                add(button("Clear") { clearScratch() }, Fit(7))
            }
            add(Region().apply { HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0 }, Fit(ALWAYS))
            for (a in actions) {
                add(text(a.reason, "strong", hint = ""), Fit(11, keep = 110.0))
                when (a.button) {
                    "restart" -> add(button("Restart", primary = true) {
                        rt.emitEvent(Fcp.Event.HUMAN_RESTART_REQUEST, Json.obj().put("via", "bar"))
                    }, Fit(12))
                    "copy" -> add(button("Copy reason") { copy(if (a.kind == "rebuildFailed") a.next else a.reason) }, Fit(12))
                }
            }
            val noAgent = synchronized(lock) { noAgent() }
            if (!synchronized(lock) { isStandalone() }) { // needs the server's viewer
                add(button("Open CSS inspector") { requestInspector(stage) }.apply {
                    tooltip = tip("Opens the CSS inspector in a browser")
                }, Fit(3))
            }
            val isLocked = locked()
            add(button("Pick") { rt.pick.toggle(stage) }.apply {
                isDisable = isLocked
                tooltip = tip("Click a node in the app to hand it to your agent")
            }, Fit(6))
            if (isLocked) {
                add(button("Take over", primary = true) { takeOver() }.apply {
                    tooltip = tip("Ends the agent's input lock so you can use the app again")
                }, Fit(12))
            }
            val shared = synchronized(lock) { control == "shared" }
            if (!noAgent) add(button(if (shared) "Hand back to agent" else "Let me drive") { toggleControl() }, Fit(4))
            add(more, Fit(ALWAYS))
            add(iconButton("collapse", "Collapse to a pill") { toggleCollapsed() }, Fit(ALWAYS))
        }

        /** Fits the bar into [width] (see [Fit]); the `⋯` button shows only when a button had to leave. */
        private fun fit(width: Double) {
            root.applyCss()
            if (!allocate(width, false)) allocate(width, true)
        }

        /** One pass of [fit]; false when a button found no room and [withMore] did not reserve the `⋯` button. */
        private fun allocate(width: Double, withMore: Boolean): Boolean {
            // HBox rounds every child up to whole pixels; measure the same way so the sum stays within the width
            fun size(n: Node): Double = root.snapSizeX(n.prefWidth(-1.0))
            val gap = root.snapSpaceX(root.spacing)
            var left = width - root.snappedLeftInset() - root.snappedRightInset() + gap
            if (withMore) left -= size(more) + gap
            show(more, withMore)
            var overflow = false
            val order = fits.entries.filter { it.key !== more }.sortedByDescending { it.value.priority }
            for ((n, f) in order) {
                val pref = size(n)
                val want = if (f.elastic) minOf(pref, f.keep) else pref
                val take = when {
                    f.priority == ALWAYS || want + gap <= left -> want
                    n is Label && minOf(pref, f.keep) + gap <= left -> floor(left - gap)
                    else -> -1.0
                }
                show(n, take >= 0)
                if (take < 0) {
                    overflow = overflow || n is Button
                    continue
                }
                if (n is Label) n.minWidth = take
                left -= take + gap
            }
            for ((n, f) in order) {
                if (!f.elastic || !n.isManaged || left <= 0) continue
                val l = n as Label
                val grant = minOf(size(l) - l.minWidth, floor(left))
                l.minWidth += grant
                left -= grant
            }
            return withMore || !overflow
        }

        private fun show(n: Node, on: Boolean) {
            n.isVisible = on
            n.isManaged = on
        }

        private fun showMenu() {
            val hidden = root.children.filter { !it.isManaged && it !== more }
            val items = ArrayList<MenuItem>()
            hidden.filterIsInstance<Label>().filter { "brand" !in it.styleClass }.forEach { l ->
                items.add(MenuItem(l.text).apply { isDisable = true; styleClass.add("info") })
            }
            val buttons = hidden.filterIsInstance<Button>()
            if (items.isNotEmpty() && buttons.isNotEmpty()) items.add(SeparatorMenuItem())
            buttons.forEach { b -> items.add(MenuItem(b.text).apply { isDisable = b.isDisable; setOnAction { b.fire() } }) }
            menu.items.setAll(items)
            if (menu.isShowing) {
                menu.hide()
                return
            }
            // right edge under the button: the menu opens toward the window, not past its edge
            val b = more.localToScreen(more.boundsInLocal) ?: return
            menu.anchorLocation = PopupWindow.AnchorLocation.CONTENT_TOP_RIGHT
            menu.show(more, b.maxX, b.maxY + 2)
        }

        /** Where the bar fits without covering app controls: above the window, else over its title bar; null when neither. */
        private fun outsideDock(): Dock? {
            val scene = stage.scene ?: return null
            return when {
                onScreen(stage.x + 1, stage.y - HEIGHT) -> Dock.ABOVE
                stage.style == StageStyle.DECORATED && scene.y >= HEIGHT -> Dock.TITLE
                else -> null
            }
        }

        private fun position() {
            val scene = stage.scene ?: return
            val spot = outsideDock()
            if ((effective == "pill" || spot == null && !inside) != pill) {
                build()
                return
            }
            dock = spot ?: Dock.INSIDE
            if ((dock != Dock.ABOVE) != ("below" in root.styleClass)) {
                if (dock == Dock.ABOVE) root.styleClass.remove("below") else root.styleClass.add("below")
            }
            var left = if (dock == Dock.INSIDE) stage.x + scene.x else stage.x
            var width = if (dock == Dock.INSIDE) scene.width else stage.width
            if (dock == Dock.TITLE) {
                // keeps the window's own minimize/maximize/close buttons clear: left on macOS, right elsewhere
                val reserve = minOf(maxOf(WINDOW_BUTTONS, scene.y * 4), width / 2)
                width -= reserve
                if (MAC) left += reserve
            }
            val top = when (dock) {
                Dock.ABOVE -> stage.y - HEIGHT
                Dock.TITLE -> stage.y
                Dock.INSIDE -> stage.y + scene.y
            }
            val x: Double
            val y: Double
            if (pill) {
                root.applyCss()
                val w = root.prefWidth(-1.0)
                // inside the window the corners belong to the app (an undecorated app's own close button); center it
                x = if (dock == Dock.INSIDE) left + (width - w) / 2 else maxOf(left, left + width - w - PILL_INSET)
                y = top + 2
            } else {
                val w = maxOf(MIN_WIDTH, width)
                if (w != fittedWidth) {
                    root.prefWidth = w
                    fit(w)
                    fittedWidth = w
                }
                x = left
                y = top
            }
            win.place(x, y)
        }

        private fun onScreen(x: Double, y: Double): Boolean = Screen.getScreens().any { it.visualBounds.contains(x, y) }
    }

    private companion object {
        const val HEIGHT = 24.0
        const val MIN_WIDTH = 120.0
        const val PILL_INSET = 8.0

        /** Title bar width left free for the window's own buttons when the bar docks over it. */
        const val WINDOW_BUTTONS = 140.0
        val MAC = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("mac")

        /** Linux/GTK: popups are override-redirect there, so the bar needs a real window (see [StageBar]). */
        val LINUX = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("linux")

        /** [Fit.priority] of what the bar always shows. */
        const val ALWAYS = Int.MAX_VALUE

        /** Pointer travel before a press on the bar starts moving the window. */
        const val DRAG_SLOP = 4.0
        val CSS: String? = DevBar::class.java.getResource("devbar.css")?.toExternalForm()
    }
}

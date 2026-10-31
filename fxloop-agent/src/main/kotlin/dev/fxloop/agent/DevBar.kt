package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import java.util.IdentityHashMap
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.ListChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.stage.Popup
import javafx.stage.PopupWindow
import javafx.stage.Stage
import javafx.stage.Window

/**
 * Translucent pill popup at the top-right of every showing app [Stage] (PRD 07 §7.3): Inspect CSS and Pick buttons plus a
 * status text. Never takes focus and is invisible to tools via [Overlay.OVERLAY_KEY]. FX thread only.
 */
internal object DevBar {
    private const val STYLE = "-fx-background-color: rgba(30,30,34,0.82); -fx-background-radius: 11; " +
        "-fx-border-color: rgba(255,255,255,0.18); -fx-border-radius: 11; -fx-font-family: system; -fx-font-size: 11px;"
    private const val BUTTON = "-fx-background-color: rgba(255,255,255,0.14); -fx-background-radius: 8; -fx-text-fill: white; " +
        "-fx-font-size: 11px; -fx-padding: 1 7 1 7; -fx-cursor: hand;"

    private class Bar(val popup: Popup, val status: Label, val showing: InvalidationListener, val geometry: InvalidationListener)

    private val bars = IdentityHashMap<Stage, Bar>()
    private var started = false
    private var text = ""

    /** Test hook: receives every `inspectorRequested` payload in addition to the FCP event. */
    @Volatile
    internal var requestListener: ((ObjectNode) -> Unit)? = null

    fun start() {
        if (started) return
        started = true
        Window.getWindows().toList().forEach(::consider)
        Window.getWindows().addListener(ListChangeListener<Window> { c ->
            while (c.next()) {
                c.addedSubList.forEach { w -> Platform.runLater { consider(w) } }
                c.removed.forEach { w -> (w as? Stage)?.let { s -> Platform.runLater { detach(s) } } }
            }
        })
    }

    /** Number of bar popups currently showing. */
    fun count(): Int = bars.values.count { it.popup.isShowing }

    internal fun popups(): List<Popup> = bars.values.map { it.popup }

    /** FCP `setBarState`. */
    fun setBarState(p: ObjectNode): ObjectNode = Fx.call {
        if (p.hasNonNull("text")) text = p.get("text").asText()
        bars.values.forEach { applyText(it.status) }
        Json.obj().put("bars", count())
    }

    private fun applyText(l: Label) {
        l.text = text
        l.isVisible = text.isNotEmpty()
        l.isManaged = text.isNotEmpty()
    }

    private fun consider(w: Window) {
        if (w !is Stage || w.properties.containsKey(Overlay.OVERLAY_KEY) || bars.containsKey(w)) return
        try {
            attach(w)
        } catch (t: Throwable) {
            AgentLog.warn("dev bar could not attach to a stage", t)
        }
    }

    private fun attach(stage: Stage) {
        val status = Label().apply { style = "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 11px;" }
        applyText(status)
        val title = Label("FXLoop").apply { style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;" }
        val inspect = button("Inspect CSS") { requestInspector(stage) }
        val pickBtn = button("Pick") { Overlay.arm(listOf(stage)) }
        val row = HBox(6.0, title, status, inspect, pickBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(2.0, 8.0, 2.0, 10.0)
            style = STYLE
            minHeight = 22.0
        }
        val popup = Popup().apply {
            isAutoFix = false
            isAutoHide = false
            consumeAutoHidingEvents = false
            anchorLocation = PopupWindow.AnchorLocation.WINDOW_TOP_RIGHT
            properties[Overlay.OVERLAY_KEY] = true
            content.add(row)
        }
        val geometry = InvalidationListener { reposition(stage, popup) }
        val showing = InvalidationListener { sync(stage, popup) }
        bars[stage] = Bar(popup, status, showing, geometry)
        stage.showingProperty().addListener(showing)
        stage.sceneProperty().addListener(showing)
        stage.xProperty().addListener(geometry)
        stage.yProperty().addListener(geometry)
        stage.widthProperty().addListener(geometry)
        stage.heightProperty().addListener(geometry)
        sync(stage, popup)
    }

    private fun detach(stage: Stage) {
        val bar = bars.remove(stage) ?: return
        bar.popup.hide()
        stage.showingProperty().removeListener(bar.showing)
        stage.sceneProperty().removeListener(bar.showing)
        stage.xProperty().removeListener(bar.geometry)
        stage.yProperty().removeListener(bar.geometry)
        stage.widthProperty().removeListener(bar.geometry)
        stage.heightProperty().removeListener(bar.geometry)
    }

    private fun sync(stage: Stage, popup: Popup) {
        try {
            if (stage.isShowing && stage.scene != null) {
                if (!popup.isShowing) popup.show(stage, 0.0, 0.0)
                reposition(stage, popup)
            } else {
                popup.hide()
            }
        } catch (t: Throwable) {
            AgentLog.warn("dev bar update failed", t)
        }
    }

    private fun reposition(stage: Stage, popup: Popup) {
        if (!popup.isShowing) return
        val s = stage.scene
        popup.anchorX = stage.x + (s?.x ?: 0.0) + (s?.width ?: stage.width) - 8.0
        popup.anchorY = stage.y + (s?.y ?: 0.0) + 4.0
    }

    private fun button(label: String, action: () -> Unit) = Button(label).apply {
        isFocusTraversable = false
        style = BUTTON
        setOnAction { action() }
    }

    private fun requestInspector(stage: Stage) {
        val o = Json.obj()
        val owner = stage.scene?.focusOwner?.let(Overlay::resolvable)
        if (owner != null) {
            NodePath.of(owner)?.let { o.put("path", it) }
            o.put("h", AgentRuntime.current().handles.handleOf(owner))
        }
        requestListener?.invoke(o)
        AgentRuntime.get()?.emit(Fcp.Event.INSPECTOR_REQUESTED, o)
    }
}

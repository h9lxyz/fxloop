package org.lab59.fxloop.agent

import javafx.animation.PauseTransition
import javafx.beans.InvalidationListener
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.geometry.Point2D
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.robot.Robot
import javafx.stage.Popup
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import kotlin.math.abs

/**
 * A frame around a node: four thin edge windows just outside it plus a label window above or below it. Windows cannot
 * be click-through, so nothing ever overlaps the node itself (the pointer stays on the app's scene). The windows sit
 * at screen coordinates that a window move would strand, so the owner window and the node are watched and the frame
 * is redrawn whenever either moves or resizes, and put away while the window is hidden or iconified or the node has
 * left it.
 *
 * On Linux and Windows, `Popup` windows float above every other application (GTK override-redirect on Linux,
 * `WS_EX_TOPMOST` on Windows), so when [ownedStage] is true and the owner is a [Stage], each part uses an owned
 * transparent [Stage] that stays one level above its owner and sinks behind other apps with it (falling back to
 * `Popup` when the owner is itself a popup). Even then, a frame can land on an overlapping window like the browser
 * inspector, and a part under the pointer takes hover from the row that drew it: the part under the pointer therefore
 * waits until the pointer has moved off it. FX thread only.
 */
internal class NodeFrame(
    color: String,
    private val label: (Node) -> String,
    private val onPress: () -> Unit = {},
    private val ownedStage: Boolean = Overlays.OWNED_STAGE,
) {
    private var node: Node? = null
    private var owner: Window? = null
    private val edges = List(4) { FramePart(Region().apply { style = "-fx-background-color: $color;" }, fixedSize = true) }
    private val text = Label().apply {
        style = "-fx-background-color: rgba(40,40,40,0.92); -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 1 5 1 5;"
    }
    private val tag = FramePart(text, fixedSize = false)
    private val onOwnerChange = InvalidationListener { draw() }
    private val onNodeChange = ChangeListener<Any?> { _, _, _ -> draw() }
    private val retry = PauseTransition(Duration.millis(RETRY_MS)).apply { setOnFinished { draw() } }

    private inner class FramePart(val content: Region, private val fixedSize: Boolean) {
        private var stage: Stage? = null
        private var popup: Popup? = null

        val window: Window? get() = stage ?: popup

        init {
            content.isMouseTransparent = true
        }

        fun prepare(owner: Window) {
            if (ownedStage && owner is Stage) {
                if (stage?.owner === owner) return
                detachPopup()
                detachStage()
                stage = Overlays.ownedStage(owner, "FXLoop highlight", StackPane(content)).apply {
                    // the label lies over the app, so a press on it counts as one on the app (the frame is outside the node)
                    scene.addEventFilter(MouseEvent.MOUSE_PRESSED) { onPress() }
                }
            } else {
                detachStage()
                if (popup == null) {
                    popup = Popup().apply {
                        Overlays.mark(this)
                        isAutoHide = false
                        isAutoFix = false
                        isHideOnEscape = false // an owner passes its key events to its popups: Escape would knock out one edge
                        this.content.add(this@FramePart.content)
                        scene.addEventFilter(MouseEvent.MOUSE_PRESSED) { onPress() }
                    }
                }
            }
        }

        fun place(owner: Window, r: Rectangle2D, pointer: Point2D): Boolean {
            if (r.contains(pointer)) {
                hide()
                return true
            }
            prepare(owner)
            if (fixedSize) {
                content.setPrefSize(r.width, r.height)
                content.setMinSize(r.width, r.height)
                content.setMaxSize(r.width, r.height)
            }
            val s = stage
            if (s != null) {
                if (fixedSize) {
                    s.width = r.width
                    s.height = r.height
                } else if (s.width.isNaN() || s.height.isNaN() ||
                    abs(s.width - content.prefWidth(-1.0)) > 0.5 || abs(s.height - content.prefHeight(-1.0)) > 0.5
                ) {
                    s.sizeToScene()
                }
                s.x = r.minX
                s.y = r.minY
                if (!s.isShowing) s.show()
            } else {
                val p = popup!!
                if (p.isShowing && p.ownerWindow !== owner) p.hide()
                if (p.isShowing) {
                    p.x = r.minX
                    p.y = r.minY
                } else {
                    p.show(owner, r.minX, r.minY)
                }
            }
            return false
        }

        fun hide() {
            window?.hide()
        }

        private fun detachStage() {
            val s = stage ?: return
            s.hide()
            (s.scene?.root as? StackPane)?.children?.clear()
            stage = null
        }

        private fun detachPopup() {
            val p = popup ?: return
            p.hide()
            p.content.clear()
            popup = null
        }
    }

    fun show(owner: Window, n: Node) {
        track(owner)
        follow(n)
        draw()
    }

    /** Re-places the frame over the node's current screen bounds; puts it away while the node does not map to the owner. */
    private fun draw() {
        val n = node ?: return
        val w = owner ?: return
        val b = n.localToScreen(n.boundsInLocal)
        if (!w.isShowing || (w as? Stage)?.isIconified == true || n.scene?.window !== w || b == null) {
            conceal()
            return
        }
        text.text = label(n)
        val at = pointer()
        val t = 2.0
        val rects = listOf(
            Rectangle2D(b.minX - t, b.minY - t, b.width + 2 * t, t),
            Rectangle2D(b.minX - t, b.maxY, b.width + 2 * t, t),
            Rectangle2D(b.minX - t, b.minY, t, b.height),
            Rectangle2D(b.maxX, b.minY, t, b.height),
        )
        var deferred = false
        for ((i, r) in rects.withIndex()) {
            deferred = edges[i].place(w, r, at) || deferred
        }
        tag.prepare(w)
        text.applyCss()
        val h = text.prefHeight(-1.0).coerceAtLeast(16.0)
        val above = Screen.getScreens().any { it.visualBounds.contains(b.minX, b.minY - t - h) }
        val y = if (above) b.minY - t - h else b.maxY + t
        deferred = tag.place(w, Rectangle2D(b.minX - t, y, text.prefWidth(-1.0), h), at) || deferred
        if (deferred) retry.playFromStart() else retry.stop()
    }

    private fun track(w: Window) {
        if (owner === w) return
        untrack()
        owner = w
        watchedProperties(w).forEach { it.addListener(onOwnerChange) }
    }

    private fun untrack() {
        val w = owner ?: return
        watchedProperties(w).forEach { it.removeListener(onOwnerChange) }
        owner = null
    }

    private fun watchedProperties(w: Window) = listOfNotNull(
        w.xProperty(), w.yProperty(), w.widthProperty(), w.heightProperty(), w.showingProperty(),
        (w as? Stage)?.iconifiedProperty(),
    )

    private fun follow(n: Node) {
        if (node === n) return
        unfollow()
        node = n
        watchedProperties(n).forEach { it.addListener(onNodeChange) }
    }

    private fun unfollow() {
        val n = node ?: return
        watchedProperties(n).forEach { it.removeListener(onNodeChange) }
        node = null
    }

    private fun watchedProperties(n: Node) =
        listOf<ObservableValue<*>>(n.localToSceneTransformProperty(), n.boundsInLocalProperty(), n.sceneProperty())

    fun hide() {
        untrack()
        unfollow()
        conceal()
    }

    private fun conceal() {
        retry.stop()
        edges.forEach { it.hide() }
        tag.hide()
    }

    /** True while any part of the frame is on screen. */
    fun isShown(): Boolean = (edges + tag).mapNotNull { it.window }.any { it.isShowing }

    /** Screen rects of the edge and label windows, empty while hidden (tests). */
    fun rects(): List<Rectangle2D> =
        (edges.asSequence() + sequenceOf(tag)).mapNotNull { it.window }.filter { it.isShowing }
            .map { Rectangle2D(it.x, it.y, it.width, it.height) }.toList()

    companion object {
        const val RETRY_MS = 250.0
        private var robot: Robot? = null

        /** The pointer's screen position; tests replace it. */
        internal var pointer: () -> Point2D = { (robot ?: Robot().also { robot = it }).mousePosition }
    }
}

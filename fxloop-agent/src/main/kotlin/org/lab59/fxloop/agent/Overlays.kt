package org.lab59.fxloop.agent

import java.util.Locale
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.paint.Paint
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.Window

/** Agent-owned overlay windows (dev bar, pick outline): invisible to every tool (PRD 07 §7.3). */
internal object Overlays {
    const val KEY = "fxloop.overlay"

    /**
     * Whether persistent overlays use an owned transparent [Stage] instead of a `Popup`.
     *
     * On Linux (GTK), a `Popup` maps to an override-redirect window (`GTK_WINDOW_POPUP`) that the window manager
     * never stacks; on Windows (Win32), Glass gives `Window.POPUP` the `WS_EX_TOPMOST` extended style so it floats
     * above every other application. An owned `Stage` stacks with its owner window and sinks behind other apps on
     * both platforms. On macOS, a `Popup` already stacks with its owner as a child window without taking focus.
     */
    val OWNED_STAGE: Boolean = usesOwnedStage(System.getProperty("os.name", ""))

    internal fun usesOwnedStage(osName: String): Boolean =
        !osName.lowercase(Locale.ROOT).contains("mac")

    fun mark(w: Window) {
        w.properties[KEY] = true
    }

    fun isOverlay(w: Window): Boolean = w.properties[KEY] == true

    /**
     * Builds an agent-owned transparent [Stage] owned by [owner], marked as an overlay, and returning focus to
     * [owner] if it ever receives focus (a `Popup` never takes focus, whereas a `Stage` does on show and on clicks).
     */
    fun ownedStage(owner: Stage, title: String, content: Parent): Stage {
        val stage = Stage(StageStyle.TRANSPARENT)
        stage.initOwner(owner)
        stage.title = title
        stage.scene = Scene(content, null as Paint?).also {
            it.root.style = "-fx-background-color: transparent;"
        }
        mark(stage)
        stage.focusedProperty().addListener { _, _, focused ->
            if (focused) Platform.runLater { if (stage.isShowing && owner.isShowing) owner.requestFocus() }
        }
        return stage
    }

    /** Whether a `Window.getWindows()` change [c] touched an app window rather than only overlays. Consumes [c]. */
    fun appChanged(c: ListChangeListener.Change<out Window>): Boolean {
        var app = false
        while (c.next()) app = app || (c.addedSubList + c.removed).any { !isOverlay(it) }
        return app
    }
}

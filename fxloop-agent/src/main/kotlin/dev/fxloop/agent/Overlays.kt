package dev.fxloop.agent

import javafx.collections.ListChangeListener
import javafx.stage.Window

/** Agent-owned overlay windows (dev bar, pick outline): invisible to every tool (PRD 07 §7.3). */
internal object Overlays {
    const val KEY = "fxloop.overlay"

    fun mark(w: Window) {
        w.properties[KEY] = true
    }

    fun isOverlay(w: Window): Boolean = w.properties[KEY] == true

    /** Whether a `Window.getWindows()` change [c] touched an app window rather than only overlays. Consumes [c]. */
    fun appChanged(c: ListChangeListener.Change<out Window>): Boolean {
        var app = false
        while (c.next()) app = app || (c.addedSubList + c.removed).any { !isOverlay(it) }
        return app
    }
}

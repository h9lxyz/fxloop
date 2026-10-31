package dev.fxloop.agent

import javafx.stage.Window

/** Agent-owned overlay windows (dev bar, pick outline): invisible to every tool (PRD 07 §7.3). */
internal object Overlays {
    const val KEY = "fxloop.overlay"

    fun mark(w: Window) {
        w.properties[KEY] = true
    }

    fun isOverlay(w: Window): Boolean = w.properties[KEY] == true
}

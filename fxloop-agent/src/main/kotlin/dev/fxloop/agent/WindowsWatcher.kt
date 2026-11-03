package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.stage.Window

/**
 * Emits [Fcp.Event.WINDOWS_CHANGED] whenever an app window opens or closes, so an inspector page opened earlier can
 * list — and switch to — a dialog that appeared later. Changes within one pulse coalesce into one event; agent
 * overlays never count. Must be used on the FX thread only.
 */
internal class WindowsWatcher(private val emit: (String, ObjectNode) -> Unit) {
    private var installed = false
    private var queued = false

    fun install() {
        if (installed) return
        installed = true
        Window.getWindows().addListener(ListChangeListener { c ->
            var app = false
            while (c.next()) app = app || (c.addedSubList + c.removed).any { !Overlays.isOverlay(it) }
            if (app) schedule()
        })
    }

    /** The full current window list, e.g. for a server that just (re)connected and has not seen this app yet. */
    fun emitNow() {
        emit(Fcp.Event.WINDOWS_CHANGED, Json.obj().setNode("windows", Windows.describeAll()))
    }

    private fun schedule() {
        if (queued) return
        queued = true
        Platform.runLater {
            queued = false
            emitNow()
        }
    }
}

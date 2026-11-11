package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.Json
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.ListChangeListener
import javafx.stage.Stage
import javafx.stage.Window

/**
 * Emits [Fcp.Event.WINDOWS_CHANGED] whenever an app window opens, closes or changes title, so an inspector page opened
 * earlier can list — and switch to — a dialog that appeared later, under its current title. Changes queued together
 * coalesce into one event; agent overlays never count. Must be used on the FX thread only.
 */
internal class WindowsWatcher(private val emit: (String, ObjectNode) -> Unit) {
    private var installed = false
    private var queued = false
    private var titled = emptySet<Stage>()
    private val onTitle = InvalidationListener { schedule() }

    fun install() {
        if (installed) return
        installed = true
        Window.getWindows().addListener(ListChangeListener { c -> if (Overlays.appChanged(c)) schedule() })
        trackTitles()
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
            trackTitles()
            emitNow()
        }
    }

    private fun trackTitles() {
        val now = Windows.showing().filterIsInstance<Stage>().toSet()
        (titled - now).forEach { it.titleProperty().removeListener(onTitle) }
        (now - titled).forEach { it.titleProperty().addListener(onTitle) }
        titled = now
    }
}

package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import javafx.scene.control.DialogPane
import javafx.stage.Modality
import javafx.stage.PopupWindow
import javafx.stage.Stage
import javafx.stage.Window

/** Window enumeration and addressing. Must be called on the FX thread. */
internal object Windows {
    private const val ID_KEY = "fxloop.windowId"
    private var nextId = 0

    /** Showing app windows; agent overlays (dev bar, pick outline) are never part of any tool's view. */
    fun showing(): List<Window> = Window.getWindows().filter { it.isShowing && it.scene != null && !Overlays.isOverlay(it) }

    /** Stays with [w] for as long as it lives, unlike its index or title. */
    fun id(w: Window): Int = w.properties.getOrPut(ID_KEY) { ++nextId } as Int

    fun type(w: Window): String {
        if (w is PopupWindow) return "POPUP"
        if (w is Stage) {
            val dialog = w.modality != Modality.NONE || w.scene != null && w.scene.root is DialogPane
            return if (dialog) "DIALOG" else "STAGE"
        }
        return "WINDOW"
    }

    /** Path segment for a window: `Stage[i]` among stages, `Popup[i]` among popups. */
    fun segment(w: Window): String {
        val popup = w is PopupWindow
        var i = 0
        for (other in showing()) {
            if (other === w) break
            if ((other is PopupWindow) == popup) i++
        }
        return (if (popup) "Popup[" else "Stage[") + i + "]"
    }

    fun bySegment(kind: String, index: Int): Window {
        var i = 0
        for (w in showing()) {
            val popup = w is PopupWindow
            val match = when (kind) {
                "Stage" -> !popup
                "Popup" -> popup
                else -> true
            }
            if (match) {
                if (i == index) return w
                i++
            }
        }
        throw FcpException(
            Fcp.Error.WINDOW_NOT_FOUND,
            "no showing window $kind[$index]; call fx_get_app_status to list windows",
        )
    }

    /** Index into [showing]; `null`/negative selects the focused window (else the first stage). */
    fun byIndex(index: Int?): Window {
        val all = showing()
        if (all.isEmpty()) throw FcpException(Fcp.Error.WINDOW_NOT_FOUND, "the application has no showing window yet")
        if (index == null || index < 0) return focused(all)
        if (index >= all.size) {
            throw FcpException(Fcp.Error.WINDOW_NOT_FOUND, "windowIndex $index out of range (${all.size} windows)")
        }
        return all[index]
    }

    fun focused(all: List<Window>): Window {
        for (w in all) if (w.isFocused && w !is PopupWindow) return w
        for (w in all) if (w !is PopupWindow) return w
        return all[0]
    }

    fun indexOf(w: Window): Int = showing().indexOf(w)

    fun describeAll(): ArrayNode {
        val arr = Json.array()
        showing().forEachIndexed { i, w -> arr.add(describe(w, i)) }
        return arr
    }

    fun describe(w: Window, index: Int): ObjectNode {
        val o = Json.obj()
        o.put("index", index)
        o.put("id", id(w))
        o.put("segment", segment(w))
        if (w is Stage) o.put("title", w.title)
        o.put("type", type(w))
        o.put("focused", w.isFocused)
        o.put("x", Math.round(w.x))
        o.put("y", Math.round(w.y))
        o.put("w", Math.round(w.width))
        o.put("h", Math.round(w.height))
        o.put("showing", w.isShowing)
        return o
    }
}

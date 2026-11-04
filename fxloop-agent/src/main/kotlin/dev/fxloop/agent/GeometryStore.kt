package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javafx.animation.PauseTransition
import javafx.beans.InvalidationListener
import javafx.collections.ListChangeListener
import javafx.geometry.Rectangle2D
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration

/**
 * Persists stage geometry per stage index to `state.json` and restores it on next start (PRD 02 §5). Overlay stages
 * (the dev bar on Linux) neither get an entry nor count toward the index.
 */
internal class GeometryStore(private val file: Path) {
    private lateinit var state: ObjectNode
    private val debounce = PauseTransition(Duration.millis(400.0))
    private val windows = ListChangeListener<Window> { c ->
        while (c.next()) {
            c.addedSubList.forEach { attach(it) }
        }
    }

    /** FX thread. */
    fun install() {
        state = load()
        debounce.setOnFinished { save() }
        for (w in Window.getWindows()) attach(w)
        Window.getWindows().addListener(windows)
    }

    /** Test hook: stops watching new windows, so a test's store does not restore the next test's stages. FX thread. */
    internal fun uninstall() {
        Window.getWindows().removeListener(windows)
        debounce.stop()
    }

    private fun attach(w: Window) {
        if (w !is Stage || Overlays.isOverlay(w) || w.properties.containsKey("fxloop.geometry")) return
        w.properties["fxloop.geometry"] = java.lang.Boolean.TRUE
        restore(w)
        val l = InvalidationListener { debounce.playFromStart() }
        w.xProperty().addListener(l)
        w.yProperty().addListener(l)
        w.widthProperty().addListener(l)
        w.heightProperty().addListener(l)
        w.maximizedProperty().addListener(l)
    }

    private fun stageIndex(stage: Stage): Int {
        var i = 0
        for (w in Window.getWindows()) {
            if (w === stage) return i
            if (w is Stage && !Overlays.isOverlay(w)) i++
        }
        return i
    }

    private fun restore(stage: Stage) {
        val g = state.path("stages").path(stageIndex(stage).toString())
        if (g.isMissingNode) return
        val x = g.path("x").asDouble()
        val y = g.path("y").asDouble()
        val screens = Screen.getScreensForRectangle(Rectangle2D(x, y, 40.0, 40.0))
        if (screens.isEmpty()) return // monitor layout changed: keep default placement
        stage.x = x
        stage.y = y
        if (g.path("w").asDouble() > 0) {
            stage.width = g.path("w").asDouble()
            stage.height = g.path("h").asDouble()
        }
        stage.isMaximized = g.path("maximized").asBoolean(false)
    }

    private fun save() {
        val stages = state.putObject("stages")
        var i = 0
        for (w in Window.getWindows()) {
            if (w is Stage && w.isShowing && !Overlays.isOverlay(w)) {
                val g = stages.putObject((i++).toString())
                g.put("x", w.x).put("y", w.y).put("w", w.width).put("h", w.height)
                g.put("maximized", w.isMaximized)
                val screens = Screen.getScreensForRectangle(w.x, w.y, 1.0, 1.0)
                g.put("screenId", if (screens.isEmpty()) 0 else Screen.getScreens().indexOf(screens[0]))
            }
        }
        val snapshot = state.deepCopy()
        Thread({
            try {
                Files.createDirectories(file.toAbsolutePath().parent)
                Files.writeString(file, Json.pretty(snapshot))
            } catch (e: IOException) {
                AgentLog.warn("could not save $file", e)
            }
        }, "fxloop-geometry-save").apply { isDaemon = true }.start()
    }

    private fun load(): ObjectNode {
        try {
            if (Files.exists(file)) {
                return Json.MAPPER.readTree(Files.readString(file)) as ObjectNode
            }
        } catch (e: IOException) {
            AgentLog.warn("ignoring unreadable $file", e)
        } catch (e: ClassCastException) {
            AgentLog.warn("ignoring unreadable $file", e)
        }
        return Json.obj()
    }
}

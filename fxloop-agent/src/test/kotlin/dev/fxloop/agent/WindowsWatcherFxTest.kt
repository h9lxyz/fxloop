package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.DialogPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** WindowsWatcher: a page opened before a dialog appears still learns about it (`windowsChanged`). */
@ExtendWith(ApplicationExtension::class)
class WindowsWatcherFxTest {
    companion object {
        private val events = CopyOnWriteArrayList<ObjectNode>()
        private val watcher = WindowsWatcher { _, p -> events.add(p) }
    }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(VBox(Button("OK")), 300.0, 200.0)
        stage.show()
        watcher.install()
        WaitForAsyncUtils.waitForFxEvents()
        events.clear()
    }

    private fun titles(): List<String> = events.last().path("windows").map { it.path("title").asText("") }

    private fun until(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met; events=$events")
            WaitForAsyncUtils.waitForFxEvents()
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    @Test
    fun `a dialog that opens later is emitted and leaves the list when it closes`() {
        val shown = arrayOfNulls<Stage>(1)
        try {
            Platform.runLater {
                val d = Stage()
                d.title = "Late Dialog"
                d.scene = Scene(DialogPane())
                d.show()
                shown[0] = d
            }
            until { events.isNotEmpty() && titles().contains("Late Dialog") }
            assertThat(events.last().path("windows").first { it.path("title").asText() == "Late Dialog" }.path("type").asText())
                .isEqualTo("DIALOG")

            Platform.runLater { shown[0]!!.hide() }
            until { events.isNotEmpty() && !titles().contains("Late Dialog") }
        } finally {
            Platform.runLater { shown[0]?.hide() }
        }
    }

    @Test
    fun `emitNow lists the showing windows`() {
        events.clear()
        Platform.runLater { watcher.emitNow() }
        until { events.isNotEmpty() }
        assertThat(events).hasSize(1)
        val windows = events[0].path("windows")
        assertThat(windows.size()).isEqualTo(1)
        assertThat(windows.first().path("type").asText()).isEqualTo("STAGE")
    }

    @Test
    fun `agent overlays never emit`() {
        events.clear()
        Platform.runLater {
            val s = Stage()
            Overlays.mark(s)
            s.scene = Scene(VBox())
            s.show()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertThat(events).isEmpty()
    }
}

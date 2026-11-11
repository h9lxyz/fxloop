package org.lab59.fxloop.agent

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

    private fun app(): Stage = Windows.showing().first() as Stage

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
    fun `a title change is emitted under the same id`() {
        val before = Fx.call { Windows.id(app()) }
        Platform.runLater { app().title = "Renamed" }
        until { events.isNotEmpty() && titles().contains("Renamed") }
        assertThat(events.last().path("windows").first().path("id").asInt()).isEqualTo(before)
    }

    @Test
    fun `a window keeps its id when an earlier one closes and its index shifts`() {
        val first = arrayOfNulls<Stage>(1)
        val second = arrayOfNulls<Stage>(1)
        try {
            Fx.call {
                first[0] = Stage().apply { scene = Scene(VBox()); show() }
                second[0] = Stage().apply { scene = Scene(VBox()); show() }
            }
            val (id, index) = Fx.call { Windows.id(second[0]!!) to Windows.indexOf(second[0]!!) }
            Fx.call { first[0]!!.hide() }
            val shifted = Fx.call { Windows.describeAll() }.first { it.path("id").asInt() == id }
            assertThat(shifted.path("index").asInt()).isEqualTo(index - 1)
        } finally {
            Fx.call { first[0]?.hide(); second[0]?.hide() }
        }
    }

    @Test
    fun `agent overlays never emit`() {
        events.clear()
        val shown = arrayOfNulls<Stage>(1)
        try {
            Platform.runLater {
                val s = Stage()
                Overlays.mark(s)
                s.scene = Scene(VBox())
                s.show()
                shown[0] = s
            }
            WaitForAsyncUtils.waitForFxEvents()
            assertThat(events).isEmpty()
        } finally {
            Platform.runLater { shown[0]?.hide() }
        }
    }
}

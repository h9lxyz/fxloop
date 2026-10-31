package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

@ExtendWith(ApplicationExtension::class)
class DevBarFxTest {
    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.get() ?: AgentRuntime.startForTest()
        }
    }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(VBox(Button("OK").apply { id = "ok" }), 400.0, 300.0)
        stage.show()
    }

    @AfterEach
    fun off() {
        fx { rt.bar.applyState(state("off")) }
    }

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        val r = Fx.call(work = work)
        WaitForAsyncUtils.waitForFxEvents()
        return r
    }

    private fun state(bar: String, control: String = "agent", build: ObjectNode.() -> Unit = {}): ObjectNode =
        Json.obj {
            put("bar", bar).put("mode", "headless (monocle)").put("control", control).put("driving", false).put("watch", "all")
            build()
        }

    @Test
    fun rendersWithoutTouchingTheApp() {
        val before = fx { Windows.showing().first().scene.root.layoutBounds }
        val d = fx {
            rt.bar.applyState(
                state("on") {
                    putArray("actionNeeded").addObject().put("kind", "restart").put("reason", "fields added in EditForm")
                        .put("next", "Restart").put("button", "restart")
                },
            )
        }
        assertThat(d.path("rendered").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(d.path("texts").path("actionNeeded")[0].asText()).isEqualTo("fields added in EditForm")
        assertThat(d.path("texts").path("session").asText()).isEqualTo("headless (monocle) · Agent mode · Saves apply CSS, FXML and code")
        val after = fx {
            assertThat(Windows.showing().none { Overlays.isOverlay(it) }).isTrue()
            assertThat(Window.getWindows().any { Overlays.isOverlay(it) }).isTrue()
            Windows.showing().first().scene.root.layoutBounds
        }
        assertThat(after).isEqualTo(before)
        val off = fx { rt.bar.applyState(state("off")) }
        assertThat(off.path("rendered").asInt()).isEqualTo(0)
        assertThat(fx { Window.getWindows().none { Overlays.isOverlay(it) } }).isTrue()
    }

    @Test
    fun standaloneBarShowsBeforeAnyServer() {
        val d = fx {
            rt.bar.standalone("on")
            rt.bar.describe()
        }
        assertThat(d.path("bar").asText()).isEqualTo("on")
        assertThat(d.path("rendered").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(d.path("texts").path("session").asText()).startsWith("FXLoop · no agent connected")
        // a server state replaces it
        val connected = fx { rt.bar.applyState(state("on", "shared")) }
        assertThat(connected.path("texts").path("session").asText()).isEqualTo("headless (monocle) · You can interact · Saves apply CSS, FXML and code")
    }

    @Test
    fun controlTextsAndHistory() {
        fx { rt.bar.applyState(state("on", "shared")) }
        rt.bar.agentInput(true)
        rt.bar.agentInput(false)
        val d = fx { rt.bar.describe() }
        val hist = d.path("controlHistory").map { it.asText() }
        assertThat(hist).contains("You can interact", "Agent is using your mouse")
        val driving = fx { rt.bar.applyState(state("on") { put("driving", true) }) }
        assertThat(driving.path("texts").path("session").asText()).contains("Agent is driving — hands off")
    }

    @Test
    fun lastReloadShowsOnNewSeq() {
        val d = fx {
            rt.bar.applyState(state("on") { putObject("lastReload").put("text", "CSS applied · 12 ms").put("ok", true).put("seq", 7) })
        }
        assertThat(d.path("texts").path("lastReload").asText()).isEqualTo("CSS applied · 12 ms")
    }
}

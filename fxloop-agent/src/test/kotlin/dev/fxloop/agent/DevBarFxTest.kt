package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
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
        assertThat(d.path("texts").path("session").asText()).isEqualTo("headless (monocle) · Agent mode · you can interact · Saves apply CSS, FXML and code")
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
    fun inspectorHelperStateReadsAsNoAgent() {
        val d = fx { rt.bar.applyState(state("on", "shared") { put("sidecar", true) }) }
        assertThat(d.path("rendered").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(d.path("texts").path("session").asText()).startsWith("FXLoop · no agent connected")
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

    @Test
    fun inputLockShowsTheDeveloperIsWatching() {
        val d = fx {
            rt.bar.applyState(state("on") {
                put("activity", "clicking Button#save")
                putObject("lock").put("remainingMs", 10_000).put("reason", "checkout flow")
            })
        }
        assertThat(d.path("locked").asBoolean()).isTrue()
        assertThat(rt.human.locked).isTrue()
        assertThat(d.path("texts").path("session").asText()).contains("Agent is driving — you're watching")
        assertThat(d.path("texts").path("activity").asText()).isEqualTo("clicking Button#save")
        // the next state without a lock gives the developer access again
        val after = fx { rt.bar.applyState(state("on")) }
        assertThat(after.path("locked").asBoolean()).isFalse()
        assertThat(rt.human.locked).isFalse()
        assertThat(after.path("texts").path("activity").asText()).isEmpty()
        assertThat(after.path("texts").path("session").asText()).contains("you can interact")
    }

    @Test
    fun setBarStateDoesNotWaitForABlockedFxThread() {
        // the agent serves requests on one worker: a push that waited here would hold back the tool call behind it
        val dispatcher = Dispatcher(rt)
        val release = CountDownLatch(1)
        Platform.runLater { release.await(10, TimeUnit.SECONDS) }
        try {
            val t0 = System.nanoTime()
            dispatcher.dispatch(Fcp.Method.SET_BAR_STATE, state("on") { put("activity", "clicking Button#ok") })
            val r = dispatcher.dispatch(Fcp.Method.SET_BAR_STATE, state("on") { put("activity", "reading the scene graph") })
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000)
            assertThat(r.path("queued").asBoolean()).isTrue()
        } finally {
            release.countDown()
        }
        // applied once the FX thread is free; pushes made while it was blocked coalesce to the latest
        assertThat(fx { rt.bar.describe() }.path("texts").path("activity").asText()).isEqualTo("reading the scene graph")
    }

    @Test
    fun disconnectReleasesTheLock() {
        fx { rt.bar.applyState(state("on") { putObject("lock").put("remainingMs", 10_000) }) }
        rt.onDisconnected()
        assertThat(rt.human.locked).isFalse()
    }
}

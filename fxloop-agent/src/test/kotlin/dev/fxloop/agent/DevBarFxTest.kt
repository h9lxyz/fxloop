package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
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
        fx {
            rt.bar.applyState(state("off"))
            if (rt.pick.active) rt.pick.toggle(null) // a press and release on the Pick button turned it on
        }
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

    private fun stage(): Stage = Windows.showing().first() as Stage

    /** The showing bar's root; laid out now rather than on the next pulse. */
    private fun bar(): HBox {
        val root = Window.getWindows().filter { Overlays.isOverlay(it) && it.isShowing }
            .firstNotNullOf { it.scene?.root?.lookup("#fxloop-bar") as HBox? }
        root.scene.root.applyCss()
        root.scene.root.layout()
        return root
    }

    private fun view(d: JsonNode): JsonNode = d.path("views")[0]

    private fun restartNeeded(): ObjectNode = state("on") {
        putArray("actionNeeded").addObject().put("kind", "restart").put("reason", "Restart needed · pom.xml changed")
            .put("next", "Restart").put("button", "restart")
    }

    private fun mouse(type: EventType<MouseEvent>, screenX: Double, screenY: Double) = MouseEvent(
        type, 0.0, 0.0, screenX, screenY, MouseButton.PRIMARY, 1, false, false, false, false,
        type != MouseEvent.MOUSE_RELEASED, false, false, false, false, true, null,
    )

    /** Presses on [target], drags by ([dx], [dy]) in steps, releases. */
    private fun drag(target: Node, dx: Double, dy: Double) = fx {
        val p = target.localToScreen(target.boundsInLocal)
        val x = p.minX + 3
        val y = p.minY + 3
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_PRESSED, x, y))
        for (i in 1..4) Event.fireEvent(target, mouse(MouseEvent.MOUSE_DRAGGED, x + dx * i / 4, y + dy * i / 4))
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_RELEASED, x + dx, y + dy))
    }

    @Test
    fun narrowWindowMovesButtonsIntoTheMenuInsteadOfSqueezingThem() {
        val width = fx { stage().width }
        try {
            fx { stage().width = 300.0 }
            val d = fx { rt.bar.applyState(restartNeeded()) }
            assertThat(view(d).path("pill").asBoolean()).isFalse()
            assertThat(view(d).path("overflow").map { it.asText() }).contains("Open CSS inspector", "Let me drive")
            fx {
                val bar = bar()
                assertThat(bar.width).isLessThanOrEqualTo(300.0)
                val shown = bar.lookupAll(".fxl-btn").filterIsInstance<Button>().filter { it.isManaged }
                assertThat(shown.map { it.text }).contains("Restart")
                assertThat(shown).anyMatch { "collapse" in it.styleClass }.anyMatch { "more" in it.styleClass }
                for (b in shown) assertThat(b.width).`as`(b.text).isGreaterThanOrEqualTo(b.prefWidth(-1.0) - 0.5)
                // the reason stays next to its button, truncated rather than gone
                val reason = bar.lookupAll(".fxl-text").filterIsInstance<Label>().single { it.text.startsWith("Restart needed") }
                assertThat(reason.isManaged).isTrue()
                assertThat(reason.width).isGreaterThanOrEqualTo(100.0)
            }
            // wider again: what fits comes back from the menu (well past the first width: fonts differ per OS)
            fx { stage().width = 900.0 }
            assertThat(view(fx { rt.bar.describe() }).path("overflow").map { it.asText() }).doesNotContain("Pick")
        } finally {
            fx { stage().width = width }
        }
    }

    @Test
    fun pillExpandsOverTheWindowWhereNoDockFits() {
        val y = fx { stage().y }
        try {
            // at the top of the screen, undecorated (Monocle): neither above the window nor over a title bar fits
            fx { stage().y = 0.0 }
            val d = fx { rt.bar.applyState(state("on")) }
            assertThat(view(d).path("pill").asBoolean()).isTrue()
            fx { (bar().lookup(".expand") as Button).fire() }
            val expanded = view(fx { rt.bar.describe() })
            assertThat(expanded.path("pill").asBoolean()).isFalse()
            assertThat(expanded.path("dock").asText()).isEqualTo("inside")
            // room above again: the bar docks there
            fx { stage().y = 200.0 }
            assertThat(view(fx { rt.bar.describe() }).path("dock").asText()).isEqualTo("above")
        } finally {
            fx { stage().y = y }
        }
    }

    @Test
    fun draggingTheBarMovesTheWindowLikeATitleBar() {
        fx { rt.bar.applyState(state("on")) }
        val x0 = fx { stage().x }
        val y0 = fx { stage().y }
        try {
            drag(fx { bar() }, 40.0, 30.0)
            assertThat(fx { stage().x }).isEqualTo(x0 + 40.0)
            assertThat(fx { stage().y }).isEqualTo(y0 + 30.0)
            assertThat(fx { bar().scene.window.y }).isEqualTo(y0 + 30.0 - 24.0)
            // a drag that starts on a button is the button's
            drag(fx { bar().lookupAll(".fxl-btn").filterIsInstance<Button>().first { it.text == "Pick" } }, 50.0, 50.0)
            assertThat(fx { stage().x }).isEqualTo(x0 + 40.0)
            // the agent's input lock keeps the window where the agent expects it
            fx { rt.bar.applyState(state("on") { putObject("lock").put("remainingMs", 10_000) }) }
            drag(fx { bar() }, 50.0, 50.0)
            assertThat(fx { stage().x }).isEqualTo(x0 + 40.0)
            // (double-click maximizes; not checked here: a window grown past its first size breaks Monocle's painter)
        } finally {
            fx {
                stage().x = x0
                stage().y = y0
            }
        }
    }

    @Test
    fun appStylesheetsDoNotRestyleTheBar() {
        val rules = ".root .button { -fx-padding: 30; } .root .label { -fx-font-size: 30px; }"
        val css = "data:text/css;base64," + java.util.Base64.getEncoder().encodeToString(rules.toByteArray())
        fx { stage().scene.stylesheets.add(css) }
        try {
            fx { rt.bar.applyState(state("on")) }
            fx {
                val bar = bar()
                assertThat(bar.height).isEqualTo(24.0)
                assertThat(bar.lookupAll(".fxl-btn").filter { it.isManaged }).allMatch { (it as Button).height <= 18.0 }
            }
        } finally {
            fx { stage().scene.stylesheets.remove(css) }
        }
    }

    @Test
    fun disconnectReleasesTheLock() {
        fx { rt.bar.applyState(state("on") { putObject("lock").put("remainingMs", 10_000) }) }
        rt.onDisconnected()
        assertThat(rt.human.locked).isFalse()
    }
}

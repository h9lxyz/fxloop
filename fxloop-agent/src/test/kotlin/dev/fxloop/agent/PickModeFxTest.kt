package dev.fxloop.agent

import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.concurrent.atomic.AtomicInteger
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

@ExtendWith(ApplicationExtension::class)
class PickModeFxTest {
    private val fired = AtomicInteger()
    private lateinit var box: VBox
    private lateinit var button: Button

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
        button = Button("Save").apply { id = "saveBtn"; setOnAction { fired.incrementAndGet() } }
        box = VBox(button)
        stage.scene = Scene(box, 300.0, 200.0)
        stage.show()
    }

    @AfterEach
    fun reset() {
        fx {
            if (rt.pick.active) rt.pick.toggle(null)
            rt.pick.clock = System::currentTimeMillis
        }
    }

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        val r = Fx.call(work = work)
        WaitForAsyncUtils.waitForFxEvents()
        return r
    }

    private fun mouse(type: EventType<MouseEvent>, down: Boolean) = MouseEvent(
        type, 5.0, 5.0, 5.0, 5.0, MouseButton.PRIMARY, 1, false, false, false, false,
        down, false, false, false, false, true, null,
    )

    private fun click(target: Node) = fx {
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_PRESSED, true))
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_RELEASED, false))
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_CLICKED, false))
    }

    private fun code(block: () -> Unit): String = try {
        block()
        ""
    } catch (e: FcpException) {
        e.code
    }

    @Test
    fun clickIsConsumedAndSelects() {
        fx { rt.pick.toggle(null) }
        assertThat(rt.pick.active).isTrue()
        click(button)
        assertThat(fired.get()).isEqualTo(0)
        assertThat(rt.pick.active).isFalse()
        assertThat(fx { Targets.resolve(Json.obj().put("picked", true)) }).isSameAs(button)
        assertThat(fx { Targets.hasTarget(Json.obj().put("picked", true)) }).isTrue()
    }

    @Test
    fun escapeLeaves() {
        fx { rt.pick.toggle(null) }
        fx { Event.fireEvent(box, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)) }
        assertThat(rt.pick.active).isFalse()
        assertThat(fx { Windows.showing().none { Overlays.isOverlay(it) } }).isTrue()
    }

    @Test
    fun nothingPickedAndStale() {
        assertThat(code { fx { PickMode(rt).picked() } }).isEqualTo(Fcp.Error.NODE_NOT_FOUND)

        fx { rt.pick.toggle(null) }
        click(button)
        val now = System.currentTimeMillis()
        rt.pick.clock = { now + 11 * 60_000 }
        assertThat(code { fx { rt.pick.picked() } }).isEqualTo(Fcp.Error.PICK_STALE)

        rt.pick.clock = System::currentTimeMillis
        fx { box.children.remove(button) }
        assertThat(code { fx { rt.pick.picked() } }).isEqualTo(Fcp.Error.PICK_STALE)
    }

    @Test
    fun getPickedDescribesTheNode() {
        fx { rt.pick.toggle(null) }
        click(button)
        val r = rt.pick.get(Json.obj())
        assertThat(r.path("target").path("fxId").asText()).isEqualTo("saveBtn")
        assertThat(r.path("explain").asText()).contains("Button#saveBtn")
        assertThat(r.path("pngB64").asText()).isNotEmpty()
        assertThat(r.path("details").path("styleClass").map { it.asText() }).contains("button")
        assertThat(rt.pick.set(Json.obj().put("on", true)).path("active").asBoolean()).isTrue()
        assertThat(rt.pick.set(Json.obj().put("on", false)).path("active").asBoolean()).isFalse()
    }
}

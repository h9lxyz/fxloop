package dev.fxloop.agent

import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Insets
import javafx.geometry.Point2D
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

@ExtendWith(ApplicationExtension::class)
class PickModeFxTest {
    private val fired = AtomicInteger()
    private val realPointer = NodeFrame.pointer
    private lateinit var stage: Stage
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
        this.stage = stage
        rt.human.install()
        button = Button("Save").apply { id = "saveBtn"; setOnAction { fired.incrementAndGet() } }
        box = VBox(button)
        stage.scene = Scene(box, 300.0, 200.0)
        stage.show()
    }

    /** The real pointer sits wherever the last test class left the stage, and a frame part under it stays away. */
    @BeforeEach
    fun pointerOffTheFrame() {
        NodeFrame.pointer = { Point2D(-1000.0, -1000.0) }
    }

    @AfterEach
    fun reset() {
        fx {
            if (rt.pick.active) rt.pick.toggle(null)
            rt.pick.release()
            Overlay.highlight(Json.obj().put("clear", true))
            NodeFrame.pointer = realPointer
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

    @Test
    fun outlineFollowsTheWindow() {
        fx { rt.pick.toggle(null) }
        fx { Event.fireEvent(button, mouse(MouseEvent.MOUSE_MOVED, false)) }
        assertThat(fx { rt.pick.outlineRects() }).isNotEmpty
        fx {
            stage.x += 100.0
            stage.y += 50.0
        }
        val rects = fx { rt.pick.outlineRects() }
        val b = fx { button.localToScreen(button.boundsInLocal)!! }
        val t = 2.0
        assertThat(rects.take(4)).containsExactlyElementsOf(
            listOf(
                Rectangle2D(b.minX - t, b.minY - t, b.width + 2 * t, t),
                Rectangle2D(b.minX - t, b.maxY, b.width + 2 * t, t),
                Rectangle2D(b.minX - t, b.minY, t, b.height),
                Rectangle2D(b.maxX, b.minY, t, b.height),
            ),
        )
        // the label sits just above or below the node, never over it
        assertThat(rects[4].minX).isEqualTo(b.minX - t)
        assertThat(rects[4].maxY <= b.minY || rects[4].minY >= b.maxY).isTrue()
        fx { rt.pick.toggle(null) }
        assertThat(fx { rt.pick.outlineRects() }).isEmpty()
    }

    /** The top edge of the outline must sit on [n]'s current screen position. */
    private fun assertOutlineOn(n: Node) {
        val rects = fx { rt.pick.outlineRects() }
        val b = fx { n.localToScreen(n.boundsInLocal)!! }
        assertThat(rects).hasSize(5)
        assertThat(rects[0].minX).isEqualTo(b.minX - 2.0)
        assertThat(rects[0].minY).isEqualTo(b.minY - 2.0)
        assertThat(rects[0].width).isCloseTo(b.width + 4.0, within(1.0)) // popups take whole pixels
    }

    @Test
    fun pickStaysOutlinedAndMovesWithTheWindow() {
        fx { rt.pick.toggle(null) }
        click(button)
        assertThat(rt.pick.active).isFalse()
        assertOutlineOn(button)
        fx { stage.x += 40.0 }
        assertOutlineOn(button)
    }

    @Test
    fun outlineFollowsTheNodeInsideTheWindow() {
        fx { rt.pick.hold(button) }
        fx { box.padding = Insets(30.0) }
        assertOutlineOn(button)
        fx { button.prefWidth = 150.0 }
        assertOutlineOn(button)
    }

    @Test
    fun aPressInTheAppEndsTheSelection() {
        fx { rt.pick.toggle(null) }
        click(button)
        assertThat(fx { rt.pick.outlineRects() }).isNotEmpty
        click(box)
        assertThat(fx { rt.pick.outlineRects() }).isEmpty()
    }

    @Test
    fun anAgentClickKeepsTheSelection() {
        fx { rt.pick.hold(button) }
        fx { rt.human.asAgent { Event.fireEvent(box, mouse(MouseEvent.MOUSE_PRESSED, true)) } }
        assertOutlineOn(button)
    }

    @Test
    fun inspectorHoldOutlastsThePreviewAndTheNextHoldMovesIt() {
        val hold = { n: Node -> Overlay.highlight(Json.obj().put("path", NodePath.of(n)).put("hold", true)) }
        assertThat(fx { hold(button) }.path("shown").asBoolean()).isTrue()
        fx { Overlay.highlight(Json.obj().put("clear", true)) }
        assertOutlineOn(button)
        assertThat(fx { hold(box) }.path("shown").asBoolean()).isTrue()
        assertOutlineOn(box)
        fx { Overlay.highlight(Json.obj().put("release", true)) }
        assertThat(fx { rt.pick.outlineRects() }).isEmpty()
    }

    @Test
    fun aFramePartWaitsUntilThePointerIsOffIt() {
        val b = fx { button.localToScreen(button.boundsInLocal)!! }
        NodeFrame.pointer = { Point2D(b.minX + 1, b.minY - 1) } // on the top edge
        fx { rt.pick.hold(button) }
        assertThat(fx { rt.pick.outlineRects() }).hasSize(4)
        NodeFrame.pointer = { Point2D(0.0, 0.0) }
        WaitForAsyncUtils.sleep(NodeFrame.RETRY_MS.toLong() + 200, TimeUnit.MILLISECONDS)
        assertOutlineOn(button)
    }

    @Test
    fun thePreviewNeverLiesUnderThePointerAndLeavesTheHeldNodeAlone() {
        val preview = { n: Node -> Overlay.highlight(Json.obj().put("path", NodePath.of(n))) }
        val b = fx { box.localToScreen(box.boundsInLocal)!! }
        NodeFrame.pointer = { Point2D(b.minX + 10, b.minY + 10) } // inside the node, as over a browser lying on the app
        assertThat(fx { preview(box) }.path("shown").asBoolean()).isTrue()
        val shown = popups()
        assertThat(shown).isNotEmpty.noneMatch { it.contains(NodeFrame.pointer()) }

        fx { Overlay.highlight(Json.obj().put("path", NodePath.of(button)).put("hold", true)) }
        assertThat(fx { Overlay.isShown() }).isFalse() // a click on the row ends its preview
        val held = popups().size
        assertThat(fx { preview(button) }.path("shown").asBoolean()).isTrue()
        assertThat(fx { Overlay.isShown() }).isFalse()
        assertThat(popups()).hasSize(held)
        assertOutlineOn(button)
    }

    private fun popups() = fx {
        Window.getWindows().filter { Overlays.isOverlay(it) && it.isShowing }.map { Rectangle2D(it.x, it.y, it.width, it.height) }
    }

    @Test
    fun pickModeBorrowsTheOutlineAndEscapeGivesItBack() {
        val other = Button("Other")
        fx { box.children.add(other); box.applyCss(); box.layout() }
        fx { rt.pick.hold(button) }
        fx { rt.pick.toggle(null) }
        fx { Event.fireEvent(other, mouse(MouseEvent.MOUSE_MOVED, false)) }
        assertOutlineOn(other)
        fx { Event.fireEvent(box, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)) }
        assertOutlineOn(button)
    }

    @Test
    fun outlineWaitsOutARemovalAndAnUnrenderedHoldEndsIt() {
        fx { rt.pick.hold(button) }
        fx { box.children.remove(button) }
        assertThat(fx { rt.pick.outlineRects() }).isEmpty()
        fx { box.children.add(button) }
        assertOutlineOn(button)
        fx { button.isVisible = false }
        assertThat(fx { rt.pick.hold(button) }.path("shown").asBoolean()).isFalse()
        assertThat(fx { rt.pick.outlineRects() }).isEmpty()
        fx { button.isVisible = true }
    }
}

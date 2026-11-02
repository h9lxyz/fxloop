package dev.fxloop.agent

import dev.fxloop.protocol.Json
import javafx.css.PseudoClass
import javafx.scene.Scene
import javafx.scene.control.Button
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
class ForcedStatesFxTest {
    private lateinit var a: Button
    private lateinit var b: Button
    private val hover = PseudoClass.getPseudoClass("hover")

    companion object {
        @JvmStatic
        @BeforeAll
        fun runtime() {
            AgentRuntime.get() ?: AgentRuntime.startForTest()
        }
    }

    @Start
    fun start(stage: Stage) {
        a = Button("A").apply { id = "a"; setOnAction { } }
        b = Button("B").apply { id = "b" }
        stage.scene = Scene(VBox(a, b), 300.0, 200.0)
        stage.scene.stylesheets.add("data:text/css,%23a%7B-fx-background-color:%23dddddd;%7D%23a:hover%7B-fx-background-color:%23ff9900;%7D")
        stage.show()
    }

    @AfterEach
    fun reset() {
        fx { ForcedStates.clear(); Overlay.highlight(Json.obj().put("clear", true)) }
    }

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        val r = Fx.call(work = work)
        WaitForAsyncUtils.waitForFxEvents()
        return r
    }

    private fun force(btn: Button, vararg states: String) = fx {
        val p = Json.obj().put("path", NodePath.of(btn))
        val arr = p.putArray("states")
        states.forEach { arr.add(it) }
        ForcedStates.force(p)
    }

    @Test
    fun hoverSurvivesRemovalAndClearRestores() {
        val r = force(a, "hover")
        assertThat(r.path("forced").map { it.asText() }).containsExactly("hover")
        assertThat(r.path("pseudoClasses").any { it.path("name").asText() == "hover" && it.path("forced").asBoolean() }).isTrue()
        fx { a.pseudoClassStateChanged(hover, false) }
        WaitForAsyncUtils.waitForFxEvents()
        assertThat(fx { a.pseudoClassStates.contains(hover) }).isTrue()
        assertThat(fx { ForcedStates.forcedNames(a) }).containsExactly("hover")
        fx { ForcedStates.clear() }
        assertThat(fx { a.pseudoClassStates.contains(hover) }).isFalse()
        assertThat(fx { ForcedStates.describe() }).isNull()
    }

    @Test
    fun givesUpAfterRepeatedReversals() {
        force(a, "hover")
        repeat(4) {
            fx { a.pseudoClassStateChanged(hover, false) }
            WaitForAsyncUtils.waitForFxEvents()
        }
        assertThat(fx { ForcedStates.forcedNames(a) }).isEmpty()
        assertThat(fx { a.pseudoClassStates.contains(hover) }).isFalse()
    }

    @Test
    fun forcingAnotherNodeClearsTheFirst() {
        force(a, "hover", "armed")
        force(b, "focused")
        assertThat(fx { a.pseudoClassStates.contains(hover) }).isFalse()
        assertThat(fx { ForcedStates.forcedNames(a) }).isEmpty()
        assertThat(fx { ForcedStates.forcedNames(b) }).containsExactly("focused")
        assertThat(fx { ForcedStates.describe()!!.path("forced").size() }).isEqualTo(1)
    }

    @Test
    fun suspendedClearsThenRestores() {
        force(a, "hover")
        val inside = fx { ForcedStates.suspended { a.pseudoClassStates.contains(hover) } }
        assertThat(inside).isFalse()
        assertThat(fx { a.pseudoClassStates.contains(hover) }).isTrue()
        assertThat(fx { ForcedStates.forcedNames(a) }).containsExactly("hover")
    }

    @Test
    fun listsHandlersWithLambdaNames() {
        val r = fx { Handlers.describe(Json.obj().put("path", NodePath.of(a))) }
        val h = r.path("handlers")
        assertThat(h.size()).isEqualTo(1)
        assertThat(h[0].path("property").asText()).isEqualTo("onAction")
        assertThat(h[0].path("handler").asText()).startsWith("lambda in ").contains("ForcedStatesFxTest")
        assertThat(r.path("note").asText()).contains("addEventHandler")
    }

    @Test
    fun multiHighlightCountsShownAndStale() {
        val ha = fx { AgentRuntime.current().handles.handleOf(a) }
        val hb = fx { AgentRuntime.current().handles.handleOf(b) }
        val p = Json.obj().put("labels", true)
        p.putArray("handles").add(ha).add(hb).add(99999)
        val r = fx { Overlay.highlight(p) }
        assertThat(r.path("shown").asInt()).isEqualTo(2)
        assertThat(r.path("notShown").asInt()).isEqualTo(1)
        assertThat(fx { Overlay.isShown() }).isTrue()
        val single = fx { Overlay.highlight(Json.obj().put("path", NodePath.of(a))) }
        assertThat(single.path("shown").asBoolean()).isTrue()
    }

    @Test
    fun screenshotRendersForcedState() {
        force(a, "hover")
        val shot = Snapshots.screenshot(Json.obj().put("path", NodePath.of(a)).put("target", "node").put("settleMs", 0))
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(shot["pngB64"].asText())))
        val rgb = img.getRGB(img.width / 2, 2) and 0xffffff
        assertThat(Integer.toHexString(rgb)).isEqualTo("ff9900")
    }
}

package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.concurrent.atomic.AtomicInteger
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Control
import javafx.scene.control.Label
import javafx.scene.control.SkinBase
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/**
 * Ribbons keep every page in the scene, laid out under the active one, and hide inactive pages through a skin node.
 * Their controls must not look rendered: no stacked bounds, no highlight, no robot click on whatever is on top.
 */
@ExtendWith(ApplicationExtension::class)
class RibbonFxTest {
    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.startForTest()
        }
    }

    /** A custom ribbon: toggle-button headers over a stack of page bodies, inactive bodies hidden in the skin. */
    private class Ribbon(vararg val pages: Pair<String, Node>) : Control() {
        override fun createDefaultSkin() = object : SkinBase<Ribbon>(this) {
            init {
                val group = ToggleGroup()
                val bodies = pages.map { StackPane(it.second).apply { styleClass.add("ribbon-body") } }
                val headers = pages.mapIndexed { i, p ->
                    ToggleButton(p.first).apply {
                        toggleGroup = group
                        setOnAction { bodies.forEachIndexed { j, b -> b.isVisible = j == i } }
                    }
                }
                bodies.forEachIndexed { j, b -> b.isVisible = j == 0 }
                children.add(VBox(HBox(*headers.toTypedArray()), StackPane(*bodies.toTypedArray())))
            }
        }
    }

    private val homeFired = AtomicInteger()

    private fun page(prefix: String) = HBox(
        8.0,
        *(0 until 3).map { i ->
            Button("$prefix $i").apply {
                id = "${prefix}_$i"
                if (prefix == "home") setOnAction { homeFired.incrementAndGet() }
            }
        }.toTypedArray(),
    )

    @Start
    fun start(stage: Stage) {
        val tabs = TabPane(Tab("Home", page("home")), Tab("Insert", page("insert"))).apply { id = "tabRibbon" }
        val custom = Ribbon("Home" to page("chome"), "Insert" to page("cinsert")).apply { id = "customRibbon" }
        stage.scene = Scene(VBox(tabs, custom, Label("below")), 600.0, 400.0)
        stage.show()
    }

    private fun buttons(): Map<String, JsonNode> {
        WaitForAsyncUtils.waitForFxEvents()
        val g = Dispatcher(rt).dispatch(Fcp.Method.GET_SCENE_GRAPH, Json.obj().put("maxDepth", 40).put("maxNodes", 3000))
        val out = LinkedHashMap<String, JsonNode>()
        fun walk(n: JsonNode) {
            if (n.path("type").asText() == "Button") out[n.path("fxId").asText()] = n
            n.path("children").forEach { walk(it) }
        }
        walk(g.path("root"))
        return out
    }

    @Test
    fun `inactive pages are not shown and carry no stacked bounds`() {
        val all = buttons()
        for (id in listOf("home_1", "chome_1")) {
            assertThat(all.getValue(id).has("shown")).isFalse()
            assertThat(all.getValue(id).has("boundsScreen")).isTrue()
        }
        for (id in listOf("insert_1", "cinsert_1")) {
            assertThat(all.getValue(id).path("shown").asBoolean(true)).isFalse()
            assertThat(all.getValue(id).has("boundsScreen")).isFalse()
        }
        val found = Fx.call { Finder.find(Json.obj().put("type", "Button").put("limit", 50)) }.path("items")
            .associateBy { it.path("fxId").asText() }
        assertThat(found.getValue("insert_1").path("visible").asBoolean()).isTrue()
        assertThat(found.getValue("insert_1").path("shown").asBoolean()).isFalse()
        assertThat(found.getValue("insert_1").has("boundsScreen")).isFalse()
        assertThat(found.getValue("home_1").path("shown").asBoolean()).isTrue()
    }

    @Test
    fun `highlight refuses an unrendered node and names what hides it`() {
        val r = Fx.call { Overlay.highlight(Json.obj().put("query", "#insert_1")) }
        assertThat(r.path("shown").asBoolean()).isFalse()
        assertThat(r.path("reason").asText()).contains("skin node", "TabPane#tabRibbon", "hidden")
        val custom = Fx.call { Overlay.highlight(Json.obj().put("query", "#cinsert_1")) }
        assertThat(custom.path("reason").asText()).contains("Ribbon#customRibbon")
        Fx.call { Overlay.highlight(Json.obj().put("clear", true)) }
    }

    @Test
    fun `robot click on a hidden ribbon page is refused instead of hitting the page on top`() {
        val before = homeFired.get()
        val e = runCatching { Interaction.click(Json.obj().put("query", "#cinsert_1").put("settleMs", 0)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(FcpException::class.java)
        assertThat((e as FcpException).code).isEqualTo(Fcp.Error.UNSUPPORTED_TARGET)
        assertThat(e.message).contains("not rendered")
        assertThat(homeFired.get()).isEqualTo(before)
    }

    @Test
    fun `lint reports the inactive pages as excluded`() {
        val lint = Fx.call { VisualLint.lint(Json.obj()) }
        val hidden = lint.path("excluded").filter { it.path("reason").asText() == "hidden" }.map { it.path("path").asText() }
        assertThat(hidden).anyMatch { it.endsWith("TabPane#tabRibbon/HBox[1]") }
        assertThat(hidden).anyMatch { it.contains("Ribbon#customRibbon/") }
    }
}

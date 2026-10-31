package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.util.concurrent.atomic.AtomicInteger
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** Exercises the agent logic directly (no sockets) against a small scene in headless Monocle. */
@ExtendWith(ApplicationExtension::class)
class AgentFxTest {
    private val fired = AtomicInteger()

    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.startForTest()
        }
    }

    @Start
    fun start(stage: Stage) {
        val name = TextField().apply { id = "name"; text = "Ada" }
        val ok = Button("OK").apply { id = "ok"; setOnAction { fired.incrementAndGet() } }
        val label = Label().apply { id = "lbl"; textProperty().bind(name.textProperty()) }
        val table = TableView(FXCollections.observableArrayList("a", "b", "c")).apply {
            id = "table"
            prefHeight = 150.0
            columns.add(TableColumn<String, String>("Letter").apply {
                setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value) }
            })
        }
        val deepBox = VBox().apply {
            repeat(30) { children.add(Label("row $it")) }
            children.add(Button("Deep").apply { id = "deep" })
        }
        val scroll = ScrollPane(deepBox).apply { id = "scroll"; prefHeight = 80.0 }
        val tabs = TabPane(
            Tab("One", Label("first")).apply { isClosable = false },
            Tab("Two", Label("second")).apply { isClosable = false },
        ).apply { id = "tabs"; prefHeight = 60.0 }
        val overlap = Pane(
            Region().apply { setPrefSize(50.0, 20.0) },
            Region().apply { setPrefSize(50.0, 20.0); translateX = 10.0 },
        ).apply { id = "overlapPane"; setPrefSize(80.0, 20.0) }
        val root = VBox(name, ok, label, table, scroll, tabs, overlap)
        stage.scene = Scene(root, 500.0, 700.0)
        stage.show()
    }

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        return Fx.call {
            val w = Windows.showing().first()
            w.scene.root.applyCss()
            w.scene.root.layout()
            work()
        }
    }

    private fun node(query: String): Node = fx { Targets.query(query, null).single() }

    private fun params(build: ObjectNode.() -> Unit): ObjectNode = Json.obj(build)

    private fun errorCode(block: () -> Unit): String {
        try {
            block()
        } catch (e: FcpException) {
            return e.code
        }
        error("expected FcpException")
    }

    @Test
    fun pathRoundTrip() {
        val ok = node("#ok")
        val path = fx { NodePath.of(ok) }
        assertThat(path).isEqualTo("Stage[0]/Scene/VBox/Button#ok")
        assertThat(fx { NodePath.resolve(path) }).isSameAs(ok)
        assertThat(fx { NodePath.resolve("Stage[0]/Scene//Button#ok") }).isSameAs(ok)
        assertThat(fx { NodePath.resolve("Stage[0]/Scene/VBox/Button[0]") }).isSameAs(ok)
        assertThat(fx { NodePath.resolve("Stage[0]/Scene/VBox/TextField[0]") }).isSameAs(node("#name"))
        val deep = node("#deep")
        assertThat(fx { NodePath.resolve(NodePath.of(deep)) }).isSameAs(deep)
    }

    @Test
    fun ambiguousAndMissingPaths() {
        assertThat(errorCode { fx { NodePath.resolve("Stage[0]/Scene//Button") } }).isEqualTo(Fcp.Error.AMBIGUOUS_PATH)
        assertThat(errorCode { fx { NodePath.resolve("Stage[0]/Scene/VBox/Button#nope") } }).isEqualTo(Fcp.Error.NODE_NOT_FOUND)
        assertThat(errorCode { fx { NodePath.resolve("garbage") } }).isEqualTo(Fcp.Error.BAD_REQUEST)
        assertThat(errorCode { fx { NodePath.resolve("Stage[5]/Scene/VBox") } }).isEqualTo(Fcp.Error.WINDOW_NOT_FOUND)
        // an index disambiguates a descendant search
        assertThat(fx { NodePath.resolve("Stage[0]/Scene//Button[1]") }).isSameAs(node("#deep"))
    }

    @Test
    fun logicalTreeExposesVisibleRowsOnly() {
        val table = node("#table")
        val rows = fx { LogicalTree.children(table) }
        assertThat(rows).hasSize(3)
        assertThat(rows).allMatch { it is TableRow<*> }
        // controls are leaves: no skin internals
        assertThat(fx { LogicalTree.children(node("#ok")) }).isEmpty()
        // a tab pane exposes its tab contents
        assertThat(fx { LogicalTree.children(node("#tabs")) }).hasSize(2)
    }

    @Test
    fun sceneSerializerHonoursBudgets() {
        val json = fx {
            val s = SceneSerializer(12, 3)
            val out = s.serialize(Windows.byIndex(null).scene.root)
            assertThat(s.truncated).isTrue()
            assertThat(s.emitted).isEqualTo(3)
            out
        }
        assertThat(json.toString()).contains("\"truncated\":true")
        val shallow = fx {
            val s = SceneSerializer(1, 400)
            s.serialize(Windows.byIndex(null).scene.root).also { assertThat(s.truncated).isTrue() }
        }
        assertThat(shallow.toString()).contains("\"truncated\":true")
        val full = fx {
            val s = SceneSerializer(12, 400)
            s.serialize(Windows.byIndex(null).scene.root).also { assertThat(s.truncated).isFalse() }
        }
        assertThat(full.toString()).contains("\"fxId\":\"ok\"")
    }

    @Test
    fun propsCoerceAndRejectBoundProperties() {
        val set = fx { Props.set(params { put("query", "#ok"); put("name", "prefWidth"); put("value", "123.5") }) }
        assertThat(set.path("valueAfter").asDouble()).isEqualTo(123.5)
        val got = fx { Props.get(params { put("query", "#ok"); put("name", "prefWidth") }) }
        assertThat(got.path("value").asDouble()).isEqualTo(123.5)
        assertThat(got.path("type").asText()).isEqualTo("double")
        fx { Props.set(params { put("query", "#ok"); put("name", "text"); put("value", "Go") }) }
        assertThat(fx { (node("#ok") as Button).text }).isEqualTo("Go")
        fx { Props.set(params { put("query", "#ok"); put("name", "text"); put("value", "OK") }) }
        assertThat(errorCode { fx { Props.set(params { put("query", "#ok"); put("name", "prefWidth"); put("value", "abc") }) } })
            .isEqualTo(Fcp.Error.COERCION_FAILED)
        assertThat(errorCode { fx { Props.set(params { put("query", "#ok"); put("name", "noSuchThing"); put("value", "1") }) } })
            .isEqualTo(Fcp.Error.PROPERTY_NOT_FOUND)
        assertThat(errorCode { fx { Props.set(params { put("query", "#lbl"); put("name", "text"); put("value", "x") }) } })
            .isEqualTo(Fcp.Error.PROPERTY_BOUND)
        assertThat(errorCode { fx { Interaction.setText(params { put("query", "#lbl"); put("text", "x") }) } })
            .isEqualTo(Fcp.Error.UNSUPPORTED_TARGET)
        val explained = fx { Introspect.explainProperty(params { put("query", "#lbl"); put("name", "text") }) }
        assertThat(explained.path("bound").asBoolean()).isTrue()
    }

    @Test
    fun `window prefix reads and resizes the stage`() {
        assertThat(errorCode { fx { Props.set(params { put("query", "#ok"); put("name", "width"); put("value", "400") }) } })
            .isEqualTo(Fcp.Error.UNSUPPORTED_TARGET)
        val set = fx { Props.set(params { put("query", "#ok"); put("name", "window.width"); put("value", "420") }) }
        assertThat(set.path("set").asBoolean()).isTrue()
        WaitForAsyncUtils.waitForFxEvents()
        val got = fx { Props.get(params { put("query", "#ok"); put("name", "window.width") }) }
        assertThat(got.path("name").asText()).isEqualTo("window.width")
        assertThat(got.path("value").asDouble()).isEqualTo(420.0)
        fx { Props.set(params { put("query", "#ok"); put("name", "window.width"); put("value", "500") }) }
    }

    @Test
    fun finderByTextAndType() {
        val byText = fx { Finder.find(params { put("text", "ok") }) }
        assertThat(byText.path("total").asInt()).isEqualTo(1)
        assertThat(byText.path("items").get(0).path("fxId").asText()).isEqualTo("ok")
        val byType = fx { Finder.find(params { put("type", "Button") }) }
        assertThat(byType.path("total").asInt()).isEqualTo(2)
        val byQuery = fx { Finder.find(params { put("query", "#deep") }) }
        assertThat(byQuery.path("items").get(0).path("type").asText()).isEqualTo("Button")
        assertThat(errorCode { fx { Finder.find(Json.obj()) } }).isEqualTo(Fcp.Error.BAD_REQUEST)
    }

    @Test
    fun visualLintFindsTranslatedSiblingOverlap() {
        val out = fx { VisualLint.lint(params { put("query", "#overlapPane"); putArray("checks").add("siblingOverlap") }) }
        val items = out.path("items").toList()
        assertThat(items).hasSize(1)
        assertThat(items[0].path("check").asText()).isEqualTo("siblingOverlap")
        assertThat(items[0].path("heuristic").asBoolean()).isTrue()
    }

    @Test
    fun contrastRatio() {
        val ratio = VisualLint.ratio(Color.web("#d0d0d0"), Color.web("#ffffff"))
        assertThat(ratio).isCloseTo(1.5, Offset.offset(0.1))
        assertThat(VisualLint.ratio(Color.BLACK, Color.WHITE)).isCloseTo(21.0, Offset.offset(0.01))
    }

    @Test
    fun stateSnapshotContainsPaths() {
        val snap = fx { StateSnapshot.capture(2000) }
        val nodes = snap.path("nodes")
        assertThat(nodes.has("Stage[0]/Scene/VBox/Button#ok")).isTrue()
        assertThat(nodes.path("Stage[0]/Scene/VBox/TextField#name").path("text").asText()).isEqualTo("Ada")
        assertThat(nodes.path("Stage[0]/Scene/VBox/Label#lbl").path("text").asText()).isEqualTo("Ada")
        assertThat(snap.path("overflow").asBoolean()).isFalse()
        val capped = fx { StateSnapshot.capture(2) }
        assertThat(capped.path("overflow").asBoolean()).isTrue()
    }

    @Test
    fun dispatcherRoundTrip() {
        val d = Dispatcher(rt)
        assertThat(d.dispatch(Fcp.Method.PING, null).has("pong")).isTrue()
        val found = d.dispatch(Fcp.Method.FIND_NODES, params { put("query", "#ok") })
        assertThat(found.path("total").asInt()).isEqualTo(1)
        val handle = found.path("items").get(0).path("h").asInt()
        assertThat(rt.handles.resolve(handle)).isSameAs(node("#ok"))
        val before = fired.get()
        val click = d.dispatch(Fcp.Method.CLICK, params { put("handle", handle); put("mode", "event"); put("settleMs", 0) })
        assertThat(click.path("clicked").asBoolean()).isTrue()
        assertThat(fired.get()).isEqualTo(before + 1)
        d.dispatch(Fcp.Method.SET_TEXT, params { put("query", "#name"); put("text", "Grace") })
        assertThat((node("#name") as TextField).text).isEqualTo("Grace")
        assertThat((node("#lbl") as Label).text).isEqualTo("Grace")
        fx { Props.set(params { put("query", "#name"); put("name", "text"); put("value", "Ada") }) }
        val graph = d.dispatch(Fcp.Method.GET_SCENE_GRAPH, params { put("maxDepth", 3) })
        assertThat(graph.path("root").path("type").asText()).isEqualTo("VBox")
        assertThat(graph.path("window").path("segment").asText()).isEqualTo("Stage[0]")
        val status = d.dispatch(Fcp.Method.STATUS, null)
        assertThat(status.path("mode").asText()).isEqualTo("test")
        val visible: JsonNode = d.dispatch(Fcp.Method.PATH_VISIBLE, params { put("path", "Stage[0]/Scene/VBox/Button#ok") })
        assertThat(visible.path("visible").asBoolean()).isTrue()
        assertThat(errorCode { d.dispatch("nope", null) }).isEqualTo(Fcp.Error.UNKNOWN_METHOD)
        assertThat(errorCode { d.dispatch(Fcp.Method.CLICK, params { put("path", "Stage[0]/Scene//Button"); put("mode", "event") }) })
            .isEqualTo(Fcp.Error.AMBIGUOUS_PATH)
    }

    @Test
    fun tabsAreSelectedWhenEnsuringVisibility() {
        val second = fx { (Targets.query("#tabs", null).single() as TabPane).tabs[1].content }
        fx { Interaction.ensureVisible(second) }
        assertThat(fx { (Targets.query("#tabs", null).single() as TabPane).selectionModel.selectedIndex }).isEqualTo(1)
        fx { (Targets.query("#tabs", null).single() as TabPane).selectionModel.select(0) }
    }

    private fun mouse(type: javafx.event.EventType<MouseEvent>, target: Node, button: MouseButton = MouseButton.PRIMARY): MouseEvent {
        val b = target.localToScreen(target.boundsInLocal)
        return MouseEvent(
            type, 5.0, 5.0, b.minX + 5, b.minY + 5, button, 1, false, false, false, false,
            button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY, false, false, true, PickResult(target, 5.0, 5.0),
        )
    }

    private fun clickOn(target: Node) {
        for (t in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            javafx.event.Event.fireEvent(target, mouse(t, target))
        }
    }

    private fun overlayPopups() = fx { javafx.stage.Window.getWindows().filter { it.properties[Overlay.OVERLAY_KEY] == true } }

    @Test
    fun highlightPopupIsInvisibleToTools() {
        val before = fx { Windows.showing().size }
        val r = fx { Overlay.highlight(Json.obj().put("path", NodePath.of(Targets.query("#ok", null).single()))) }
        WaitForAsyncUtils.waitForFxEvents()
        assertThat(r.path("shown").asBoolean()).isTrue()
        assertThat(r.path("bounds").path("w").asInt()).isGreaterThan(0)
        assertThat(fx { Overlay.isShown() }).isTrue()
        assertThat(overlayPopups()).isNotEmpty()
        assertThat(fx { Windows.showing().size }).isEqualTo(before)
        assertThat(fx { Windows.showing().none { it.properties[Overlay.OVERLAY_KEY] == true } }).isTrue()
        val cleared = fx { Overlay.highlight(Json.obj().put("clear", true)) }
        assertThat(cleared.path("shown").asBoolean()).isFalse()
        assertThat(fx { Overlay.isShown() }).isFalse()
    }

    @Test
    fun inspectorPickStartArmsPickModeAndConsumesTheClick() {
        val ok = node("#ok")
        val dispatcher = Dispatcher(rt)
        try {
            // control: unarmed, the same synthetic click reaches the button
            fx { clickOn(ok) }
            assertThat(fired.get()).isEqualTo(1)
            dispatcher.dispatch(Fcp.Method.PICK_START, Json.obj())
            assertThat(rt.pick.active).isTrue()
            fx { clickOn(ok) }
            WaitForAsyncUtils.waitForFxEvents()
            assertThat(fired.get()).isEqualTo(1)
            assertThat(rt.pick.active).isFalse()
            assertThat(fx { rt.pick.picked() }).isSameAs(ok)
            // cancel
            dispatcher.dispatch(Fcp.Method.PICK_START, Json.obj())
            dispatcher.dispatch(Fcp.Method.PICK_START, Json.obj().put("cancel", true))
            assertThat(rt.pick.active).isFalse()
        } finally {
            fx { if (rt.pick.active) rt.pick.toggle(null) }
        }
    }

    @Test
    fun skinFlagExposesInternals() {
        fun count(skin: Boolean): Int {
            val p = Json.obj().put("maxDepth", 30).put("maxNodes", 5000)
            if (skin) p.put("skin", true)
            return Dispatcher(rt).dispatch(Fcp.Method.GET_SCENE_GRAPH, p).path("nodeCount").asInt()
        }
        val plain = count(false)
        val skin = count(true)
        assertThat(skin).isGreaterThan(plain)
        val p = Json.obj().put("maxDepth", 30).put("maxNodes", 5000).put("skin", true)
        assertThat(Dispatcher(rt).dispatch(Fcp.Method.GET_SCENE_GRAPH, p).toString()).contains("\"skin\":true")
    }

    @Test
    fun errorRingDeduplicates() {
        val ring = ErrorRing(10)
        val ex = IllegalStateException("boom")
        ring.record(Thread.currentThread(), ex)
        ring.record(Thread.currentThread(), ex)
        val all = ring.since(0)
        assertThat(all.size()).isEqualTo(1)
        assertThat(all[0].path("dedupCount").asInt()).isEqualTo(2)
        assertThat(ring.since(1).size()).isEqualTo(0)
    }

    @Test
    fun optionsParsing() {
        val o = AgentOptions.parse("handshake=/tmp/h.json,token=abc,logs=true,fxmlHook=false")
        assertThat(o.handshake.toString()).isEqualTo("/tmp/h.json")
        assertThat(o.token).isEqualTo("abc")
        assertThat(o.captureLogs).isTrue()
        assertThat(o.fxmlHook).isFalse()
        assertThat(o.bar).isNull()
        assertThat(o.inspectorEdit).isFalse()
        assertThat(AgentOptions.parse("handshake=/x,inspectorEdit=true").inspectorEdit).isTrue()
        assertThat(AgentOptions.parse("handshake=/x,bar=ON").bar).isEqualTo("on")
        assertThat(AgentOptions.parse("handshake=/x,bar=pill").bar).isEqualTo("pill")
        assertThat(AgentOptions.parse("handshake=/x,bar=sideways").bar).isNull()
        val tf = java.nio.file.Files.createTempFile("fxloop-token", ".txt")
        java.nio.file.Files.writeString(tf, "  secret-token\n")
        assertThat(AgentOptions.parse("handshake=/x,tokenFile=$tf").token).isEqualTo("secret-token")
        java.nio.file.Files.delete(tf)
    }
}

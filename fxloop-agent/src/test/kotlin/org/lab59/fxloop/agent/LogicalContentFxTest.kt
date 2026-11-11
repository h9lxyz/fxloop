package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.Json
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TitledPane
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableColumn
import javafx.scene.control.TreeTableView
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** User content hosted by built-in controls (cell graphics, graphics, placeholders) is part of the logical tree. */
@ExtendWith(ApplicationExtension::class)
class LogicalContentFxTest {
    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.startForTest()
        }
    }

    /** Graphic-only custom cell: a clipped name label and an action button, no cell text. */
    private class ContactCell : ListCell<String>() {
        private val name = Label().apply { styleClass.add("contact-name"); prefWidth = 30.0; maxWidth = 30.0 }
        private val call = Button("Call").apply { styleClass.add("call-button") }
        private val box = HBox(4.0, name, call)

        override fun updateItem(item: String?, empty: Boolean) {
            super.updateItem(item, empty)
            text = null
            if (empty || item == null) {
                graphic = null
            } else {
                name.text = item
                graphic = box
            }
        }
    }

    @Start
    fun start(stage: Stage) {
        val contacts = ListView(FXCollections.observableArrayList((0 until 50).map { "Contact number $it" })).apply {
            id = "contacts"
            prefHeight = 120.0
            setCellFactory { ContactCell() }
        }
        val scores = TableView(FXCollections.observableArrayList("a", "b")).apply {
            id = "scores"
            prefHeight = 110.0
            columns.add(TableColumn<String, String>("Name").apply {
                graphic = CheckBox().apply { id = "selectAll" }
                setCellValueFactory { SimpleStringProperty(it.value) }
            })
            columns.add(TableColumn<String, String>("Action").apply {
                setCellValueFactory { SimpleStringProperty(it.value) }
                setCellFactory {
                    object : TableCell<String, String>() {
                        override fun updateItem(item: String?, empty: Boolean) {
                            super.updateItem(item, empty)
                            graphic = if (empty || item == null) null else Button("Edit $item")
                        }
                    }
                }
            })
        }
        val root = TreeItem("root").apply {
            isExpanded = true
            children.addAll(TreeItem("leaf one"), TreeItem("leaf two"))
        }
        val tree = TreeTableView(root).apply {
            id = "tree"
            prefHeight = 110.0
            columns.add(TreeTableColumn<String, String>("Item").apply {
                setCellValueFactory { SimpleStringProperty(it.value.value) }
            })
        }
        val titled = TitledPane("Section", Label("body")).apply {
            id = "titled"
            graphic = CheckBox("on").apply { id = "titledCheck" }
        }
        val iconButton = Button("Save", Label("*").apply { id = "star" }).apply { id = "iconButton" }
        val empty = ListView<String>().apply {
            id = "empty"
            prefHeight = 60.0
            placeholder = Button("Add first").apply { id = "addFirst" }
        }
        val tabs = TabPane(
            Tab("Inbox", Label("inbox body")).apply { isClosable = false; graphic = Label("3").apply { id = "badge" } },
            Tab("Archive", VBox(Label("old one"), Label("old two")).apply { id = "archive" }).apply { isClosable = false },
        ).apply { id = "tabs"; prefHeight = 60.0 }
        stage.scene = Scene(VBox(contacts, scores, tree, titled, iconButton, empty, tabs), 600.0, 900.0)
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

    private fun graph(build: ObjectNode.() -> Unit): JsonNode =
        Dispatcher(rt).dispatch(Fcp.Method.GET_SCENE_GRAPH, Json.obj { put("maxDepth", 64); put("maxNodes", 5000); build() })

    private fun walk(n: JsonNode, out: MutableList<JsonNode> = ArrayList()): List<JsonNode> {
        out.add(n)
        n.path("children").forEach { walk(it, out) }
        return out
    }

    @Test
    fun `custom cell graphics are logical children`() {
        val buttons = fx { Targets.query(".call-button", null) }
        assertThat(buttons).isNotEmpty.allMatch { it is Button }
        val first = buttons.first()
        val path = fx { NodePath.of(first) }!!
        assertThat(path).matches("Stage\\[0]/Scene/VBox/ListView#contacts/ContactCell\\[\\d+]/HBox\\[0]/Button\\[0]")
        assertThat(fx { NodePath.resolve(path) }).isSameAs(first)
        val found = fx { Finder.find(Json.obj().put("type", "Button").put("limit", 200)) }
        assertThat(found.path("items").map { it.path("path").asText() }).contains(path)
    }

    @Test
    fun `scene graph descends into cell graphics and names graphic-only cells`() {
        val list = graph { put("query", "#contacts") }.path("root")
        assertThat(list.path("visibleCells")[0].asText()).startsWith("Contact number 0")
        val cell = list.path("children")[0]
        assertThat(cell.path("children")[0].path("type").asText()).isEqualTo("HBox")
        assertThat(walk(cell).map { it.path("type").asText() }).contains("Button")
        assertThat(list.path("excluded").path("reason").asText()).isEqualTo("virtualized")
        assertThat(list.path("excluded").path("count").asInt()).isEqualTo(50 - list.path("kids").asInt())
    }

    @Test
    fun `tree table rows expose their cells`() {
        val tree = graph { put("query", "#tree") }.path("root")
        val row = tree.path("children")[0]
        assertThat(row.path("type").asText()).isEqualTo("TreeTableRow")
        assertThat(row.path("children")[0].path("type").asText()).isEqualTo("TreeTableCell")
    }

    @Test
    fun `skin traversal reaches cell internals and keeps logical paths`() {
        val list = graph { put("query", "#contacts"); put("skin", true) }.path("root")
        val nodes = walk(list)
        // rendered cells are logical; VirtualFlow's spare (empty, recycled) cells are skin
        val cells = nodes.filter { it.path("type").asText() == "ContactCell" && !it.has("skin") }
        assertThat(cells).hasSize(graph { put("query", "#contacts") }.path("root").path("kids").asInt())
        assertThat(cells).allMatch { it.path("path").asText().matches(Regex(".*/ListView#contacts/ContactCell\\[\\d+]")) }
        assertThat(walk(cells[0]).map { it.path("type").asText() }).contains("HBox", "Button")
        // the VirtualFlow plumbing is skin and carries no (unresolvable) path
        val flow = nodes.first { it.path("type").asText() == "VirtualFlow" }
        assertThat(flow.path("skin").asBoolean()).isTrue()
        assertThat(flow.has("path")).isFalse()
        for (n in nodes.filter { it.has("path") }) {
            assertThat(fx { NodePath.of(NodePath.resolve(n.path("path").asText())) }).isEqualTo(n.path("path").asText())
        }
        assertThat(list.path("visibleCells")[0].asText()).startsWith("Contact number 0")
    }

    @Test
    fun `graphics, column graphics, tab graphics and placeholders are logical`() {
        assertThat(fx { NodePath.of(node("#titledCheck")) }).isEqualTo("Stage[0]/Scene/VBox/TitledPane#titled/CheckBox#titledCheck")
        assertThat(fx { LogicalTree.children(node("#titled")).map { NodePath.typeName(it) } }).containsExactly("Label", "CheckBox")
        assertThat(fx { NodePath.of(node("#star")) }).isEqualTo("Stage[0]/Scene/VBox/Button#iconButton/Label#star")
        assertThat(fx { NodePath.of(node("#selectAll")) }).isEqualTo("Stage[0]/Scene/VBox/TableView#scores/CheckBox#selectAll")
        assertThat(fx { NodePath.of(node("#badge")) }).isEqualTo("Stage[0]/Scene/VBox/TabPane#tabs/Label#badge")
        assertThat(fx { NodePath.of(node("#addFirst")) }).isEqualTo("Stage[0]/Scene/VBox/ListView#empty/Button#addFirst")
        val edit = fx { Targets.query(".table-cell .button", null) }
        assertThat(edit.map { (it as Button).text }).contains("Edit a", "Edit b")
        // a placeholder that is not showing is not content
        fx { (node("#empty") as ListView<String>).items.add("now filled") }
        assertThat(fx { LogicalTree.children(node("#empty")).map { NodePath.typeName(it) } }).containsExactly("ListCell")
        fx { (node("#empty") as ListView<String>).items.clear() }
    }

    @Test
    fun `visual lint checks cell content and reports what it skipped`() {
        val lint = fx { VisualLint.lint(Json.obj().put("query", "#contacts")) }
        val truncated = lint.path("items").filter { it.path("check").asText() == "textTruncated" }
        assertThat(truncated.map { it.path("path").asText() }).anyMatch { it.contains("/ContactCell[") && it.endsWith("/Label[0]") }
        assertThat(lint.path("checked").asInt()).isGreaterThan(1)
        val virtualized = lint.path("excluded").single { it.path("reason").asText() == "virtualized" }
        assertThat(virtualized.path("path").asText()).endsWith("ListView#contacts")
        assertThat(virtualized.path("count").asInt()).isBetween(1, 49)

        val tabs = fx { VisualLint.lint(Json.obj().put("query", "#tabs")) }
        val hidden = tabs.path("excluded").single { it.path("reason").asText() == "hidden" }
        assertThat(hidden.path("path").asText()).endsWith("TabPane#tabs/VBox#archive")
        assertThat(hidden.path("count").asInt()).isEqualTo(3)
    }
}

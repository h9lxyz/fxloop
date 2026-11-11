package org.lab59.fxloop.agent

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Accordion
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.DialogPane
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuButton
import javafx.scene.control.Pagination
import javafx.scene.control.RadioButton
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitMenuButton
import javafx.scene.control.SplitPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TitledPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToolBar
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableCell
import javafx.scene.control.TreeTableColumn
import javafx.scene.control.TreeTableView
import javafx.scene.control.TreeView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/**
 * Invariant sweep: every node the application created and the window displays, wherever a built-in control hosts
 * it, is reachable in the logical tree (and so by paths, search, lint and snapshots). User nodes carry `.u`.
 */
@ExtendWith(ApplicationExtension::class)
class HostedContentSweepFxTest {
    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.startForTest()
        }
    }

    private lateinit var root: Parent

    private fun <T : Node> u(n: T, name: String): T = n.apply { styleClass.add("u"); properties["sweep"] = name }

    private fun label(name: String) = u(Label(name), name)

    @Start
    fun start(stage: Stage) {
        val leaves = (0 until 3).map { "leaf $it" }
        val list = ListView(FXCollections.observableArrayList(leaves)).apply {
            prefHeight = 90.0
            setCellFactory {
                object : ListCell<String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        graphic = if (empty || item == null) null else u(HBox(label("list cell label $item")), "list cell box $item")
                    }
                }
            }
        }
        val emptyList = ListView<String>().apply { prefHeight = 50.0; placeholder = label("list placeholder") }
        val treeRoot = TreeItem("tree root", label("tree item graphic")).apply {
            isExpanded = true
            children.add(TreeItem("tree child"))
        }
        val tree = TreeView(treeRoot).apply { prefHeight = 80.0 }
        val customTree = TreeView(TreeItem("custom root")).apply {
            prefHeight = 40.0
            setCellFactory {
                object : TreeCell<String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        graphic = if (empty || item == null) null else label("tree cell $item")
                    }
                }
            }
        }
        val table = TableView(FXCollections.observableArrayList("r0")).apply {
            prefHeight = 90.0
            val nested = TableColumn<String, String>("Group").apply { graphic = label("parent column graphic") }
            nested.columns.add(TableColumn<String, String>("A").apply {
                graphic = label("column graphic")
                setCellValueFactory { SimpleStringProperty(it.value) }
                setCellFactory {
                    object : TableCell<String, String>() {
                        override fun updateItem(item: String?, empty: Boolean) {
                            super.updateItem(item, empty)
                            graphic = if (empty || item == null) null else label("table cell $item")
                        }
                    }
                }
            })
            columns.add(nested)
        }
        val emptyTable = TableView<String>().apply {
            prefHeight = 60.0
            columns.add(TableColumn<String, String>("X"))
            placeholder = label("table placeholder")
        }
        val treeTableRoot = TreeItem("tt root", label("tree table item graphic")).apply {
            isExpanded = true
            children.add(TreeItem("tt child"))
        }
        val treeTable = TreeTableView(treeTableRoot).apply {
            prefHeight = 90.0
            columns.add(TreeTableColumn<String, String>("Name").apply {
                graphic = label("tree table column graphic")
                setCellValueFactory { SimpleStringProperty(it.value.value) }
            })
            columns.add(TreeTableColumn<String, String>("Act").apply {
                setCellValueFactory { SimpleStringProperty(it.value.value) }
                setCellFactory {
                    object : TreeTableCell<String, String>() {
                        override fun updateItem(item: String?, empty: Boolean) {
                            super.updateItem(item, empty)
                            graphic = if (empty || item == null) null else label("tree table cell $item")
                        }
                    }
                }
            })
        }
        val labeled = FlowPane(
            Label("label", label("label graphic")),
            Button("button", label("button graphic")),
            ToggleButton("toggle", label("toggle graphic")),
            CheckBox("check").apply { graphic = label("check graphic") },
            RadioButton("radio").apply { graphic = label("radio graphic") },
            Hyperlink("link", label("hyperlink graphic")),
            MenuButton("menu", label("menu button graphic")),
            SplitMenuButton().apply { text = "split"; graphic = label("split menu button graphic") },
        )
        val tabs = TabPane(
            Tab("t0", label("tab content")).apply { graphic = label("tab graphic") },
            Tab("t1", label("hidden tab content")),
        ).apply { prefHeight = 60.0 }
        val titled = TitledPane("titled", label("titled content")).apply { graphic = label("titled graphic") }
        val accordion = Accordion(TitledPane("acc", label("accordion content")).apply { graphic = label("accordion pane graphic") })
            .apply { expandedPane = panes[0] }
        val scroll = ScrollPane(label("scroll content")).apply { prefHeight = 30.0 }
        val split = SplitPane(label("split left"), label("split right")).apply { prefHeight = 30.0 }
        val toolbar = ToolBar(label("toolbar item"))
        val buttonBar = ButtonBar().apply { buttons.add(u(Button("bb"), "button bar button")) }
        val pagination = Pagination(3).apply { setPageFactory { label("page $it") }; prefHeight = 80.0 }
        val combo = ComboBox(FXCollections.observableArrayList("c0")).apply {
            value = "c0"
            buttonCell = object : ListCell<String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    graphic = if (empty || item == null) null else label("combo button cell graphic")
                }
            }
        }
        val menuBar = MenuBar(Menu("m", label("menu graphic")))
        val dialog = DialogPane().apply {
            header = label("dialog header")
            content = label("dialog content")
            graphic = label("dialog graphic")
            buttonTypes.add(javafx.scene.control.ButtonType.OK)
        }
        root = javafx.scene.layout.VBox(
            list, emptyList, tree, customTree, table, emptyTable, treeTable, labeled, tabs, titled, accordion, scroll,
            split, toolbar, buttonBar, pagination, combo, menuBar, dialog,
        )
        stage.scene = Scene(ScrollPane(root).apply { isFitToWidth = true }, 700.0, 1000.0)
        stage.show()
    }

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        return Fx.call {
            root.scene.root.applyCss()
            root.scene.root.layout()
            work()
        }
    }

    private fun displayed(n: Node): Boolean {
        var c: Node? = n
        while (c != null) {
            if (!c.isVisible) return false
            c = c.parent
        }
        return n.scene != null
    }

    @Test
    fun `every displayed user node is logical`() {
        val missing = fx {
            root.lookupAll(".u").filter { displayed(it) && !LogicalTree.isLogical(it) }.map { it.properties["sweep"] }
        }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `every logical node is in the scene`() {
        val detached = fx { NodePath.descendantsInclusive(root.scene.root).filter { it.scene == null }.map { NodePath.typeName(it) } }
        assertThat(detached).isEmpty()
    }
}

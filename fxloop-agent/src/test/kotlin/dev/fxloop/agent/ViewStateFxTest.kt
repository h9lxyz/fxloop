package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.collections.FXCollections
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.CheckBox
import javafx.scene.control.ListView
import javafx.scene.control.PasswordField
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.TitledPane
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** [ViewState] capture/restore and `Reloads.reloadFxml` carry-over, headless. */
@ExtendWith(ApplicationExtension::class)
class ViewStateFxTest {
    private lateinit var oldTree: Pane
    private lateinit var freshTree: Pane
    private lateinit var holder: HBox

    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.startForTest()
        }
    }

    private fun tree(): Pane {
        val name = TextField().apply { id = "name" }
        val pw = PasswordField().apply { id = "pw" }
        val list = ListView(FXCollections.observableArrayList((0 until 60).map { "item $it" })).apply {
            id = "list"
            prefHeight = 100.0
        }
        val table = TableView(FXCollections.observableArrayList((0 until 10).map { "r$it" })).apply {
            id = "table"
            prefHeight = 100.0
            columns.add(TableColumn<String, String>("C").apply {
                setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value) }
            })
        }
        val tabs = TabPane(
            Tab("One", VBox(CheckBox("deep").apply { id = "deepBox" })).apply { id = "t1"; isClosable = false },
            Tab("Two", javafx.scene.control.Label("second")).apply { id = "t2"; isClosable = false },
        ).apply { id = "tabs"; prefHeight = 60.0 }
        val scroll = ScrollPane(VBox().apply {
            repeat(40) { children.add(javafx.scene.control.Label("row $it")) }
        }).apply { id = "scroll"; prefHeight = 80.0 }
        val titled = TitledPane("T", javafx.scene.control.Label("body")).apply { id = "titled" }
        val check = CheckBox("c").apply { id = "check" }
        return VBox(name, pw, list, table, tabs, scroll, titled, check).apply { id = "tree" }
    }

    @Start
    fun start(stage: Stage) {
        oldTree = tree()
        freshTree = tree()
        holder = HBox(oldTree, freshTree)
        stage.scene = Scene(holder, 900.0, 1100.0)
        stage.show()
    }

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        return Fx.call {
            holder.scene.root.applyCss()
            holder.scene.root.layout()
            work()
        }
    }

    private fun <T : Node> Pane.byId(id: String): T {
        @Suppress("UNCHECKED_CAST")
        return lookup("#$id") as T
    }

    private fun skippedPaths(r: ObjectNode) = r.path("skipped").map { it.path("path").asText() + "|" + it.path("reason").asText() }

    @Test
    fun carriesViewStateOntoStructurallyIdenticalSubtree() {
        fx {
            oldTree.byId<TextField>("name").apply { text = "hello world"; selectRange(2, 5) }
            oldTree.byId<PasswordField>("pw").text = "secret"
            oldTree.byId<ListView<String>>("list").apply { selectionModel.select(3) }
            oldTree.byId<TableView<String>>("table").selectionModel.select(2)
            oldTree.byId<TabPane>("tabs").selectionModel.select(1)
            oldTree.byId<ScrollPane>("scroll").vvalue = 0.5
            oldTree.byId<TitledPane>("titled").isExpanded = false
            oldTree.byId<CheckBox>("check").isSelected = true
            oldTree.byId<TextField>("name").requestFocus()
        }
        fx {
            oldTree.byId<ListView<String>>("list").scrollTo(20)
        }
        val result = fx {
            // Layout must settle for the list's flow before capture.
            oldTree.byId<ListView<String>>("list").apply { applyCss(); layout() }
            val snap = ViewState.capture(oldTree)
            ViewState.restore(snap, freshTree)
        }
        fx { }
        fx {
            assertThat(result.path("restored").asInt()).isGreaterThanOrEqualTo(3)
            val f = freshTree
            val name = f.byId<TextField>("name")
            assertThat(name.text).isEqualTo("hello world")
            assertThat(name.anchor).isEqualTo(2)
            assertThat(name.caretPosition).isEqualTo(5)
            assertThat(f.byId<PasswordField>("pw").text).isEmpty()
            assertThat(skippedPaths(result)).anyMatch { it.contains("pw") && it.endsWith("|password") }
            assertThat(f.byId<ListView<String>>("list").selectionModel.selectedIndex).isEqualTo(3)
            assertThat(f.byId<TableView<String>>("table").selectionModel.selectedIndex).isEqualTo(2)
            assertThat(f.byId<TabPane>("tabs").selectionModel.selectedIndex).isEqualTo(1)
            assertThat(f.byId<ScrollPane>("scroll").vvalue).isEqualTo(0.5)
            assertThat(f.byId<TitledPane>("titled").isExpanded).isFalse()
            assertThat(f.byId<CheckBox>("check").isSelected).isTrue()
            assertThat(holder.scene.focusOwner).isSameAs(name)
            val flow = f.byId<ListView<String>>("list").lookupAll(".virtual-flow").first() as VirtualFlow<*>
            assertThat(flow.firstVisibleCell.index).isGreaterThan(0)
        }
    }

    @Test
    fun unselectedTabContentIsCovered() {
        fx { oldTree.byId<CheckBox>("deepBox").isSelected = true }
        val result = fx { ViewState.restore(ViewState.capture(oldTree), freshTree) }
        assertThat(result.path("restored").asInt()).isGreaterThanOrEqualTo(1)
        fx { assertThat(freshTree.byId<CheckBox>("deepBox").isSelected).isTrue() }
    }

    @Test
    fun typeMismatchOutOfBoundsAndMissingAreSkipped() {
        val old = fx {
            VBox(CheckBox("a").apply { id = "x"; isSelected = true }, javafx.scene.control.ComboBox<String>().apply { id = "gone"; items.add("a"); selectionModel.select(0) })
        }
        val fresh = fx {
            VBox(TextField().apply { id = "x" })
        }
        val result = fx { ViewState.restore(ViewState.capture(old), fresh) }
        assertThat(skippedPaths(result)).anyMatch { it.contains("x") && it.contains("type changed") }
        assertThat(skippedPaths(result)).anyMatch { it.contains("gone") && it.endsWith("|no matching node") }

        val bigList = fx { ListView(FXCollections.observableArrayList("a", "b", "c", "d", "e")).apply { selectionModel.select(4) } }
        val smallList = fx { ListView(FXCollections.observableArrayList("a")) }
        val r2 = fx { ViewState.restore(ViewState.capture(bigList), smallList) }
        assertThat(skippedPaths(r2)).anyMatch { it.endsWith("|out of bounds") }
        assertThat(smallList.selectionModel.selectedIndex).isEqualTo(-1)
    }

    @Test
    fun boundPropertyIsSkipped() {
        val old = fx { VBox(CheckBox("a").apply { id = "x"; isSelected = true }) }
        val other = javafx.beans.property.SimpleBooleanProperty(false)
        val fresh = fx { VBox(CheckBox("a").apply { id = "x"; selectedProperty().bind(other) }) }
        val result = fx { ViewState.restore(ViewState.capture(old), fresh) }
        assertThat(skippedPaths(result)).anyMatch { it.endsWith("|bound") }
    }

    @Test
    fun reloadFxmlCarriesStateAndCanOptOut() {
        val url = javaClass.getResource("/dev/fxloop/agent/viewstate.fxml")!!
        val first = fx {
            val loader = FXMLLoader(url)
            val root = loader.load<VBox>()
            (root.lookup("#who") as TextField).text = "kept"
            (root.lookup("#agree") as CheckBox).isSelected = true
            holder.children.setAll(root)
            rt.fxml.record(url, root, null, "test")
            root
        }
        val r = Reloads.reloadFxml(Json.obj().apply { putArray("files").add("viewstate.fxml") })
        assertThat(r.path("swapped")).hasSize(1)
        assertThat(r.path("stateCarried").path("restored").asInt()).isGreaterThanOrEqualTo(2)
        assertThat(r.path("swapped").get(0).path("stateCarried").path("restored").asInt()).isGreaterThanOrEqualTo(2)
        val now = fx { holder.children[0] as Pane }
        assertThat(now).isNotSameAs(first)
        assertThat((now.lookup("#who") as TextField).text).isEqualTo("kept")
        assertThat((now.lookup("#agree") as CheckBox).isSelected).isTrue()

        (now.lookup("#who") as TextField).let { fx { it.text = "again" } }
        val r2 = Reloads.reloadFxml(Json.obj().apply { putArray("files").add("viewstate.fxml"); put("carryState", false) })
        assertThat(r2.path("stateCarried").path("restored").asInt()).isEqualTo(0)
        val after = fx { holder.children[0] as Pane }
        assertThat((after.lookup("#who") as TextField).text).isEmpty()
    }
}

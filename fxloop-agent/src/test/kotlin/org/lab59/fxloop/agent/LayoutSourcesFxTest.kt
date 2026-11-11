package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** Constraint keys, `explainLayout` sources and the FXML edit plan (PRD 09 C), headless. */
@ExtendWith(ApplicationExtension::class)
class LayoutSourcesFxTest {
    private lateinit var fxmlRoot: HBox
    private lateinit var codeBox: HBox
    private lateinit var grid: GridPane
    private lateinit var anchor: AnchorPane
    private lateinit var file: Path

    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.startForTest()
        }

        const val FXML = """<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.layout.HBox?>

<HBox xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml" fx:id="rootBox">
    <children>
        <Button fx:id="save" text="Save" prefWidth="80.0"/>
        <Button text="Plain" />
    </children>
</HBox>
"""
    }

    @Start
    fun start(stage: Stage) {
        val dir = Files.createTempDirectory("fxml-layout")
        file = dir.resolve("view.fxml")
        Files.writeString(file, FXML)
        val loader = FXMLLoader(file.toUri().toURL())
        fxmlRoot = loader.load()
        rt.fxml.record(loader, fxmlRoot, "test")
        codeBox = HBox(Button("a").apply { id = "a" }, Button("b").apply { id = "b"; prefWidth = 50.0 })
        grid = GridPane().apply { add(Button("g").apply { id = "g" }, 0, 0) }
        anchor = AnchorPane(Button("n").apply { id = "n" })
        stage.scene = Scene(VBox(fxmlRoot, codeBox, grid, anchor), 400.0, 400.0)
        stage.show()
    }

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        return Fx.call {
            fxmlRoot.scene.root.applyCss()
            fxmlRoot.scene.root.layout()
            work()
        }
    }

    private fun byId(root: Pane, id: String): Node = root.lookup("#$id")

    private fun params(n: Node, name: String? = null, value: String? = null): ObjectNode {
        val o = Json.obj().put("path", NodePath.of(n))
        if (name != null) {
            o.put("name", name)
            if (value == null) o.putNull("value") else o.put("value", value)
        }
        return o
    }

    @Test
    fun constraintKeysSetAndClear() {
        fx {
            val a = byId(codeBox, "a")
            val r = Props.set(params(a, "HBox.hgrow", "always"))
            assertThat(HBox.getHgrow(a)).isEqualTo(Priority.ALWAYS)
            assertThat(r.path("valueBefore").isNull).isTrue()
            assertThat(r.path("valueAfter").asText()).isEqualTo("ALWAYS")
            assertThat(Props.get(params(a, "HBox.hgrow")).path("value").asText()).isEqualTo("ALWAYS")
            Props.set(params(a, "HBox.margin", "1 2 3 4"))
            assertThat(HBox.getMargin(a)!!.left).isEqualTo(4.0)
            Props.set(params(a, "HBox.hgrow", ""))
            Props.set(params(a, "HBox.margin", null))
            val hg: Priority? = HBox.getHgrow(a)
            assertThat(hg).isNull()
            assertThat(HBox.getMargin(a)).isNull()

            val g = byId(grid, "g")
            Props.set(params(g, "GridPane.columnIndex", "2"))
            Props.set(params(g, "GridPane.halignment", "center"))
            assertThat(GridPane.getColumnIndex(g)).isEqualTo(2)
            assertThat(GridPane.getHalignment(g)).isEqualTo(javafx.geometry.HPos.CENTER)
            Props.set(params(g, "GridPane.columnIndex", null))
            val cleared: Int? = GridPane.getColumnIndex(g)
            assertThat(cleared).isNull()

            val n = byId(anchor, "n")
            Props.set(params(n, "AnchorPane.topAnchor", "12"))
            assertThat(AnchorPane.getTopAnchor(n)).isEqualTo(12.0)
            Props.set(params(n, "AnchorPane.topAnchor", null))
            val top: Double? = AnchorPane.getTopAnchor(n)
            assertThat(top).isNull()
        }
    }

    @Test
    fun explainLayoutReportsSources() {
        fx {
            val save = byId(fxmlRoot, "save")
            val r = Introspect.explainLayout(params(save))
            assertThat(r.path("parentKind").asText()).isEqualTo("HBox")
            val pw = r.path("sizes2").path("prefWidth")
            assertThat(pw.path("value").asDouble()).isEqualTo(80.0)
            assertThat(pw.path("source").path("kind").asText()).isEqualTo("fxml")
            assertThat(pw.path("source").path("line").asInt()).isEqualTo(7)
            assertThat(r.path("sizes2").path("prefHeight").path("source").path("kind").asText()).isEqualTo("computed")
            assertThat(r.path("fxml").path("line").asInt()).isEqualTo(7)
            assertThat(r.path("fxml").path("fxId").asText()).isEqualTo("save")
            assertThat(r.path("fxml").path("element").asText()).isEqualTo("Button")
            assertThat(r.path("editable").map { it.path("key").asText() }).contains("HBox.hgrow", "HBox.margin", "prefWidth")
            assertThat(r.path("transforms").path("scaleX").asDouble()).isEqualTo(1.0)

            // a node without fx:id is located by position
            val plain = fxmlRoot.children[1]
            val r2 = Introspect.explainLayout(params(plain))
            assertThat(r2.path("fxml").path("line").asInt()).isEqualTo(8)

            val b = byId(codeBox, "b")
            val r3 = Introspect.explainLayout(params(b))
            assertThat(r3.path("sizes2").path("prefWidth").path("source").path("kind").asText()).isEqualTo("code")
            assertThat(r3.has("fxml")).isFalse()
        }
    }

    @Test
    fun planPatchesExistingAndAddsAttributes() {
        fx {
            val save = byId(fxmlRoot, "save")
            val plain = fxmlRoot.children[1]
            val p = Json.obj()
            val edits = p.putArray("edits")
            edits.add(params(save).put("property", "prefWidth").put("value", "-1"))
            edits.add(params(save).put("property", "HBox.hgrow").put("value", "always"))
            edits.add(params(plain).put("property", "HBox.hgrow").put("value", "ALWAYS"))
            edits.add(params(byId(codeBox, "a")).put("property", "prefWidth").put("value", "10"))
            edits.add(params(save).put("property", "HBox.margin").put("value", "4"))
            val r = FxmlEdit.plan(p)
            val f = r.path("files")[0]
            assertThat(f.path("absFile").asText()).isEqualTo(file.toString())
            assertThat(f.path("sha256").asText()).isEqualTo(org.lab59.fxloop.agent.css.ScratchApply.sha256(FXML))
            val text = f.path("text").asText()
            assertThat(text).contains("""<Button fx:id="save" text="Save" prefWidth="-1" HBox.hgrow="ALWAYS"/>""")
            assertThat(text).contains("""<Button text="Plain" HBox.hgrow="ALWAYS" />""")
            assertThat(f.path("changes").map { it.path("attribute").asText() }).containsExactlyInAnyOrder("prefWidth", "HBox.hgrow", "HBox.hgrow")
            val change = f.path("changes").first { it.path("attribute").asText() == "prefWidth" }
            assertThat(change.path("from").asText()).isEqualTo("80.0")
            assertThat(change.path("to").asText()).isEqualTo("-1")
            // code-built node and Insets go to code
            assertThat(r.path("code").map { it.path("property").asText() }).containsExactlyInAnyOrder("prefWidth", "HBox.margin")
        }
    }

    @Test
    fun planRemovesAttribute() {
        fx {
            val p = Json.obj()
            p.putArray("edits").add(params(byId(fxmlRoot, "save")).put("property", "prefWidth").putNull("value"))
            val text = FxmlEdit.plan(p).path("files")[0].path("text").asText()
            assertThat(text).contains("""<Button fx:id="save" text="Save"/>""")
        }
    }

    @Test
    fun planRefusesWhenDocumentMovedOn() {
        val original = Files.readString(file)
        try {
            Files.writeString(file, original.replace("""<Button fx:id="save" """, """<Label fx:id="save" """))
            fx {
                val p = Json.obj()
                p.putArray("edits").add(params(byId(fxmlRoot, "save")).put("property", "prefWidth").put("value", "5"))
                assertThatThrownBy { FxmlEdit.plan(p) }
                    .isInstanceOfSatisfying(FcpException::class.java) { assertThat(it.code).isEqualTo(Fcp.Error.BAD_REQUEST) }
            }
        } finally {
            Files.writeString(file, original)
        }
    }
}

package org.lab59.fxloop.server.lint

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

class FxmlLintTest {
    companion object {
        lateinit var classes: Path
        lateinit var proj: Path

        @TempDir
        @JvmStatic
        lateinit var tmp: Path

        private const val HEAD = "<?xml version=\"1.0\"?>\n" +
            "<?import javafx.scene.layout.VBox?>\n" +
            "<?import javafx.scene.control.*?>\n"

        @BeforeAll
        @JvmStatic
        fun compileStubs() {
            val src = tmp.resolve("src")
            classes = tmp.resolve("classes")
            proj = tmp.resolve("proj")
            Files.createDirectories(classes)
            Files.createDirectories(proj)
            val files = mapOf(
                "javafx/event/Event.java" to "package javafx.event; public class Event {}",
                "javafx/event/ActionEvent.java" to "package javafx.event; public class ActionEvent extends Event {}",
                "javafx/fxml/FXML.java" to "package javafx.fxml; import java.lang.annotation.*;" +
                    " @Retention(RetentionPolicy.RUNTIME) public @interface FXML {}",
                "javafx/scene/control/Label.java" to "package javafx.scene.control; public class Label {}",
                "javafx/scene/control/TextField.java" to "package javafx.scene.control; public class TextField {}",
                "javafx/scene/control/Button.java" to "package javafx.scene.control; public class Button {}",
                "javafx/scene/layout/VBox.java" to "package javafx.scene.layout; public class VBox {}",
                "demo/MainController.java" to "package demo; import javafx.fxml.FXML; import javafx.scene.control.*;" +
                    " public class MainController {" +
                    " @FXML private TextField nameField; @FXML private Label statusLabel;" +
                    " @FXML void addContact(){} @FXML void bad(String s){} }",
            )
            val jc = ToolProvider.getSystemJavaCompiler()
            val args = arrayListOf("-d", classes.toString())
            for ((k, v) in files) {
                val p = src.resolve(k)
                Files.createDirectories(p.parent)
                Files.writeString(p, v)
                args.add(p.toString())
            }
            assertThat(jc.run(null, null, null, *args.toTypedArray())).isZero()
        }

        private fun doc(children: String): String =
            HEAD + "<VBox xmlns:fx=\"http://javafx.com/fxml\" fx:controller=\"demo.MainController\">\n" +
                children + "\n</VBox>\n"
    }

    private fun lint(name: String, body: String): FxmlLint.Result {
        val f = proj.resolve(name)
        Files.writeString(f, body)
        return FxmlLint.lint(listOf(f), listOf(classes), proj)
    }

    @Test
    fun cleanFile() {
        val r = lint(
            "clean.fxml",
            doc(
                "<TextField fx:id=\"nameField\"/>\n<Label fx:id=\"statusLabel\"/>\n" +
                    "<Button onAction=\"#addContact\"/>",
            ),
        )
        assertThat(r.findings).isEmpty()
        assertThat(r.filesChecked).isEqualTo(1)
    }

    @Test
    fun renamedFxIdSuggestsField() {
        val r = lint("a.fxml", doc("<TextField fx:id=\"nameFeld\"/>"))
        assertThat(r.findings).hasSize(1)
        val f = r.findings[0]
        assertThat(f.check).isEqualTo("missingFxIdField")
        assertThat(f.suggestion).contains("nameField")
        assertThat(f.line).isEqualTo(5)
        assertThat(f.file).isEqualTo("a.fxml")
    }

    @Test
    fun typeMismatch() {
        val r = lint("b.fxml", doc("<Label fx:id=\"nameField\"/>"))
        assertThat(r.findings).extracting<String> { it.check }.containsExactly("typeMismatch")
        assertThat(r.findings[0].detail).contains("TextField").contains("Label")
    }

    @Test
    fun missingHandler() {
        val r = lint("c.fxml", doc("<Button onAction=\"#nope\"/>"))
        assertThat(r.findings).extracting<String> { it.check }.containsExactly("missingHandlerMethod")
        assertThat(r.findings[0].line).isEqualTo(5)
    }

    @Test
    fun invalidHandlerSignature() {
        val r = lint("d.fxml", doc("<Button onAction=\"#bad\"/>"))
        assertThat(r.findings).extracting<String> { it.check }.containsExactly("handlerSignatureInvalid")
    }

    @Test
    fun duplicateFxId() {
        val r = lint("e.fxml", doc("<TextField fx:id=\"nameField\"/>\n<TextField fx:id=\"nameField\"/>"))
        assertThat(r.findings).extracting<String> { it.check }.containsExactly("duplicateFxId")
        assertThat(r.findings[0].line).isEqualTo(6)
    }

    @Test
    fun unresolvedImport() {
        val r = lint(
            "f.fxml",
            "<?import does.not.Exist?>\n<?import javafx.scene.layout.VBox?>\n" +
                "<VBox xmlns:fx=\"http://javafx.com/fxml\"/>\n",
        )
        assertThat(r.findings).extracting<String> { it.check }.containsExactly("unresolvedImport")
        assertThat(r.findings[0].line).isEqualTo(1)
    }

    @Test
    fun unresolvedElementAndController() {
        val r = lint("g.fxml", "<VBox xmlns:fx=\"http://javafx.com/fxml\" fx:controller=\"no.Such\"><Ghost/></VBox>")
        assertThat(r.findings).extracting<String> { it.check }.containsOnly("unresolvedImport")
        assertThat(r.findings).hasSize(3)
    }

    @Test
    fun parseError() {
        val r = lint("h.fxml", "<VBox>\n<Label>\n</VBox>")
        assertThat(r.findings).hasSize(1)
        assertThat(r.findings[0].check).isEqualTo("parseError")
        assertThat(r.findings[0].line).isNotNull()
    }

    @Test
    fun findFxmlWalks() {
        Files.writeString(proj.resolve("z.fxml"), "<a/>")
        assertThat(FxmlLint.findFxml(listOf(proj))).anyMatch { it.endsWith("z.fxml") }
    }
}

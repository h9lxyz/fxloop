package dev.fxloop.server.export

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.server.loop.JournalEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import java.io.StringWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

class TestExporterTest {
    companion object {
        private val M = ObjectMapper()

        private fun o(json: String): ObjectNode = M.readTree(json) as ObjectNode

        fun w2(): List<JournalEntry> {
            val nameField = "Stage[0]/Scene/VBox/HBox[0]/TextField#nameField"
            return listOf(
                JournalEntry(
                    1, "fx_set_text", o("{\"path\":\"$nameField\",\"text\":\"Ada\",\"clearFirst\":true}"),
                    o("{\"path\":\"$nameField\",\"type\":\"TextField\",\"fxId\":\"nameField\"}"),
                    o(
                        "{\"added\":[],\"removed\":[],\"changed\":[{\"path\":\"$nameField" +
                            "\",\"prop\":\"text\",\"old\":\"\",\"new\":\"Ada\"}," +
                            "{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/Button#addBtn\",\"prop\":\"disabled\",\"old\":true,\"new\":false}]," +
                            "\"windowChanges\":[],\"overflow\":false}",
                    ),
                    "Stage:FXLoop Sample", 1,
                ),
                JournalEntry(
                    2, "fx_click_node", o("{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/ComboBox#categoryBox\",\"mode\":\"robot\"}"),
                    o("{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/ComboBox#categoryBox\",\"type\":\"ComboBox\",\"fxId\":\"categoryBox\"}"),
                    o(
                        "{\"added\":[{\"path\":\"Popup[0]/Scene/CSSBridge/ListView\",\"type\":\"ListView\"}],\"removed\":[]," +
                            "\"changed\":[{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/ComboBox#categoryBox\",\"prop\":\"focused\",\"old\":false,\"new\":true}]," +
                            "\"windowChanges\":[{\"change\":\"opened\",\"window\":\"Popup[0]\"}],\"overflow\":false}",
                    ),
                    "Stage:FXLoop Sample", 2,
                ),
                JournalEntry(
                    3, "fx_click_node", o("{\"path\":\"Popup[0]/Scene/CSSBridge/ListView/ListCell[1]\",\"button\":\"primary\"}"),
                    o("{\"path\":\"Popup[0]/Scene/CSSBridge/ListView/ListCell[1]\",\"type\":\"ListCell\",\"text\":\"Work\"}"),
                    o(
                        "{\"changed\":[{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/ComboBox#categoryBox\",\"prop\":\"value\",\"old\":null,\"new\":\"Work\"}]," +
                            "\"windowChanges\":[{\"change\":\"closed\",\"window\":\"Popup[0]\"}]}",
                    ),
                    "Stage:FXLoop Sample", 3,
                ),
                JournalEntry(
                    4, "fx_click_node", o("{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/Button#addBtn\"}"),
                    o("{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/Button#addBtn\",\"type\":\"Button\",\"fxId\":\"addBtn\",\"text\":\"Add\"}"),
                    o(
                        "{\"added\":[{\"path\":\"Stage[0]/Scene/VBox/TableView#contactTable/TableRow[0]\",\"type\":\"TableRow\",\"text\":\"Ada\"}]," +
                            "\"changed\":[{\"path\":\"Stage[0]/Scene/VBox/Label#statusLabel\",\"prop\":\"text\",\"old\":\"0 contacts\",\"new\":\"1 contacts\"}," +
                            "{\"path\":\"Stage[0]/Scene/VBox/TableView#contactTable\",\"prop\":\"itemCount\",\"old\":0,\"new\":1}," +
                            "{\"path\":\"Stage[0]/Scene/VBox/HBox[0]/TextField#nameField\",\"prop\":\"text\",\"old\":\"Ada\",\"new\":\"\"}]}",
                    ),
                    "Stage:FXLoop Sample", 4,
                ),
            )
        }

        private fun latest(dir: Path, artifact: String): Path {
            Assumptions.assumeTrue(Files.isDirectory(dir), "missing $dir")
            val v = Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList().lastOrNull() }
            Assumptions.assumeTrue(v != null)
            return Files.list(v!!).use { f ->
                f.filter {
                    it.fileName.toString().startsWith("$artifact-") && it.toString().endsWith(".jar") &&
                        !it.toString().contains("sources")
                }.findFirst().orElse(null)
            } ?: Assumptions.abort("no jar in $v")
        }

        private fun src(fqcn: String, code: String): JavaFileObject =
            object : SimpleJavaFileObject(URI.create("string:///" + fqcn.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                override fun getCharContent(ignore: Boolean): CharSequence = code
            }
    }

    @Test
    fun generatesExpectedCallsAndAssertions() {
        val ex = TestExporter.export("AddContact", "dev.fxloop.sample", "dev.fxloop.sample.ContactApp", w2())
        val s = ex.testSource
        assertThat(ex.className).isEqualTo("AddContactGeneratedTest")
        assertThat(ex.steps).isEqualTo(4)
        assertThat(s).startsWith("// Generated by fxloop from the session journal")
        assertThat(s).contains("package dev.fxloop.sample;")
            .contains("new dev.fxloop.sample.ContactApp().start(stage);")
            .contains("// step 1: fx_set_text")
            .contains("robot.interact(() -> robot.lookup(\"#nameField\").queryAs(TextInputControl.class).setText(\"Ada\"));")
            .contains("robot.clickOn(\"#categoryBox\");")
            .contains("robot.clickOn(\"Work\");")
            .contains("robot.clickOn(\"#addBtn\");")
            .contains("FxAssert.verifyThat(\"#nameField\", TextInputControlMatchers.hasText(\"Ada\"));")
            .contains("FxAssert.verifyThat(\"#addBtn\", NodeMatchers.isEnabled());")
            .contains("FxAssert.verifyThat(\"#categoryBox\", ComboBoxMatchers.hasSelectedItem(\"Work\"));")
            .contains("FxAssert.verifyThat(\"#statusLabel\", LabeledMatchers.hasText(\"1 contacts\"));")
            .contains("FxAssert.verifyThat(\"#contactTable\", TableViewMatchers.hasNumRows(1));")
            .contains("WaitForAsyncUtils.waitForFxEvents();")
            .doesNotContain("MouseButton")
            .doesNotContain("FxPath")
            .doesNotContain("verify added TableRow")
        assertThat(ex.todos).isZero()
        assertThat(ex.helperSource).contains("package dev.fxloop.sample;").contains("static Node lookup(FxRobot robot, String path)")
    }

    @Test
    fun exportsHumanEntriesAndSkipsRedactedOnes() {
        val entries = listOf(
            JournalEntry(
                1, "fx_set_text", o("{\"path\":\"Stage[0]/Scene/TextField#nameField\",\"text\":\"Bob\"}"),
                o("{\"path\":\"Stage[0]/Scene/TextField#nameField\",\"type\":\"TextField\",\"fxId\":\"nameField\"}"),
                null, "", 1, "human",
            ),
            JournalEntry(
                2, "fx_set_text", o("{\"path\":\"Stage[0]/Scene/PasswordField#pw\",\"text\":\"\"}"),
                o("{\"path\":\"Stage[0]/Scene/PasswordField#pw\",\"type\":\"PasswordField\",\"fxId\":\"pw\"}"),
                null, "", 2, "human", true,
            ),
        )
        val ex = TestExporter.export("H", "", "a.B", entries)
        assertThat(ex.testSource).contains("setText(\"Bob\")").contains("recorded from the developer's own input")
            .contains("// TODO(fxloop): step 2 edited a password field")
            .doesNotContain("#pw\"")
        assertThat(ex.todos).isEqualTo(1)
        assertThat(ex.steps).isEqualTo(2)
    }

    @Test
    fun fallsBackToFxPathAndEscapes() {
        val entries = listOf(
            JournalEntry(
                1, "fx_click_node", o("{\"path\":\"Stage[0]/Scene/VBox/Pane/Region[2]\",\"button\":\"secondary\",\"clickCount\":2}"),
                o("{\"path\":\"Stage[0]/Scene/VBox/Pane/Region[2]\",\"type\":\"Region\"}"), null, "", 1,
            ),
            JournalEntry(2, "fx_click_node", o("{\"x\":10,\"y\":20}"), null, null, "", 2),
            JournalEntry(
                3, "fx_set_text", o("{\"path\":\"Stage[0]/Scene/TextField#f\",\"text\":\"a\\\"b\\\\c\\n\",\"clearFirst\":false}"),
                null, null, "", 3,
            ),
            JournalEntry(
                4, "fx_type_keys", o("{\"keys\":[{\"code\":\"S\",\"modifiers\":[\"SHORTCUT\"]},{\"code\":\"ENTER\"}]}"),
                null, null, "", 4,
            ),
            JournalEntry(
                5, "fx_set_property", o("{\"path\":\"Stage[0]/Scene/CheckBox#c\",\"name\":\"selected\",\"value\":true}"),
                null, null, "", 5,
            ),
        )
        val ex = TestExporter.export("X", "", "a.B", entries)
        assertThat(ex.testSource)
            .contains("robot.doubleClickOn(FxPath.lookup(robot, \"Stage[0]/Scene/VBox/Pane/Region[2]\"), MouseButton.SECONDARY);")
            .contains("robot.clickOn(10, 20);")
            .contains("appendText(\"a\\\"b\\\\c\\n\")")
            .contains("robot.push(KeyCode.SHORTCUT, KeyCode.S);")
            .contains("robot.push(KeyCode.ENTER);")
            .doesNotContain("package ")
        assertThat(ex.todos).isEqualTo(3)
    }

    @Test
    fun generatedSourceCompiles() {
        val cp = ArrayList<Path>()
        val repo = Path.of(System.getProperty("user.home"), ".m2", "repository")
        val jars = listOf(
            "org/openjfx/javafx-base/21.0.13/javafx-base-21.0.13-linux.jar",
            "org/openjfx/javafx-graphics/21.0.13/javafx-graphics-21.0.13-linux.jar",
            "org/openjfx/javafx-controls/21.0.13/javafx-controls-21.0.13-linux.jar",
            "org/testfx/testfx-core/4.0.18/testfx-core-4.0.18.jar",
            "org/testfx/testfx-junit5/4.0.18/testfx-junit5-4.0.18.jar",
        )
        for (j in jars) {
            val p = repo.resolve(j)
            Assumptions.assumeTrue(Files.exists(p), "missing $p")
            cp.add(p)
        }
        cp.add(latest(repo.resolve("org/junit/jupiter/junit-jupiter-api"), "junit-jupiter-api"))
        cp.add(latest(repo.resolve("org/hamcrest/hamcrest"), "hamcrest"))
        cp.add(latest(repo.resolve("org/apiguardian/apiguardian-api"), "apiguardian-api"))
        cp.add(latest(repo.resolve("org/opentest4j/opentest4j"), "opentest4j"))
        cp.add(latest(repo.resolve("org/junit/platform/junit-platform-commons"), "junit-platform-commons"))
        // app class stub so the generated bootstrap resolves
        val stub = "package dev.fxloop.sample; public class ContactApp extends javafx.application.Application {" +
            " public void start(javafx.stage.Stage s) {} }"

        val ex = TestExporter.export("AddContact", "dev.fxloop.sample", "dev.fxloop.sample.ContactApp", w2())
        val jc = ToolProvider.getSystemJavaCompiler()
        Assumptions.assumeTrue(jc != null)
        val diags = DiagnosticCollector<JavaFileObject>()
        val out = Files.createTempDirectory("exporter-compile")
        val cps = cp.joinToString(File.pathSeparator) { it.toString() }
        val task = jc.getTask(
            StringWriter(), null, diags,
            listOf("-d", out.toString(), "-classpath", cps, "--release", "17", "-proc:none"),
            null,
            listOf(
                src("dev.fxloop.sample.ContactApp", stub),
                src("dev.fxloop.sample.AddContactGeneratedTest", ex.testSource),
                src("dev.fxloop.sample.FxPath", ex.helperSource),
            ),
        )
        val ok = task.call()
        val errors = diags.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
            .map { it.source.name + ":" + it.lineNumber + " " + it.getMessage(null) }
        assertThat(errors).isEmpty()
        assertThat(ok).isTrue()
    }
}

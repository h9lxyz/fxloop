package dev.fxloop.acceptance

import dev.fxloop.acceptance.Harness.Companion.CONTROLLER
import dev.fxloop.acceptance.Harness.Companion.CSS
import dev.fxloop.acceptance.Harness.Companion.FXML
import dev.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files

/** PRD 02 §12 workflows W1–W6 + PRD 04 A-V2/A-V3 + PRD 03 G4 export, on one evolving copy of the sample app. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MvpAcceptanceTest {
    private val h = Harness()

    @AfterAll
    fun close() = h.close()

    private fun tier(r: com.fasterxml.jackson.databind.JsonNode, name: String) = r["tiersApplied"].firstOrNull { it["tier"].asText() == name }

    @Test @Order(1)
    fun `W1 css loop keeps state, is fast, and shows a visual diff (A-V3)`() {
        h.ok("fx_get_app_status")
        h.ok("fx_set_text", args("query" to "#nameField", "text" to "typed"))
        h.edit(CSS) { it.replace("#2e7d32", "#c62828") }
        val r = h.ok("fx_reload")
        val css = tier(r, "css")!!
        assertThat(css["ms"].asLong()).isLessThan(300)
        assertThat(r["visualDiff"]["changedPct"].asDouble()).isGreaterThan(0.0)
        assertThat(Files.exists(h.project.resolve(r["visualDiff"]["diffPath"].asText()))).isTrue()
        assertThat(h.prop("#nameField", "text").asText()).isEqualTo("typed")
        val shot = h.raw("fx_get_screenshot", args("seq" to r["visualDiff"]["afterSeq"].asLong()))
        assertThat(shot.images).hasSize(1)
    }

    @Test @Order(2)
    fun `A-V2 a selector that matches nothing reports zero change with a hint`() {
        h.edit(CSS) { "$it\n#doesNotExist { -fx-background-color: hotpink; }\n" }
        val r = h.ok("fx_reload")
        assertThat(r["visualDiff"]["changedPct"].asDouble()).isLessThan(0.05) // ≈ 0: caret blink may remain
        assertThat(r["visualDiff"]["hint"].asText()).contains("selector")
    }

    @Test @Order(3)
    fun `W2 logic hot-swap, then effects show the new behaviour`() {
        h.edit(CONTROLLER) { it.replace("new Contact(nameField.getText(),", "new Contact(nameField.getText() + \"!\",") }
        val r = h.ok("fx_reload")
        val swap = tier(r, "hotswap")!!
        assertThat(swap["details"]["ok"].asBoolean()).isTrue()
        assertThat(swap["ms"].asLong()).isLessThan(2000)
        h.ok("fx_set_text", args("query" to "#nameField", "text" to "Ada"))
        val click = h.ok("fx_click_node", args("query" to "#addBtn"))
        val fx = click["effects"]
        assertThat(fx["added"].any { it.path("text").asText().contains("Ada!") }).isTrue()
        assertThat(fx["changed"].any { it["path"].asText().endsWith("#statusLabel") && it["new"].asText() == "1 contacts" }).isTrue()
    }

    @Test @Order(4)
    fun `W3 compile errors come back as data and nothing is swapped`() {
        h.edit(CONTROLLER) { it.replace("nameField.clear();", "nameField.clear()") }
        val r = h.ok("fx_reload")
        val err = r["compileErrors"][0]
        assertThat(err["file"].asText()).endsWith("MainController.java")
        assertThat(err["line"].asInt()).isGreaterThan(0)
        h.edit(CONTROLLER) { it.replace("nameField.clear()\n", "nameField.clear();\n") }
        assertThat(tier(h.ok("fx_reload"), "hotswap")!!["details"]["ok"].asBoolean()).isTrue()
    }

    @Test @Order(5)
    fun `W4 structural change restarts and replays the journal`() {
        assertThat(h.ok("fx_get_journal")["count"].asInt()).isGreaterThanOrEqualTo(3)
        h.edit(CONTROLLER) { it.replaceFirst("{", "{\n    private int fxloopAddedField = 42;") }
        val r = h.ok("fx_reload")
        assertThat(r["needsRestart"].asBoolean()).isTrue()
        assertThat(r["restarted"].asBoolean()).isTrue()
        // javac already wrote the class: the restart relaunches without a build-tool prepare
        assertThat(r["tiersApplied"].first { it["tier"].asText() == "restart" }["details"]["prepare"].asText()).isEqualTo("skipped")
        assertThat(r["replay"]["replayed"].asInt()).isGreaterThanOrEqualTo(3)
        assertThat(h.ok("fx_get_scene_graph", args("query" to "#contactTable"))["root"]["itemCount"].asInt()).isGreaterThanOrEqualTo(1)
    }

    @Test @Order(6)
    fun `W5 fxml edit swaps live`() {
        h.edit(FXML) { it.replace("Tip: names must not be empty", "Tip: edited live") }
        val r = h.ok("fx_reload")
        val fxml = tier(r, "fxml")!!
        assertThat(fxml["details"]["swapped"].size()).isEqualTo(1)
        assertThat(r.path("restarted").asBoolean(false)).isFalse()
        assertThat(h.prop("#hintLabel", "text").asText()).isEqualTo("Tip: edited live")
    }

    @Test @Order(7)
    fun `W6 lint catches an fx id without a field`() {
        h.edit(FXML) { it.replace("fx:id=\"nameField\"", "fx:id=\"nameFeld\"") }
        val r = h.ok("fx_lint_fxml", args("all" to true))
        val f = r["items"].first { it["check"].asText() == "missingFxIdField" }
        assertThat(f["suggestion"].asText()).contains("nameField")
        h.edit(FXML) { it.replace("fx:id=\"nameFeld\"", "fx:id=\"nameField\"") }
        h.ok("fx_reload")
    }

    @Test @Order(8)
    fun `G4 the journal exports as a compiling, passing TestFX test`() {
        val r = h.ok("fx_export_test", args("name" to "Acceptance"))
        assertThat(r["steps"].asInt()).isGreaterThan(0)
        val test = h.project.resolve(r["file"].asText())
        assertThat(test).exists()
        val res = h.ctx.session.build.run(listOf(h.repo.resolve("mvnw").toString(), "-q", "-B", "-f",
            h.project.resolve("pom.xml").toString(), "test", "-Dtest=AcceptanceGeneratedTest", "-Dsurefire.failIfNoSpecifiedTests=false"), 600)
        assertThat(res.exit).describedAs(res.tail(60)).isZero()
    }
}

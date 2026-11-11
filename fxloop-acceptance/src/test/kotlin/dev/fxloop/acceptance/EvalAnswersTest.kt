package dev.fxloop.acceptance

import dev.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Self-verification of the eval suites (mcp-builder guide): each expected answer in the evals XML files is re-derived here
 * purely through tool calls. Attach-mode questions are covered by [AttachAcceptanceTest].
 */
class EvalAnswersTest {
    private val repo = Path.of(System.getProperty("fxloop.repo") ?: "..").toAbsolutePath().normalize()

    private fun answers(file: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Files.newInputStream(repo.resolve("fxloop-acceptance/src/test/resources/evals/$file")))
        val nodes = doc.getElementsByTagName("answer")
        return (0 until nodes.length).map { nodes.item(it).textContent.trim() }
    }

    @Test
    fun `poc answers`() {
        val expected = answers("poc.xml")
        Harness(appArgs = listOf("--seed")).use { h ->
            val actual = ArrayList<String>()
            actual += h.prop("#statusLabel", "text").asText()
            h.ok("fx_set_text", args("query" to "#nameField", "text" to ""))
            actual += h.ok("fx_find_nodes", args("type" to "Button"))["items"].first { h.prop("#${it["fxId"].asText()}", "disabled").asBoolean() &&
                it["fxId"].asText() == "addBtn" }["fxId"].asText()
            h.ok("fx_click_node", args("query" to "#boomBtn"))
            Thread.sleep(300)
            actual += h.ok("fx_get_ui_errors")["errors"][0]["exceptionClass"].asText().substringAfterLast('.')
            h.ok("fx_click_node", args("query" to "#categoryBox"))
            actual += h.ok("fx_find_nodes", args("type" to "ListCell"))["items"].count { it["path"].asText().startsWith("Popup") }.toString()
            h.ok("fx_type_keys", args("keys" to listOf(mapOf("code" to "ESCAPE"))))
            actual += h.ok("fx_get_scene_graph", args("query" to "#contactTable"))["root"]["visibleCells"]
                .map { it.asText() }.first { it.endsWith("| Friends") }.substringBefore(" |")
            assertThat(actual).containsExactlyElementsOf(expected)
        }
    }

    @Test
    fun `mvp answers`() {
        val expected = answers("mvp.xml")
        val actual = ArrayList<String>()
        Harness().use { h ->
            val set = h.ok("fx_set_text", args("query" to "#nameField", "text" to "Ada"))
            val click = h.ok("fx_click_node", args("query" to "#addBtn"))
            actual += click["effects"]["changed"].first { it["path"].asText().endsWith("#statusLabel") }["new"].asText()
            actual += set["effects"]["changed"].first { it["path"].asText().endsWith("#addBtn") }["prop"].asText()
            actual += h.ok("fx_get_journal")["count"].asText()
            actual += h.ok("fx_get_journal")["entries"][0]["tool"].asText()
            actual += h.ok("fx_lint_fxml", args("all" to true))["total"].asText()
            actual += h.ok("fx_get_app_status")["fxml"][0]["controller"].asText()
            actual += h.ok("fx_find_nodes", args("query" to "#addBtn"))["items"][0]["path"].asText().substringAfterLast('/')
            actual += h.ok("fx_get_property", args("query" to "#statusLabel", "name" to "text"))["bound"].asText()
            actual += if (h.ok("fx_reload").has("note")) "note" else "?"
            actual += h.ok("fx_get_app_status")["windows"][0]["title"].asText()
        }
        Harness(appArgs = listOf("--seed", "--broken-css")).use { h ->
            val lint = h.ok("fx_lint_visual")["items"]
            actual += lint.first { it["check"].asText() == "siblingOverlap" }["path"].asText().substringAfterLast('#')
            actual += lint.first { it["check"].asText() == "lowContrast" }["path"].asText().substringAfterLast('#')
            actual += h.ok("fx_explain_style", args("query" to "#hintLabel", "property" to "-fx-text-fill"))["items"][0]["origin"].asText()
        }
        assertThat(actual).containsExactlyElementsOf(expected)
    }

    @Test
    fun `production answers (gauntlet and sample)`() {
        val expected = answers("production.xml")
        val actual = ArrayList<String>()
        Harness(app = "fxloop-gauntlet-app", webview = true).use { h ->
            h.ok("fx_click_node", args("query" to "#alertBtn"))
            actual += h.ok("fx_find_nodes", args("text" to "Hello from alert"))["items"].first { it["type"].asText() == "Label" }["text"].asText()
            h.ok("fx_type_keys", args("keys" to listOf(mapOf("code" to "ESCAPE"))))
            actual += h.ok("fx_get_scene_graph", args("query" to "#bigTable"))["root"]["itemCount"].asText()
            h.ok("fx_click_node", args("query" to "#deepBtn"))
            actual += h.prop("#deepResult", "text").asText()
            h.ok("fx_click_node", args("query" to "#web"))
            Thread.sleep(500)
            actual += h.ok("fx_eval_js", args("query" to "#web", "script" to "document.getElementById('title').textContent"))["result"].asText()
            h.ok("fx_click_node", args("query" to "#openStageBtn"))
            actual += h.ok("fx_get_app_status")["windows"].map { it.path("title").asText() }.first { it.contains("Second") }
            h.ok("fx_click_node", args("query" to "#lowContrastLabel"))
            actual += h.ok("fx_lint_visual", args("query" to "#tabs", "checks" to listOf("lowContrast")))["items"]
                .first { it["detail"].asText().startsWith("contrast") }["path"].asText().substringAfterLast('#')
        }
        Harness().use { h ->
            actual += h.ok("fx_explain_property", args("query" to "#addBtn", "name" to "disable"))["bound"].asText()
            h.ok("fx_set_text", args("query" to "#nameField", "text" to "Ada"))
            h.ok("fx_click_node", args("query" to "#addBtn"))
            actual += h.ok("fx_export_test", args("name" to "Eval", "write" to false))["className"].asText()
        }
        actual += listOf("attached", "NOT_SUPERVISED") // asserted live in AttachAcceptanceTest
        assertThat(actual).containsExactlyElementsOf(expected)
    }
}

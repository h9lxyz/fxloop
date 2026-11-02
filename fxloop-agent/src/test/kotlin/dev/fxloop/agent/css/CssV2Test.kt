package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.AgentRuntime
import dev.fxloop.agent.Fx
import dev.fxloop.agent.Introspect
import dev.fxloop.agent.Windows
import dev.fxloop.protocol.Json
import javafx.scene.Scene
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** Lookup chains, simulated states, lint and the sheets view (PRD 09 B), headless Monocle. */
@ExtendWith(ApplicationExtension::class)
class CssV2Test {
    companion object {
        @JvmStatic
        @BeforeAll
        fun runtime() {
            AgentRuntime.startForTest()
        }

        private fun res(name: String) = CssV2Test::class.java.getResource(name)!!.toExternalForm()
    }

    private lateinit var x: Pane

    @Start
    fun start(stage: Stage) {
        x = Pane().apply { id = "x"; styleClass += "x"; prefHeight = 10.0 }
        val parent = VBox(x).apply { styleClass += "parent" }
        val cy = Pane().apply { id = "cy"; styleClass += "cy"; styleClass += "cyc" }
        val b = Pane().apply { id = "b"; styleClass += "b" }
        val live = VBox().apply { id = "live"; styleClass += "live" }
        val sibling = VBox().apply { styleClass += "sibling" }
        val lint = VBox(live, sibling).apply { stylesheets += res("lint.css") }
        val root = VBox(parent, cy, b, lint)
        stage.scene = Scene(root, 300.0, 300.0).apply { stylesheets += res("chain.css") }
        stage.show()
    }

    private fun prep() {
        WaitForAsyncUtils.waitForFxEvents()
        Fx.call { Windows.showing().first().scene.root.applyCss() }
    }

    private fun query(q: String) = Json.obj { put("query", q) }

    private fun prop(q: String, property: String, states: List<String>? = null): ObjectNode {
        prep()
        val p = query(q).put("property", property)
        states?.let { p.set<JsonNode>("states", Json.strings(it)) }
        return Introspect.explainStyle(p)
    }

    @Test
    fun `lookup chain ends in the live background color`() {
        prep()
        val cascade = CssInspect.cascade(query("#x"))
        val bg = cascade["properties"].first { it["cssProperty"].asText() == "-fx-background-color" }
        val chain = bg["lookups"]
        assertThat(chain.map { it["name"].asText() }).containsExactly("-hover", "-fx-base")
        assertThat(chain.map { it["depth"].asInt() }).containsExactly(0, 1)
        assertThat(chain[1]["definedAt"]["selector"].asText()).isEqualTo(".root")
        assertThat(chain[1]["value"].asText()).isEqualTo("#3c3f41")
        val live = Fx.call { x.background.fills[0].fill as Color }
        assertThat(bg["lookupValue"].asText()).isEqualTo(ColorMath.hex(live))
        assertThat(chain[0]["resolved"].asBoolean()).isTrue()
        assertThat(cascade["pseudoClasses"].all { it.has("name") && it.has("forced") }).isTrue()
    }

    @Test
    fun `lookup cycle terminates and is flagged`() {
        val i = prop("#cy", "-fx-background-color")["items"][0]
        assertThat(i["lookups"].map { it["name"].asText() }).containsExactly("-a", "-b")
        assertThat(i["lookups"].any { it.path("cycle").asBoolean() }).isTrue()
        assertThat(i.has("lookupValue")).isFalse()
    }

    @Test
    fun `states param simulates hover without touching the node`() {
        assertThat(prop("#b", "-fx-background-color")["items"][0]["winner"]["selector"].asText()).isEqualTo(".b")
        val r = prop("#b", "-fx-background-color", listOf("hover"))
        val i = r["items"][0]
        assertThat(i["winner"]["selector"].asText()).isEqualTo(".b:hover")
        assertThat(i["simulation"].asText()).isEqualTo("assumed")
        assertThat(r["simulatedStates"].map { it.asText() }).containsExactly("hover")
        val live = Fx.call { Windows.showing().first().scene.root.lookup("#b")!!.pseudoClassStates.map { it.pseudoClassName } }
        assertThat(live).doesNotContain("hover")
    }

    @Test
    fun `lint reports dead rules, the misspelt class and the parse error`() {
        prep()
        val r = CssLint.lint(Json.obj())
        val sheet = r["sheets"].first { it["url"].asText().endsWith("lint.css") }
        assertThat(sheet["errors"].size()).isGreaterThan(0)
        val unused = r["unused"].filter { it["file"].asText().endsWith("lint.css") }.map { it["selector"].asText() }
        assertThat(unused).containsExactlyInAnyOrder("HBox.live", ".sibling .live")
        val unk = r["unknownSelectors"].first { it["file"].asText().endsWith("lint.css") }
        assertThat(unk["selector"].asText()).isEqualTo(".livv")
        assertThat(unk["unknown"].map { it.asText() }).containsExactly(".livv")
        assertThat(unk["line"].asInt()).isEqualTo(4)
        assertThat(r["unused"].none { it["selector"].asText().contains("livv") }).isTrue()
    }

    @Test
    fun `sheets lists in effective order and counts rule matches`() {
        prep()
        val urls = CssLint.sheets(Json.obj())["sheets"].map { it["url"].asText() }
        assertThat(urls.indexOfFirst { it.contains("modena") }).isLessThan(urls.indexOfFirst { it.endsWith("chain.css") })
        assertThat(urls.indexOfFirst { it.endsWith("chain.css") }).isLessThan(urls.indexOfFirst { it.endsWith("lint.css") })
        val one = CssLint.sheets(Json.obj { put("sheet", urls.first { it.endsWith("lint.css") }) })
        val rules = one["ruleList"]
        assertThat(rules[0]["selector"].asText()).isEqualTo(".live")
        assertThat(rules[0]["matches"].asInt()).isEqualTo(1)
        assertThat(rules[0]["handles"].size()).isEqualTo(1)
        assertThat(rules[1]["matches"].asInt()).isEqualTo(0)
        assertThat(rules[0]["line"].asInt()).isEqualTo(1)
    }
}

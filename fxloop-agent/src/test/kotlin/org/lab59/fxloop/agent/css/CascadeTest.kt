package org.lab59.fxloop.agent.css

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.agent.AgentRuntime
import org.lab59.fxloop.agent.Fx
import org.lab59.fxloop.agent.Introspect
import org.lab59.fxloop.agent.Windows
import org.lab59.fxloop.protocol.Json
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.text.Font
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** The cascade engine against a scene with one known conflict per row of PRD 08 §7 (headless Monocle). */
@ExtendWith(ApplicationExtension::class)
class CascadeTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun runtime() {
            AgentRuntime.startForTest()
        }
    }

    @Start
    fun start(stage: Stage) {
        val save = Button("Save").apply { id = "save"; styleClass += "primary" }
        val bar = HBox(save).apply { styleClass += "bar" }
        val accent = Label("accent").apply { id = "acc"; styleClass += "accented" }
        val panel = VBox(bar, accent).apply {
            styleClass += "panel"
            stylesheets += CascadeTest::class.java.getResource("panel.css")!!.toExternalForm()
        }
        val inlined = Label("inline").apply { id = "inlined"; style = "-fx-text-fill: blue;" }
        val important = Label("important").apply { id = "important"; style = "-fx-text-fill: blue;" }
        val bound = Label("bound").apply { id = "bound"; styleClass += "accented"; textFillProperty().bind(SimpleObjectProperty<Paint>(Color.PINK)) }
        val miss = Button("miss").apply { id = "miss" }
        val fonted = Label("font").apply { id = "fonted"; styleClass += "fonted" }
        val child = Label("child").apply { id = "child" }
        val big = VBox(child).apply { id = "big"; style = "-fx-font-size: 18px;" }
        val unresolved = Label("u").apply { id = "unresolved"; styleClass += "unresolved" }
        val box = VBox(Label("no id")).apply { id = "box" }
        val coded = Label("coded").apply { id = "coded"; font = Font.font(22.0) }
        val root = VBox(panel, inlined, important, bound, miss, fonted, big, unresolved, box, coded)
        stage.scene = Scene(root, 400.0, 500.0).apply {
            stylesheets += CascadeTest::class.java.getResource("cascade.css")!!.toExternalForm()
        }
        stage.show()
    }

    private fun call(f: (ObjectNode) -> ObjectNode, query: String, vararg kv: Pair<String, String>): ObjectNode {
        WaitForAsyncUtils.waitForFxEvents()
        Fx.call { Windows.showing().first().scene.root.applyCss() }
        return f(Json.obj { put("query", query); kv.forEach { (k, v) -> put(k, v) } })
    }

    private fun item(query: String, property: String): JsonNode =
        call(Introspect::explainStyle, query, "property" to property)["items"][0]

    @Test
    fun `more specific rule wins and the loser says why`() {
        val i = item("#save", "-fx-background-color")
        assertThat(i["winner"]["selector"].asText()).isEqualTo(".panel .bar .button")
        assertThat(i["winner"]["specificity"].asText()).isEqualTo("0,3,0")
        assertThat(i["winner"]["file"].asText()).endsWith("cascade.css")
        assertThat(i["winner"]["line"].asInt()).isEqualTo(9)
        assertThat(i["winner"]["text"].asText()).isEqualTo("-fx-background-color: #e8e8e8;")
        val primary = i["overridden"].first { it["selector"].asText() == ".primary" }
        assertThat(primary["reason"].asText()).isEqualTo("lower specificity (0,1,0 < 0,3,0)")
        assertThat(i["overridden"].any { it["origin"].asText() == "USER_AGENT" }).isTrue()
        assertThat(i["simulation"].asText()).isEqualTo("ok")
    }

    @Test
    fun `inline style beats the sheet and is reported as a blocker`() {
        val i = item("#inlined", "-fx-text-fill")
        assertThat(i["winner"]["origin"].asText()).isEqualTo("INLINE")
        assertThat(i["blockers"].map { it["kind"].asText() }).contains("inline-wins")
        assertThat(i["simulation"].asText()).isEqualTo("ok")
    }

    @Test
    fun `important beats inline`() {
        val i = item("#important", "-fx-text-fill")
        assertThat(i["winner"]["selector"].asText()).isEqualTo("#important")
        assertThat(i["winner"]["important"].asBoolean()).isTrue()
        assertThat(i["overridden"][0]["reason"].asText()).startsWith("overridden by !important")
    }

    @Test
    fun `bound property is a blocker and suggest refuses a selector`() {
        assertThat(item("#bound", "-fx-text-fill")["blockers"].map { it["kind"].asText() }).contains("bound")
        val s = call(CssInspect::suggest, "#bound", "property" to "-fx-text-fill")
        assertThat(s["selector"].isNull).isTrue()
    }

    @Test
    fun `rule missing a style class is a near miss`() {
        val r = call(Introspect::explainStyle, "#miss", "property" to "-fx-text-fill")
        val all = r["items"][0]["blockers"].toList() + r["blockers"].toList()
        assertThat(all.map { it["cause"].asText() }).anyMatch { it.contains("missing style class 'ghost'") }
    }

    @Test
    fun `skin-only property points at the skin node`() {
        val r = call(Introspect::explainStyle, "#save", "property" to "-fx-fill")
        val b = r["blockers"].first { it["kind"].asText() == "skin-node" }
        assertThat(b["selector"].asText()).isEqualTo("#save .text")
    }

    @Test
    fun `font size inherited from a parent inline style`() {
        val i = item("#child", "-fx-font-size")
        assertThat(i["inheritedFrom"]["path"].asText()).contains("big")
        assertThat(i["inheritedFrom"]["winner"]["origin"].asText()).isEqualTo("INLINE")
    }

    @Test
    fun `looked-up color resolves to the nearest ancestor definition`() {
        val i = item("#acc", "-fx-text-fill")
        val l = i["lookups"].first { it["name"].asText() == "-accent" }
        assertThat(l["definedAt"]["file"].asText()).endsWith("panel.css")
        assertThat(item("#unresolved", "-fx-text-fill")["blockers"].map { it["kind"].asText() }).contains("lookup-unresolved")
    }

    @Test
    fun `sub-property beats the shorthand declared before it`() {
        val i = item("#fonted", "-fx-font-size")
        assertThat(i["winner"]["text"].asText()).isEqualTo("-fx-font-size: 20px;")
        assertThat(i["overridden"].any { it.path("property").asText() == "-fx-font" }).isTrue()
    }

    @Test
    fun `parse errors surface as sheet blockers`() {
        val r = call(CssInspect::cascade, "#acc")
        val panel = r["sheets"].first { it["url"].asText().endsWith("panel.css") }
        assertThat(panel["parseErrors"].size()).isGreaterThan(0)
        assertThat(r["blockers"].map { it["kind"].asText() }).contains("sheet-error")
    }

    @Test
    fun `cascade lists rules most precedent first with declaration status`() {
        val r = call(CssInspect::cascade, "#save")
        val sels = r["rules"].map { it["selector"].asText() }
        assertThat(sels.indexOf(".panel .bar .button")).isLessThan(sels.indexOf(".primary"))
        val primary = r["rules"].first { it["selector"].asText() == ".primary" }
        assertThat(primary["declarations"][0]["status"].asText()).isEqualTo("overridden")
        assertThat(r["properties"].filter { it["simulation"].asText() == "mismatch" }.map { it["cssProperty"].asText() }).isEmpty()
    }

    @Test
    fun `suggest override beats the winner and is verified`() {
        val s = call(CssInspect::suggest, "#save", "property" to "-fx-background-color", "value" to "red")
        assertThat(s["selector"].asText()).isEqualTo(".panel .bar .button.primary")
        assertThat(s["needsImportant"].asBoolean()).isFalse()
        assertThat(s["why"].asText()).contains("0,4,0 > 0,3,0")
        val inline = call(CssInspect::suggest, "#inlined", "property" to "-fx-text-fill")
        assertThat(inline["needsImportant"].asBoolean()).isTrue()
    }

    @Test
    fun `rules false restores origin-only output`() {
        val r = call(Introspect::explainStyle, "#save", "rules" to "false")
        assertThat(r.has("note")).isTrue()
        assertThat(r["items"].none { it.has("winner") }).isTrue()
    }

    @Test
    fun `cascade lists properties nothing sets as defaults, the font with the system default`() {
        val props = call(CssInspect::cascade, "#box .label")["properties"].associateBy { it["cssProperty"].asText() }
        val size = props["-fx-font-size"]!!
        assertThat(size["default"].asBoolean()).isTrue()
        assertThat(size["css"].asText()).isEqualTo("${Font.getDefault().size.toInt()}px")
        assertThat(size["note"].asText()).contains("Font.getDefault()")
        assertThat(props["-fx-wrap-text"]!!["css"].asText()).isEqualTo("false")
        // a rule from modena is not a default
        assertThat(props["-fx-text-fill"]!!.has("default")).isFalse()
    }

    @Test
    fun `a value set in code is the code candidate with its CSS text`() {
        val props = call(CssInspect::cascade, "#coded")["properties"].associateBy { it["cssProperty"].asText() }
        val size = props["-fx-font-size"]!!
        assertThat(size["winner"]["ruleId"].asText()).isEqualTo("code")
        assertThat(size["css"].asText()).isEqualTo("22px")
        assertThat(props["-fx-font"]!!["css"].asText()).isEqualTo("22px \"${Font.getDefault().family}\"")
    }

    @Test
    fun `new rule scopes start at the narrowest selector for the node`() {
        val scopes = call(CssInspect::cascade, "#box .label")["scopes"].toList()
        assertThat(scopes[0]["selector"].asText()).isEqualTo("#box .label")
        assertThat(scopes[0]["matches"].asInt()).isEqualTo(1)
        val all = scopes.first { it["selector"].asText() == ".label" }
        assertThat(all["matches"].asInt()).isGreaterThan(5)
        assertThat(call(CssInspect::cascade, "#save")["scopes"][0]["selector"].asText()).isEqualTo("#save")

        val typed = call(CssInspect::selectorMatches, "#box .label", "selector" to ".panel .label")
        assertThat(typed["valid"].asBoolean()).isTrue()
        assertThat(typed["matchesTarget"].asBoolean()).isFalse()
        assertThat(call(CssInspect::selectorMatches, "#box .label", "selector" to "{")["valid"].asBoolean()).isFalse()
    }

    @Test
    fun `suggest scopes an override for a node without an id to its ancestors`() {
        val s = call(CssInspect::suggest, "#box .label", "property" to "-fx-text-fill", "value" to "red")
        assertThat(s["selector"].asText()).isEqualTo("#box .label")
        assertThat(s["needsImportant"].asBoolean()).isFalse()
    }

    @Test
    fun `cascade reports value types for the inspector editors`() {
        val t = call(CssInspect::cascade, "#save")["types"]
        assertThat(t["-fx-text-fill"]["kind"].asText()).isEqualTo("paint")
        assertThat(t["-fx-background-color"]["kind"].asText()).isEqualTo("paint")
        assertThat(t["-fx-background-color"]["list"].asBoolean()).isTrue()
        assertThat(t["-fx-padding"]["kind"].asText()).isEqualTo("insets")
        assertThat(t["-fx-font-size"]["kind"].asText()).isEqualTo("size")
        assertThat(t["-fx-font-weight"]["kind"].asText()).isEqualTo("font-weight")
        assertThat(t["-fx-wrap-text"]["kind"].asText()).isEqualTo("boolean")
        assertThat(t["-fx-wrap-text"]["initial"].asText()).isEqualTo("false")
        assertThat(t["-fx-opacity"]["initial"].asText()).isEqualTo("1")
        val align = t["-fx-alignment"]
        assertThat(align["kind"].asText()).isEqualTo("enum")
        assertThat(align["choices"].map { it.asText() }).contains("center-left", "baseline-right")
        assertThat(t["-fx-cursor"]["kind"].asText()).isEqualTo("cursor")
        assertThat(t["-fx-effect"]["kind"].asText()).isEqualTo("effect")
    }
}

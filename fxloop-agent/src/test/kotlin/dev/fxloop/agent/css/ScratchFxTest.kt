package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.AgentRuntime
import dev.fxloop.agent.Fx
import dev.fxloop.agent.Reloads
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** The inspector's scratch stylesheet (PRD 08 §5): install, cascade labels, survival across CSS reloads, rejection. */
@ExtendWith(ApplicationExtension::class)
class ScratchFxTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun runtime() {
            AgentRuntime.startForTest()
        }
    }

    private lateinit var scene: Scene
    private lateinit var go: Label

    @Start
    fun start(stage: Stage) {
        go = Label("go").apply { id = "go"; styleClass += "go" }
        scene = Scene(VBox(go), 200.0, 100.0).apply {
            stylesheets += ScratchFxTest::class.java.getResource("scratch-base.css")!!.toExternalForm()
        }
        stage.scene = scene
        stage.show()
    }

    @AfterEach
    fun clear() {
        Scratch.clear()
    }

    private fun set(css: String): ObjectNode = Scratch.set(Json.obj { put("css", css) })

    private fun <T> fx(work: () -> T): T {
        WaitForAsyncUtils.waitForFxEvents()
        return Fx.call {
            scene.root.applyCss()
            work()
        }
    }

    private fun textFillRule(): ObjectNode {
        val c = CssInspect.cascade(Json.obj { put("query", "#go") })
        return c["properties"].first { it["cssProperty"].asText() == "-fx-text-fill" } as ObjectNode
    }

    @Test
    fun `scratch rule is the last scene sheet, wins and shows as scratch n`() {
        val r = set(".go { -fx-text-fill: blue; }\n#go { -fx-padding: 4; }")
        assertThat(r["active"].asBoolean()).isTrue()
        assertThat(r["rules"].asInt()).isEqualTo(2)
        assertThat(r["items"][0]["selector"].asText()).isEqualTo(".go")
        assertThat(r["items"][0]["declarations"][0]["value"].asText()).isEqualTo("blue")
        assertThat(fx { scene.stylesheets.last() }).startsWith("data:text/css;base64,")
        assertThat(fx { go.textFill }).isEqualTo(Color.BLUE)

        val p = textFillRule()
        assertThat(p["winner"]["file"].asText()).isEqualTo("scratch:1")
        assertThat(p["winner"]["scratch"].asBoolean()).isTrue()
        assertThat(p["simulation"].asText()).isEqualTo("ok")
        assertThat(p["overridden"][0]["file"].asText()).endsWith("scratch-base.css")
    }

    @Test
    fun `css reload keeps the scratch sheet last and reports it`() {
        set(".go { -fx-text-fill: blue; }")
        fx { scene.stylesheets.add(ScratchFxTest::class.java.getResource("scratch-base.css")!!.toExternalForm() + "?again") }
        val r = Reloads.reloadCss(Json.obj { putArray("files").add("scratch-base.css") })
        assertThat(r["scratchRules"].asInt()).isEqualTo(1)
        assertThat(fx { scene.stylesheets.last() }).startsWith("data:")
        assertThat(fx { go.textFill }).isEqualTo(Color.BLUE)
    }

    @Test
    fun `clear removes the sheet and restores the source value`() {
        set(".go { -fx-text-fill: blue; }")
        val r = Scratch.clear()
        assertThat(r["active"].asBoolean()).isFalse()
        assertThat(fx { scene.stylesheets.none { it.startsWith("data:") } }).isTrue()
        assertThat(fx { go.textFill }).isEqualTo(Color.RED)
        assertThat(Scratch.describe()["rules"].asInt()).isEqualTo(0)
    }

    @Test
    fun `at-rules, parse errors, rule-less and oversized text are rejected and nothing is installed`() {
        for (bad in listOf("@import url(\"x.css\");\n.go { -fx-text-fill: blue; }", "-fx-text-fill: blue;", ".go { -fx-text-fill: ; }", ".go { -fx-text-fill: blue; }".repeat(4000))) {
            assertThatThrownBy { set(bad) }.isInstanceOf(FcpException::class.java)
        }
        assertThat(Scratch.active).isFalse()
        assertThat(fx { go.textFill }).isEqualTo(Color.RED)
    }

    @Test
    fun `data urls decode both base64 and percent encoding`() {
        assertThat(Scratch.dataUrlText("data:text/css;base64,LmEgeyB9")).isEqualTo(".a { }")
        assertThat(Scratch.dataUrlText("data:text/css,.a%20%7B%20%7D")).isEqualTo(".a { }")
        assertThat(Scratch.dataUrlText("file:/x.css")).isNull()
    }
}

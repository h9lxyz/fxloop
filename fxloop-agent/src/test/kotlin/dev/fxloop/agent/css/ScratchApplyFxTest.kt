package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.AgentRuntime
import dev.fxloop.agent.Fx
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** Inspector *Apply to file*: scratch rules become edits of the source stylesheet they belong to. */
@ExtendWith(ApplicationExtension::class)
class ScratchApplyFxTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun runtime() {
            AgentRuntime.startForTest()
        }
    }

    @TempDir
    lateinit var project: Path

    private lateinit var scene: Scene

    @Start
    fun start(stage: Stage) {
        scene = Scene(VBox(Label("go").apply { id = "go"; styleClass += "go" }), 200.0, 100.0)
        stage.scene = scene
        stage.show()
    }

    @AfterEach
    fun clear() {
        Scratch.clear()
        Fx.call { scene.stylesheets.clear() }
    }

    private fun sheet(text: String): Path {
        val f = project.resolve("src/main/resources/app.css")
        Files.createDirectories(f.parent)
        Files.writeString(f, text)
        Fx.call { scene.stylesheets.setAll(f.toUri().toString()) }
        WaitForAsyncUtils.waitForFxEvents()
        return f
    }

    private fun plan(scratch: String): ObjectNode {
        Scratch.set(Json.obj { put("css", scratch) })
        return ScratchApply.plan(Json.obj {
            put("projectDir", project.toString())
            putArray("resourceRoots").add(project.resolve("src/main/resources").toString())
        })
    }

    @Test
    fun `values are replaced in place, new properties go before the brace, new selectors are appended`() {
        val f = sheet(".go {\n  -fx-text-fill: red;\n  -fx-font-size: 12px !important; /* keep */\n}\n\n#other { -fx-padding: 1 }\n")
        val r = plan(".go { -fx-text-fill: blue; -fx-font-size: 14px; -fx-opacity: 0.5; }\n#other { -fx-padding: 4; -fx-spacing: 2; }\n.fresh { -fx-cursor: hand; }")
        val file = r["files"].single()
        assertThat(file["file"].asText()).isEqualTo("src/main/resources/app.css")
        assertThat(file["sha256"].asText()).isEqualTo(ScratchApply.sha256(Files.readString(f)))
        assertThat(file["text"].asText()).isEqualTo(
            ".go {\n  -fx-text-fill: blue;\n  -fx-font-size: 14px !important; /* keep */\n  -fx-opacity: 0.5;\n}\n\n" +
                "#other { -fx-padding: 4; -fx-spacing: 2; }\n\n.fresh {\n    -fx-cursor: hand;\n}\n",
        )
        val kinds = file["changes"].map { it["kind"].asText() + ":" + it.path("property").asText(it.path("selector").asText()) }
        assertThat(kinds).containsExactly("update:-fx-text-fill", "update:-fx-font-size", "add:-fx-opacity", "update:-fx-padding",
            "add:-fx-spacing", "newRule:.fresh")
        assertThat(file["changes"][0]["from"].asText()).isEqualTo("red")
        assertThat(file["changes"][5]["line"].asInt()).isEqualTo(9)
        assertThat(Files.readString(f)).startsWith(".go {\n  -fx-text-fill: red;") // the agent only plans
    }

    @Test
    fun `the rule that already sets the property wins over a later one with the same selector`() {
        sheet(".go { -fx-text-fill: red; }\n.go { -fx-padding: 2; }\n")
        val text = plan(".go { -fx-text-fill: green; }")["files"][0]["text"].asText()
        assertThat(text).isEqualTo(".go { -fx-text-fill: green; }\n.go { -fx-padding: 2; }\n")
    }

    @Test
    fun `nothing to apply without scratch css or a project stylesheet`() {
        assertThatThrownBy { ScratchApply.plan(Json.obj()) }.isInstanceOf(FcpException::class.java)
        Scratch.set(Json.obj { put("css", ".go { -fx-text-fill: blue; }") })
        assertThatThrownBy { ScratchApply.plan(Json.obj { put("projectDir", project.toString()) }) }
            .isInstanceOf(FcpException::class.java).hasMessageContaining("Copy as CSS")
    }
}

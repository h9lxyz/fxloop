package org.lab59.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.agent.AgentRuntime
import org.lab59.fxloop.agent.Fx
import org.lab59.fxloop.agent.Reloads
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/**
 * Inspector *Apply to file*: scratch rules become edits of the source stylesheet they belong to, a project sheet or a
 * library sheet the developer mapped to its source; any of them can be picked instead.
 */
@ExtendWith(ApplicationExtension::class)
class ScratchApplyFxTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun runtime() {
            AgentRuntime.startForTest()
            // a cached `jar:` connection keeps the library's jar open, and Windows then cannot delete the temp dir
            URLConnection.setDefaultUseCaches("jar", false)
        }

        @JvmStatic
        @AfterAll
        fun restoreJarCache() {
            URLConnection.setDefaultUseCaches("jar", true)
        }
    }

    @TempDir
    lateinit var project: Path

    /** A library checked out next to the project: its source and what the app loads (a build copy or a jar). */
    @TempDir
    lateinit var lib: Path

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

    /** The library's `widgets.css` with [text]: (URL the app loads, source file). */
    private fun library(text: String, jar: Boolean = false): Pair<String, Path> {
        val src = lib.resolve("src/main/resources/com/acme/widgets.css")
        Files.createDirectories(src.parent)
        Files.writeString(src, text)
        val url = if (jar) {
            val j = lib.resolve("widgets-1.2.jar")
            JarOutputStream(Files.newOutputStream(j)).use { out ->
                out.putNextEntry(JarEntry("com/acme/widgets.css"))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            "jar:${j.toUri()}!/com/acme/widgets.css"
        } else {
            val copy = lib.resolve("target/classes/com/acme/widgets.css")
            Files.createDirectories(copy.parent)
            Files.writeString(copy, text)
            copy.toUri().toString()
        }
        Fx.call { scene.stylesheets.add(0, url) }
        WaitForAsyncUtils.waitForFxEvents()
        return url to src
    }

    private fun roots(mappings: Map<String, Path>) = Json.obj {
        put("projectDir", project.toString())
        putArray("resourceRoots").add(project.resolve("src/main/resources").toString())
        val arr = putArray("cssMappings")
        mappings.forEach { (url, file) -> arr.addObject().put("url", url).put("file", file.toString()) }
    }

    private fun plan(scratch: String, mappings: Map<String, Path> = emptyMap(), targets: Map<Int, String> = emptyMap()): ObjectNode {
        Scratch.set(Json.obj { put("css", scratch) })
        return ScratchApply.plan(roots(mappings).apply {
            val t = putObject("targets")
            targets.forEach { (n, f) -> t.put(n.toString(), f) }
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

    @Test
    fun `a rule only a mapped library sheet has goes into its source, or into a project sheet the developer picks`() {
        sheet("#other { -fx-padding: 1; }\n")
        val (url, src) = library(".go { -fx-text-fill: red; }\n")
        val r = plan(".go { -fx-text-fill: blue; }", mapOf(url to src))
        val item = r["items"].single()
        assertThat(item["file"].asText()).isEqualTo(src.toString())
        assertThat(item["kind"].asText()).isEqualTo("merge")
        assertThat(item["external"].asBoolean()).isTrue()
        assertThat(item["options"].map { it["file"].asText() + ":" + it["kind"].asText() })
            .containsExactly("src/main/resources/app.css:append", "$src:merge")
        val f = r["files"].single()
        assertThat(f["text"].asText()).isEqualTo(".go { -fx-text-fill: blue; }\n")
        assertThat(f["external"].asBoolean()).isTrue()
        assertThat(f["live"].asBoolean()).isTrue() // a build copy in a directory: the server copies the source over it
        assertThat(f["sheet"].asText()).isEqualTo(url)
        assertThat(r.has("keep")).isFalse()

        val o = plan(".go { -fx-text-fill: blue; }", mapOf(url to src), mapOf(1 to "src/main/resources/app.css"))
        assertThat(o["items"][0]["kind"].asText()).isEqualTo("append")
        assertThat(o["files"].single()["file"].asText()).isEqualTo("src/main/resources/app.css")
        assertThat(o["files"][0]["text"].asText()).isEqualTo("#other { -fx-padding: 1; }\n\n.go {\n    -fx-text-fill: blue;\n}\n")
        assertThatThrownBy { plan(".go { -fx-text-fill: blue; }", mapOf(url to src), mapOf(1 to "/etc/other.css")) }
            .isInstanceOf(FcpException::class.java).hasMessageContaining("/etc/other.css")
    }

    @Test
    fun `a rule from an unmapped library sheet is an override in the project sheet, with a hint to map the library`() {
        sheet("#other { -fx-padding: 1; }\n")
        val (url, _) = library(".go { -fx-text-fill: red; }\n", jar = true)
        val item = plan(".go { -fx-text-fill: blue; }")["items"].single()
        assertThat(item["file"].asText()).isEqualTo("src/main/resources/app.css")
        assertThat(item["kind"].asText()).isEqualTo("append")
        assertThat(item["options"].size()).isEqualTo(1)
        assertThat(item["unmapped"].single()["url"].asText()).isEqualTo(url)
        assertThat(item["unmapped"][0]["from"].asText()).isEqualTo("widgets-1.2.jar")
    }

    @Test
    fun `rules saved into a library the app loads from a jar stay in scratch, and a version bump keeps the mapping`() {
        sheet("#other { -fx-padding: 1; }\n")
        val (url, src) = library(".go { -fx-text-fill: red; }\n", jar = true)
        val r = plan(".go { -fx-text-fill: blue; }\n#other { -fx-padding: 3; }", mapOf(url.replace("widgets-1.2", "widgets-1.1") to src))
        val libFile = r["files"].single { it.path("external").asBoolean() }
        assertThat(libFile["file"].asText()).isEqualTo(src.toString())
        assertThat(libFile["live"].asBoolean()).isFalse()
        assertThat(libFile["from"].asText()).isEqualTo("widgets-1.2.jar")
        assertThat(r["keep"].asText()).isEqualTo(".go {\n    -fx-text-fill: blue;\n}\n\n")

        // a CSS reload never touches a jar sheet: `?fxloop=` would make its URL unloadable
        assertThat(Reloads.reloadCss(Json.obj { putArray("files").add("com/acme/widgets.css").add("widgets.css") })["replaced"].asInt()).isZero()
        assertThat(Fx.call { scene.stylesheets.first() }).isEqualTo(url)
    }

    @Test
    fun `the cascade marks sheets outside the app and points mapped rules at their source`() {
        Fx.call { scene.stylesheets.clear() }
        val (url, src) = library(".go { -fx-text-fill: red; }\n", jar = true)
        fun cascade(mappings: Map<String, Path> = emptyMap()) = CssInspect.cascade(roots(mappings).put("query", "#go"))
        val before = cascade()
        val sheet = before["sheets"].single { it["url"].asText() == url }
        assertThat(sheet["external"].asBoolean()).isTrue()
        assertThat(sheet["mappable"].asBoolean()).isTrue()
        assertThat(sheet["from"].asText()).isEqualTo("widgets-1.2.jar")
        assertThat(sheet["resource"].asText()).isEqualTo("com/acme/widgets.css")
        assertThat(sheet.has("mapped")).isFalse()
        assertThat(before["sheets"].first { it["origin"].asText() == "USER_AGENT" }["builtin"].asBoolean()).isTrue()
        assertThat(before["rules"].single { it["selector"].asText() == ".go" }["file"].asText()).isEqualTo("widgets.css")

        val after = cascade(mapOf(url to src))
        val mapped = after["sheets"].single { it["url"].asText() == url }["mapped"]
        assertThat(mapped["absFile"].asText()).isEqualTo(src.toString())
        assertThat(mapped["match"].asText()).isEqualTo("identical")
        val rule = after["rules"].single { it["selector"].asText() == ".go" }
        assertThat(rule["file"].asText()).isEqualTo(src.toString())
        assertThat(rule["absFile"].asText()).isEqualTo(src.toString())
        assertThat(rule["line"].asInt()).isEqualTo(1)

        Files.writeString(src, "/* edited */\n.go { -fx-text-fill: green; }\n")
        val edited = cascade(mapOf(url to src))["sheets"].single { it["url"].asText() == url }["mapped"]
        assertThat(edited["match"].asText()).isEqualTo("differs")
        assertThat(edited["reason"].asText()).contains("lines may be off")
        Files.writeString(src, ".something-else { }\n")
        assertThat(cascade(mapOf(url to src))["sheets"].single { it["url"].asText() == url }["mapped"]["reason"].asText())
            .contains("1 of 1 loaded rules")
    }
}

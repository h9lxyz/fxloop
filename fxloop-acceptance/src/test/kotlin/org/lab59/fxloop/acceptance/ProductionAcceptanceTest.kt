package org.lab59.fxloop.acceptance

import org.lab59.fxloop.acceptance.Harness.Companion.args
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.launch.LaunchDescriptor
import org.lab59.fxloop.server.app.AppSession
import org.lab59.fxloop.server.app.ProcessTree
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.nio.file.Files

/** PRD 03 §9 robustness rows + §10 perf on the gauntlet app, the WebView bridge (G6) and production evals. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProductionAcceptanceTest {
    private val h = Harness(app = "fxloop-gauntlet-app", webview = true)

    @AfterAll
    fun close() = h.close()

    private fun text(query: String) = h.prop(query, "text").asText()

    @Test @Order(1)
    fun `non-blocking alert dialog is a reachable window`() {
        h.ok("fx_click_node", args("query" to "#alertBtn"))
        val windows = h.ok("fx_get_app_status")["windows"]
        assertThat(windows.map { it["type"].asText() }).contains("DIALOG")
        val hit = h.ok("fx_find_nodes", args("text" to "Hello from alert"))
        assertThat(hit["total"].asInt()).isGreaterThan(0)
        h.ok("fx_type_keys", args("keys" to listOf(mapOf("code" to "ESCAPE"))))
    }

    @Test @Order(2)
    fun `context menu items are clickable in their popup window`() {
        h.ok("fx_click_node", args("query" to "#ctxTarget", "button" to "secondary"))
        val copy = h.ok("fx_find_nodes", args("text" to "Copy"))["items"].first { it["path"].asText().startsWith("Popup") }
        h.ok("fx_click_node", args("path" to copy["path"].asText()))
        assertThat(text("#menuResult")).containsIgnoringCase("copy")
    }

    @Test @Order(3)
    fun `second stage is targeted by window index`() {
        h.ok("fx_click_node", args("query" to "#openStageBtn"))
        val win = h.ok("fx_get_app_status")["windows"].first { it.path("title").asText() == "Gauntlet Second Stage" }
        h.ok("fx_click_node", args("query" to "#secondStageBtn", "windowIndex" to win["index"].asInt()))
        assertThat(h.ok("fx_get_property", args("query" to "#secondStageBtn", "name" to "text"))["value"].asText()).isEqualTo("Clicked")
    }

    @Test @Order(4)
    fun `robot click auto-selects tabs and scrolls to deep nodes (ensureVisible)`() {
        h.ok("fx_click_node", args("query" to "#deepBtn"))
        assertThat(text("#deepResult")).isEqualTo("deep clicked")
        h.ok("fx_click_node", args("query" to "#innerBtn"))
    }

    @Test @Order(5)
    fun `virtualized 100k table serializes the visible window only, within budget`() {
        val t0 = System.currentTimeMillis()
        val g = h.ok("fx_get_scene_graph", args("query" to "#bigTable"))
        val ms = System.currentTimeMillis() - t0
        assertThat(g["root"]["itemCount"].asInt()).isEqualTo(100_000)
        assertThat(g["root"]["visibleCells"].size()).isLessThanOrEqualTo(10)
        assertThat(Json.write(g).length).isLessThan(64 * 1024)
        assertThat(ms).isLessThan(1500)
    }

    @Test @Order(6)
    fun `5000-node graph stays within output and time budgets (G9)`() {
        h.ok("fx_click_node", args("query" to "#buildBigBtn", "effects" to false))
        val t0 = System.currentTimeMillis()
        val g = h.ok("fx_get_scene_graph", args("query" to "#bigPane"))
        val ms = System.currentTimeMillis() - t0
        assertThat(g["truncated"].asBoolean()).isTrue()
        assertThat(Json.write(g).length).isLessThan(64 * 1024)
        // end-to-end (MCP tool → FCP → FX thread → JSON) on a loaded CI box; the agent-side serializer budget
        // (≤ 150 ms for 5 000 nodes, PRD 03 §10) is far below this, the margin absorbs scheduling noise
        assertThat(ms).isLessThan(6000)
        val t1 = System.currentTimeMillis()
        h.ok("fx_get_scene_graph", args("query" to "#bigPane", "maxNodes" to 5000, "maxDepth" to 4))
        assertThat(System.currentTimeMillis() - t1).isLessThan(6000)
    }

    @Test @Order(7)
    fun `low-contrast label is found by visual lint (G12)`() {
        h.ok("fx_click_node", args("query" to "#lowContrastLabel"))
        val lint = h.ok("fx_lint_visual", args("query" to "#tabs", "checks" to listOf("lowContrast", "textTruncated", "zeroSizeVisible", "outOfScene")))["items"]
        assertThat(lint.any { it["check"].asText() == "lowContrast" && it["path"].asText().endsWith("#lowContrastLabel") }).describedAs(lint.toString()).isTrue()
        assertThat(lint.any { it["check"].asText() == "textTruncated" && it["path"].asText().endsWith("#truncLabel") }).describedAs(lint.toString()).isTrue()
    }

    @Test @Order(8)
    fun `WebView JavaScript bridge (G6)`() {
        h.ok("fx_click_node", args("query" to "#web"))
        Thread.sleep(500)
        val r = h.ok("fx_eval_js", args("query" to "#web", "script" to "document.getElementById('title').textContent"))
        assertThat(r["result"].asText()).isEqualTo("Gauntlet Web")
    }

    @Test @Order(9)
    fun `secondary windows survive in status and close cleanly`() {
        assertThat(h.ok("fx_get_app_status")["windows"].size()).isGreaterThanOrEqualTo(2)
        assertThat(h.ok("fx_get_ui_errors")["errors"].size()).isZero()
    }
}

/** PRD 03 §3 attach mode against a JVM fxloop did not launch. */
class AttachAcceptanceTest {
    @Test
    fun `attach to a running app, act read-only, and refuse restart`() {
        Harness().use { h ->
            val d = h.ctx.session.build.prepare()
            val cmd = buildList {
                if (System.getenv("FXLOOP_ACCEPTANCE_HEADLESS") != "none") addAll(listOf("xvfb-run", "-a"))
                add(AppSession.javaExecutable())
                add("-XX:+EnableDynamicAgentLoading")
                add(Harness.RUN_MARKER)
                add("-cp")
                add((listOf(d.outputDir) + d.classpath).joinToString(File.pathSeparator))
                add(d.mainClass)
                add("--seed")
            }
            val p = ProcessTree.track(ProcessBuilder(cmd).directory(h.project.toFile()).redirectErrorStream(true)
                .redirectOutput(h.project.resolve("attached.log").toFile()).start())
            try {
                val javaPid = awaitJavaChild(p)
                Thread.sleep(2500)
                val st = h.ok("fx_attach", args("pid" to javaPid))
                assertThat(st["mode"].asText()).isEqualTo("attached")
                assertThat(h.prop("#statusLabel", "text").asText()).isEqualTo("3 contacts")
                assertThat(h.err("fx_restart_app")["code"].asText()).isEqualTo("NOT_SUPERVISED")
                assertThat(h.err("fx_reload")["code"].asText()).isEqualTo("RELOAD_UNAVAILABLE")
            } finally {
                ProcessTree.kill(p) // the test owns this tree; attach itself never kills foreign JVMs
            }
        }
    }

    /** xvfb-run spawns the JVM as a descendant; find the java process. */
    private fun awaitJavaChild(p: Process): Long {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val java = (listOf(p.toHandle()) + p.descendants().toList()).firstOrNull {
                it.info().command().orElse("").endsWith("java")
            }
            if (java != null) return java.pid()
            Thread.sleep(100)
        }
        error("no java process")
    }
}

/** PRD 03 §4 Monocle lane: the agent works headless without any X server (event-mode input). */
class MonocleLaneTest {
    @Test
    fun `status, scene, screenshot and event clicks under Monocle`() {
        Harness(headlessOverride = "monocle").use { h ->
            assertThat(h.ok("fx_get_app_status")["state"].asText()).isEqualTo("running")
            h.ok("fx_set_text", args("query" to "#nameField", "text" to "Mona"))
            h.ok("fx_click_node", args("query" to "#addBtn", "mode" to "event"))
            assertThat(h.prop("#statusLabel", "text").asText()).isEqualTo("1 contacts")
            val shot = h.raw("fx_take_screenshot")
            assertThat(shot.images).hasSize(1)
            assertThat(Files.size(h.project.resolve(shot.structured!!["file"].asText()))).isGreaterThan(1000)
        }
    }
}

/** Unused-import guard for LaunchDescriptor (kept for readers of attach setup). */
@Suppress("unused")
private val launchDescriptorType = LaunchDescriptor::class

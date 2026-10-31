package dev.fxloop.server.mcp

import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import dev.fxloop.server.Config
import dev.fxloop.server.registry
import dev.fxloop.server.tools.Ctx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ToolRegistryTest {
    @Test
    fun `fcp errors become in-result errors with a next step and the FX stack`() {
        val reg = ToolRegistry()
        reg.add(ToolDef("t", "T", "d", Schema.obj().build(), ToolDef.Kind.READ) {
            throw FcpException(Fcp.Error.FX_TIMEOUT, "blocked", Json.obj { set<com.fasterxml.jackson.databind.JsonNode>("fxThreadStack", Json.strings(listOf("a.B.c(B.java:1)"))) })
        })
        val r = reg.call("t")
        assertThat(r.isError).isTrue()
        assertThat(r.text).contains("FX_TIMEOUT").contains("Next:").contains("a.B.c")
        assertThat(r.structured!!["data"]["fxThreadStack"].size()).isEqualTo(1)
        val mcp = ToolRegistry.toMcpResult(r)
        assertThat(mcp.isError()).isTrue()
    }

    @Test
    fun `tool surface follows naming and annotation conventions`(@TempDir dir: Path) {
        val ctx = Ctx(Config(projectDir = dir, webview = true, testMode = true))
        val reg = registry(ctx)
        val names = reg.all().map { it.name }
        assertThat(names).allMatch { it.matches(Regex("fx_[a-z_]+")) }
        assertThat(names).contains(
            "fx_get_app_status", "fx_start_app", "fx_restart_app", "fx_stop_app", "fx_get_logs", "fx_get_ui_errors",
            "fx_get_scene_graph", "fx_find_nodes", "fx_click_node", "fx_type_keys", "fx_set_text", "fx_get_property",
            "fx_set_property", "fx_take_screenshot", "fx_reload", "fx_reload_fxml", "fx_get_journal", "fx_describe_at",
            "fx_lint_fxml", "fx_get_node_details", "fx_connect_existing", "fx_set_watch_mode", "fx_compare_screenshots",
            "fx_get_screenshot", "fx_lint_visual", "fx_attach", "fx_export_test", "fx_explain_property", "fx_explain_style",
            "fx_explain_layout", "fx_eval_js", "fx_capture_states")
        val specs = reg.toMcp()
        assertThat(specs).allSatisfy { assertThat(it.tool().annotations().openWorldHint()).isFalse() }
        assertThat(specs.first { it.tool().name() == "fx_get_scene_graph" }.tool().annotations().readOnlyHint()).isTrue()
        assertThat(specs.first { it.tool().name() == "fx_restart_app" }.tool().annotations().destructiveHint()).isTrue()
        ctx.shutdown()
    }

    @Test
    fun `eval_js is not registered unless enabled`(@TempDir dir: Path) {
        val reg = registry(Ctx(Config(projectDir = dir)))
        assertThat(reg.all().map { it.name }).doesNotContain("fx_eval_js", "fx_test_block_fx_thread")
    }

    @Test
    fun `config reads env and cli flags`() {
        val c = Config.load(mapOf("FXLOOP_PROJECT_DIR" to "/tmp/x", "FXLOOP_JVM_ARGS" to "-Xmx1g \"-Da=b c\""),
            arrayOf("--headless=monocle", "--viewer=0"))
        assertThat(c.projectDir).isEqualTo(Path.of("/tmp/x"))
        assertThat(c.jvmArgs).containsExactly("-Xmx1g", "-Da=b c")
        assertThat(c.headless).isEqualTo("monocle")
        assertThat(c.viewerPort).isZero()
    }
}

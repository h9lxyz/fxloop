package dev.fxloop.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.fxloop.protocol.Tokens
import dev.fxloop.server.app.Versions
import dev.fxloop.server.mcp.ToolRegistry
import dev.fxloop.server.see.Viewer
import dev.fxloop.server.tools.ActTools
import dev.fxloop.server.tools.CoreTools
import dev.fxloop.server.tools.Ctx
import dev.fxloop.server.tools.LoopTools
import dev.fxloop.server.tools.SeeTools
import dev.fxloop.server.tools.WhyTools
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch

/** Instructions sent to MCP clients at initialization. */
const val INSTRUCTIONS = """FXLoop drives the JavaFX app in this project. Typical loop: fx_get_app_status (auto-starts) →
fx_take_screenshot / fx_get_scene_graph / fx_find_nodes to see → fx_click_node / fx_set_text / fx_type_keys to act (results
include 'effects', a diff of what changed) → edit code → fx_reload (CSS/FXML live, Java hot-swap, restart+replay when needed;
compile errors come back as data) → verify. Screenshots are persisted under target/fxloop/screens — cite the file path so the
developer can open it. Use fx_get_ui_errors for exceptions on the FX thread, fx_explain_* for "why", fx_lint_fxml /
fx_lint_visual for static checks, fx_export_test to turn the session into a TestFX test."""

/** Builds the full tool surface. */
fun registry(ctx: Ctx): ToolRegistry = ToolRegistry().also { reg ->
    CoreTools.register(reg, ctx)
    ActTools.register(reg, ctx)
    SeeTools.register(reg, ctx)
    LoopTools.register(reg, ctx)
    WhyTools.register(reg, ctx)
}

/** stdio MCP server entry point. stdout carries MCP frames only; all logging goes to stderr. */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    System.getenv("FXLOOP_LOG")?.let { System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", it) }
    if (args.any { it == "--version" }) {
        System.err.println("fxloop-server ${Versions.SERVER}")
        return
    }
    val config = Config.load(System.getenv(), args)
    val log = LoggerFactory.getLogger("dev.fxloop.server")
    val ctx = Ctx(config)
    config.viewerPort?.let { port ->
        val viewer = Viewer(port, Tokens.generate(), ctx.visuals.store)
        viewer.start()
        ctx.viewer = viewer
        log.info("live viewer: {}", viewer.url())
    }
    val reg = registry(ctx)
    val transport = StdioServerTransportProvider(JacksonMcpJsonMapper(ObjectMapper()))
    val server = McpServer.sync(transport)
        .serverInfo("fxloop", Versions.SERVER)
        .instructions(INSTRUCTIONS.trimIndent())
        .requestTimeout(Duration.ofMinutes(10))
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(reg.toMcp())
        .build()
    log.info("fxloop {} ready: project {} ({} tools)", Versions.SERVER, config.projectDir, reg.all().size)
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        ctx.shutdown()
        runCatching { server.close() }
        done.countDown()
    })
    done.await()
}

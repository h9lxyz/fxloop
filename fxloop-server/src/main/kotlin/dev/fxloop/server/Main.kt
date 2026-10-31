package dev.fxloop.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.fxloop.protocol.Handshake
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.app.Versions
import dev.fxloop.server.mcp.ToolRegistry
import dev.fxloop.server.tools.ActTools
import dev.fxloop.server.tools.CoreTools
import dev.fxloop.server.tools.Ctx
import dev.fxloop.server.tools.LoopTools
import dev.fxloop.server.tools.PromptTools
import dev.fxloop.server.tools.SeeTools
import dev.fxloop.server.tools.WhyTools
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

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
    PromptTools.register(reg, ctx)
    reg.observer = ctx::toolActivity
}

/** Markdown tool reference generated from the live registry (`--tool-reference`, written to docs/tools.md). */
fun toolReference(reg: ToolRegistry): String = buildString {
    appendLine("# FXLoop tool reference")
    appendLine()
    appendLine("_Generated from the tool registry (`java -jar fxloop-server-*-all.jar --tool-reference`). Do not edit by hand._")
    appendLine()
    appendLine("| Tool | Kind | Summary |")
    appendLine("|---|---|---|")
    reg.all().forEach { appendLine("| [`${it.name}`](#${it.name}) | ${it.kind.name.lowercase()} | ${it.title} |") }
    reg.all().forEach { t ->
        appendLine()
        appendLine("## ${t.name}")
        appendLine()
        appendLine(t.description)
        appendLine()
        @Suppress("UNCHECKED_CAST")
        val props = t.inputSchema["properties"] as Map<String, Map<String, Any>>
        val required = (t.inputSchema["required"] as? List<*>).orEmpty()
        if (props.isEmpty()) {
            appendLine("_No parameters._")
        } else {
            appendLine("| Param | Type | Required | Description |")
            appendLine("|---|---|---|---|")
            props.forEach { (name, def) ->
                val type = (def["type"] ?: "any").toString() + ((def["enum"] as? List<*>)?.let { " (${it.joinToString("\\|")})" } ?: "")
                appendLine("| `$name` | $type | ${if (name in required) "yes" else ""} | ${def["description"]} |")
            }
        }
    }
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
    if (args.any { it == "--tool-reference" }) {
        print(toolReference(registry(Ctx(config.copy(webview = true)))))
        return
    }
    val log = LoggerFactory.getLogger("dev.fxloop.server")
    val ctx = Ctx(config)
    if (config.sidecar) runSidecar(ctx)
    config.viewerPort?.let { port ->
        ctx.ensureViewer(port)
        ctx.live.push()
    }
    val reg = registry(ctx)
    // The server must die with its MCP client: the SDK only completes the session on stdin EOF (it never exits the
    // JVM), and a client that dies without closing the pipe is caught by the parent watchdog. Either way main()
    // returns through exitProcess, which runs the shutdown hooks that reap every launched app tree.
    val done = CountDownLatch(1)
    val stdin = EofAwareInputStream(System.`in`) { done.countDown() }
    ParentWatchdog.start { done.countDown() }
    val transport = StdioServerTransportProvider(JacksonMcpJsonMapper(ObjectMapper()), stdin, System.out)
    val server = McpServer.sync(transport)
        .serverInfo("fxloop", Versions.SERVER)
        .instructions(INSTRUCTIONS.trimIndent())
        .requestTimeout(Duration.ofMinutes(10))
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(reg.toMcp())
        .build()
    log.info("fxloop {} ready: project {} ({} tools)", Versions.SERVER, config.projectDir, reg.all().size)
    Runtime.getRuntime().addShutdownHook(Thread {
        ctx.shutdown()
        runCatching { server.close() }
    })
    done.await()
    log.info("MCP client is gone; shutting down")
    exitProcess(0)
}

/**
 * `--sidecar=true`, started by `fxloop:run` / `fxloopRun` next to the app: no MCP. Keeps a yielding FCP connection to the
 * developer-run app so the dev bar's Inspect CSS (the viewer and CSS inspector) works without an agent session. An
 * agent session's server takes the app over; this reconnects once it is gone. Waits up to two minutes for the app's
 * handshake (it starts alongside the app), then exits with the app or its parent.
 */
private fun runSidecar(ctx: Ctx): Nothing {
    val log = LoggerFactory.getLogger("dev.fxloop.server.sidecar")
    val handshake = ctx.session.fxloopDir.resolve("handshake.json")
    ParentWatchdog.start { exitProcess(0) }
    Runtime.getRuntime().addShutdownHook(Thread { ctx.shutdown() })
    val viewer = ctx.ensureViewer(ctx.config.viewerPort ?: 0)
    System.err.println("[fxloop] CSS inspector: ${viewer.inspectUrl()} (also via Inspect CSS in the dev bar)")
    var yielded = false
    var seen = false
    val startDeadline = System.currentTimeMillis() + 120_000
    while (true) {
        val hs = runCatching { Handshake.readFrom(handshake) }.getOrNull()
        val alive = hs != null && ProcessHandle.of(hs.pid).map { it.isAlive }.orElse(false)
        if (!alive) {
            if (seen || System.currentTimeMillis() > startDeadline) {
                log.info("the app is gone; inspector helper exits")
                exitProcess(0)
            }
            Thread.sleep(500)
            continue
        }
        seen = true
        if (!ctx.session.connected) {
            try {
                ctx.session.connectExisting(handshake, AppSession.Mode.EMBEDDED, yieldToAgents = true)
                if (yielded) log.info("agent session ended; inspector helper is back")
                yielded = false
            } catch (e: Exception) { // BUSY while an agent session has the app, or the app is going away
                if (!yielded) log.info("an agent session has the app; inspector helper waits ({})", e.message)
                yielded = true
            }
        }
        // While an agent session has the app, open pages follow it to that session's inspector (Ask agent reaches it).
        viewer.setMovedTo(if (ctx.session.connected) null else Ctx.advertisedInspector(ctx.session.fxloopDir.resolve(Ctx.INSPECTOR_FILE)))
        Thread.sleep(1500)
    }
}

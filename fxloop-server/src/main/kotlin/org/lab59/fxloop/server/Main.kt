package org.lab59.fxloop.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.lab59.fxloop.server.app.AppSession
import org.lab59.fxloop.server.app.Versions
import org.lab59.fxloop.server.mcp.ToolRegistry
import org.lab59.fxloop.server.tools.ActTools
import org.lab59.fxloop.server.tools.CoreTools
import org.lab59.fxloop.server.tools.Ctx
import org.lab59.fxloop.server.tools.LoopTools
import org.lab59.fxloop.server.tools.PromptTools
import org.lab59.fxloop.server.tools.SeeTools
import org.lab59.fxloop.server.tools.WhyTools
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

private val TARGET_PARAMS = linkedSetOf("handle", "path", "query", "windowIndex", "picked")

private val TOOL_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Run and connect" to listOf(
        "fx_get_app_status", "fx_start_app", "fx_restart_app", "fx_stop_app",
        "fx_connect_existing", "fx_attach", "fx_set_control_mode",
    ),
    "See" to listOf(
        "fx_take_screenshot", "fx_compare_screenshots", "fx_capture_states",
        "fx_get_screenshot", "fx_get_picked",
    ),
    "Read the scene" to listOf(
        "fx_get_scene_graph", "fx_find_nodes", "fx_get_property",
        "fx_get_node_details", "fx_describe_at",
    ),
    "Ask why and inspector" to listOf(
        "fx_explain_layout", "fx_explain_style", "fx_suggest_override",
        "fx_explain_property", "fx_open_inspector", "fx_wait_for_prompt", "fx_reply_to_prompt",
    ),
    "Find problems" to listOf(
        "fx_lint_visual", "fx_lint_css", "fx_lint_fxml", "fx_get_ui_errors", "fx_get_logs",
    ),
    "Drive" to listOf(
        "fx_click_node", "fx_type_keys", "fx_set_text", "fx_set_property", "fx_eval_js", "fx_lock_input",
    ),
    "Apply edits" to listOf(
        "fx_reload", "fx_reload_fxml", "fx_set_watch_mode",
    ),
    "Lock it in" to listOf(
        "fx_get_journal", "fx_export_test",
    ),
)

private fun slug(heading: String): String =
    heading.lowercase().replace(Regex("[^a-z0-9 _-]"), "").replace(' ', '-')

private fun formatParamType(def: Map<String, Any>): String {
    val base = "`${def["type"] ?: "any"}`"
    val enumValues = (def["enum"] as? List<*>)?.joinToString(" \\| ") { "`$it`" }
    return if (enumValues != null) "$base ($enumValues)" else base
}

/** Markdown tool reference generated from the live registry (`--tool-reference`, written to docs/tools.md). */
fun toolReference(reg: ToolRegistry): String = buildString {
    val byName = reg.all().associateBy { it.name }
    val seen = LinkedHashSet<String>()
    val grouped = buildList {
        for ((group, names) in TOOL_GROUPS) {
            val tools = names.mapNotNull { name -> byName[name]?.also { seen += name } }
            if (tools.isNotEmpty()) add(group to tools)
        }
        val other = reg.all().filter { it.name !in seen }
        if (other.isNotEmpty()) add("Other" to other)
    }

    appendLine("# FXLoop tool reference")
    appendLine()
    appendLine("_Generated from the tool registry (`java -jar fxloop-server-*-all.jar --tool-reference`). Do not edit by hand._")
    appendLine()
    appendLine("For the workflow-oriented introduction, see the [MCP server overview](mcp.md). Tool kinds indicate side effects: `read`, `idempotent_write`, `write`, and `destructive`. `fx_eval_js` is disabled unless `FXLOOP_WEBVIEW=true`.")
    appendLine()
    appendLine(grouped.joinToString(" · ") { (group, _) -> "[$group](#${slug(group)})" } + " · [Node target](#node-target)")
    appendLine()
    appendLine("| Tool | Category | Kind | Summary |")
    appendLine("|---|---|---|---|")
    for ((group, tools) in grouped) {
        for (t in tools) {
            appendLine("| [`${t.name}`](#${t.name}) | [$group](#${slug(group)}) | `${t.kind.name.lowercase()}` | ${t.title} |")
        }
    }

    appendLine()
    appendLine("## Common parameters")
    appendLine()
    appendLine("### Node target")
    appendLine()
    appendLine("Tools that operate on a JavaFX node accept any one of `handle`, `path`, `query`, or `picked: true` (pass `windowIndex` to scope `query` or pick a non-focused window):")
    appendLine()
    appendLine("| Param | Type | Description |")
    appendLine("|---|---|---|")
    appendLine("| `handle` | `integer` | Session node handle (from `fx_get_scene_graph` / `fx_find_nodes`) |")
    appendLine("| `path` | `string` | Stable node path, e.g. `Stage[0]/Scene/VBox/Button#addBtn` or `Stage[0]//Button#addBtn` |")
    appendLine("| `query` | `string` | CSS selector that must match exactly one node, e.g. `#addBtn` |")
    appendLine("| `windowIndex` | `integer` | Window index (see `fx_get_app_status.windows`); default: focused window |")
    appendLine("| `picked` | `boolean` | Target the node the developer picked in pick mode (see `fx_get_picked`) |")

    for ((group, tools) in grouped) {
        appendLine()
        appendLine("## $group")
        for (t in tools) {
            @Suppress("UNCHECKED_CAST")
            val props = t.inputSchema["properties"] as Map<String, Map<String, Any>>
            val required = (t.inputSchema["required"] as? List<*>).orEmpty()
            val hasTarget = TARGET_PARAMS.all { it in props }
            val specificProps = if (hasTarget) props.filterKeys { it !in TARGET_PARAMS } else props

            appendLine()
            appendLine("### ${t.name}")
            appendLine()
            val meta = buildString {
                append("_Kind: `${t.kind.name.lowercase()}`_")
                if (hasTarget) append(" · Accepts [node target parameters](#node-target).")
            }
            appendLine(meta)
            appendLine()
            appendLine(t.description)
            when {
                props.isEmpty() -> {
                    appendLine()
                    appendLine("_No parameters._")
                }
                specificProps.isEmpty() -> Unit
                else -> {
                    appendLine()
                    appendLine("| Param | Type | Required | Description |")
                    appendLine("|---|---|---|---|")
                    specificProps.forEach { (name, def) ->
                        val desc = def["description"]?.toString().orEmpty().replace("|", "\\|").replace("\n", " ")
                        appendLine("| `$name` | ${formatParamType(def)} | ${if (name in required) "yes" else ""} | $desc |")
                    }
                }
            }
        }
    }
}

/** `--help`: the jar's commands; anything else starts the MCP server, which a developer at a terminal would not expect. */
fun usage(): String = """
    fxloop-server ${Versions.SERVER}: the FXLoop MCP server (stdio). Register it with your agent as `java -jar <this jar>`.

    java -jar fxloop-server.jar                      start the MCP server on stdin/stdout (what the agent runs)
    java -jar fxloop-server.jar --version            version, build commit and a JDK check
    java -jar fxloop-server.jar doctor [--json] [--client=claude-code|cursor|vscode|codex|gemini|all]
                                                     check the project in the current directory (never writes)
    java -jar fxloop-server.jar attach [<pid>] [--list] [--once] [--project-dir=<dir>] [--watch=css-only|all|off]
                                                     dev bar and CSS inspector for a JavaFX app you already started
    java -jar fxloop-server.jar --seed-local-repo    write FXLoop's Maven artifacts into ~/.m2 (until Maven Central)
    java -jar fxloop-server.jar --tool-reference     the MCP tool reference as Markdown

    Server options are --key=value flags, FXLOOP_* environment variables or an fxloop.properties file in the project
    (docs/configuration.md). Setup guide: https://github.com/h9lxyz/fxloop/blob/main/INSTALL.md
""".trimIndent() + "\n"

/** stdio MCP server entry point. stdout carries MCP frames only; all logging goes to stderr. */
fun main(args: Array<String>) {
    if (args.firstOrNull() in setOf("--help", "-h", "help")) {
        print(usage())
        return
    }
    // `doctor` is a read-only diagnostic: no Config/MCP startup (PRD 11 §6)
    if (args.firstOrNull() == "doctor") exitProcess(org.lab59.fxloop.server.cli.Doctor.main(args.drop(1)))
    // `attach`: a terminal command for a developer-run app, no MCP; quiet logging unless FXLOOP_LOG asks for more
    if (args.firstOrNull() == "attach") {
        System.setProperty("java.awt.headless", "true")
        val level = System.getenv("FXLOOP_LOG")
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level ?: "warn")
        // the AUDIT lines (attach requested / attached) stay visible on stderr unless FXLOOP_LOG sets another level
        if (level == null) System.setProperty("org.slf4j.simpleLogger.log.org.lab59.fxloop.audit", "info")
        exitProcess(org.lab59.fxloop.server.attach.AttachCommand.main(args.drop(1)))
    }
    System.setProperty("java.awt.headless", "true")
    System.getenv("FXLOOP_LOG")?.let { System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", it) }
    if (args.any { it == "--version" }) {
        // Not MCP mode, so stdout is free. Exit code is non-zero when the JDK check fails.
        val code = org.lab59.fxloop.server.cli.JdkCheck.run(Versions.SERVER)
        if (code != 0) exitProcess(code)
        return
    }
    // TEMPORARY pre-Central bridge, removed after Central publishing (PRD 10 §7).
    if (args.any { it == "--seed-local-repo" }) exitProcess(org.lab59.fxloop.server.cli.SeedLocalRepo.run(args))
    val config = Config.load(System.getenv(), args)
    if (args.any { it == "--tool-reference" }) {
        print(toolReference(registry(Ctx(config.copy(webview = true)))))
        return
    }
    val log = LoggerFactory.getLogger("org.lab59.fxloop.server")
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
    log.info("fxloop {} ready: project {} ({} tools, headless={}, prepare-route={})", Versions.SERVER, config.projectDir, reg.all().size,
        config.headless ?: "no", config.prepareRoute)
    Runtime.getRuntime().addShutdownHook(Thread {
        ctx.shutdown()
        runCatching { server.close() }
    })
    done.await()
    log.info("MCP client is gone; shutting down")
    exitProcess(0)
}

/**
 * `--sidecar=true`, started by `fxloop:run` / `fxloopRun` next to the app: no MCP, the [InspectorHelper] loop. Waits up to
 * two minutes for the app's handshake (it starts alongside the app), then exits with the app or its parent.
 */
private fun runSidecar(ctx: Ctx): Nothing {
    ParentWatchdog.start { exitProcess(0) }
    Runtime.getRuntime().addShutdownHook(Thread { ctx.shutdown() })
    val viewer = ctx.ensureViewer(ctx.config.viewerPort ?: 0)
    System.err.println("[fxloop] CSS inspector: ${viewer.inspectUrl()} (also via Open CSS inspector in the dev bar)")
    InspectorHelper.serve(ctx, ctx.session.fxloopDir.resolve("handshake.json"), AppSession.Mode.EMBEDDED, null,
        System.currentTimeMillis() + 120_000)
    exitProcess(0)
}

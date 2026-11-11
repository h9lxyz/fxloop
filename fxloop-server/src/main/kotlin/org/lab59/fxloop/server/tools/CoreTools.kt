package org.lab59.fxloop.server.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.protocol.Paging
import org.lab59.fxloop.server.app.LiveState
import org.lab59.fxloop.server.mcp.Schema
import org.lab59.fxloop.server.mcp.ToolDef
import org.lab59.fxloop.server.mcp.ToolDef.Kind
import org.lab59.fxloop.server.mcp.ToolRegistry
import org.lab59.fxloop.server.mcp.ToolResult

internal fun ToolRegistry.tool(name: String, title: String, kind: Kind, description: String, schema: Schema,
    handler: (ObjectNode) -> ToolResult) = add(ToolDef(name, title, description, schema.build(), kind, handler))

internal fun ok(text: String, s: ObjectNode) = ToolResult.ok(text, s)

/** Lifecycle, ears and eyes-as-data tools (PRD 01 §7). */
object CoreTools {
    fun register(reg: ToolRegistry, ctx: Ctx) {
        val s = ctx.session

        reg.tool("fx_get_app_status", "App status", Kind.READ,
            "Application state (starting/running/exited/stopped), pid, uptime, mode, FCP connection, showing windows " +
                "(index, title, type STAGE|POPUP|DIALOG), the FXML load registry and scratchCss? (CSS the developer tried live in " +
                "the inspector: apply it to the stylesheets, then fx_reload{clearScratch:true}), scratchProps? ([{h, path, property, before, after}] layout/property edits the developer tried live: apply them to the FXML or code; clearScratch reverts them). Auto-starts a never-started app, or connects " +
                "to the one the developer already runs from this project (mvn fxloop:run, gradle fxloopRun, FxLoopDev.install()).",
            Schema.obj()) {
            val started = s.ensureStarted()
            val st = s.status()
            ctx.viewer?.let { v -> st.put("viewerUrl", v.url()); st.put("inspectorUrl", v.inspectUrl()) }
            st.put("watchMode", ctx.watch.mode)
            st.put("screensDir", ctx.visuals.relative(ctx.visuals.store.dir))
            val live = ctx.live.status(st["human"])
            listOf("control", "controlSetting", "human", "actionNeeded", "inputLock").forEach { k -> st.set<JsonNode>(k, live[k]) }
            ctx.scratchProps.list().takeIf { it.isNotEmpty() }?.let { st.set<JsonNode>("scratchProps", org.lab59.fxloop.server.see.ScratchProps.toJson(it)) }
            val windows = st.path("windows").joinToString("; ") { "#${it["index"]} ${it.path("type").asText()} '${it.path("title").asText("")}'" }
            val needed = live.path("actionNeeded").joinToString(", ") { "${it.path("reason").asText()} · ${it.path("next").asText()}" }
            val how = when {
                !started -> ""
                s.mode == org.lab59.fxloop.server.app.AppSession.Mode.EMBEDDED -> "(connected to the app the developer is running) "
                else -> "(app auto-started) "
            }
            ok(how + "${st["state"].asText()} pid=${st.path("pid").asText("-")} " +
                "windows=[${windows}] uiErrors=${st.path("uiErrors").asInt()}\n" +
                "control=${live["control"].asText()} actionNeeded=[$needed]", st)
        }

        reg.tool("fx_set_control_mode", "Set control mode", Kind.IDEMPOTENT_WRITE,
            "Who may drive the UI: agent (the agent owns mouse and keyboard; human input during an action is a conflict) or " +
                "shared (human and agent interleave; agent clicks/keys use synthetic events and restarts wait for the developer). " +
                "Ask the developer before switching to agent while they are present. Returns the status.",
            Schema.obj().strEnum("mode", "agent or shared", "agent", "shared").required("mode")) { a ->
            val mode = if (a["mode"].asText() == "shared") LiveState.Control.SHARED else LiveState.Control.AGENT
            val r = ctx.live.setControl(mode, "tool")
            r.setAll<JsonNode>(ctx.live.status(s.status().path("human")))
            ok("control: ${r["previous"].asText()} → ${r["control"].asText()}", r)
        }

        reg.tool("fx_lock_input", "Lock developer input", Kind.IDEMPOTENT_WRITE,
            "Temporarily block the developer's mouse and keyboard input to the app windows while you drive the UI like a " +
                "human (a click-through, a multi-step form) so stray input cannot interfere. Inspector editing pauses too, and " +
                "the dev bar shows that the developer is watching, what you are doing and a Take over button. Tell the " +
                "developer before you lock; release it (locked=false) as soon as the sequence is done. It expires after " +
                "`seconds` anyway. inputLock.releasedBy=developer means they took over: stop driving and ask.",
            Schema.obj()
                .bool("locked", "true locks, false releases")
                .str("reason", "Shown to the developer, e.g. 'walking through the checkout flow'")
                .integer("seconds", "Lock duration (default 60)", 5, 600)
                .required("locked")) { a ->
            val r = if (a.path("locked").asBoolean()) {
                ctx.live.lock(a.path("seconds").asInt(60).coerceIn(5, 600), a.path("reason").asText(null))
            } else {
                ctx.live.unlock("tool")
            }
            ok(if (r.path("locked").asBoolean()) "input locked for ${r.path("remainingMs").asLong() / 1000} s: the developer is watching"
            else "input unlocked: the developer has access", Json.obj { set<JsonNode>("inputLock", r) })
        }

        reg.tool("fx_start_app", "Start app", Kind.WRITE,
            "Build (compile + launch descriptor) and launch the app with the fxloop agent; waits for the FCP handshake. " +
                "When the developer already runs it from this project (fxloop:run, fxloopRun, FxLoopDev.install()), connects " +
                "to that instead (no restart in that mode). Error if already running.", Schema.obj()) {
            s.start()
            if (s.mode == org.lab59.fxloop.server.app.AppSession.Mode.EMBEDDED) ok("connected to the app the developer is running (pid ${s.pid})", s.status())
            else ok("started in ${s.lastStartupMs} ms", s.status().put("startupMs", s.lastStartupMs))
        }

        reg.tool("fx_restart_app", "Restart app", Kind.DESTRUCTIVE,
            "Kill and relaunch the app (MCP session survives). replay=true re-executes the action journal afterwards " +
                "(restart-and-restore). Stage geometry is restored automatically.",
            Schema.obj().bool("replay", "Replay the action journal after restart (default false)")) { a ->
            val r = ctx.restartWithReplay(a.path("replay").asBoolean(false))
            ctx.clearRestartNeeded()
            r.setAll<JsonNode>(s.status())
            ok("restarted in ${r["startupMs"]} ms" + (r["replay"]?.let { "; replay $it" } ?: ""), r)
        }

        reg.tool("fx_stop_app", "Stop app", Kind.DESTRUCTIVE, "Stop the app (destroy, escalate to force-kill after 3 s).",
            Schema.obj()) {
            val r = s.stop()
            ok("stopped" + (r["exitCode"]?.let { " (exit $it)" } ?: ""), r)
        }

        reg.tool("fx_get_logs", "Get logs", Kind.READ,
            "Tail of the app's process stdout/stderr (source=process) and/or captured in-app java.util.logging records " +
                "(source=app, needs FXLOOP_APP_LOGS=true).",
            Schema.obj().integer("lines", "Lines to return (default 100, max 1000)", 1, 1000)
                .strEnum("stream", "Process stream (default both)", "both", "stdout", "stderr")
                .strEnum("source", "process | app | both (default process)", "process", "app", "both")) { a ->
            val n = a.path("lines").asInt(100).coerceIn(1, 1000)
            val stream = a.path("stream").asText("both")
            val source = a.path("source").asText("process")
            val lines = ArrayList<String>()
            if (source != "app") {
                if (stream != "stderr") lines += s.stdout.tail(n).map { "[out] $it" }
                if (stream != "stdout") lines += s.stderr.tail(n).map { "[err] $it" }
            }
            if (source != "process") {
                lines += s.appLogs.since(0).takeLast(n).map { "[app ${it.path("level").asText()} ${it.path("logger").asText()}] ${it.path("message").asText()}" }
            }
            val tail = lines.takeLast(n)
            ok(tail.joinToString("\n").ifEmpty { "(no log lines)" }, Json.obj {
                set<JsonNode>("lines", Json.strings(tail))
                put("truncated", lines.size > n)
            })
        }

        reg.tool("fx_get_ui_errors", "Get UI errors", Kind.READ,
            "Uncaught exceptions captured on the JavaFX Application Thread (class, message, top frames, dedupCount). " +
                "Pass sinceSeq=lastSeq to poll for new ones.",
            Schema.obj().integer("sinceSeq", "Return errors with seq > sinceSeq (default 0)")
                .integer("limit", "Max errors (default 20)", 1, 200)) { a ->
            val all = s.errors.since(a.path("sinceSeq").asLong(0))
            val limit = a.path("limit").asInt(20)
            val page = all.take(limit)
            ok(if (page.isEmpty()) "no UI errors" else page.joinToString("\n") {
                "#${it["seq"]} ${it.path("exceptionClass").asText()}: ${it.path("message").asText()} (x${it.path("dedupCount").asInt(1)}) at ${it.path("topFrame").asText()}"
            }, Json.obj {
                set<JsonNode>("errors", Json.array().addAll(page))
                put("lastSeq", s.errors.lastSeq())
                put("has_more", all.size > limit)
            })
        }

        reg.tool("fx_get_scene_graph", "Get scene graph", Kind.READ,
            "Bounded JSON of the logical scene graph of a window (or subtree): h (handle), path, type, fxId, styleClass, " +
                "text, value, visible (only when false), disabled/focused (only when true), boundsScreen, kids. Output is budgeted " +
                "(depth, nodes, ~56 KB) with {truncated:true} markers. shown:false marks a node that is not rendered (it or an " +
                "ancestor, e.g. an inactive tab or ribbon page, is hidden or transparent); it has no boundsScreen. User nodes " +
                "hosted by built-in controls (graphics, cell graphics, tab/column graphics, a showing placeholder, the current " +
                "page) are children. Virtualized controls report itemCount, visibleCells and excluded {reason:virtualized, count} " +
                "for items without a rendered cell. Skin internals of built-in controls are omitted unless skin=true.",
            Schema.obj().target().integer("rootHandle", "Subtree root handle")
                .bool("skin", "Include skin (control-internal) nodes; they carry skin:true and no path, so address them by handle")
                .integer("maxDepth", "Depth budget (default 12)", 1, 64).integer("maxNodes", "Node budget (default 400)", 1, 5000)) { a ->
            s.ensureStarted()
            val r = s.call(Fcp.Method.GET_SCENE_GRAPH, a) as ObjectNode
            ok("window '${r.path("window").path("title").asText("")}' nodes=${r.path("nodeCount").asInt()} truncated=${r.path("truncated").asBoolean()}\n" +
                Json.write(r["root"]), r)
        }

        reg.tool("fx_find_nodes", "Find nodes", Kind.READ,
            "Find nodes by CSS selector (query, e.g. '#addBtn', '.button'), case-insensitive text contains (text), or " +
                "simple type name (type). Searches all showing windows incl. popups unless windowIndex is set. Paginated. " +
                "shown:false means not rendered (e.g. on an inactive tab or ribbon page); such hits have no boundsScreen.",
            Schema.obj().str("query", "CSS selector").str("text", "Text contains (case-insensitive)").str("type", "Simple class name, e.g. Button")
                .integer("windowIndex", "Restrict to one window").integer("limit", "Page size (default 20)", 1, 200)
                .integer("offset", "Page offset")) { a ->
            s.ensureStarted()
            val r = s.call(Fcp.Method.FIND_NODES, a) as ObjectNode
            ok("${r["total"]} match(es)" + r["items"].joinToString("") { "\n  h=${it["h"]} ${it["path"].asText()}" +
                (it["text"]?.let { t -> " '${t.asText()}'" } ?: "") }, r)
        }

        reg.tool("fx_get_property", "Get property", Kind.READ,
            "Read a JavaFX property of a node: value, type, bound (true if bound — writes would fail), writable.",
            Schema.obj().target().str("name", "Property name, e.g. text, disable, value, prefWidth; window.<name> reads the node's Stage, e.g. window.width").required("name")) { a ->
            val r = s.call(Fcp.Method.GET_PROPERTY, a) as ObjectNode
            ok("${r["name"].asText()} = ${r["value"]} (${r["type"].asText()}${if (r["bound"].asBoolean()) ", bound" else ""})", r)
        }

        reg.tool("fx_get_node_details", "Get node details", Kind.READ,
            "Reflective dump of every property of a node (name, one-line value, type, bound, styleable). Paginated.",
            Schema.obj().target().integer("limit", "Page size (default 60)", 1, 200).integer("offset", "Page offset")) { a ->
            val r = s.call(Fcp.Method.NODE_DETAILS, a) as ObjectNode
            ok("${r.path("class").asText()}: ${r["total"]} properties" + r["items"].joinToString("") { "\n  ${it["name"].asText()}=${it["value"]}" }, r)
        }

        reg.tool("fx_describe_at", "Describe at point", Kind.READ,
            "Deepest addressable node at a screen point (from a screenshot or boundsScreen): path, ancestors, bounds.",
            Schema.obj().number("x", "Screen x").number("y", "Screen y").required("x", "y")) { a ->
            val r = s.call(Fcp.Method.DESCRIBE_AT, a) as ObjectNode
            ok("${r.path("node").path("path").asText()} (picked ${r.path("pickedType").asText()})", r)
        }

        if (ctx.config.testMode) {
            reg.tool("fx_test_block_fx_thread", "Block FX thread (test)", Kind.DESTRUCTIVE,
                "TEST ONLY: blocks the FX thread for ms milliseconds to exercise FX_TIMEOUT handling.",
                Schema.obj().integer("ms", "Block duration (default 8000)")) { a ->
                ok("blocking", s.call(Fcp.Method.BLOCK_FX_THREAD, a) as ObjectNode)
            }

            reg.tool("fx_test_human_input", "Inject human input (test)", Kind.WRITE,
                "TEST ONLY: fires synthetic events outside the agent-action bracket, as if the developer clicked (kind=click, " +
                    "target), typed text into a field (kind=text, target, text, commit) or pressed keys (kind=keys, keys).",
                Schema.obj().strEnum("kind", "click | text | keys", "click", "text", "keys").target().str("text", "Text for kind=text")
                    .any("keys", "Key presses for kind=keys, as in fx_type_keys").bool("commit", "Press Enter after typing (kind=text)")
                    .required("kind")) { a ->
                ok("injected human ${a["kind"].asText()}", s.call(Fcp.Method.TEST_HUMAN_INPUT, a) as ObjectNode)
            }

            reg.tool("fx_test_get_bar_state", "Get dev bar state (test)", Kind.READ,
                "TEST ONLY: what the dev bar currently renders (bar mode, texts, windows, control history).", Schema.obj()) {
                ok("bar state", s.call(Fcp.Method.GET_BAR_STATE) as ObjectNode)
            }

            reg.tool("fx_test_set_pick_mode", "Set pick mode (test)", Kind.WRITE,
                "TEST ONLY: turns the agent's pick mode on or off, as the dev bar's pick button does.",
                Schema.obj().bool("on", "Pick mode on/off").integer("windowIndex", "Window to pick in").required("on")) { a ->
                ok("pick mode ${if (a["on"].asBoolean()) "on" else "off"}", s.call(Fcp.Method.SET_PICK_MODE, a) as ObjectNode)
            }
        }
    }

    /** Paginates a list into the standard envelope (shared by several tools). */
    fun page(items: List<JsonNode>, a: ObjectNode, defaultLimit: Int): ObjectNode =
        Paging.page(items, a.path("offset").asInt(0), a.path("limit").asInt(defaultLimit))
}

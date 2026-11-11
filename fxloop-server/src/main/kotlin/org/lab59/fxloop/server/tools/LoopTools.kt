package org.lab59.fxloop.server.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.tools.attach.VirtualMachine
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.server.Config
import org.lab59.fxloop.server.app.AppSession
import org.lab59.fxloop.server.lint.FxmlLint
import org.lab59.fxloop.server.app.ActionNeeded
import org.lab59.fxloop.server.loop.ReloadStory
import org.lab59.fxloop.server.loop.Reloader
import org.lab59.fxloop.server.loop.SceneDiff
import org.lab59.fxloop.server.mcp.Schema
import org.lab59.fxloop.server.mcp.ToolDef.Kind
import org.lab59.fxloop.server.mcp.ToolException
import org.lab59.fxloop.server.mcp.ToolRegistry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** The dev loop (PRD 02 §3–§9, PRD 03 §3): reload tiers, FXML lint, watch mode, embedded/attach connections. */
object LoopTools {
    private val audit = LoggerFactory.getLogger("org.lab59.fxloop.audit")

    fun register(reg: ToolRegistry, ctx: Ctx) {
        val s = ctx.session

        reg.tool("fx_reload", "Reload", Kind.IDEMPOTENT_WRITE,
            "Apply your edits: detects changed files since the last launch/reload (or takes files=[…]) and routes them " +
                "through the cheapest tiers — css (live re-apply + visualDiff), fxml (live swap of registered documents), " +
                "java (in-process javac → HotSwap; compile errors returned as data, nothing swapped), then remount (after a " +
                "successful HotSwap or FXML swap the registered FxLoopDev.view mounts are rebuilt in place, view state carried " +
                "over; tier restage with FXLOOP_RESTAGE), restart (+replay) when needed — but a restart waits while the " +
                "developer is using the app. Returns tiersApplied, compileErrors?, stateCarried?{restored,skipped}, " +
                "needsRestart?, restarted?, restartDeferred? (\"humanActive\"), restartFailed?, waitingForBuild?, replay?, " +
                "visualDiff?, effects.",
            Schema.obj().strArray("files", "Explicit changed files (project-relative); default: auto-detect")
                .bool("allowRestart", "Restart automatically when required (default true)")
                .bool("replay", "Replay the journal after a restart (default FXLOOP_REPLAY, true)")
                .bool("visualDiff", "Before/after pixel diff for the css tier (default true)")
                .bool("remount", "Rebuild registered views after a HotSwap (default true)")
                .bool("carryState", "Carry view state (selection, scroll, text, …) across a remount/FXML swap (default true)")
                .bool("restartWhenHumanActive", "Restart even while the developer is using the app (default false: the restart is deferred)")
                .bool("clearScratch", "Remove the inspector's scratch CSS (fx_get_app_status.scratchCss) and revert its scratch property " +
                    "edits (scratchProps) once you have applied them to the sources; the css visualDiff then compares scratch vs. " +
                    "source (default false)")
                .effects()) { a ->
            s.ensureStarted()
            if (s.mode == AppSession.Mode.ATTACHED) {
                guardLive { ctx.live.setActionNeeded(listOf(ActionNeeded("attachedEdits",
                    "Edits don't apply live when attached · relaunch with FXLoop or your IDE", "Relaunch with FXLoop or your IDE"))) }
            }
            if (a.path("clearScratch").asBoolean(false)) runCatching { ctx.clearScratchProps() }
            val effectsOn = a.path("effects").asBoolean(true)
            val before = if (effectsOn) runCatching { ctx.snapshot() }.getOrNull() else null
            val files = a["files"]?.map { it.asText() }
            val r = ctx.reloader.reload(Reloader.Options(
                files = files,
                allowRestart = a.path("allowRestart").asBoolean(true),
                replay = a.path("replay").asBoolean(ctx.config.replayOnRestart),
                visualDiff = a.path("visualDiff").asBoolean(true),
                remount = a.path("remount").asBoolean(true),
                carryState = a.path("carryState").asBoolean(true),
                restartWhenHumanActive = a.path("restartWhenHumanActive").asBoolean(false),
                clearScratch = a.path("clearScratch").asBoolean(false),
            )) { replay, rebuild -> ctx.restartWithReplay(replay, rebuild) }
            publish(ctx, r)
            if (before != null && s.connected) {
                Thread.sleep(100)
                r.set<JsonNode>("effects", SceneDiff.diff(before, ctx.snapshot()))
            }
            ok(summary(r), r)
        }

        reg.tool("fx_reload_fxml", "Reload FXML", Kind.IDEMPOTENT_WRITE,
            "Force a live swap of one FXML document (project-relative path): re-copies it, re-runs FXMLLoader and replaces " +
                "the old root in place. The controller is re-created; view state (selection, scroll, text, …) is carried over " +
                "unless carryState=false — see stateCarried in the result.",
            Schema.obj().str("path", "FXML file path, e.g. src/main/resources/app/main.fxml")
                .bool("carryState", "Carry view state across the swap (default true)").required("path")) { a ->
            if (s.mode == AppSession.Mode.ATTACHED) {
                guardLive { ctx.live.setActionNeeded(listOf(ActionNeeded("attachedEdits",
                    "Edits don't apply live when attached · relaunch with FXLoop or your IDE", "Relaunch with FXLoop or your IDE"))) }
            }
            val r = ctx.reloader.reload(Reloader.Options(files = listOf(a["path"].asText()), allowRestart = false, visualDiff = false,
                javaTier = false, carryState = a.path("carryState").asBoolean(true))) { _, _ -> Json.obj() }
            publish(ctx, r)
            ok(summary(r), r)
        }

        reg.tool("fx_set_watch_mode", "Set watch mode", Kind.IDEMPOTENT_WRITE,
            "Human-facing auto-apply on save: css-only (default) re-applies CSS instantly; all also swaps FXML and hot-swaps Java/Kotlin; " +
                "off disables. Never restarts.",
            Schema.obj().strEnum("mode", "css-only | all | off", "css-only", "all", "off").required("mode")) { a ->
            ctx.applyWatchMode(a["mode"].asText())
            ok("watch mode ${ctx.watch.mode}", Json.obj { put("mode", ctx.watch.mode) })
        }

        reg.tool("fx_lint_css", "Lint CSS", Kind.READ,
            "Dead, broken and misspelt rules in the author stylesheets of the open windows: unused (the rule's selectors match no " +
                "node in the open windows), unknownSelectors (a style class or #id no node has, e.g. .bttn), and parse errors per " +
                "sheet, all with file:line. Low severity: a rule may style a screen that is not open, so open the other screens " +
                "(or pass windowIndex) before deleting anything. Pseudo-classes are ignored when matching.",
            Schema.obj().integer("windowIndex", "One window (default: every showing window)", 0, 64)) { a ->
            s.ensureStarted()
            val r = s.call(Fcp.Method.CSS_LINT, ctx.withCssRoots(a)) as ObjectNode
            ok(lintCssText(r), r)
        }

        reg.tool("fx_lint_fxml", "Lint FXML", Kind.READ,
            "Static FXML check against compiled controllers (works before the app starts): missingFxIdField, typeMismatch, " +
                "missingHandlerMethod, handlerSignatureInvalid, unresolvedImport, duplicateFxId — with line numbers and fixes.",
            Schema.obj().str("path", "One FXML file (project-relative)").bool("all", "Lint every FXML under the source/resource roots")) { a ->
            val d = s.descriptor ?: s.build.prepare()
            val files = if (a.hasNonNull("path")) listOf(ctx.visuals.confine(a["path"].asText()))
            else FxmlLint.findFxml((d.resourceRoots + d.sourceRoots).map(Path::of))
            val cp = (listOf(d.outputDir) + d.classpath + d.modulePath).map(Path::of)
            val result = FxmlLint.lint(files, cp, ctx.config.projectDir)
            val items = result.findings.map { Json.tree(it) }
            val r = CoreTools.page(items, a, 100).put("filesChecked", result.filesChecked)
            ok("${result.findings.size} finding(s) in ${result.filesChecked} file(s)" + result.findings.joinToString("") {
                "\n  ${it.file}:${it.line ?: "?"} ${it.check}: ${it.detail}" + (it.suggestion?.let { s -> " → $s" } ?: "")
            }, r)
        }

        reg.tool("fx_connect_existing", "Connect existing", Kind.WRITE,
            "Connect to an app started outside fxloop: mvn fxloop:run, gradle fxloopRun, or an IDE launch that called " +
                "FxLoopDev.install(). fx_get_app_status finds these by itself; use this for a non-default handshake. Reads its " +
                "owner-only handshake file. No supervision: restart is unavailable in this mode.",
            Schema.obj().str("handshakePath", "Handshake file (default target/fxloop/handshake.json)")) { a ->
            val p = ctx.visuals.confine(a.path("handshakePath").asText("target/fxloop/handshake.json"))
            val st = s.connectExisting(p, AppSession.Mode.EMBEDDED)
            ok("connected (embedded) pid=${st.path("pid").asText()}", st)
        }

        reg.tool("fx_attach", "Attach to JVM", Kind.WRITE,
            "Secondary mode: list local JVMs (list=true) or attach the agent to a running JavaFX app by pid. No reload " +
                "tiers and no restart in this mode. JDK 21+ targets print a dynamic-agent warning (JEP 451).",
            Schema.obj().integer("pid", "Target process id").bool("list", "List attachable JVMs")) { a ->
            if (!Config.attachAllowed) throw ToolException("DISABLED", "attach is disabled on this server (-Dfxloop.attach=false)", "use fx_start_app")
            if (a.path("list").asBoolean(false) || !a.hasNonNull("pid")) list() else attach(ctx, a["pid"].asLong())
        }
    }

    private inline fun guardLive(block: () -> Unit) {
        try {
            block()
        } catch (e: NotImplementedError) {
            // LiveState slice not merged yet
        } catch (e: RuntimeException) {
            audit.debug("live state update failed: {}", e.message)
        }
    }

    /** Hands the reload outcome to the dev bar (PRD 07 §7.1); never fails the reload. */
    private fun publish(ctx: Ctx, r: ObjectNode) = guardLive {
        val story = ReloadStory.of(r, ctx.session.mode, ctx.config.externalCompile)
        ctx.live.reloaded(story.lastReload, story.ok, story.actionNeeded)
    }

    private fun list(): org.lab59.fxloop.server.mcp.ToolResult {
        val me = ProcessHandle.current().pid().toString()
        val items = VirtualMachine.list().filter { it.id() != me }.map { vm ->
            val cmd = runCatching { ProcessHandle.of(vm.id().toLong()).flatMap { it.info().commandLine() }.orElse("") }.getOrDefault("")
            Json.obj {
                put("pid", vm.id().toLong())
                put("displayName", vm.displayName())
                put("javafxLikely", Regex("javafx|openjfx", RegexOption.IGNORE_CASE).containsMatchIn(cmd + vm.displayName()))
            }
        }
        return ok(items.joinToString("\n") { "${it["pid"]} ${it["displayName"].asText()}${if (it["javafxLikely"].asBoolean()) " (JavaFX)" else ""}" }
            .ifEmpty { "no attachable JVMs" }, Json.obj { set<JsonNode>("jvms", Json.array().addAll(items)) })
    }

    private fun attach(ctx: Ctx, pid: Long): org.lab59.fxloop.server.mcp.ToolResult {
        audit.info("AUDIT attach requested pid={} by MCP client", pid)
        val handshake = ctx.session.fxloopDir.resolve("attach-$pid.json")
        Files.createDirectories(handshake.parent)
        Files.deleteIfExists(handshake)
        val vm = try {
            VirtualMachine.attach(pid.toString())
        } catch (e: Exception) {
            throw ToolException("ATTACH_FAILED", "cannot attach to $pid: ${e.message}",
                "attach may be disabled (-XX:+DisableAttachMechanism) or the JVM belongs to another user; use the dev-loop mode")
        }
        val warnings = Json.array()
        try {
            val spec = vm.systemProperties.getProperty("java.specification.version", "17")
            if ((spec.substringBefore('.').toIntOrNull() ?: 17) >= 21) {
                warnings.add("target JDK $spec prints a dynamic-agent warning (JEP 451); launch it with -XX:+EnableDynamicAgentLoading for clean runs")
            }
            vm.loadAgent(ctx.session.agentJar().toString(), "handshake=$handshake")
        } catch (e: Exception) {
            throw ToolException("ATTACH_FAILED", "agent load failed: ${e.message}",
                "jlinked runtimes may lack java.instrument; relaunch via fx_start_app (dev-loop mode)")
        } finally {
            runCatching { vm.detach() }
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (!Files.exists(handshake) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (!Files.exists(handshake)) throw ToolException("ATTACH_FAILED", "agent loaded but no handshake within 15 s", "is JavaFX running in that JVM?")
        val st = ctx.session.connectExisting(handshake, AppSession.Mode.ATTACHED)
        st.set<JsonNode>("warnings", warnings)
        audit.info("AUDIT attached pid={}", pid)
        return ok("attached to $pid (mode attached: no reload tiers, no restart)" + warnings.joinToString("") { "\nwarning: ${it.asText()}" }, st)
    }

    private fun summary(r: ObjectNode): String {
        val sb = StringBuilder()
        val tiers = r.path("tiersApplied")
        sb.append(if (tiers.isEmpty) r.path("note").asText("nothing to reload") else
            tiers.joinToString(", ") { "${it["tier"].asText()}(${it["files"].size()} files, ${it["ms"]} ms)" })
        r["compileErrors"]?.let { errs ->
            sb.append("\nCOMPILE ERRORS (nothing swapped):")
            errs.forEach { sb.append("\n  ${it.path("file").asText()}:${it.path("line").asText()}:${it.path("col").asText()} ${it.path("message").asText()}") }
        }
        tiers.firstOrNull { it.path("tier").asText() == "remount" || it.path("tier").asText() == "restage" }?.let { t ->
            val d = t.path("details")
            val keys = d.path("remounted").joinToString(", ") { it.asText() }
            if (keys.isNotEmpty()) sb.append("\n${t["tier"].asText()}: remounted $keys")
            d.path("failed").forEach { sb.append("\n  remount FAILED ${it.path("key").asText()}: ${it.path("error").asText()}") }
            d.path("skipped").forEach { sb.append("\n  remount skipped ${it.path("key").asText()}: ${it.path("reason").asText()}") }
        }
        r["stateCarried"]?.let { sb.append("\nstate carried: ${it.path("restored").asInt()} restored, ${it.path("skipped").size()} skipped") }
        if (r.path("needsRestart").asBoolean()) {
            sb.append("\nneeds restart: ${r["restartReasons"]}")
            sb.append(when {
                r.path("restarted").asBoolean() -> " → restarted"
                r.has("restartDeferred") -> "\nrestart deferred: the developer is using the app — fx_reload{restartWhenHumanActive:true} or fx_restart_app to override"
                r.has("restartFailed") -> "\nrestart failed: ${r["restartFailed"].path("message").asText()}"
                else -> " (not restarted)"
            })
        }
        if (r.path("waitingForBuild").asBoolean()) sb.append("\nwaiting for a build: Java sources changed but no new classes appeared (external compile)")
        r["replay"]?.let { sb.append("\nreplay: $it") }
        r["visualDiff"]?.let { sb.append("\nvisualDiff: ${it["changedPct"]}% ${it["verdict"].asText()}" + (it["hint"]?.let { h -> " — ${h.asText()}" } ?: "")) }
        r["effects"]?.let { sb.append("\n").append(SceneDiff.summary(it)) }
        return sb.toString()
    }
}

/** "Why?" tools (PRD 03 §6) and the WebView bridge (§7). */
object WhyTools {
    fun register(reg: ToolRegistry, ctx: Ctx) {
        val s = ctx.session
        reg.tool("fx_explain_property", "Explain property", Kind.READ,
            "Why does a property have its value? value, type, bound, binding dependency tree (depth ≤ 5, owners as node " +
                "paths) and CSS styleOrigin. Bidirectional peers are not discoverable via public API (reported as unknown-peer).",
            Schema.obj().target().str("name", "Property name, e.g. disable").required("name")) { a ->
            val r = s.call(Fcp.Method.EXPLAIN_PROPERTY, a) as ObjectNode
            ok(Json.pretty(r), r)
        }
        reg.tool("fx_explain_style", "Explain style", Kind.READ,
            "CSS-styled properties of a node with their origin (USER_AGENT/AUTHOR/INLINE/USER), inline style, style " +
                "classes, pseudo-classes and candidate stylesheets along the parent chain. With rules (default) each property " +
                "also names the winner rule (selector, file:line), the overridden rules with reasons (all of them when " +
                "property is given) and blockers that stop an override (inline style, !important, code-set value). With " +
                "states=[\"hover\"] the cascade is simulated as if those pseudo-classes were set (nothing changes on the node; " +
                "items carry simulation:\"assumed\"). Paginated.",
            Schema.obj().target().str("property", "One CSS property, e.g. -fx-text-fill").integer("limit", "Page size", 1, 200)
                .integer("offset", "Page offset")
                .strArray("states", "Pseudo-classes to assume, without the colon, e.g. [\"hover\",\"focused\"]")
                .bool("rules", "Rule-level cascade: winner rule with file:line, overridden rules with reasons, blockers (default true). false = origin-only output as before")) { a ->
            val r = s.call(Fcp.Method.EXPLAIN_STYLE, ctx.withCssRoots(a)) as ObjectNode
            ok(r["items"].joinToString("\n") { styleLine(it) }
                .ifEmpty { "no CSS-styled properties" } + "\nstylesheets: " + r["candidateStylesheets"].joinToString { it["url"].asText() }, r)
        }
        reg.tool("fx_suggest_override", "Suggest CSS override", Kind.READ,
            "Suggest the smallest CSS rule that makes a property of a node take effect: selector, target file, whether " +
                "!important is needed, a ready-to-paste snippet, why, and blockers (inline style, code-set value). Does not " +
                "edit anything; apply it with your editor, then fx_reload.",
            Schema.obj().target().str("property", "CSS property, e.g. -fx-background-color")
                .str("value", "Desired value, e.g. #ff0000").str("file", "Stylesheet to put the rule in (project-relative); default: best match")
                .required("property")) { a ->
            val r = s.call(Fcp.Method.CSS_SUGGEST, ctx.withCssRoots(a)) as ObjectNode
            ok(buildString {
                append(r.path("snippet").asText(""))
                r.path("why").asText(null)?.let { append("\nwhy: ").append(it) }
                r.path("file").asText(null)?.let { append("\nfile: ").append(it) }
                r.path("blockers").forEach { append("\nblocker: ").append(blockerText(it)) }
            }, r)
        }
        reg.tool("fx_open_inspector", "Open CSS inspector", Kind.READ,
            "For the human developer: opens the browser CSS inspector (live scene tree, styles with rule file:line, " +
                "layout) on the running app, optionally preselecting a node. Returns the URL and whether a browser was " +
                "opened (FXLOOP_BROWSER=none disables opening). Do not call it to inspect yourself; use fx_explain_style.",
            Schema.obj().target()) { a ->
            var h: Int? = null
            var path: String? = null
            if (a.has("handle") || a.has("path") || a.has("query")) {
                s.ensureStarted()
                val t = (s.call(Fcp.Method.NODE_DETAILS, a) as ObjectNode).let { it["target"] ?: it }
                h = t.path("h").takeIf { it.isInt }?.asInt()
                path = t.path("path").asText(null)
            }
            val r = ctx.inspector.open(h, path)
            ok((if (r["opened"].asBoolean()) "opened " else "inspector at ") + r["url"].asText(), r)
        }
        reg.tool("fx_explain_layout", "Explain layout", Kind.READ,
            "Why is it there/that size? layout/parent/screen bounds, insets, min/pref/max sizes, parent constraints " +
                "(grow, margins, anchors, grid cell) and anomaly hints (unmanaged, translated, below pref width).",
            Schema.obj().target()) { a ->
            val r = s.call(Fcp.Method.EXPLAIN_LAYOUT, ctx.withCssRoots(a)) as ObjectNode
            ok(Json.pretty(r), r)
        }
        if (ctx.config.webview) {
            reg.tool("fx_eval_js", "Evaluate JavaScript", Kind.WRITE,
                "Run JavaScript in a WebView's engine on the FX thread; result is JSON-serialized.",
                Schema.obj().target().str("script", "JavaScript expression").integer("timeoutMs", "Timeout (default 3000)", 100, 30_000)
                    .required("script")) { a ->
                a.put("enabled", true)
                val r = s.call(Fcp.Method.EVAL_JS, a, a.path("timeoutMs").asLong(3000) + 2000) as ObjectNode
                ok("result: ${r["result"]}", r)
            }
        }
    }
}

private fun blockerText(b: com.fasterxml.jackson.databind.JsonNode) =
    "${b.path("kind").asText()}: ${b.path("cause").asText()}" + b.path("next").asText(null)?.let { " -> $it" }.orEmpty()

/** One text line per explain-style item: rule-level when the agent sent a winner, origin-only otherwise. */
/** Text summary of a `cssLint` result: one line per finding with `file:line`. */
internal fun lintCssText(r: JsonNode): String {
    fun at(it: JsonNode) = it.path("file").asText("?") + ":" + (it.path("line").takeIf { l -> l.isInt }?.asText() ?: "?")
    val unused = r.path("unused")
    val unknown = r.path("unknownSelectors")
    val errors = r.path("sheets").flatMap { sh -> sh.path("errors").map { e -> sh.path("file").asText(sh.path("url").asText("?")) to e } }
    return "${unused.size()} unused rule(s), ${unknown.size()} unknown selector(s), ${errors.size} parse error(s) in " +
        "${r.path("sheets").size()} sheet(s) over ${r.path("nodes").asInt()} node(s)" +
        unused.joinToString("") { "\n  unused ${at(it)} ${it.path("selector").asText()}" } +
        unknown.joinToString("") { "\n  unknown ${at(it)} ${it.path("selector").asText()} (no node has ${it.path("unknown").joinToString { u -> u.asText() }})" } +
        errors.joinToString("") { (f, e) -> "\n  error $f:${e.path("line").takeIf { l -> l.isInt }?.asText() ?: "?"} ${e.path("message").asText()}" } +
        (r.path("note").asText(null)?.let { "\n$it" } ?: "")
}

internal fun styleLine(it: com.fasterxml.jackson.databind.JsonNode): String {
    val base = "${it["cssProperty"].asText()} = ${it["value"]} [${it["origin"].asText()}]"
    val w = it.path("winner")
    if (!w.isObject) return base
    val where = w.path("file").asText(null)?.let { f -> " ($f${if (w.has("line")) ":" + w.path("line").asInt() else ""})" }.orEmpty()
    val n = if (it.has("overridden")) it.path("overridden").size() else it.path("overriddenCount").asInt()
    return buildString {
        append(base).append(" <- ").append(w.path("selector").asText("?")).append(where)
        if (n > 0) append(" (+$n overridden)")
        it.path("blockers").forEach { b -> append("\n    blocker ").append(blockerText(b)) }
    }
}

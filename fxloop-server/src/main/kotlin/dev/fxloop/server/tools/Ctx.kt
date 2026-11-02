package dev.fxloop.server.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.Tokens
import dev.fxloop.server.Config
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.app.ActionNeeded
import dev.fxloop.server.app.LiveState
import dev.fxloop.server.loop.Journal
import dev.fxloop.server.loop.JournalEntry
import dev.fxloop.server.loop.Reloader
import dev.fxloop.server.loop.SceneDiff
import dev.fxloop.server.loop.WatchMode
import dev.fxloop.server.mcp.ToolResult
import dev.fxloop.server.see.CssMappings
import dev.fxloop.server.see.Inspector
import dev.fxloop.server.see.Prompts
import dev.fxloop.server.see.ScreenStore
import dev.fxloop.server.see.Viewer
import dev.fxloop.server.see.Visuals
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Shared services behind the tool surface: session, visuals, journal, reload, effects, replay. */
class Ctx(val config: Config) {
    private val log = LoggerFactory.getLogger(Ctx::class.java)
    val session = AppSession(config)
    val visuals = Visuals(session, ScreenStore(session.fxloopDir.resolve("screens"), config.screensKeep), config.screenshotRatePerSec)
    val journal = Journal(500)
    val live = LiveState(config, session)
    val reloader = Reloader(session, visuals, live)
    val watch: WatchMode = WatchMode { files ->
        val seq = live.reloadCount()
        val r = reloader.reload(Reloader.Options(files = files, allowRestart = false, replay = false, visualDiff = false,
            javaTier = watchModeName() == "all", consume = false)) { _, _ -> Json.obj() }
        if (live.reloadCount() == seq) {
            val tiers = r.path("tiersApplied").map { it.asText() }
            live.reloaded("watch: " + tiers.joinToString(", ").ifEmpty { "no change" }, true, live.actionItems())
        }
        r
    }
    var viewer: Viewer? = null
    private val restarting = AtomicBoolean(false)

    /** Stylesheets outside the project the developer mapped to their source in the inspector (PRD 08 §5). */
    val cssMappings = CssMappings(config.projectDir) {
        session.descriptor?.let { d -> java.nio.file.Path.of(d.workingDir).resolve(d.outputDir).toAbsolutePath().normalize() }
    }

    val inspector = Inspector({ ensureViewer() }, config.browserEnabled)

    /** Developer prompts from the inspector's Ask agent box ([PromptTools]); every change is pushed to the page. */
    val prompts = Prompts().also { q -> q.onChange = { viewer?.publish("prompts", it) } }

    /** Builds the viewer with the inspector API wired to [session] and starts it (port 0 = ephemeral). */
    @Synchronized
    fun ensureViewer(port: Int = 0): Viewer = viewer ?: Viewer(port, Tokens.generate(), visuals.store,
        api = { m, p, t -> session.call(m, if (m in CSS_METHODS) withCssRoots(p) else p, t) }, connected = { session.connected }, editorUrl = config.editorUrl,
        editEnabled = { config.inspectorEdit || session.appInspectorEdit }, lockText = { live.lockText() },
        releaseLock = { live.unlock("developer") }, scratchApply = ::applyScratch, prompts = prompts, sheetMap = cssMappings).also {
        it.start()
        viewer = it
        log.info("live viewer: {}", it.url())
        advertiseInspector()
    }

    /**
     * An agent session records its inspector in `<fxloopDir>/inspector.json` (owner-only, like the handshake) so the
     * `fxloop:run` inspector helper can reach it: it sends its pages here while we hold the app and forwards Ask agent
     * prompts here while it does. The helper itself never advertises.
     */
    private fun advertiseInspector() {
        val v = viewer ?: return
        if (config.sidecar) return
        runCatching {
            dev.fxloop.protocol.OwnerOnly.write(session.fxloopDir.resolve(INSPECTOR_FILE),
                Json.write(Json.obj { put("pid", ProcessHandle.current().pid()); put("url", v.inspectUrl()) }))
        }.onFailure { log.debug("cannot write {}: {}", INSPECTOR_FILE, it.toString()) }
    }

    /**
     * Project layout for the CSS engine, so `file` names the source stylesheet rather than its build copy (PRD 08 §3.2),
     * plus the developer's mappings of stylesheets outside the project to their source.
     */
    fun withCssRoots(p: ObjectNode): ObjectNode {
        p.set<JsonNode>("cssMappings", cssMappings.toJson())
        val d = session.descriptor ?: return p.put("projectDir", config.projectDir.toString())
        val wd = java.nio.file.Path.of(d.workingDir)
        fun abs(s: String) = wd.resolve(s).toAbsolutePath().normalize().toString()
        p.put("projectDir", config.projectDir.toString())
        p.set<JsonNode>("outputDirs", Json.strings(listOf(abs(d.outputDir))))
        p.set<JsonNode>("resourceRoots", Json.strings(d.resourceRoots.map(::abs)))
        return p
    }

    /**
     * Inspector *Apply to file*: asks the agent where each scratch rule goes ([Fcp.Method.CSS_SCRATCH_PLAN]; [targets]
     * picks the stylesheet for scratch rule n); a dry run returns that plan. Otherwise checks every file (a project
     * stylesheet or a mapped library source, unchanged since the agent read it), writes them, reloads the CSS and drops
     * the scratch sheet, except for rules saved into a library the app loads from a jar: those stay in scratch CSS so the
     * app keeps showing them until the library is rebuilt. Returns `{applied, items, files:[{file, changes}], scratch}`.
     */
    fun applyScratch(dryRun: Boolean, targets: Map<Int, String> = emptyMap()): ObjectNode {
        if (session.descriptor == null || session.mode == AppSession.Mode.ATTACHED) {
            throw dev.fxloop.server.mcp.ToolException("RELOAD_UNAVAILABLE",
                "Apply to file needs a session with the project's source layout (not attach mode); use Copy as CSS", null)
        }
        val params = withCssRoots(Json.obj())
        if (targets.isNotEmpty()) params.putObject("targets").also { t -> targets.forEach { (n, f) -> t.put(n.toString(), f) } }
        val plan = session.call(Fcp.Method.CSS_SCRATCH_PLAN, params, 15_000) as ObjectNode
        val files = plan.path("files").toList()
        val summary = Json.obj {
            put("dryRun", dryRun)
            put("rules", plan.path("rules").asInt())
            plan.path("items").takeIf { it.isArray }?.let { set<JsonNode>("items", it) }
            val arr = putArray("files")
            files.forEach { f ->
                val o = arr.addObject().put("file", f.path("file").asText())
                if (f.path("external").asBoolean()) {
                    o.put("external", true).put("live", f.path("live").asBoolean(true))
                    f.path("from").asText(null)?.let { o.put("from", it) }
                }
                o.set<JsonNode>("changes", f.path("changes"))
            }
        }
        if (dryRun || files.isEmpty()) return summary.put("applied", false)
        val d = session.descriptor
        val wd = d?.let { java.nio.file.Path.of(it.workingDir) } ?: config.projectDir
        val roots = (d?.resourceRoots ?: emptyList()).map { wd.resolve(it).toAbsolutePath().normalize() } + config.projectDir.toAbsolutePath().normalize()
        val output = d?.let { wd.resolve(it.outputDir).toAbsolutePath().normalize() }
        val writes = files.map { f ->
            val path = java.nio.file.Path.of(f.path("absFile").asText()).toAbsolutePath().normalize()
            val inProject = roots.any { path.startsWith(it) } && (output == null || !path.startsWith(output))
            val ok = path.fileName.toString().lowercase().endsWith(".css") && java.nio.file.Files.isRegularFile(path) &&
                (inProject || cssMappings.isMapped(path))
            if (!ok) throw dev.fxloop.server.mcp.ToolException("NOT_A_PROJECT_STYLESHEET", "${f.path("file").asText()} is not a stylesheet of this project", null)
            val now = sha256(java.nio.file.Files.readString(path))
            if (now != f.path("sha256").asText()) {
                throw dev.fxloop.server.mcp.ToolException("FILE_CHANGED", "${f.path("file").asText()} changed while applying; try again", null)
            }
            path to f.path("text").asText()
        }
        writes.forEach { (path, text) -> java.nio.file.Files.writeString(path, text) }
        val keep = plan.path("keep").asText("")
        val r = reloader.reload(Reloader.Options(files = writes.map { it.first.toString() }, allowRestart = false, replay = false,
            visualDiff = false, javaTier = false, consume = false, clearScratch = keep.isBlank())) { _, _ -> Json.obj() }
        val scratch = if (keep.isBlank()) Json.obj { put("active", false); put("rules", 0) }
        else session.call(Fcp.Method.CSS_SCRATCH_SET, Json.obj { put("css", keep) }, 15_000)
        live.reloaded("inspector: CSS applied to " + files.joinToString(", ") { it.path("file").asText().substringAfterLast('/') },
            true, live.actionItems())
        return summary.put("applied", true).also {
            it.set<JsonNode>("reload", r)
            it.set<JsonNode>("scratch", scratch)
        }
    }

    /** [dev.fxloop.server.mcp.ToolRegistry.observer]: the dev bar shows what the agent is doing while a tool runs. */
    fun toolActivity(tool: String, args: ObjectNode, done: Boolean) {
        if (tool in QUIET_TOOLS || tool.startsWith("fx_test_")) return
        if (done) live.activity(null) else live.activity(activityText(tool, args))
    }

    private fun publish(event: String, json: JsonNode) {
        viewer?.publish(event, json)
    }

    init {
        reloader.mappedSheets = { cssMappings.urlsFor(it) }
        live.watchMode = { watch.mode }
        live.statusSink = { viewer?.publishStatus(it) }
        reloader.onReloaded { r ->
            publish("reload", Json.obj {
                val files = putArray("files")
                val names = putArray("tiers")
                r.path("tiersApplied").forEach { t ->
                    names.add(t.path("tier").asText())
                    t.path("files").forEach { f -> if (files.none { it.asText() == f.asText() }) files.add(f.asText()) }
                }
            })
        }

        session.onLaunched {
            reloader.checkpoint()
            dev.fxloop.server.loop.InProcessCompiler.warmUpAsync()
            applyWatchMode(watch.mode.takeIf { it != "off" } ?: config.watchMode)
            live.push()
        }
        session.onConnected {
            reloader.checkpoint(session.startedAt.takeIf { session.mode == AppSession.Mode.EMBEDDED })
            if (session.mode == AppSession.Mode.EMBEDDED) live.developerOwnsApp()
            live.push()
            advertiseInspector()
        }
        session.onAgentEvent(::onAgentEvent)
    }

    private fun onAgentEvent(event: String, payload: JsonNode) {
        when (event) {
            Fcp.Event.HUMAN_ACTION -> {
                if (config.recordHuman && payload.hasNonNull("tool")) {
                    val args = (payload["args"] as? ObjectNode)?.deepCopy() ?: Json.obj()
                    journal.record(payload["tool"].asText(), args, (payload["target"] as? ObjectNode)?.deepCopy(), null, "",
                        source = "human", redacted = payload.path("redacted").asBoolean(false))
                }
                live.onHumanAction(payload)
            }
            Fcp.Event.HUMAN_PICK -> {
                live.onHumanPick(payload)
                publish("pick", payload) // an open inspector page selects the picked node
            }
            Fcp.Event.SCRATCH_CHANGED -> publish("scratch", payload)
            Fcp.Event.INPUT_LOCK_RELEASED -> live.unlock("developer")
            Fcp.Event.INSPECTOR_REQUESTED -> inspector.open(payload.path("h").takeIf { it.isInt }?.asInt(), payload.path("path").asText(null))
            Fcp.Event.CONTROL_MODE_CHANGED -> {
                val mode = if (payload.path("mode").asText() == "shared") LiveState.Control.SHARED else LiveState.Control.AGENT
                live.setControl(mode, "bar")
            }
            Fcp.Event.HUMAN_RESTART_REQUEST -> humanRestart()
        }
    }

    private fun humanRestart() {
        if (session.mode != AppSession.Mode.LAUNCHED) {
            live.setActionNeeded(live.actionItems().filter { it.kind != "restart" } + ActionNeeded("rebuildIde",
                "restart requested from the dev bar, but fxloop did not launch this JVM",
                "Rebuild and restart the app from your IDE", "copy"))
            return
        }
        if (!restarting.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "fxloop-human-restart") {
            try {
                restartWithReplay(true)
                clearRestartNeeded()
            } catch (e: RuntimeException) {
                log.warn("restart requested from the dev bar failed: {}", e.message)
                live.setActionNeeded(live.actionItems().filter { it.kind != "restart" } + ActionNeeded("rebuildFailed",
                    "restart failed: ${e.message}", "Check fx_get_logs, fix the cause, then restart", "copy"))
            } finally {
                restarting.set(false)
            }
        }
    }

    /** Drops the "restart needed" entries of the action-needed list (after any restart) and pushes. */
    fun clearRestartNeeded() {
        live.setActionNeeded(live.actionItems().filter { it.kind != "restart" })
    }

    private fun watchModeName() = watch.mode

    fun applyWatchMode(mode: String) {
        val d = session.descriptor
        val roots = when {
            d == null -> emptyList()
            mode == "css-only" -> d.resourceRoots.map(java.nio.file.Path::of)
            else -> (d.sourceRoots + d.resourceRoots).map(java.nio.file.Path::of)
        }
        watch.set(mode, roots)
    }

    /** Flat watched-property snapshot used for effects. */
    fun snapshot(): JsonNode = session.call(Fcp.Method.SNAPSHOT_STATE, Json.obj { put("maxNodes", 2000) })

    private fun fingerprint(snap: JsonNode?): String =
        snap?.path("windows")?.joinToString("") { "${it.path("type").asText()}:${it.path("title").asText("")}|" } ?: ""

    /**
     * Runs an action tool: pre-snapshot, FCP call, post-snapshot diff (`effects`), journal entry.
     * Note text is prepended with an auto-start note when the call started the app.
     */
    fun action(tool: String, method: String, args: ObjectNode, describe: (JsonNode) -> String): ToolResult {
        val started = session.ensureStarted()
        val withEffects = args.path("effects").asBoolean(true)
        args.remove("effects")
        val before = if (withEffects) snapshot() else null
        if (live.control == LiveState.Control.SHARED && !args.has("mode") && tool in SHARED_EVENT_TOOLS) args.put("mode", "event")
        val r = session.call(method, args, 15_000) as ObjectNode
        val target = r["target"] as? ObjectNode
        var effects: ObjectNode? = null
        if (before != null) {
            effects = SceneDiff.diff(before, snapshot())
            r.set<JsonNode>("effects", effects)
        }
        journal.record(tool, replayable(args, target), target?.deepCopy()?.apply { remove("h") }, effects, fingerprint(before))
        val conflicts = r.path("conflicts").let { if (it.isArray) it.size() else it.asInt(0) }
        val tookOver = if (live.takeOverNotice()) "\n[the developer took over: the input lock is released; stop driving the UI and ask them]" else ""
        val text = (if (started) "(app auto-started) " else "") + describe(r) + tookOver +
            (if (conflicts > 0) " [$conflicts human input conflict(s): the developer was interacting at the same time]" else "") + (effects?.let { "\n" + SceneDiff.summary(it) } ?: "")
        return ToolResult.ok(text, r)
    }

    private fun replayable(args: ObjectNode, target: ObjectNode?): ObjectNode {
        val a = args.deepCopy()
        val path = target?.path("path")?.asText(null)
        if (path != null) {
            listOf("handle", "query", "windowIndex").forEach(a::remove)
            a.put("path", path)
        }
        return a
    }

    /** Re-executes journal entries whose target path resolves; stops at the first failure (PRD 02 §5). */
    fun replay(entries: List<JournalEntry>): ObjectNode {
        var skipped = 0
        live.driving(true)
        try {
            return replayEntries(entries) { skipped++ }.also { if (skipped > 0) it.put("skippedRedacted", skipped) }
        } finally {
            live.driving(false)
        }
    }

    private fun replayEntries(entries: List<JournalEntry>, onRedacted: () -> Unit): ObjectNode {
        var replayed = 0
        for (e in entries) {
            if (e.redacted) {
                onRedacted()
                continue
            }
            val method = METHODS[e.tool] ?: continue
            val path = e.args.path("path").asText(null)
            if (path != null && !awaitVisible(path)) {
                return Json.obj {
                    put("replayed", replayed)
                    putObject("failedAt").put("step", e.seq).put("tool", e.tool).put("reason", "path not visible within 3 s: $path")
                }
            }
            try {
                session.call(method, e.args.deepCopy(), 15_000)
                replayed++
            } catch (ex: FcpException) {
                return Json.obj {
                    put("replayed", replayed)
                    putObject("failedAt").put("step", e.seq).put("tool", e.tool).put("reason", "${ex.code}: ${ex.message}")
                }
            }
        }
        return Json.obj { put("replayed", replayed); put("of", entries.size) }
    }

    private fun awaitVisible(path: String): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (session.call(Fcp.Method.PATH_VISIBLE, Json.obj { put("path", path) }).path("visible").asBoolean()) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Restart (tier 4 / `fx_restart_app`), optionally replaying the journal recorded before it. */
    /**
     * @param rebuild false skips the build-tool prepare; by default only when every edit since the last checkpoint is
     * already compiled into `outputDir` ([Reloader.upToDate], [Reloader.rebuildOnRestart])
     */
    fun restartWithReplay(replay: Boolean, rebuild: Boolean = !reloader.upToDate()): ObjectNode {
        val entries = journal.entries()
        val r = session.restart(reuseDescriptor = !rebuild)
        if (replay && entries.isNotEmpty()) r.set<JsonNode>("replay", replay(entries))
        return r
    }

    fun shutdown() {
        if (!config.sidecar) runCatching {
            val f = session.fxloopDir.resolve(INSPECTOR_FILE)
            if (Json.parse(java.nio.file.Files.readString(f)).path("pid").asLong() == ProcessHandle.current().pid()) java.nio.file.Files.delete(f)
        }
        runCatching { watch.dispose() }
        runCatching { viewer?.close() }
        runCatching { session.shutdown() }
        log.info("fxloop server stopped")
    }

    companion object {
        /** Inspector of the agent session holding the app, read by the `fxloop:run` helper ([advertiseInspector]). */
        const val INSPECTOR_FILE = "inspector.json"

        /** The advertised URL if [file] names a live process and a loopback inspector URL; else null. */
        fun advertisedInspector(file: java.nio.file.Path): String? = runCatching {
            val j = Json.parse(java.nio.file.Files.readString(file))
            val url = j.path("url").asText("")
            val alive = ProcessHandle.of(j.path("pid").asLong(-1)).map { it.isAlive }.orElse(false)
            url.takeIf { alive && INSPECT_URL.matches(it) }
        }.getOrNull()

        private val INSPECT_URL = Regex("^http://127\\.0\\.0\\.1:\\d{1,5}/inspect\\?t=[A-Za-z0-9_%-]+$")

        private val SHARED_EVENT_TOOLS = setOf("fx_click_node", "fx_type_keys")

        private fun sha256(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Tools the bar does not announce: status polls and the lock itself. */
        private val QUIET_TOOLS = setOf("fx_get_app_status", "fx_lock_input", "fx_set_control_mode", "fx_get_picked",
            "fx_wait_for_prompt", "fx_reply_to_prompt")

        /** The bar's "Agent: …" text for a tool call, in the developer's words. */
        fun activityText(tool: String, a: ObjectNode): String {
            val on = target(a)
            return when (tool) {
                "fx_click_node" -> "clicking ${on ?: "the app"}"
                "fx_type_keys" -> "pressing " + (keys(a) ?: "keys") + (on?.let { " in $it" } ?: "")
                "fx_set_text" -> "typing into ${on ?: "a text field"}"
                "fx_set_property" -> "setting ${a.path("property").asText("a property")}" + (on?.let { " on $it" } ?: "")
                "fx_take_screenshot", "fx_get_screenshot" -> "taking a screenshot"
                "fx_compare_screenshots" -> "comparing screenshots"
                "fx_get_scene_graph" -> "reading the scene graph"
                "fx_find_nodes" -> "looking for nodes"
                "fx_get_node_details", "fx_get_property", "fx_describe_at" -> "inspecting ${on ?: "a node"}"
                "fx_explain_style", "fx_explain_property", "fx_explain_layout", "fx_suggest_override" ->
                    "checking why ${on ?: "a node"} looks like this"
                "fx_reload", "fx_reload_fxml" -> "reloading your changes"
                "fx_start_app" -> "starting the app"
                "fx_restart_app" -> "restarting the app"
                "fx_stop_app" -> "stopping the app"
                "fx_get_logs", "fx_get_ui_errors" -> "reading logs"
                "fx_lint_fxml", "fx_lint_visual" -> "checking the UI for problems"
                "fx_capture_states" -> "capturing UI states"
                "fx_eval_js" -> "running a script in the app"
                "fx_open_inspector" -> "opening the CSS inspector"
                else -> tool.removePrefix("fx_").replace('_', ' ')
            }
        }

        private fun target(a: ObjectNode): String? {
            a.path("path").asText(null)?.let { p -> return p.substringAfterLast('/').take(48).ifEmpty { null } }
            a.path("query").asText(null)?.let { return it.take(48) }
            return null
        }

        private fun keys(a: ObjectNode): String? {
            val k = a.path("keys")
            if (!k.isArray || k.isEmpty) return a.path("text").asText(null)?.let { "\"" + it.take(24) + "\"" }
            return k.take(3).joinToString(", ") { c ->
                if (c.isTextual) c.asText() else (c.path("modifiers").map { it.asText().lowercase().replaceFirstChar(Char::uppercase) } +
                    c.path("code").asText()).joinToString("+")
            }
        }
        val CSS_METHODS = setOf(Fcp.Method.EXPLAIN_STYLE, Fcp.Method.CSS_CASCADE, Fcp.Method.CSS_SUGGEST)

        val METHODS = mapOf(
            "fx_click_node" to Fcp.Method.CLICK,
            "fx_type_keys" to Fcp.Method.TYPE_KEYS,
            "fx_set_text" to Fcp.Method.SET_TEXT,
            "fx_set_property" to Fcp.Method.SET_PROPERTY,
        )
    }
}

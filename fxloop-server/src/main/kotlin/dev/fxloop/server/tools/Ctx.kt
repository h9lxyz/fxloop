package dev.fxloop.server.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.Tokens
import dev.fxloop.server.Config
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.loop.Journal
import dev.fxloop.server.loop.JournalEntry
import dev.fxloop.server.loop.Reloader
import dev.fxloop.server.loop.SceneDiff
import dev.fxloop.server.loop.WatchMode
import dev.fxloop.server.mcp.ToolResult
import dev.fxloop.server.see.Inspector
import dev.fxloop.server.see.ScreenStore
import dev.fxloop.server.see.Viewer
import dev.fxloop.server.see.Visuals
import org.slf4j.LoggerFactory

/** Shared services behind the tool surface: session, visuals, journal, reload, effects, replay. */
class Ctx(val config: Config) {
    private val log = LoggerFactory.getLogger(Ctx::class.java)
    val session = AppSession(config)
    val visuals = Visuals(session, ScreenStore(session.fxloopDir.resolve("screens"), config.screensKeep), config.screenshotRatePerSec)
    val journal = Journal(500)
    val reloader = Reloader(session, visuals)
    val watch: WatchMode = WatchMode { files ->
        reloader.reload(Reloader.Options(files = files, allowRestart = false, replay = false, visualDiff = false,
            javaTier = watchModeName() == "all", consume = false)) { Json.obj() }
    }
    var viewer: Viewer? = null
    val inspector = Inspector({ ensureViewer() }, config.browserEnabled)

    /** Builds the viewer with the inspector API wired to [session] and starts it (port 0 = ephemeral). */
    @Synchronized
    fun ensureViewer(port: Int = 0): Viewer = viewer ?: Viewer(port, Tokens.generate(), visuals.store,
        api = { m, p, t -> session.call(m, if (m in CSS_METHODS) withCssRoots(p) else p, t) }, connected = { session.connected }, editorUrl = config.editorUrl).also {
        it.start()
        viewer = it
        log.info("live viewer: {}", it.url())
    }

    /** Project layout for the CSS engine, so `file` names the source stylesheet rather than its build copy (PRD 08 §3.2). */
    fun withCssRoots(p: ObjectNode): ObjectNode {
        val d = session.descriptor ?: return p.put("projectDir", config.projectDir.toString())
        val wd = java.nio.file.Path.of(d.workingDir)
        fun abs(s: String) = wd.resolve(s).toAbsolutePath().normalize().toString()
        p.put("projectDir", config.projectDir.toString())
        p.set<JsonNode>("outputDirs", Json.strings(listOf(abs(d.outputDir))))
        p.set<JsonNode>("resourceRoots", Json.strings(d.resourceRoots.map(::abs)))
        return p
    }

    private fun publish(event: String, json: JsonNode) {
        viewer?.publish(event, json)
    }

    private fun onAgentEvent(event: String, payload: JsonNode) {
        when (event) {
            Fcp.Event.INSPECTOR_REQUESTED -> inspector.open(payload.path("h").takeIf { it.isInt }?.asInt(), payload.path("path").asText(null))
            Fcp.Event.HUMAN_PICK -> {
                publish("pick", payload)
                if ((viewer?.subscriberCount("inspect") ?: 0) == 0) {
                    inspector.open(payload.path("h").takeIf { it.isInt }?.asInt(), payload.path("path").asText(null))
                }
            }
        }
    }

    init {
        session.onAgentEvent { e, p -> runCatching { onAgentEvent(e, p) }.onFailure { log.debug("agent event {} failed", e, it) } }
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
            applyWatchMode(watch.mode.takeIf { it != "off" } ?: config.watchMode)
        }
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
        val r = session.call(method, args, 15_000) as ObjectNode
        val target = r["target"] as? ObjectNode
        var effects: ObjectNode? = null
        if (before != null) {
            effects = SceneDiff.diff(before, snapshot())
            r.set<JsonNode>("effects", effects)
        }
        journal.record(tool, replayable(args, target), target?.deepCopy()?.apply { remove("h") }, effects, fingerprint(before))
        val text = (if (started) "(app auto-started) " else "") + describe(r) + (effects?.let { "\n" + SceneDiff.summary(it) } ?: "")
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
        var replayed = 0
        for (e in entries) {
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
    fun restartWithReplay(replay: Boolean): ObjectNode {
        val entries = journal.entries()
        val r = session.restart()
        if (replay && entries.isNotEmpty()) r.set<JsonNode>("replay", replay(entries))
        return r
    }

    fun shutdown() {
        runCatching { watch.dispose() }
        runCatching { viewer?.close() }
        runCatching { session.shutdown() }
        log.info("fxloop server stopped")
    }

    companion object {
        val CSS_METHODS = setOf(Fcp.Method.EXPLAIN_STYLE, Fcp.Method.CSS_CASCADE, Fcp.Method.CSS_SUGGEST)

        val METHODS = mapOf(
            "fx_click_node" to Fcp.Method.CLICK,
            "fx_type_keys" to Fcp.Method.TYPE_KEYS,
            "fx_set_text" to Fcp.Method.SET_TEXT,
            "fx_set_property" to Fcp.Method.SET_PROPERTY,
        )
    }
}

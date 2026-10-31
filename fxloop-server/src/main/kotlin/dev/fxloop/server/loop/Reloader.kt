package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.LaunchDescriptor
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.app.LiveState
import dev.fxloop.server.mcp.ToolException
import dev.fxloop.server.see.Frame
import dev.fxloop.server.see.ScreenStore
import dev.fxloop.server.see.Visuals
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Tier router (PRD 02 §3): CSS live → FXML swap → HotSwap → restart(+replay). Mixed change-sets execute tiers in
 * that order; one call returns the full story.
 */
class Reloader(private val session: AppSession, private val visuals: Visuals, private val live: LiveState) {
    /** @property consume whether this reload advances the change baseline (watch mode passes explicit files: false) */
    data class Options(
        val files: List<String>? = null,
        val allowRestart: Boolean = true,
        val replay: Boolean = true,
        val visualDiff: Boolean = true,
        val javaTier: Boolean = true,
        val consume: Boolean = true,
        val remount: Boolean = true,
        val carryState: Boolean = true,
        val restartWhenHumanActive: Boolean = false,
    )

    /** What tier 4 does with a needed restart (PRD 07 §6). */
    enum class RestartDecision { RESTART, DEFER, NONE }

    private val log = LoggerFactory.getLogger(Reloader::class.java)
    private val sources = ChangeScanner()
    private val outputs = ChangeScanner()

    /** Re-baselines change detection; called after launch and after each reload. */
    @Synchronized
    fun checkpoint() {
        val d = session.descriptor ?: return
        sources.checkpoint(watchedRoots(d))
        outputs.checkpoint(listOf(Path.of(d.outputDir)))
    }

    fun watchedRoots(d: LaunchDescriptor): List<Path> =
        (d.sourceRoots + d.resourceRoots).map(Path::of) + BUILD_FILES.map(session.config.projectDir::resolve)

    /** @param restartWithReplay performs tier 4 (restart, then replay when its argument is true) and returns its report */
    @Synchronized
    fun reload(opt: Options, restartWithReplay: (Boolean) -> ObjectNode): ObjectNode {
        val d = session.descriptor
        if (d == null || session.mode == AppSession.Mode.ATTACHED) {
            throw ToolException("RELOAD_UNAVAILABLE",
                if (session.mode == AppSession.Mode.ATTACHED) "reload tiers are unavailable in attach mode (no launch.json / source mapping)"
                else "no launch descriptor yet", "start the app with fx_start_app (dev-loop mode)")
        }
        val out = Json.obj()
        val tiers = out.putArray("tiersApplied")
        val css = ArrayList<Path>()
        val fxml = ArrayList<Path>()
        val java = ArrayList<Path>()
        val other = ArrayList<Path>()
        val reasons = Json.array()
        for (c in changes(opt, d)) {
            val name = c.file.name
            val lower = name.lowercase()
            when {
                name in BUILD_FILES -> reasons.add("$name changed")
                c.deleted -> reasons.add("${visuals.relative(c.file)} deleted")
                lower.endsWith(".css") -> css.add(c.file)
                lower.endsWith(".fxml") -> fxml.add(c.file)
                lower.endsWith(".java") -> java.add(c.file)
                else -> other.add(c.file)
            }
        }
        var needsRestart = !reasons.isEmpty
        var fxmlSwapped = 0

        if (other.isNotEmpty()) {
            val t0 = System.currentTimeMillis()
            tiers.add(tier("resources", copyResources(d, other), t0, Json.obj { put("note", "copied to outputDir; takes effect on next load") }))
        }
        if (css.isNotEmpty()) cssTier(d, css, opt, tiers, out)
        if (fxml.isNotEmpty()) {
            val t0 = System.currentTimeMillis()
            val rels = copyResources(d, fxml)
            val r = session.call(Fcp.Method.RELOAD_FXML, Json.obj {
                set<JsonNode>("files", Json.strings(rels))
                put("carryState", opt.carryState)
            }) as ObjectNode
            tiers.add(tier("fxml", rels, t0, r))
            fxmlSwapped = r.path("swapped").size()
            r["stateCarried"]?.let { mergeCarried(out, it) }
            r["unregistered"]?.let { u ->
                needsRestart = true
                u.forEach { reasons.add("FXML ${it.asText()} is not in the load registry") }
            }
        }
        if (opt.javaTier) {
            if (session.config.externalCompile) {
                val classes = changedClasses(d)
                if (classes.isNotEmpty()) {
                    if (!redefine(classes, emptyList(), tiers, System.currentTimeMillis(), "external", reasons)) needsRestart = true
                } else if (java.isNotEmpty()) {
                    out.put("waitingForBuild", true)
                }
            } else if (java.isNotEmpty()) {
                when (javaTier(d, java, tiers, out, reasons)) {
                    null -> { // compile errors: no partial swap, no restart
                        if (opt.consume) checkpoint()
                        return out
                    }
                    false -> needsRestart = true
                    true -> {}
                }
            }
        }
        val hotswapOk = tiers.any { it.path("tier").asText() == "hotswap" && it.path("details").path("ok").asBoolean() }
        if (hotswapOk || fxmlSwapped > 0) remountTier(hotswapOk && opt.remount, opt, tiers, out)
        if (needsRestart) {
            out.put("needsRestart", true)
            out.set<JsonNode>("restartReasons", reasons)
            val deferred = opt.allowRestart && session.mode == AppSession.Mode.LAUNCHED && !opt.restartWhenHumanActive &&
                safeDefer()
            when (restartDecision(opt.allowRestart, session.mode, opt.restartWhenHumanActive, deferred)) {
                RestartDecision.DEFER -> out.put("restartDeferred", "humanActive")
                RestartDecision.RESTART -> {
                    val t0 = System.currentTimeMillis()
                    try {
                        val r = restartWithReplay(opt.replay)
                        tiers.add(tier("restart", emptyList(), t0, r))
                        out.put("restarted", true)
                        r["replay"]?.let { out.set<JsonNode>("replay", it) }
                    } catch (e: ToolException) {
                        out.set<JsonNode>("restartFailed", Json.obj {
                            put("message", e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: e.code)
                        })
                    }
                }
                RestartDecision.NONE -> {}
            }
        }
        if (tiers.isEmpty) out.put("note", "no changes detected since the last reload/launch")
        if (opt.consume) checkpoint()
        return out
    }

    private fun safeDefer(): Boolean = try {
        live.shouldDeferRestart()
    } catch (e: NotImplementedError) {
        false
    }

    /** Calls `remountViews` once; an older agent (UNKNOWN_METHOD) and any failure are ignored — a remount never fails a reload. */
    private fun remountTier(doRemount: Boolean, opt: Options, tiers: ArrayNode, out: ObjectNode) {
        val t0 = System.currentTimeMillis()
        val r = try {
            session.call(Fcp.Method.REMOUNT_VIEWS, Json.obj {
                put("remount", doRemount)
                put("carryState", opt.carryState)
                put("restage", session.config.restage)
            }) as? ObjectNode ?: return
        } catch (e: FcpException) {
            if (e.code != Fcp.Error.UNKNOWN_METHOD) log.warn("remountViews failed: {}", e.message)
            return
        } catch (e: RuntimeException) {
            log.warn("remountViews failed: {}", e.message)
            return
        }
        r["stateCarried"]?.let { mergeCarried(out, it) }
        val restaged = r.path("restaged").asBoolean()
        if (restaged || remountNoteworthy(r)) tiers.add(tier(if (restaged) "restage" else "remount", emptyList(), t0, r))
    }

    private fun mergeCarried(out: ObjectNode, add: JsonNode) {
        val cur = out.path("stateCarried")
        val restored = cur.path("restored").asInt(0) + add.path("restored").asInt(0)
        val skipped = Json.array()
        cur.path("skipped").forEach { skipped.add(it) }
        add.path("skipped").forEach { skipped.add(it) }
        out.set<JsonNode>("stateCarried", Json.obj {
            put("restored", restored)
            set<JsonNode>("skipped", skipped)
        })
    }

    private fun changes(opt: Options, d: LaunchDescriptor): List<ChangeScanner.Change> {
        val files = opt.files
        if (files.isNullOrEmpty()) return sources.changes(watchedRoots(d))
        return files.map { f ->
            val p = if (Path.of(f).isAbsolute) Path.of(f) else visuals.confine(f)
            ChangeScanner.Change(p, !p.exists())
        }
    }

    private fun cssTier(d: LaunchDescriptor, css: List<Path>, opt: Options, tiers: ArrayNode, out: ObjectNode) {
        val before = if (opt.visualDiff) safeCapture("css-before") else null
        val t0 = System.currentTimeMillis()
        val rels = copyResources(d, css)
        val r = session.call(Fcp.Method.RELOAD_CSS, Json.obj { set<JsonNode>("files", Json.strings(rels)) }) as ObjectNode
        tiers.add(tier("css", rels, t0, r))
        if (before == null) return
        val after = safeCapture("css-after") ?: return
        val c = visuals.compare(ScreenStore.read(before.file), ScreenStore.read(after.file), 0.06, "diff-css")
        val vd = c.structured
        vd.put("beforeSeq", before.seq)
        vd.put("afterSeq", after.seq)
        when {
            // "≈ 0": below 0.05 % only caret blink / AA noise can be left (PRD 04 §4)
            c.diff.changedPct < NO_CHANGE_PCT -> vd.put("hint",
                "no visual change — the edited selector may not match any node; check fx_explain_style on the intended target")
            c.diff.changedPct > 30 -> vd.put("hint", "change affected more than expected — selector may be broader than intended (see regions)")
        }
        out.set<JsonNode>("visualDiff", vd)
    }

    private fun safeCapture(label: String): Frame? = try {
        visuals.captureForDiff(label)
    } catch (e: RuntimeException) {
        log.debug("visual diff capture skipped: {}", e.message)
        null
    }

    /** @return true = swapped, false = needs restart, null = compile errors (reported in [out]) */
    private fun javaTier(d: LaunchDescriptor, java: List<Path>, tiers: ArrayNode, out: ObjectNode, reasons: ArrayNode): Boolean? {
        val t0 = System.currentTimeMillis()
        val rels = java.map(visuals::relative)
        val buildName = session.build.kind.name.lowercase()
        fun buildToolCompile(via: String): Map<String, ByteArray>? {
            val r = session.build.compile()
            if (r.exit != 0) {
                out.set<JsonNode>("compileErrors", Json.array().add(Json.obj { put("kind", "error"); put("message", r.tail(30)) }))
                tiers.add(tier("java", rels, t0, Json.obj { put("compiler", via); put("ok", false) }))
                return null
            }
            return changedClasses(d)
        }
        if (d.annotationProcessors) {
            val classes = buildToolCompile(buildName) ?: return null
            return redefine(classes, rels, tiers, t0, buildName, reasons)
        }
        val r = InProcessCompiler.compile(d, java)
        if (r.failure != null) {
            log.info("in-process javac failed abnormally ({}); falling back to the build tool", r.failure)
            val classes = buildToolCompile("fallback:$buildName") ?: return null
            return redefine(classes, rels, tiers, t0, "fallback:$buildName", reasons)
        }
        if (!r.success) {
            val errs = Json.array()
            r.diagnostics.filter { it.path("kind").asText() == "error" }.forEach {
                (it as ObjectNode).put("file", visuals.relative(Path.of(it.path("file").asText(""))))
                errs.add(it)
            }
            out.set<JsonNode>("compileErrors", errs)
            tiers.add(tier("java", rels, t0, Json.obj { put("compiler", "javac"); put("ok", false); put("errors", errs.size()) }))
            return null
        }
        outputs.changes(listOf(Path.of(d.outputDir))) // our own writes are not "external" changes
        return redefine(r.classes, rels, tiers, t0, "javac", reasons)
    }

    private fun redefine(classes: Map<String, ByteArray>, rels: List<String>, tiers: ArrayNode, t0: Long, via: String, reasons: ArrayNode): Boolean {
        val payload = Json.array()
        classes.forEach { (name, bytes) -> payload.addObject().put("name", name).put("bytesB64", Base64.getEncoder().encodeToString(bytes)) }
        val details = Json.obj { put("compiler", via); put("classes", classes.size) }
        return try {
            val r = session.call(Fcp.Method.REDEFINE_CLASSES, Json.obj { set<JsonNode>("classes", payload) }, 30_000)
            details.set<JsonNode>("redefined", r["redefined"])
            details.set<JsonNode>("notLoaded", r["notLoaded"])
            details.put("ok", true)
            if (session.config.jbr) details.put("jbrEnhanced", true)
            tiers.add(tier("hotswap", rels, t0, details))
            true
        } catch (e: FcpException) {
            if (e.code != Fcp.Error.REDEFINE_REJECTED && e.code != Fcp.Error.DISABLED) throw e
            details.put("ok", false)
            details.put("rejected", e.message)
            e.data?.get("reason")?.let { details.set<JsonNode>("reason", it) }
            tiers.add(tier("hotswap", rels, t0, details))
            reasons.add("hotswap rejected for ${classes.keys.joinToString()}: ${e.message}")
            false
        }
    }

    private fun changedClasses(d: LaunchDescriptor): Map<String, ByteArray> {
        val out = Path.of(d.outputDir)
        return outputs.changes(listOf(out)).filter { !it.deleted && it.file.name.endsWith(".class") }.associate {
            out.relativize(it.file).toString().replace(File.separatorChar, '.').removeSuffix(".class") to Files.readAllBytes(it.file)
        }
    }

    /** Copies resources (css/fxml/other) from their root into outputDir; returns root-relative paths. */
    private fun copyResources(d: LaunchDescriptor, files: List<Path>): List<String> = files.map { f ->
        val root = (d.resourceRoots + d.sourceRoots).map(Path::of).lastOrNull { f.startsWith(it) }
        if (root == null) {
            f.name
        } else {
            val rel = root.relativize(f).toString().replace('\\', '/')
            copyWithRetry(f, Path.of(d.outputDir).resolve(rel))
            rel
        }
    }

    private fun tier(name: String, files: List<String>, t0: Long, details: ObjectNode) = Json.obj {
        put("tier", name)
        set<JsonNode>("files", Json.strings(files))
        put("ms", System.currentTimeMillis() - t0)
        set<JsonNode>("details", details)
    }

    companion object {
        /** Restart etiquette (PRD 07 §6): watch/attached/embedded never restart; a busy developer defers it. */
        fun restartDecision(allowRestart: Boolean, mode: AppSession.Mode, restartWhenHumanActive: Boolean, humanActive: Boolean): RestartDecision =
            when {
                !allowRestart || mode != AppSession.Mode.LAUNCHED -> RestartDecision.NONE
                !restartWhenHumanActive && humanActive -> RestartDecision.DEFER
                else -> RestartDecision.RESTART
            }

        /** True when a `remountViews` result carries something worth reporting as a tier. */
        fun remountNoteworthy(r: JsonNode): Boolean =
            !r.path("remounted").isEmpty || !r.path("skipped").isEmpty || !r.path("failed").isEmpty ||
                r.path("callbacks").path("ran").asInt(0) > 0 || !r.path("callbacks").path("failed").isEmpty

        const val NO_CHANGE_PCT = 0.05
        val BUILD_FILES = setOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "module-info.java")

        /** Retry-with-backoff (Windows file locks on target/classes, PRD 02 §14). */
        private fun copyWithRetry(from: Path, to: Path) {
            var attempt = 0
            while (true) {
                try {
                    Files.createDirectories(to.parent)
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (e: IOException) {
                    if (++attempt > 4) throw ToolException("COPY_FAILED", "cannot copy $from: ${e.message}",
                        "close programs locking the output directory and retry")
                    Thread.sleep(50L shl attempt)
                }
            }
        }
    }
}

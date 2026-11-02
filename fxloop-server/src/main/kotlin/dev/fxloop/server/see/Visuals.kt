package dev.fxloop.server.see

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.mcp.ToolException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Visual-feedback pillar orchestration (PRD 04): captures via the agent, composites annotations server-side,
 * persists every frame (dual-channel), compares frames, enforces the screenshot rate cap.
 */
class Visuals(private val session: AppSession, val store: ScreenStore, private val ratePerSec: Double) {
    data class Capture(val frame: Frame, val png: ByteArray, val meta: ObjectNode)
    data class Comparison(val diff: ImageOps.DiffResult, val diffFrame: Frame, val structured: ObjectNode)

    private var lastCaptureNanos = 0L

    /** Throttle (PRD 03 §8): at most [ratePerSec] agent captures per second; waits instead of failing. */
    @Synchronized
    private fun throttle() {
        if (ratePerSec <= 0) return
        val wait = lastCaptureNanos + (1_000_000_000L / ratePerSec).toLong() - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        lastCaptureNanos = System.nanoTime()
    }

    /** `fx_take_screenshot` semantics; [params] are forwarded to the agent's `screenshot`. */
    fun capture(params: ObjectNode, label: String?): Capture {
        throttle()
        val r = session.call(Fcp.Method.SCREENSHOT, params, 15_000) as ObjectNode
        var png = Base64.getDecoder().decode(r["pngB64"].asText())
        val meta = r.deepCopy().apply { remove("pngB64") }
        var kind = "screenshot"
        var legend: ArrayNode? = null
        r["targets"]?.let { targets ->
            val ox = r.path("windowOrigin").path("x").asDouble()
            val oy = r.path("windowOrigin").path("y").asDouble()
            val s = r.path("outputScale").asDouble(1.0)
            val marks = ArrayList<ImageOps.Mark>()
            val l = Json.array()
            var overflow = false
            for (t in targets) {
                if (t.has("overflow")) {
                    overflow = true
                    continue
                }
                val n = marks.size + 1
                val b = t["boundsScreen"]
                marks += ImageOps.Mark(n, (b["x"].asDouble() - ox) * s, (b["y"].asDouble() - oy) * s, b["w"].asDouble() * s, b["h"].asDouble() * s)
                l.addObject().apply {
                    put("n", n)
                    put("path", t.path("path").asText())
                    if (t.hasNonNull("fxId")) put("fxId", t["fxId"].asText())
                    put("type", t.path("type").asText())
                    if (t.hasNonNull("text")) put("text", t["text"].asText())
                    put("h", t.path("h").asInt())
                }
            }
            png = ImageOps.annotate(png, marks)
            kind = "annotated"
            meta.remove("targets")
            meta.put("markerOverflow", overflow)
            legend = l
        }
        val f = store.save(png, label ?: kind, kind)
        val out = describe(f)
        out.setAll<JsonNode>(meta)
        out.put("scale", meta.path("outputScale").asDouble())
        legend?.let { out.set<JsonNode>("legend", it) }
        return Capture(f, png, out)
    }

    /**
     * Persists the node snapshot of an agent result carrying `pngB64` (e.g. `getPicked`) like a node screenshot and
     * returns the frame description merged with the result's other fields.
     */
    fun persistNodeShot(r: ObjectNode, label: String): Capture {
        val png = Base64.getDecoder().decode(r["pngB64"].asText())
        val meta = r.deepCopy().apply { remove("pngB64") }
        val f = store.save(png, label, "screenshot")
        val out = describe(f)
        out.setAll<JsonNode>(meta)
        return Capture(f, png, out)
    }

    /**
     * PNG of one node (`target`: handle or path) at most [maxWidth] px wide, for the inspector; not persisted. Fails for
     * a node the app does not render (an unselected tab), whose picture would be stale.
     */
    fun nodePng(target: ObjectNode, maxWidth: Int): ByteArray {
        throttle()
        val p = target.deepCopy().apply { put("target", "node"); put("maxWidth", maxWidth); put("requireShown", true) }
        return Base64.getDecoder().decode(session.call(Fcp.Method.SCREENSHOT, p, 15_000)["pngB64"].asText())
    }

    /** The captioned pseudo-state strip of one node (`target`: handle or path), for the inspector; not persisted. */
    fun statesStrip(target: ObjectNode): ByteArray {
        throttle()
        val r = session.call(Fcp.Method.CAPTURE_STATES, target.deepCopy(), 15_000)
        return ImageOps.strip(r["frames"].map { Base64.getDecoder().decode(it["pngB64"].asText()) }, r["frames"].map { it["state"].asText() })
    }

    /** Small internal window capture for automatic diffs (no image block). */
    fun captureForDiff(label: String): Frame =
        capture(Json.obj { put("target", "window"); put("maxWidth", DIFF_WIDTH); put("settleMs", 150) }, label).frame

    fun describe(f: Frame): ObjectNode = Json.obj {
        put("seq", f.seq)
        put("file", relative(f.file))
        put("absoluteFile", f.file.toAbsolutePath().toString())
        put("label", f.label)
        put("w", f.w)
        put("h", f.h)
        put("bytes", f.bytes)
        put("kind", f.kind)
    }

    fun relative(file: Path): String {
        val project = session.config.projectDir
        val abs = file.toAbsolutePath().normalize()
        return if (abs.startsWith(project)) project.relativize(abs).toString().replace('\\', '/') else abs.toString()
    }

    private fun isSeq(ref: JsonNode) = ref.isNumber || ref.asText().matches(Regex("\\d+"))

    /** Resolves a `seq` (number) or a path confined to the project directory to PNG bytes. */
    fun load(ref: JsonNode?, what: String): ByteArray {
        if (ref == null || ref.isNull || ref.isMissingNode) {
            throw ToolException("BAD_REQUEST", "$what is required (seq number or path)", "pass a seq from a prior capture")
        }
        return ScreenStore.read(frame(ref).file)
    }

    fun frame(ref: JsonNode): Frame {
        if (isSeq(ref)) {
            return store.bySeq(ref.asLong()) ?: throw ToolException("NOT_FOUND", "no persisted frame with seq ${ref.asText()}",
                "see ${relative(store.dir)}/index.html for available frames")
        }
        val p = confine(ref.asText())
        if (!Files.isRegularFile(p)) throw ToolException("NOT_FOUND", "no file ${ref.asText()}", "pass a seq or an existing PNG path")
        val png = ScreenStore.read(p)
        val size = ImageOps.size(png)
        return Frame(-1, p.fileName.toString(), p, size[0], size[1], p.toFile().lastModified(), "file", png.size.toLong())
    }

    /** Path-traversal guard (PRD 03 §8): resolved path must stay within the project directory. */
    fun confine(raw: String): Path {
        val project = session.config.projectDir
        val p = project.resolve(raw).toAbsolutePath().normalize()
        if (!p.startsWith(project)) throw ToolException("BAD_REQUEST", "path escapes the project directory: $raw", "use a path inside $project")
        return p
    }

    fun compare(before: ByteArray, after: ByteArray, threshold: Double, label: String): Comparison {
        val d = ImageOps.diff(before, after, threshold, DIFF_WIDTH)
        val df = store.save(d.diffPng, label, "diff")
        val s = Json.obj {
            put("changedPct", Math.round(d.changedPct * 1000) / 1000.0)
            val regions = putArray("regions")
            d.regions.forEach { r -> regions.addObject().put("x", r.x).put("y", r.y).put("w", r.w).put("h", r.h) }
            put("diffPath", relative(df.file))
            put("diffSeq", df.seq)
            put("verdict", d.verdict)
            putObject("pixelSize").put("w", d.w).put("h", d.h)
            if (d.cropped) put("cropped", true)
        }
        return Comparison(d, df, s)
    }

    companion object {
        const val DIFF_WIDTH = 800
    }
}

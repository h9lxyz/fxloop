package org.lab59.fxloop.server.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.server.mcp.ToolException
import org.lab59.fxloop.server.mcp.Schema
import org.lab59.fxloop.server.mcp.ToolDef.Kind
import org.lab59.fxloop.server.mcp.ToolRegistry
import org.lab59.fxloop.server.mcp.ToolResult
import org.lab59.fxloop.server.see.ImageOps
import org.lab59.fxloop.server.see.ScreenStore
import java.util.Base64

/** Visual feedback pillar (PRD 04 §3): dual-channel screenshots, compare, re-show, visual lint, state strips. */
object SeeTools {
    fun register(reg: ToolRegistry, ctx: Ctx) {
        val v = ctx.visuals

        reg.tool("fx_take_screenshot", "Take screenshot", Kind.READ,
            "Capture a window or node. Always persisted to target/fxloop/screens/<seq>-<label>.png (path in result — cite it " +
                "to the human) and returned as an image unless includeImage=false. scale=2 on a node supersamples for legible " +
                "small text. annotate=interactive|all|<css> overlays numbered markers; legend maps markers → node paths.",
            Schema.obj().strEnum("target", "window (default) or node", "window", "node").target()
                .integer("maxWidth", "Max width in px (default 1200, window hard cap 1600)", 64, 3200)
                .number("scale", "0.25–3.0 render scale (node shots)").str("label", "File label")
                .str("annotate", "none (default) | interactive | all | <css selector>")
                .bool("includeImage", "Return the image block (default true)")
                .integer("settleMs", "Wait before capture (default 150)", 0, 5000)) { a ->
            ctx.session.ensureStarted()
            val label = a.path("label").asText(null)
            val include = a.path("includeImage").asBoolean(true)
            listOf("label", "includeImage").forEach(a::remove)
            val c = v.capture(a, label)
            val forced = c.meta.path("forcedStates").takeIf { it.isObject }
            val result = ok("saved ${c.meta["file"].asText()} (${c.frame.w}x${c.frame.h}, ${c.frame.bytes / 1024} KB)" +
                (forced?.let { " [forced states on ${it.path("target").path("path").asText(it.path("target").asText("a node"))}: " +
                    it.path("forced").joinToString { f -> f.asText() } + " — the developer holds them in the inspector]" } ?: "") +
                (c.meta["legend"]?.let { l -> "\nlegend:" + l.joinToString("") { "\n  [${it["n"]}] ${it["path"].asText()}" +
                    (it["text"]?.let { t -> " '${t.asText()}'" } ?: "") } } ?: ""), c.meta)
            if (include) result.image(c.png) else result
        }

        reg.tool("fx_get_picked", "Get picked node", Kind.READ,
            "The node the developer picked in the running app (dev bar pick button / pick hotkey): target, property " +
                "details, a short explanation and a screenshot of the node (persisted like fx_take_screenshot). Other tools " +
                "accept picked:true as a target. Fails with PICK_STALE when nothing recent is picked — ask the developer to pick again.",
            Schema.obj().bool("includeImage", "Return the image block (default true)")) { a ->
            ctx.session.ensureStarted()
            val r = try {
                ctx.session.call(Fcp.Method.GET_PICKED, Json.obj(), 15_000) as ObjectNode
            } catch (e: FcpException) {
                if (e.code != Fcp.Error.PICK_STALE) throw e
                throw ToolException(Fcp.Error.PICK_STALE, e.message.orEmpty(), "ask the developer to pick again (dev bar pick button)")
            }
            val c = v.persistNodeShot(r, "picked-" + r.path("target").path("fxId").asText("node"))
            val t = r.path("target")
            val result = ok("picked ${t.path("path").asText("?")} (${r.path("ageMs").asLong()} ms ago); " +
                "saved ${c.meta["file"].asText()}" + (r.path("explain").asText(null)?.let { "\n$it" } ?: ""), c.meta)
            if (a.path("includeImage").asBoolean(true)) result.image(c.png) else result
        }

        reg.tool("fx_get_screenshot", "Get screenshot", Kind.READ,
            "Re-attach a persisted frame (screenshot, diff composite, state strip) as an image: by seq, path, or latest.",
            Schema.obj().integer("seq", "Frame seq").str("path", "PNG path inside the project").bool("latest", "Newest frame")
                .bool("includeImage", "Return the image block (default true)")) { a ->
            val frame = when {
                a.hasNonNull("seq") -> v.frame(a["seq"])
                a.hasNonNull("path") -> v.frame(a["path"])
                else -> v.store.latest() ?: throw org.lab59.fxloop.server.mcp.ToolException("NOT_FOUND", "no frames yet", "call fx_take_screenshot")
            }
            val png = ScreenStore.read(frame.file)
            val r = ok("${v.relative(frame.file)} (${frame.w}x${frame.h}, ${frame.kind})", v.describe(frame))
            if (a.path("includeImage").asBoolean(true)) r.image(png) else r
        }

        reg.tool("fx_compare_screenshots", "Compare screenshots", Kind.READ,
            "Pixel diff of two frames (seq or path; after may be capture:true for a fresh capture). Returns changedPct, " +
                "clustered regions, verdict none|minor|major and a persisted diff composite (diffPath). Image only on request.",
            Schema.obj().any("before", "seq or path").any("after", "seq or path").bool("capture", "Capture a fresh 'after' frame")
                .number("threshold", "Per-pixel RGB distance 0..1 (default 0.06)").bool("includeImage", "Return the diff image (default false)")
                .required("before")) { a ->
            val before = v.load(a["before"], "before")
            val after = if (a.path("capture").asBoolean(false)) {
                v.capture(Json.obj { put("target", "window") }, "compare-after").png
            } else v.load(a["after"], "after")
            val c = v.compare(before, after, a.path("threshold").asDouble(0.06), "diff")
            val r = ok("changed ${c.structured["changedPct"]}% (${c.diff.verdict}), ${c.diff.regions.size} region(s) → ${c.structured["diffPath"].asText()}", c.structured)
            if (a.path("includeImage").asBoolean(false)) r.image(c.diff.diffPng) else r
        }

        reg.tool("fx_lint_visual", "Lint visual", Kind.READ,
            "Heuristic visual checks on a window or subtree: textTruncated, siblingOverlap, zeroSizeVisible, outOfScene, " +
                "lowContrast (WCAG ratio vs nearest solid background). Pair with an annotated screenshot. checked counts the " +
                "rendered nodes inspected; excluded lists what was skipped on purpose (reason hidden: a hidden subtree such as " +
                "an inactive tab; reason virtualized: items without a rendered cell), so show or scroll to it and lint again.",
            Schema.obj().target().strArray("checks", "Subset of checks (default all)").integer("limit", "Page size (default 50)", 1, 200)
                .integer("offset", "Page offset")) { a ->
            ctx.session.ensureStarted()
            val r = ctx.session.call(Fcp.Method.LINT_VISUAL, a) as ObjectNode
            ok("${r["total"]} finding(s)" + r["items"].joinToString("") { "\n  ${it["check"].asText()} ${it["path"].asText()}: ${it["detail"].asText()}" }, r)
        }

        reg.tool("fx_capture_states", "Capture pseudo-states", Kind.WRITE,
            "CSS workbench: render one node in each pseudo-state (none, hover, focused, pressed, disabled) via " +
                "pseudoClassStateChanged, restoring the original state afterwards; returns one captioned strip image.",
            Schema.obj().target().strArray("states", "States (default none,hover,focused,pressed,disabled)")
                .number("scale", "Render scale (default 2)")) { a ->
            val r = ctx.session.call(Fcp.Method.CAPTURE_STATES, a, 15_000) as ObjectNode
            val frames = r["frames"].map { Base64.getDecoder().decode(it["pngB64"].asText()) }
            val strip = ImageOps.strip(frames, r["frames"].map { it["state"].asText() })
            val f = v.store.save(strip, "states-" + r.path("target").path("fxId").asText("node"), "states")
            val out = v.describe(f).apply {
                set<JsonNode>("target", r["target"])
                put("method", r.path("method").asText())
                set<JsonNode>("states", Json.strings(r["frames"].map { it["state"].asText() }))
                put("note", "skins reacting only to real input may render hover/pressed incompletely (method=pseudoClass)")
            }
            ToolResult.ok("state strip ${out["file"].asText()} (${frames.size} states, original state restored)", out).image(strip)
        }
    }
}

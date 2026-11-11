package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.util.Base64
import java.util.Locale
import javafx.css.PseudoClass
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.control.Labeled
import javafx.scene.image.PixelFormat
import javafx.scene.paint.Color
import javafx.scene.transform.Transform
import javafx.stage.Window
import javax.imageio.ImageIO

/**
 * Raw frame capture. The agent stays thin: it renders and encodes PNG, and reports the window origin and output
 * scale so the server can composite annotations (PRD 04 §3.1). No `javafx.swing` dependency.
 */
internal object Snapshots {
    const val HARD_MAX_WIDTH = 1600

    private class Frame(val w: Int, val h: Int, val argb: IntArray)

    fun screenshot(p: ObjectNode): ObjectNode {
        Fx.sleep(p.path("settleMs").asLong(150))
        Fx.barrier()
        val target = p.path("target").asText(if (Targets.hasTarget(p)) "node" else "window")
        val maxWidth = minOf(HARD_MAX_WIDTH * 2, maxOf(64, p.path("maxWidth").asInt(1200)))
        val requestedScale: Double? =
            if (p.hasNonNull("scale")) maxOf(0.25, minOf(3.0, p.get("scale").asDouble())) else null
        val annotate = p.path("annotate").asText("none")
        val meta = Json.obj()
        val frame = Fx.call {
            val node: Node
            val originX: Double
            val originY: Double
            val width: Double
            val window: Window
            if ("node" == target) {
                node = Targets.resolve(p)
                if (p.path("requireShown").asBoolean(false)) {
                    NodeInfo.notShownReason(node)?.let { throw FcpException(Fcp.Error.UNSUPPORTED_TARGET, "not rendered: $it") }
                }
                val b = node.localToScreen(node.boundsInLocal)
                    ?: throw FcpException(Fcp.Error.UNSUPPORTED_TARGET, "node is not showing; cannot snapshot")
                originX = b.minX
                originY = b.minY
                width = b.width
                window = node.scene.window
                meta.setNode("target", Targets.summary(node))
            } else {
                window = Windows.byIndex(p.intOrNull("windowIndex"))
                val scene = window.scene
                node = scene.root
                originX = window.x + scene.x
                originY = window.y + scene.y
                width = scene.width
                meta.put("windowIndex", Windows.indexOf(window))
            }
            var scale = requestedScale ?: 1.0
            val cap = if ("node" == target) maxWidth else minOf(maxWidth, HARD_MAX_WIDTH)
            if (width * scale > cap) scale = cap / width
            val f = render(node, scale, window.scene)
            meta.put("outputScale", scale)
            val origin = meta.putObject("windowOrigin")
            origin.put("x", originX)
            origin.put("y", originY)
            if ("none" != annotate) {
                meta.setNode("targets", annotationTargets(window, node, annotate))
            }
            f
        }
        val png = encode(frame)
        Fx.call { ForcedStates.describe() }?.let { meta.setNode("forcedStates", it) }
        meta.put("w", frame.w)
        meta.put("h", frame.h)
        meta.put("pngB64", Base64.getEncoder().encodeToString(png))
        return meta
    }

    private fun render(node: Node, scale: Double, scene: Scene?): Frame {
        val params = SnapshotParameters()
        params.transform = Transform.scale(scale, scale)
        if (scene != null && scene.root === node) {
            val fill = scene.fill
            params.fill = if (fill is Color) fill else Color.WHITE
        }
        val img = node.snapshot(params, null)
        val w = img.width.toInt()
        val h = img.height.toInt()
        val argb = IntArray(w * h)
        img.pixelReader.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w)
        return Frame(w, h, argb)
    }

    private fun encode(f: Frame): ByteArray {
        val bi = BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, f.w, f.h, f.argb, 0, f.w)
        try {
            ByteArrayOutputStream().use { out ->
                ImageIO.write(bi, "png", out)
                return out.toByteArray()
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    /** Set-of-Mark candidates: interactive = focus-traversable or Labeled; all = every visible node; else CSS query. */
    private fun annotationTargets(window: Window, scope: Node, filter: String): ArrayNode {
        val pool: List<Node>
        if ("interactive" == filter || "all" == filter) {
            pool = NodePath.descendantsInclusive(scope)
        } else {
            val found = ArrayList<Node>()
            for (n in scope.lookupAll(filter)) {
                val owner = LogicalTree.owner(n)
                if (owner != null && !found.contains(owner)) found.add(owner)
            }
            pool = found
        }
        val out = Json.array()
        var total = 0
        for (n in pool) {
            if (!NodeInfo.shown(n) || n.scene.window !== window) continue
            if ("interactive" == filter && !(n.isFocusTraversable || n is Labeled)) continue
            val b = n.localToScreen(n.boundsInLocal)
            if (b == null || b.width < 1 || b.height < 1) continue
            total++
            if (out.size() >= 40) continue
            val t = Targets.summary(n)
            val bs = t.putObject("boundsScreen")
            bs.put("x", b.minX).put("y", b.minY).put("w", b.width).put("h", b.height)
            out.add(t)
        }
        if (total > out.size()) {
            out.add(Json.obj().put("overflow", true).put("total", total))
        }
        return out
    }

    /** `fx_capture_states`: snapshot one node per pseudo-state, restoring the original state in finally. */
    fun captureStates(p: ObjectNode): ObjectNode {
        val states = ArrayList<String>()
        p.path("states").forEach { states.add(it.asText().lowercase(Locale.ROOT)) }
        if (states.isEmpty()) states.addAll(listOf("none", "hover", "focused", "pressed", "disabled"))
        val scale = maxOf(0.25, minOf(3.0, p.path("scale").asDouble(2.0)))
        val out = Json.obj()
        val frames = out.putArray("frames")
        val rendered = ArrayList<Frame>()
        Fx.run { ForcedStates.suspended {
            val node = Targets.resolve(p)
            out.setNode("target", Targets.summary(node))
            val wasDisabled = node.isDisable
            val original = HashSet(node.pseudoClassStates)
            try {
                for (state in states) {
                    applyState(node, state)
                    node.applyCss()
                    rendered.add(render(node, scale, null))
                    resetState(node, original, wasDisabled)
                }
            } finally {
                resetState(node, original, wasDisabled)
                node.applyCss()
            }
        } }
        for (i in states.indices) {
            val f = rendered[i]
            val fr = frames.addObject()
            fr.put("state", states[i])
            fr.put("w", f.w).put("h", f.h)
            fr.put("pngB64", Base64.getEncoder().encodeToString(encode(f)))
        }
        out.put("method", "pseudoClass")
        out.put("scale", scale)
        return out
    }

    private val PSEUDO = listOf("hover", "focused", "pressed", "armed", "selected", "focus-visible")

    private fun applyState(node: Node, state: String) {
        when (state) {
            "none" -> {}
            // a bound disable property cannot be written: fall back to the :disabled pseudo-class
            "disabled" -> if (node.disableProperty().isBound) {
                node.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("disabled"), true)
            } else {
                node.isDisable = true
            }
            else -> {
                node.pseudoClassStateChanged(PseudoClass.getPseudoClass(state), true)
                if ("pressed" == state) {
                    node.pseudoClassStateChanged(PseudoClass.getPseudoClass("armed"), true)
                    node.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true)
                }
            }
        }
    }

    private fun resetState(node: Node, original: Set<PseudoClass>, wasDisabled: Boolean) {
        if (!node.disableProperty().isBound) node.isDisable = wasDisabled
        node.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("disabled"), node.isDisabled)
        for (name in PSEUDO) {
            val pc = PseudoClass.getPseudoClass(name)
            node.pseudoClassStateChanged(pc, original.contains(pc))
        }
    }
}

package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.Paging
import java.util.Locale
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Labeled
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.text.Text

/** Computable visual heuristics complementing screenshots (PRD 04 §3.5). All findings carry `heuristic:true`. */
internal object VisualLint {
    val ALL: Set<String> = setOf("textTruncated", "siblingOverlap", "zeroSizeVisible", "outOfScene", "lowContrast")

    fun lint(p: ObjectNode): ObjectNode {
        val checks = HashSet<String>()
        p.path("checks").forEach { checks.add(it.asText()) }
        if (checks.isEmpty()) checks.addAll(ALL)
        return Fx.call {
            val scope = if (Targets.hasTarget(p)) Targets.resolve(p)
            else Windows.byIndex(p.intOrNull("windowIndex")).scene.root
            val findings = ArrayList<JsonNode>()
            val nodes = NodePath.descendantsInclusive(scope)
            val sceneBounds: Bounds = BoundingBox(0.0, 0.0, scope.scene.width, scope.scene.height)
            for (n in nodes) {
                if (!treeVisible(n)) continue
                if ("textTruncated" in checks && n is Labeled && truncated(n)) {
                    findings.add(
                        finding(
                            "textTruncated", n,
                            "text '${NodeInfo.clip(n.text, 40)}' needs ${Math.round(textWidth(n))}px but has ${Math.round(available(n))}px",
                            "warn", "widen the control, allow wrapping (wrapText) or shorten the text",
                        ),
                    )
                }
                val lb = n.layoutBounds
                if ("zeroSizeVisible" in checks && (lb.width <= 0 || lb.height <= 0) && n !is Text) {
                    findings.add(
                        finding(
                            "zeroSizeVisible", n, "visible but ${lb.width}x${lb.height}", "info",
                            "set a size or hide it (setVisible(false)/setManaged(false))",
                        ),
                    )
                }
                if ("outOfScene" in checks) {
                    val sb = n.localToScene(n.boundsInLocal)
                    if (sb != null && sb.width > 0 && !sb.intersects(sceneBounds)) {
                        findings.add(
                            finding(
                                "outOfScene", n,
                                "fully outside the scene at ${Math.round(sb.minX)},${Math.round(sb.minY)}", "warn",
                                "check layoutX/Y, translate and managed=false",
                            ),
                        )
                    }
                }
                if ("lowContrast" in checks && (n is Labeled || n is Text)) {
                    contrast(n, findings)
                }
            }
            if ("siblingOverlap" in checks) {
                for (n in nodes) {
                    if (n is Parent && n !is StackPane && n !is Group) overlap(n, findings)
                }
            }
            val out = Paging.page(findings, p.path("offset").asInt(0), minOf(200, p.path("limit").asInt(50)))
            out.put("scope", NodePath.of(scope))
            out
        }
    }

    private fun treeVisible(n: Node): Boolean {
        var c: Node? = n
        while (c != null) {
            if (!c.isVisible || c.opacity == 0.0) return false
            c = c.parent
        }
        return true
    }

    private fun finding(check: String, n: Node, detail: String, severity: String, suggestion: String): ObjectNode {
        val o = Json.obj()
        o.put("check", check)
        o.put("path", NodePath.of(n))
        o.put("detail", detail)
        o.put("severity", severity)
        o.put("suggestion", suggestion)
        o.put("heuristic", true)
        return o
    }

    private fun textWidth(l: Labeled): Double {
        val t = Text(l.text)
        t.font = l.font
        return t.layoutBounds.width
    }

    private fun available(l: Labeled): Double {
        val graphic = if (l.graphic == null) 0.0 else l.graphic.prefWidth(-1.0) + l.graphicTextGap
        return l.width - l.insets.left - l.insets.right - graphic
    }

    private fun truncated(l: Labeled): Boolean {
        if (l.text == null || l.text.isEmpty() || l.isWrapText || l.width <= 0) return false
        return textWidth(l) > available(l) + 1.0
    }

    private fun overlap(parent: Parent, findings: MutableList<JsonNode>) {
        val kids = LogicalTree.children(parent).filter {
            it.isVisible && !it.isMouseTransparent && it.parent === parent && it.layoutBounds.width > 0
        }
        for (i in kids.indices) {
            for (j in i + 1 until kids.size) {
                val a = kids[i].boundsInParent
                val b = kids[j].boundsInParent
                val ix = minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX)
                val iy = minOf(a.maxY, b.maxY) - maxOf(a.minY, b.minY)
                if (ix > 1 && iy > 1) {
                    val moved = if (kids[j].translateX != 0.0 || kids[j].translateY != 0.0) kids[j] else kids[i]
                    val other = if (moved === kids[j]) kids[i] else kids[j]
                    findings.add(
                        finding(
                            "siblingOverlap", moved,
                            "overlaps sibling ${NodePath.of(other)} by ${Math.round(ix)}x${Math.round(iy)}px", "warn",
                            "check translate/margins/layoutX; inspect with fx_explain_layout",
                        ),
                    )
                }
            }
        }
    }

    private fun contrast(n: Node, findings: MutableList<JsonNode>) {
        val fill = if (n is Labeled) n.textFill else (n as Text).fill
        if (fill !is Color) return
        var bg: Color? = null
        var c: Node? = n
        while (c != null) {
            if (c is Region) {
                val b = c.background
                if (b != null && b.fills.isNotEmpty()) {
                    val top = b.fills[b.fills.size - 1]
                    val topFill = top.fill
                    if (topFill is Color && topFill.opacity > 0.95) {
                        bg = topFill
                        break
                    }
                    if (topFill !is Color) {
                        findings.add(
                            finding(
                                "lowContrast", n, "skipped:unresolvedBackground", "info",
                                "background is a gradient/image; verify contrast visually",
                            ),
                        )
                        return
                    }
                }
            }
            c = c.parent
        }
        if (bg == null) {
            val sceneFill = n.scene.fill
            bg = if (sceneFill is Color) sceneFill else Color.WHITE
        }
        val ratio = ratio(fill, bg)
        if (ratio < 3.0) {
            findings.add(
                finding(
                    "lowContrast", n,
                    String.format(Locale.ROOT, "contrast %.2f:1 (%s on %s)", ratio, NodeInfo.web(fill), NodeInfo.web(bg)),
                    if (ratio < 1.5) "warn" else "info",
                    "WCAG AA needs 4.5:1 (3:1 for large text); adjust -fx-text-fill or the background",
                ),
            )
        }
    }

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun lin(v: Double): Double = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
}

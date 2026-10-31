package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Node
import javafx.scene.control.Cell
import javafx.scene.control.TableRow

/** Bounded scene-graph → JSON (PRD 01 §5). FX thread only. */
/**
 * Bounded scene-graph → JSON. Budgets: depth, node count and approximate output bytes (PRD 03 §10: budget-truncated
 * output ≤ 64 KB). Default-valued flags are omitted: `visible` only when false, `disabled`/`focused` only when true;
 * the type's default style class is omitted.
 */
internal class SceneSerializer(private val maxDepth: Int, private val maxNodes: Int, private val maxBytes: Int = DEFAULT_MAX_BYTES) {
    private var bytes = 0

    companion object {
        const val DEFAULT_MAX_BYTES = 56 * 1024
    }

    var emitted: Int = 0
        private set
    var truncated: Boolean = false
        private set

    fun serialize(root: Node): ObjectNode = node(root, 0, NodePath.of(root))

    private fun node(n: Node, depth: Int, path: String?): ObjectNode {
        emitted++
        val o = Json.obj()
        o.put("h", AgentRuntime.current().handles.handleOf(n))
        o.put("path", path)
        o.put("type", NodePath.typeName(n))
        if (!n.id.isNullOrEmpty()) o.put("fxId", n.id)
        val defaultClass = NodePath.typeName(n).replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
        val classes = n.styleClass.filter { it != defaultClass }
        if (classes.isNotEmpty()) o.put("styleClass", classes.joinToString(" "))
        val text = NodeInfo.text(n)
        if (text != null) o.put("text", NodeInfo.clip(text, 80))
        val value = NodeInfo.value(n)
        if (value != null) NodeInfo.putValue(o, "value", value)
        if (!n.isVisible) o.put("visible", false)
        if (n.isDisabled) o.put("disabled", true)
        if (n.isFocused) o.put("focused", true)
        NodeInfo.screenBounds(n)?.let { o.setNode("boundsScreen", it) }
        val kids = LogicalTree.children(n)
        if (LogicalTree.isVirtualized(n)) {
            o.put("itemCount", NodeInfo.itemCount(n))
            val sel = NodeInfo.selectedIndex(n)
            if (sel != null && sel >= 0) o.put("selectedIndex", sel)
            val cells = o.putArray("visibleCells")
            for (c in kids) {
                if (cells.size() >= 10) break
                cells.add(NodeInfo.clip(NodeInfo.text(c), 80))
            }
        }
        o.put("kids", kids.size)
        bytes += 90 + (path?.length ?: 0) + (text?.length?.coerceAtMost(80) ?: 0) + (n.id?.length ?: 0)
        if (kids.isEmpty() || n is Cell<*> && n !is TableRow<*>) return o
        val children = o.putArray("children")
        if (depth + 1 > maxDepth) {
            truncated = true
            children.add(Json.obj().put("truncated", true).put("kids", kids.size))
            return o
        }
        val segs = NodePath.childSegments(kids)
        for (i in kids.indices) {
            if (emitted >= maxNodes || bytes >= maxBytes) {
                truncated = true
                children.add(Json.obj().put("truncated", true).put("kids", kids.size - i))
                break
            }
            children.add(node(kids[i], depth + 1, "$path/${segs[i]}"))
        }
        return o
    }
}

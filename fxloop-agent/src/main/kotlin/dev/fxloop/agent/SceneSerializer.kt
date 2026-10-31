package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Node
import javafx.scene.control.Cell
import javafx.scene.control.TableRow

/** Bounded scene-graph → JSON (PRD 01 §5). FX thread only. */
internal class SceneSerializer(private val maxDepth: Int, private val maxNodes: Int) {
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
        if (n.styleClass.isNotEmpty()) o.put("styleClass", n.styleClass.joinToString(" "))
        val text = NodeInfo.text(n)
        if (text != null) o.put("text", NodeInfo.clip(text, 80))
        val value = NodeInfo.value(n)
        if (value != null) NodeInfo.putValue(o, "value", value)
        o.put("visible", n.isVisible)
        o.put("disabled", n.isDisabled)
        o.put("focused", n.isFocused)
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
        if (kids.isEmpty() || n is Cell<*> && n !is TableRow<*>) return o
        val children = o.putArray("children")
        if (depth + 1 > maxDepth) {
            truncated = true
            children.add(Json.obj().put("truncated", true).put("kids", kids.size))
            return o
        }
        val segs = NodePath.childSegments(kids)
        for (i in kids.indices) {
            if (emitted >= maxNodes) {
                truncated = true
                children.add(Json.obj().put("truncated", true).put("kids", kids.size - i))
                break
            }
            children.add(node(kids[i], depth + 1, "$path/${segs[i]}"))
        }
        return o
    }
}

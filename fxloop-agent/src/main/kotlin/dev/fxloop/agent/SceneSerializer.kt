package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.util.IdentityHashMap
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.IndexedCell

/** Bounded scene-graph → JSON (PRD 01 §5). FX thread only. */
/**
 * Bounded scene-graph → JSON. Budgets: depth, node count and approximate output bytes (PRD 03 §10: budget-truncated
 * output ≤ 64 KB). Default-valued flags are omitted: `visible` only when false, `disabled`/`focused` only when true;
 * the type's default style class is omitted. With `skin`, all scene-graph children are walked: a node is logical
 * (and gets its path) when it is a logical child of its nearest logical ancestor; other nodes are flagged `skin`
 * and, having no resolvable path, are addressed by handle.
 */
internal class SceneSerializer(private val maxDepth: Int, private val maxNodes: Int, private val maxBytes: Int = DEFAULT_MAX_BYTES, private val skin: Boolean = false) {
    private var bytes = 0

    companion object {
        const val DEFAULT_MAX_BYTES = 56 * 1024
    }

    var emitted: Int = 0
        private set
    var truncated: Boolean = false
        private set

    fun serialize(root: Node): ObjectNode = node(root, 0, NodePath.of(root), null, NodeInfo.shown(root))

    /** The logical children of the nearest logical ancestor, with their paths; skin nodes below it share it. */
    private class Owner(path: String?, kids: List<Node>) {
        private val paths = IdentityHashMap<Node, String>()

        init {
            val segs = NodePath.childSegments(kids)
            for (i in kids.indices) paths[kids[i]] = "$path/${segs[i]}"
        }

        fun pathOf(n: Node): String? = paths[n]
    }

    /**
     * A skin node (skin mode only) comes with the [Owner] of its nearest logical ancestor; logical nodes get none.
     * A node that is not rendered ([NodeInfo.shown]) says `shown:false` and has no `boundsScreen`: its bounds are
     * stale, typically stacked under the page that is showing.
     */
    private fun node(n: Node, depth: Int, path: String?, skinOwner: Owner?, shown: Boolean): ObjectNode {
        emitted++
        val o = Json.obj()
        o.put("h", AgentRuntime.current().handles.handleOf(n))
        if (skinOwner == null) o.put("path", path) else o.put("skin", true)
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
        if (!shown) o.put("shown", false) else NodeInfo.screenBounds(n)?.let { o.setNode("boundsScreen", it) }
        val logical = if (skinOwner != null) emptyList() else LogicalTree.children(n)
        val kids = if (skin) (n as? Parent)?.childrenUnmodifiable ?: emptyList() else logical
        if (skinOwner == null && LogicalTree.isVirtualized(n)) {
            o.put("itemCount", NodeInfo.itemCount(n))
            val sel = NodeInfo.selectedIndex(n)
            if (sel != null && sel >= 0) o.put("selectedIndex", sel)
            val cells = o.putArray("visibleCells")
            for (c in logical) {
                if (cells.size() >= 10) break
                if (c is IndexedCell<*>) cells.add(NodeInfo.clip(NodeInfo.cellText(c), 80))
            }
            val unrendered = LogicalTree.unrenderedItems(n, logical)
            if (unrendered > 0) o.putObject("excluded").put("reason", "virtualized").put("count", unrendered)
        }
        o.put("kids", kids.size)
        bytes += 90 + (path?.length ?: 0) + (text?.length?.coerceAtMost(80) ?: 0) + (n.id?.length ?: 0)
        if (kids.isEmpty()) return o
        val children = o.putArray("children")
        if (depth + 1 > maxDepth) {
            truncated = true
            children.add(Json.obj().put("truncated", true).put("kids", kids.size))
            return o
        }
        val owner = if (skin) skinOwner ?: Owner(path, logical) else null
        val segs = if (skin) emptyList() else NodePath.childSegments(kids)
        for (i in kids.indices) {
            if (emitted >= maxNodes || bytes >= maxBytes) {
                truncated = true
                children.add(Json.obj().put("truncated", true).put("kids", kids.size - i))
                break
            }
            val k = kids[i]
            val kidPath = if (owner == null) "$path/${segs[i]}" else owner.pathOf(k)
            val kidShown = shown && NodeInfo.shownBelow(k, n)
            children.add(node(k, depth + 1, kidPath, if (owner != null && kidPath == null) owner else null, kidShown))
        }
        return o
    }
}

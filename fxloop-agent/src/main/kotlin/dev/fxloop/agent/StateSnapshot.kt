package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.util.ArrayDeque
import javafx.scene.Node
import javafx.scene.control.ProgressIndicator

/**
 * Flat, path-indexed snapshot of the watched properties of every logical node (input for the server-side
 * scene-diff engine, PRD 02 §6). FX thread only.
 */
internal object StateSnapshot {
    fun capture(maxNodes: Int): ObjectNode {
        val out = Json.obj()
        out.setNode("windows", Windows.describeAll())
        val nodes = out.putObject("nodes")
        var count = 0
        var overflow = false
        for (w in Windows.showing()) {
            val stack = ArrayDeque<Node>()
            val paths = ArrayDeque<String>()
            val root = w.scene.root
            val rootPath = NodePath.of(root) ?: continue
            stack.push(root)
            paths.push(rootPath)
            while (!stack.isEmpty()) {
                val n = stack.pop()
                val path = paths.pop()
                if (count >= maxNodes) {
                    overflow = true
                    break
                }
                count++
                val e = nodes.putObject(path)
                e.put("type", NodePath.typeName(n))
                val text = NodeInfo.text(n)
                if (text != null) e.put("text", NodeInfo.clip(text, 120))
                val value = NodeInfo.value(n)
                if (value != null) NodeInfo.putValue(e, "value", value)
                e.put("visible", n.isVisible)
                e.put("disabled", n.isDisabled)
                e.put("focused", n.isFocused)
                val items = NodeInfo.itemCount(n)
                if (items != null) e.put("itemCount", items)
                val sel = NodeInfo.selectedIndex(n)
                if (sel != null) e.put("selected", sel)
                if (n is ProgressIndicator) e.put("progress", n.progress)
                val kids = LogicalTree.children(n)
                val segs = NodePath.childSegments(kids)
                for (i in kids.indices.reversed()) {
                    stack.push(kids[i])
                    paths.push("$path/${segs[i]}")
                }
            }
        }
        out.put("overflow", overflow)
        out.put("count", count)
        return out
    }
}

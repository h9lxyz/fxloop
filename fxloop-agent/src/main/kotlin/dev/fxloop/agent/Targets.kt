package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import javafx.scene.Node

/** Resolves the common target params `handle | path | query (+windowIndex)` to one node. FX thread only. */
internal object Targets {
    fun hasTarget(p: ObjectNode): Boolean = p.hasNonNull("handle") || p.hasNonNull("path") || p.hasNonNull("query")

    fun resolve(p: ObjectNode): Node {
        if (p.hasNonNull("handle")) {
            val n = AgentRuntime.current().handles.resolve(p.get("handle").asInt())
            if (n == null || n.scene == null) {
                throw FcpException(
                    Fcp.Error.NODE_NOT_FOUND,
                    "handle ${p.get("handle").asInt()} is stale (node removed or collected); re-query with fx_find_nodes or use a path",
                )
            }
            return n
        }
        if (p.hasNonNull("path")) {
            return NodePath.resolve(p.get("path").asText())
        }
        if (p.hasNonNull("query")) {
            val sel = p.get("query").asText()
            val hits = query(sel, p.intOrNull("windowIndex"))
            if (hits.isEmpty()) {
                throw FcpException(
                    Fcp.Error.NODE_NOT_FOUND,
                    "CSS query '$sel' matched nothing; check the fx:id / style class with fx_find_nodes",
                )
            }
            if (hits.size > 1) {
                val c = Json.array()
                hits.take(10).forEach { c.add(NodePath.of(it)) }
                throw FcpException(
                    Fcp.Error.AMBIGUOUS_PATH,
                    "CSS query '$sel' matched ${hits.size} nodes; target one by path", Json.obj().setNode("candidates", c),
                )
            }
            return hits[0]
        }
        throw FcpException(Fcp.Error.BAD_REQUEST, "target required: pass handle, path or query")
    }

    /** CSS lookup across windows (or one window), mapped to logical owners and de-duplicated. */
    fun query(selector: String, windowIndex: Int?): List<Node> {
        val windows = if (windowIndex == null) Windows.showing() else listOf(Windows.byIndex(windowIndex))
        val out = LinkedHashSet<Node>()
        for (w in windows) {
            try {
                for (n in w.scene.root.lookupAll(selector)) {
                    LogicalTree.owner(n)?.let { out.add(it) }
                }
            } catch (e: RuntimeException) {
                throw FcpException(Fcp.Error.BAD_REQUEST, "invalid CSS selector '$selector': ${e.message}")
            }
        }
        return ArrayList(out)
    }

    fun summary(n: Node): ObjectNode {
        val o = Json.obj()
        o.put("h", AgentRuntime.current().handles.handleOf(n))
        o.put("path", NodePath.of(n))
        o.put("type", NodePath.typeName(n))
        if (n.id != null) o.put("fxId", n.id)
        val text = NodeInfo.text(n)
        if (text != null) o.put("text", NodeInfo.clip(text, 80))
        return o
    }
}

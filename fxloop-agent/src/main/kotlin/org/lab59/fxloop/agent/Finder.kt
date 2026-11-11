package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Paging
import java.util.Locale
import javafx.scene.Node

/** `findNodes`: CSS query, case-insensitive text contains, or simple type name. FX thread only. */
internal object Finder {
    fun find(p: ObjectNode): ObjectNode {
        val windowIndex = p.intOrNull("windowIndex")
        val limit = minOf(200, p.path("limit").asInt(20))
        val offset = maxOf(0, p.path("offset").asInt(0))
        val hits: List<Node>
        if (p.hasNonNull("query")) {
            hits = Targets.query(p.get("query").asText(), windowIndex)
        } else if (p.hasNonNull("text") || p.hasNonNull("type")) {
            val text = if (p.hasNonNull("text")) p.get("text").asText().lowercase(Locale.ROOT) else null
            val type = if (p.hasNonNull("type")) p.get("type").asText() else null
            val found = ArrayList<Node>()
            val windows = if (windowIndex == null) Windows.showing() else listOf(Windows.byIndex(windowIndex))
            for (w in windows) {
                for (n in NodePath.descendantsInclusive(w.scene.root)) {
                    if (type != null && !typeMatches(n, type)) continue
                    if (text != null) {
                        val t = NodeInfo.text(n)
                        if (t == null || !t.lowercase(Locale.ROOT).contains(text)) continue
                    }
                    found.add(n)
                }
            }
            hits = found
        } else {
            throw FcpException(Fcp.Error.BAD_REQUEST, "pass one of query (CSS selector), text or type")
        }
        val items = ArrayList<JsonNode>()
        for (n in hits) {
            val s = Targets.summary(n)
            val shown = NodeInfo.shown(n)
            s.put("visible", n.isVisible)
            s.put("shown", shown)
            s.put("disabled", n.isDisabled)
            if (shown) NodeInfo.screenBounds(n)?.let { s.setNode("boundsScreen", it) }
            items.add(s)
        }
        return Paging.page(items, offset, limit)
    }

    private fun typeMatches(n: Node, type: String): Boolean {
        var c: Class<*>? = n.javaClass
        while (c != null) {
            if (c.simpleName == type) return true
            c = c.superclass
        }
        return false
    }
}

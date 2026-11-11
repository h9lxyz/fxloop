package org.lab59.fxloop.protocol

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Standard pagination envelope: `{total, count, offset, items, has_more, next_offset}`. */
object Paging {
    @JvmStatic
    fun page(all: List<JsonNode>, offset: Int, limit: Int): ObjectNode {
        val total = all.size
        val from = offset.coerceIn(0, total)
        val to = minOf(total, from + maxOf(0, limit))
        return Json.obj {
            put("total", total)
            put("count", to - from)
            put("offset", from)
            set<JsonNode>("items", Json.array().addAll(all.subList(from, to)))
            put("has_more", to < total)
            if (to < total) put("next_offset", to)
        }
    }
}

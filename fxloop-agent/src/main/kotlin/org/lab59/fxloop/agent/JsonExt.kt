package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** `ObjectNode.set` with a fixed type argument so call sites stay inference-free; returns the receiver for chaining. */
internal fun ObjectNode.setNode(name: String, value: JsonNode?): ObjectNode {
    set<JsonNode>(name, value)
    return this
}

/** Integer-or-null view of a field that may be absent or JSON null. */
internal fun ObjectNode.intOrNull(name: String): Int? = if (hasNonNull(name)) get(name).asInt() else null

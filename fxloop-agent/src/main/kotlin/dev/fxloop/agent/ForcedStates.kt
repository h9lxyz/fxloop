package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import javafx.scene.Node

/** Forced pseudo-states for the inspector (PRD 09 §3): one node at a time, held until cleared. FX thread. STUB. */
internal object ForcedStates {
    /** FCP `forceStates`. */
    fun force(p: ObjectNode): ObjectNode = throw FcpException(Fcp.Error.UNKNOWN_METHOD, "forceStates is not implemented yet")

    fun clear() {}

    fun forcedNames(node: Node): Set<String> = emptySet()

    fun describe(): ObjectNode? = null

    fun <T> suspended(block: () -> T): T = block()
}

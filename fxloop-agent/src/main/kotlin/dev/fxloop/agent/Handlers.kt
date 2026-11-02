package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException

/** The `onXxx` handler properties a node has set (PRD 09 §10). STUB. */
internal object Handlers {
    fun describe(p: ObjectNode): ObjectNode = throw FcpException(Fcp.Error.UNKNOWN_METHOD, "nodeHandlers is not implemented yet")
}

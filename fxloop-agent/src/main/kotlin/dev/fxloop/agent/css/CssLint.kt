package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException

/** Stylesheet view and CSS lint (PRD 09 §7). STUB. */
internal object CssLint {
    fun lint(p: ObjectNode): ObjectNode = throw FcpException(Fcp.Error.UNKNOWN_METHOD, "cssLint is not implemented yet")

    fun sheets(p: ObjectNode): ObjectNode = throw FcpException(Fcp.Error.UNKNOWN_METHOD, "cssSheets is not implemented yet")
}

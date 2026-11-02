package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException

/** Layout edits → FXML attribute patches (PRD 09 §4.2). Read-only plan; the server writes. STUB. */
internal object FxmlEdit {
    fun plan(p: ObjectNode): ObjectNode = throw FcpException(Fcp.Error.UNKNOWN_METHOD, "fxmlEditPlan is not implemented yet")
}

package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.server.app.ActionNeeded
import dev.fxloop.server.app.AppSession

/**
 * Turns an `fx_reload` result into the developer-facing story (PRD 07 §7.1): the transient "last reload" line and the
 * "action needed" entries. Pure function of the result, so the agent's tool result and the bar never disagree.
 */
object ReloadStory {
    data class Story(val lastReload: String, val ok: Boolean, val actionNeeded: List<ActionNeeded>)

    fun of(result: ObjectNode, mode: AppSession.Mode, externalCompile: Boolean): Story = TODO("E5a")
}

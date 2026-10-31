package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json

/**
 * E5a (PRD 07 §7): the dev bar, an agent-owned `Popup` per showing app stage carrying session, last reload and
 * action-needed segments. Rendered from the full state the server pushes with `setBarState`.
 */
class DevBar(private val rt: AgentRuntime) {
    /** FCP `setBarState` (the full state, see docs/decisions/live-dev-loop.md). Returns [describe]. */
    internal fun applyState(p: ObjectNode): ObjectNode {
        TODO("E5a")
    }

    /** Called by [HumanInput] when a robot bracket opens/closes; shows the driving text for its duration. Any thread. */
    fun agentInput(active: Boolean) {
        // E5a
    }

    /** FCP `getBarState`: debug view of what the bar renders. */
    internal fun describe(): ObjectNode = Json.obj().put("bar", "off").put("rendered", false)
}

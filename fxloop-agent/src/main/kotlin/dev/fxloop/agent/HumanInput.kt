package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json

/**
 * E3 (PRD 07 §5) + E5b (§7.2): observes the developer's own input with scene event filters on every app window
 * (overlay windows excluded) and turns it into journal-shaped `humanAction` events; brackets agent actions so their
 * events are not mistaken for human ones; handles the restart and pick hotkeys.
 */
class HumanInput(private val rt: AgentRuntime) {
    /** Handle returned by [open]; pass it to [close]. */
    class Bracket internal constructor(val robot: Boolean)

    /** Installs the filters on existing and future windows. FX thread; idempotent. */
    fun install() {
        // E3
    }

    /** Applies `setBarState.config`: `{recordHuman, pickKey, restartKey}` (absent keys keep their value). */
    fun configure(config: JsonNode) {
        // E3
    }

    /** Opens an agent-action bracket (any thread). Robot brackets also make the dev bar show the driving state. */
    fun open(robot: Boolean): Bracket = Bracket(robot)

    /** Closes [b] (the 100 ms settle window starts now); returns the human events seen while it was open (`conflicts`). */
    fun close(b: Bracket): Int = 0

    /** `{lastActivityMs?, focusedWindow?, recorded, skippedHuman, recordHuman}` for `status`. FX thread. */
    internal fun describe(): ObjectNode = Json.obj()

    /** FCP `testHumanInput` (test mode only): synthetic events outside any bracket. */
    internal fun testInput(p: ObjectNode): ObjectNode {
        TODO("E3")
    }
}

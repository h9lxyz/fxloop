package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import javafx.scene.Node
import javafx.stage.Window

/**
 * E6 (PRD 07 §8): pick mode. While active, hover outlines the node under the pointer, a click is consumed and
 * selects the node (`humanPick` event), Escape leaves. The pick resolves `{"picked": true}` targets.
 */
class PickMode(private val rt: AgentRuntime) {
    @Volatile
    var active: Boolean = false
        private set

    /** Toggles pick mode on [window] (default: the focused app window). FX thread. */
    fun toggle(window: Window?) {
        TODO("E6")
    }

    /** The picked node, or throws `PICK_STALE` (older than 10 min, node left the scene) / `NODE_NOT_FOUND` (none). FX thread. */
    fun picked(): Node {
        TODO("E6")
    }

    /** FCP `getPicked`. */
    internal fun get(p: ObjectNode): ObjectNode {
        TODO("E6")
    }

    /** FCP `setPickMode {on, windowIndex?}`. */
    internal fun set(p: ObjectNode): ObjectNode {
        TODO("E6")
    }
}

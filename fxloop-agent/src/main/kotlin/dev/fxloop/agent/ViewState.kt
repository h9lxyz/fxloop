package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Node

/**
 * E2 (PRD 07 §4): view-state carry-over across an FXML swap or a remount. [capture] snapshots the old subtree,
 * [restore] re-applies it to the new subtree, matched by fx:id first, then by subtree-relative node path, and only
 * when the node type matches. FX thread only.
 */
internal object ViewState {
    /** Opaque capture of one subtree. */
    class Snapshot internal constructor()

    fun capture(root: Node): Snapshot {
        TODO("E2")
    }

    /** @return `{restored: n, skipped: [{path, reason}]}` */
    fun restore(snapshot: Snapshot, root: Node): ObjectNode {
        TODO("E2")
    }

    /** Adds [add] (`{restored, skipped}`) into [into]. */
    fun merge(into: ObjectNode, add: ObjectNode) {
        into.put("restored", into.path("restored").asInt(0) + add.path("restored").asInt(0))
        val skipped = (into.get("skipped") as? com.fasterxml.jackson.databind.node.ArrayNode ?: into.putArray("skipped"))
        add.path("skipped").forEach { skipped.add(it) }
    }

    fun empty(): ObjectNode = Json.obj().put("restored", 0).also { it.putArray("skipped") }
}

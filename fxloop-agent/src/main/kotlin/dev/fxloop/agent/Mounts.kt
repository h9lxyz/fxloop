package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.lang.instrument.Instrumentation
import java.util.function.Supplier
import javafx.scene.Node

/**
 * E1 (PRD 07 §3): view factories registered through `FxLoopDev.view(key, supplier)` and reload callbacks from
 * `FxLoopDev.onReload(runnable)`. After a successful hotswap the server calls [remount] (FCP `remountViews`), which
 * re-invokes each live factory on the FX thread and swaps the old node for the new one with [Reloads.swap].
 */
class MountRegistry {
    /** Records (or replaces) the mount [key] and returns [node] unchanged. Safe from any thread. */
    fun register(key: String, supplier: Supplier<out Node>, node: Node): Node {
        TODO("E1")
    }

    /** Adds a callback run on the FX thread after every successful hotswap or FXML tier. */
    fun onReload(callback: Runnable) {
        TODO("E1")
    }

    /** `[{key, inScene}]` for `status`. */
    internal fun describe(): ArrayNode = Json.array()

    /**
     * FCP `remountViews`. Params `{remount=true, carryState=true, restage=false}`. Result:
     * `{mounts, remounted:[key], skipped:[{key,reason}], failed:[{key,error}], stateCarried:{restored, skipped:[…]},
     * callbacks:{ran, failed:[{error}]}, restaged?, restageError?}`.
     */
    internal fun remount(p: ObjectNode): ObjectNode {
        TODO("E1")
    }

    companion object {
        /** Installs the opt-in `restage` support (records the `Application` instance); never throws. */
        internal fun installHooks(inst: Instrumentation) {
            // E1: restage hook
        }
    }
}

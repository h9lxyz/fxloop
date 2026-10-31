package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import dev.fxloop.protocol.Json
import java.lang.ref.WeakReference
import java.net.URL
import javafx.scene.Node

/**
 * FXML load-registry (PRD 02 §4): `location → weak(root), controllerClass`. Fed by the ByteBuddy hook
 * (Plan A) and by `FxLoopDev.track/load` (Plan B).
 */
class FxmlRegistry {
    data class Entry(val location: URL, val root: WeakReference<Any>, val controllerClass: String?, val source: String)

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun record(location: URL, root: Any, controller: Any?, source: String) {
        entries.removeIf { it.root.get() == null || it.root.get() === root }
        entries.add(Entry(location, WeakReference(root), controller?.javaClass?.name, source))
    }

    @Synchronized
    fun live(): List<Entry> {
        entries.removeIf { it.root.get() == null }
        return entries.toList()
    }

    @Synchronized
    fun replace(old: Entry, newRoot: Any, controller: Any?) {
        entries.remove(old)
        entries.add(Entry(old.location, WeakReference(newRoot), controller?.javaClass?.name, old.source))
    }

    @Synchronized
    internal fun describe(): ArrayNode {
        val out = Json.array()
        for (e in live()) {
            val o = out.addObject()
            o.put("location", e.location.toString())
            o.put("controller", e.controllerClass)
            o.put("source", e.source)
            val root = e.root.get()
            if (root is Node) {
                o.put("rootPath", NodePath.of(root))
            }
        }
        return out
    }

    companion object {
        /** Called from instrumented `FXMLLoader` code; must never throw. */
        @JvmStatic
        fun recordFromHook(location: Any?, root: Any?, controller: Any?) {
            try {
                val rt = AgentRuntime.get()
                if (rt != null && location is URL && root != null) {
                    rt.fxml.record(location, root, controller, "hook")
                }
            } catch (ignored: Throwable) {
                // never disturb FXMLLoader
            }
        }
    }
}

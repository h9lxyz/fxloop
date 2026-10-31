package dev.fxloop.agent

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import javafx.scene.Node

/** Session-scoped integer handles for nodes, held weakly so the agent never leaks UI (PRD 01 §4). */
class HandleTable {
    private val byHandle = HashMap<Int, WeakReference<Node>>()
    private val byNode = WeakHashMap<Node, Int>()
    private var next = 1

    @Synchronized
    fun handleOf(node: Node): Int {
        byNode[node]?.let { return it }
        val handle = next++
        byHandle[handle] = WeakReference(node)
        byNode[node] = handle
        if (byHandle.size > 10_000) {
            byHandle.values.removeIf { it.get() == null }
        }
        return handle
    }

    @Synchronized
    fun resolve(handle: Int): Node? = byHandle[handle]?.get()

    @Synchronized
    fun size(): Int = byHandle.size
}

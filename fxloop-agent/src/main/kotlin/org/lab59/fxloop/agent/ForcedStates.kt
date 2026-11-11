package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.util.Locale
import javafx.application.Platform
import javafx.collections.SetChangeListener
import javafx.css.PseudoClass
import javafx.scene.Node

/**
 * Forced pseudo-states for the inspector (PRD 09 §3): one node at a time, held until cleared. A listener on the
 * node's pseudo-class set re-asserts a forced class that disappears, at most once per pulse, and gives up on a class
 * after [MAX_REVERSALS] consecutive reversals. FX thread.
 */
internal object ForcedStates {
    private const val MAX_REVERSALS = 3
    private const val RESET_MS = 1000L
    private val NAME = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,63}")

    private class Held(val node: Node) {
        val forced = LinkedHashSet<String>()
        val before = HashSet<String>()
        val gaveUp = LinkedHashSet<String>()
        val reversals = HashMap<String, Int>()
        val lastReversal = HashMap<String, Long>()
        var pending = false
        var own = false
        var live = true
        val listener = SetChangeListener<PseudoClass> { c ->
            if (!own && c.wasRemoved() && !c.wasAdded() && c.elementRemoved.pseudoClassName in forced) schedule(this)
        }
    }

    private var held: Held? = null

    /** FCP `forceStates`. */
    fun force(p: ObjectNode): ObjectNode = Fx.call {
        val names = LinkedHashSet<String>()
        p.path("states").forEach { names.add(it.asText().trim().removePrefix(":").lowercase(Locale.ROOT)) }
        names.removeAll { it.isEmpty() }
        for (n in names) {
            if (!NAME.matches(n)) throw FcpException(Fcp.Error.BAD_REQUEST, "invalid state name '$n'")
        }
        if (names.isEmpty() || !Targets.hasTarget(p)) {
            clear()
            return@call Json.obj().setNode("forced", Json.array()).setNode("gaveUp", Json.array())
        }
        val node = Targets.resolve(p)
        if (held?.node !== node) clear()
        val h = held ?: attach(node)
        h.own = true
        try {
            for (old in h.forced.filter { it !in names }) restore(h, old)
            for (n in names) {
                if (n !in h.forced) {
                    if (has(node, n)) h.before.add(n)
                    h.forced.add(n)
                }
                h.gaveUp.remove(n)
                h.reversals.remove(n)
                node.pseudoClassStateChanged(PseudoClass.getPseudoClass(n), true)
            }
        } finally {
            h.own = false
        }
        node.applyCss()
        result(h)
    }

    private fun has(node: Node, name: String): Boolean = node.pseudoClassStates.any { it.pseudoClassName == name }

    private fun attach(node: Node): Held {
        val h = Held(node)
        held = h
        node.pseudoClassStates.addListener(h.listener)
        return h
    }

    private fun active(h: Held): List<String> = h.forced.filter { it !in h.gaveUp }

    private fun result(h: Held): ObjectNode {
        val o = Json.obj()
        o.setNode("target", Targets.summary(h.node))
        val f = o.putArray("forced")
        active(h).forEach { f.add(it) }
        val g = o.putArray("gaveUp")
        h.gaveUp.forEach { g.add(it) }
        val pcs = o.putArray("pseudoClasses")
        h.node.pseudoClassStates.map { it.pseudoClassName }.sorted().forEach {
            pcs.addObject().put("name", it).put("forced", it in h.forced && it !in h.gaveUp)
        }
        return o
    }

    private fun restore(h: Held, name: String) {
        h.forced.remove(name)
        h.gaveUp.remove(name)
        h.reversals.remove(name)
        if (name !in h.before) h.node.pseudoClassStateChanged(PseudoClass.getPseudoClass(name), false)
    }

    private fun schedule(h: Held) {
        if (h.pending) return
        h.pending = true
        Platform.runLater {
            h.pending = false
            if (!h.live) return@runLater
            val now = System.currentTimeMillis()
            h.own = true
            try {
                for (name in h.forced.toList()) {
                    if (name in h.gaveUp || has(h.node, name)) continue
                    val recent = now - (h.lastReversal[name] ?: 0L) < RESET_MS
                    val count = (if (recent) h.reversals[name] ?: 0 else 0) + 1
                    h.reversals[name] = count
                    h.lastReversal[name] = now
                    if (count >= MAX_REVERSALS) h.gaveUp.add(name) else h.node.pseudoClassStateChanged(PseudoClass.getPseudoClass(name), true)
                }
            } finally {
                h.own = false
            }
        }
    }

    fun clear() {
        val h = held ?: return
        held = null
        h.live = false
        h.node.pseudoClassStates.removeListener(h.listener)
        for (name in h.forced.toList()) restore(h, name)
        h.node.applyCss()
    }

    fun forcedNames(node: Node): Set<String> {
        val h = held ?: return emptySet()
        return if (h.node === node) active(h).toSet() else emptySet()
    }

    fun describe(): ObjectNode? {
        val h = held ?: return null
        val forced = Json.array()
        active(h).forEach { forced.add(it) }
        return Json.obj().setNode("target", Targets.summary(h.node)).setNode("forced", forced)
    }

    /** Clears the forced states, runs [block], then forces them again (a capture must see the node's real states). */
    fun <T> suspended(block: () -> T): T {
        val h = held ?: return block()
        val node = h.node
        val names = h.forced.toList()
        clear()
        try {
            return block()
        } finally {
            if (node.scene != null) {
                val again = attach(node)
                again.own = true
                try {
                    for (n in names) {
                        if (has(node, n)) again.before.add(n)
                        again.forced.add(n)
                        node.pseudoClassStateChanged(PseudoClass.getPseudoClass(n), true)
                    }
                } finally {
                    again.own = false
                }
                node.applyCss()
            }
        }
    }
}

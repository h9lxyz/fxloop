package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.Reloads
import dev.fxloop.protocol.Json
import javafx.css.CssParser
import javafx.css.Selector
import javafx.scene.Node
import javafx.scene.Parent

/**
 * Selectors a new rule for one node can use and how many nodes of its scene each matches, so the inspector's
 * *New rule* starts scoped to the picked node (`#form .label`), not to every `.label`. JavaFX CSS has no
 * `:nth-child`, so the narrowest selector is built from ids and classes on the node and its ancestors; when even that
 * matches siblings, the count says so. FX thread only.
 */
internal object Scopes {
    private val IDENT = Regex("-?[_a-zA-Z][_a-zA-Z0-9-]*")
    private const val MAX_PARTS = 4

    /** `[{selector, matches}]`, narrowest first: the most specific selector found, then the node's own id and classes. */
    fun of(node: Node): ArrayNode {
        val nodes = sceneNodes(node)
        val found = LinkedHashMap<String, Int>()
        narrowest(node, nodes)?.let { (sel, n) -> found[sel] = n }
        for (s in own(node)) {
            if (s in found) continue
            val sels = parse(s) ?: continue
            found[s] = nodes.count { n -> sels.any { it.applies(n) } }
        }
        val out = Json.array()
        found.entries.filter { it.value > 0 }.sortedBy { it.value }.forEach { (s, n) -> out.addObject().put("selector", s).put("matches", n) }
        return out
    }

    /** The narrowest selector for [node], or null when it has nothing to name it by. */
    fun narrowest(node: Node): String? = narrowest(node, sceneNodes(node))?.first

    /** `{selector, valid, matches, matchesTarget}` for a selector the developer typed; `error` when it does not parse. */
    fun describe(text: String, node: Node): ObjectNode {
        val out = Json.obj().put("selector", text)
        val sels = parse(text) ?: return out.put("valid", false).put("error", "not a selector JavaFX can parse")
        val nodes = sceneNodes(node)
        return out.put("valid", true)
            .put("matches", nodes.count { n -> sels.any { it.applies(n) } })
            .put("matchesTarget", sels.any { it.applies(node) })
    }

    /**
     * Starts from the node's own part with the fewest matches, then prefixes ancestor parts (nearest first) while
     * each one narrows the match count, until only the node is left or [MAX_PARTS] parts are used.
     */
    private fun narrowest(node: Node, nodes: List<Node>): Pair<String, Int>? {
        var best: Pair<String, List<Node>>? = null
        for (s in own(node)) {
            val sels = parse(s) ?: continue
            val hit = nodes.filter { n -> sels.any { it.applies(n) } }
            if (best == null || hit.size < best.second.size) best = s to hit
        }
        val (start, base) = best ?: return null
        var chain = start
        var count = base.size
        var parts = 1
        var anc = node.parent
        while (anc != null && count > 1 && parts < MAX_PARTS) {
            var step: Pair<String, Int>? = null
            // the direct parent may also use `>`, which drops matches nested deeper; ties keep the plain descendant form
            val joins = if (anc === node.parent) listOf(" ", " > ") else listOf(" ")
            for (part in own(anc)) {
                for (join in joins) {
                    val sel = "$part$join$chain"
                    val sels = parse(sel)?.takeIf { s -> s.any { it.applies(node) } } ?: continue
                    val n = base.count { b -> sels.any { it.applies(b) } }
                    if (n in 1 until count && (step == null || n < step.second)) step = sel to n
                }
            }
            if (step != null) {
                chain = step.first
                count = step.second
                parts++
            }
            anc = anc.parent
        }
        return chain to count
    }

    /** Simple selectors naming [n] on their own: `#id`, each class (last added first), `.a.b`; the type when it has no class. */
    private fun own(n: Node): List<String> {
        val out = ArrayList<String>()
        n.id?.takeIf { IDENT.matches(it) }?.let { out += "#$it" }
        val classes = n.styleClass.filter { IDENT.matches(it) }.distinct()
        classes.asReversed().forEach { out += ".$it" }
        if (classes.size > 1) out += classes.joinToString("") { ".$it" }
        if (classes.isEmpty() && IDENT.matches(n.typeSelector)) out += n.typeSelector
        return out
    }

    private fun sceneNodes(node: Node): List<Node> {
        val root = node.scene?.root ?: generateSequence(node) { it.parent }.last()
        return if (root is Parent) Reloads.allNodes(root) else listOf(root)
    }

    /** The selector list of one rule, or null when [text] does not parse; the parser's errors are ours, not the app's. */
    fun parse(text: String): List<Selector>? {
        if (text.isBlank() || text.length > 500 || text.any { it == '{' || it == '}' || it == ';' }) return null
        val errors = CssParser.errorsProperty()
        val before = errors.size
        return try {
            CssParser().parse("$text { -fx-opacity: 1; }")?.rules?.singleOrNull()?.selectors
                ?.takeIf { it.isNotEmpty() && errors.size == before }
        } catch (e: RuntimeException) {
            null
        } finally {
            if (errors.size > before) errors.remove(before, errors.size)
        }
    }
}

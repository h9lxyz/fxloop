package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import javafx.scene.Node

/**
 * Stable structural addresses (PRD 02 §7). Grammar:
 * ```
 * path    := window ('/Scene')? (sep segment)*
 * window  := ('Stage' | 'Popup' | 'Window') '[' int ']'
 * sep     := '/' (child step) | '//' (descendant search)
 * segment := Type? ('#' fxId)? ('[' int ']')?      -- at least one of Type / #fxId
 * ```
 * The first segment after `Scene` matches the scene root. `Type[i]` is the i-th logical child of
 * that type; `#id` matches by id; `//seg` searches all logical descendants and must be unique
 * unless an index picks the i-th match.
 */
internal object NodePath {
    private val WINDOW = Regex("^(Stage|Popup|Window)\\[(\\d+)]")
    private val SEGMENT = Regex("^([A-Za-z_$][\\w$]*)?(?:#([^\\[/]+))?(?:\\[(\\d+)])?$")

    fun typeName(n: Node): String = typeName0(n)

    fun typeName0(n: Any): String {
        var c: Class<*> = n.javaClass
        while (c.simpleName.isEmpty()) c = c.superclass
        return c.simpleName
    }

    /** Path of `node`; skin-internal nodes are mapped to their owning logical node first. */
    fun of(node: Node): String? {
        val n = LogicalTree.owner(node)
        if (n == null || n.scene == null || n.scene.window == null) return null
        val segs = ArrayList<String>()
        var cur: Node? = n
        while (cur != null) {
            val parent = LogicalTree.parent(cur)
            segs.add(0, if (parent == null) rootSegment(cur) else childSegment(parent, cur))
            cur = parent
        }
        return Windows.segment(n.scene.window) + "/Scene/" + segs.joinToString("/")
    }

    private fun rootSegment(n: Node): String =
        if (!n.id.isNullOrEmpty()) typeName(n) + "#" + n.id else typeName(n)

    private fun childSegment(parent: Node, child: Node): String {
        val type = typeName(child)
        val id = child.id
        if (!id.isNullOrEmpty()) return "$type#$id"
        var i = 0
        for (sibling in LogicalTree.children(parent)) {
            if (sibling === child) break
            if (typeName(sibling) == type) i++
        }
        return "$type[$i]"
    }

    /** Segments for all `kids` of one parent in a single pass (O(n), used by bulk traversals). */
    fun childSegments(kids: List<Node>): List<String> {
        val counters = HashMap<String, Int>()
        val out = ArrayList<String>(kids.size)
        for (k in kids) {
            val type = typeName(k)
            val i = counters.getOrDefault(type, 0)
            counters[type] = i + 1
            val id = k.id
            out.add(if (!id.isNullOrEmpty()) "$type#$id" else "$type[$i]")
        }
        return out
    }

    fun resolve(path: String?): Node {
        if (path.isNullOrBlank()) throw FcpException(Fcp.Error.BAD_REQUEST, "empty path")
        val wm = WINDOW.find(path)
            ?: throw FcpException(Fcp.Error.BAD_REQUEST, "path must start with Stage[i], Popup[i] or Window[i]: $path")
        val kind = wm.groupValues[1]
        val index = wm.groupValues[2].toInt()
        val window = if (kind == "Window") Windows.byIndex(index) else Windows.bySegment(kind, index)
        var rest = path.substring(wm.range.last + 1)
        if (rest.startsWith("/Scene")) rest = rest.substring("/Scene".length)
        val root = window.scene.root
        val steps = parseSteps(rest, path)
        if (steps.isEmpty()) return root
        var current: List<Node> = emptyList()
        var first = true
        for (step in steps) {
            val pool: List<Node>
            if (first) {
                pool = if (step.descendant) descendantsInclusive(root) else listOf(root)
                first = false
            } else {
                if (current.size != 1) break
                pool = if (step.descendant) descendants(current[0]) else LogicalTree.children(current[0])
            }
            var matches = pool.filter { step.matches(it) }
            if (step.index != null) {
                matches = if (step.index < matches.size) listOf(matches[step.index]) else emptyList()
            }
            if (matches.isEmpty()) throw notFound(path, step)
            if (matches.size > 1) throw ambiguous(path, matches)
            current = matches
        }
        return current[0]
    }

    private fun notFound(path: String, step: Step) = FcpException(
        Fcp.Error.NODE_NOT_FOUND,
        "no node matches segment '${step.raw}' of $path — the UI may have changed; re-run fx_find_nodes or fx_get_scene_graph",
    )

    private fun ambiguous(path: String, matches: List<Node>): FcpException {
        val candidates = Json.array()
        for (n in matches) {
            if (candidates.size() >= 10) break
            candidates.add(of(n))
        }
        val data = Json.obj().setNode("candidates", candidates)
        return FcpException(
            Fcp.Error.AMBIGUOUS_PATH,
            "${matches.size} nodes match $path; add [i] or use a full path from candidates", data,
        )
    }

    fun descendants(n: Node): List<Node> {
        val out = ArrayList<Node>()
        for (c in LogicalTree.children(n)) out.addAll(descendantsInclusive(c))
        return out
    }

    fun descendantsInclusive(n: Node): List<Node> {
        val out = ArrayList<Node>()
        out.add(n)
        out.addAll(descendants(n))
        return out
    }

    private fun parseSteps(rest: String, full: String): List<Step> {
        val steps = ArrayList<Step>()
        var i = 0
        while (i < rest.length) {
            if (rest[i] != '/') {
                throw FcpException(Fcp.Error.BAD_REQUEST, "malformed path near '${rest.substring(i)}' in $full")
            }
            val desc = rest.startsWith("//", i)
            i += if (desc) 2 else 1
            var end = i
            var depth = 0
            while (end < rest.length && (rest[end] != '/' || depth > 0)) {
                if (rest[end] == '[') depth++
                if (rest[end] == ']') depth--
                end++
            }
            val raw = rest.substring(i, end)
            val m = SEGMENT.matchEntire(raw)
            if (raw.isEmpty() || m == null || m.groups[1] == null && m.groups[2] == null) {
                throw FcpException(Fcp.Error.BAD_REQUEST, "malformed segment '$raw' in $full")
            }
            steps.add(Step(raw, desc, m.groups[1]?.value, m.groups[2]?.value, m.groups[3]?.value?.toInt()))
            i = end
        }
        return steps
    }

    private class Step(val raw: String, val descendant: Boolean, val type: String?, val id: String?, val index: Int?) {
        fun matches(n: Node): Boolean {
            if (id != null && id != n.id) return false
            if (type != null && !(if (descendant) typeMatches(n.javaClass, type) else typeName(n) == type)) return false
            return true
        }
    }

    private fun typeMatches(c: Class<*>, type: String): Boolean {
        var k: Class<*>? = c
        while (k != null) {
            if (k.simpleName == type) return true
            k = k.superclass
        }
        return false
    }
}

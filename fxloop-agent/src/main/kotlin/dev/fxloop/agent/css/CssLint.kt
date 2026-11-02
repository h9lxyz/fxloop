package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.AgentRuntime
import dev.fxloop.agent.Fx
import dev.fxloop.agent.NodePath
import dev.fxloop.agent.Windows
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import javafx.css.Rule
import javafx.css.Selector
import javafx.css.StyleOrigin
import javafx.scene.Node
import javafx.scene.Parent
import javafx.stage.Window

/** Stylesheet view and CSS lint (PRD 09 §7). FX thread via [Fx.call]. */
internal object CssLint {
    private val ERROR_LINE = Regex("""\[(\d+),\s*\d+]|\bline[:\s]+(\d+)""", RegexOption.IGNORE_CASE)

    /** Every node of the scene including skin nodes (which the logical tree leaves out), root first. */
    private fun nodes(root: Node): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) {
            out += n
            if (n is Parent) n.childrenUnmodifiable.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    /** Sheets installed on [w]: user agent first, then the scene's, then Parents' (outermost first); one per url+on. */
    private fun installed(w: Window, all: List<Node>): List<SheetRef> {
        val scene = w.scene
        val out = ArrayList<SheetRef>()
        val seen = HashSet<String>()
        fun add(ref: SheetRef) {
            if (seen.add(ref.url + "\u0000" + ref.on)) out += SheetRef("s${out.size}", ref.parsed, ref.origin, ref.on, out.size, ref.scratch)
        }
        Sheets.forNode(scene.root).filter { it.origin == StyleOrigin.USER_AGENT }.forEach { add(it) }
        fun install(url: String?, on: String) {
            if (url.isNullOrBlank()) return
            val resolved = Sheets.resolveUrl(url) ?: return
            val scratch = Scratch.isScratch(url)
            add(SheetRef("", Sheets.load(resolved), StyleOrigin.AUTHOR, if (scratch) "Scratch CSS" else on, 0, scratch))
        }
        scene.stylesheets.forEach { install(it, "Scene") }
        for (n in all) if (n is Parent) n.stylesheets.forEach { install(it, NodePath.of(n) ?: n.javaClass.simpleName) }
        return out
    }

    private fun errors(ref: SheetRef) = Json.array().also { arr ->
        ref.parsed.parseErrors.forEach { msg ->
            val o = arr.addObject()
            ERROR_LINE.find(msg)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()?.let { o.put("line", it) } }
            o.put("message", msg)
        }
    }

    private fun sheetObject(ref: SheetRef, roots: SourceRoots): ObjectNode {
        val d = Sheets.describe(ref, roots)
        val rules = ref.parsed.stylesheet?.rules ?: emptyList()
        val o = Json.obj()
        o.put("id", ref.id).put("url", ref.url).put("file", d.path("file").asText()).put("origin", ref.origin.name).put("on", ref.on)
        o.put("rules", rules.size).put("declarations", rules.sumOf { it.declarations.size })
        o.set<com.fasterxml.jackson.databind.JsonNode>("errors", errors(ref))
        if (d.path("external").asBoolean()) o.put("external", true)
        d.path("mapped").path("file").takeIf { !it.isMissingNode && !it.isNull }?.let { o.putObject("mapped").put("file", it.asText()) }
        return o
    }

    private fun selectorText(sel: Selector) = Sheets.key(sel.toString())

    private fun applies(sel: Selector, n: Node) = try {
        sel.applies(n)
    } catch (e: RuntimeException) {
        false
    }

    fun lint(p: ObjectNode): ObjectNode = Fx.call {
        val roots = SourceRoots.from(p)
        val windows = if (p.hasNonNull("windowIndex")) listOf(Windows.byIndex(p.get("windowIndex").asInt())) else Windows.showing()
        val perWindow = windows.map { it to nodes(it.scene.root) }
        val nodes = perWindow.flatMap { it.second }
        // unknown = a style class or id that no node in any showing window has
        val classes = HashSet<String>()
        val ids = HashSet<String>()
        for (w in Windows.showing()) for (n in nodes(w.scene.root)) {
            classes.addAll(n.styleClass)
            n.id?.takeIf { it.isNotEmpty() }?.let { ids += it }
        }
        val out = Json.obj()
        out.put("windows", windows.size).put("nodes", nodes.size)
        val sheetsOut = out.putArray("sheets")
        val unused = out.putArray("unused")
        val unknown = out.putArray("unknownSelectors")
        val seenSheets = HashSet<String>()
        val linted = HashSet<String>()
        for ((w, all) in perWindow) {
            for (ref in installed(w, all)) {
                if (ref.origin == StyleOrigin.USER_AGENT || ref.scratch) continue
                if (!seenSheets.add(ref.url + "\u0000" + ref.on)) continue
                sheetsOut.add(sheetObject(ref, roots))
                if (!linted.add(ref.url)) continue
                val file = roots.displayFile(ref.url, ref.parsed.absFile)
                for (rule in ref.parsed.stylesheet?.rules ?: emptyList()) lintRule(rule, ref, file, nodes, classes, ids, unused, unknown)
            }
        }
        out.put("note", "unused = matches no node in the open windows")
        out
    }

    private fun lintRule(
        rule: Rule, ref: SheetRef, file: String, nodes: List<Node>, classes: Set<String>, ids: Set<String>,
        unused: com.fasterxml.jackson.databind.node.ArrayNode, unknown: com.fasterxml.jackson.databind.node.ArrayNode,
    ) {
        val line = ref.parsed.sources[rule]?.line
        var anyUnknown = false
        var matched = false
        for (sel in rule.selectors) {
            val missing = ArrayList<String>()
            for (part in Selectors.parts(sel)) {
                part.styleClasses.filter { it !in classes }.forEach { if (".$it" !in missing) missing += ".$it" }
                part.id?.takeIf { it.isNotEmpty() && it !in ids }?.let { if ("#$it" !in missing) missing += "#$it" }
            }
            if (missing.isNotEmpty()) {
                anyUnknown = true
                val o = unknown.addObject()
                o.put("file", file)
                line?.let { o.put("line", it) }
                o.put("selector", selectorText(sel))
                o.set<com.fasterxml.jackson.databind.JsonNode>("unknown", Json.strings(missing))
            } else if (!matched && nodes.any { applies(sel, it) }) {
                matched = true
            }
        }
        if (!matched && !anyUnknown && rule.selectors.isNotEmpty()) {
            val o = unused.addObject().put("file", file)
            line?.let { o.put("line", it) }
            o.put("selector", rule.selectors.joinToString(", ") { selectorText(it) })
        }
    }

    fun sheets(p: ObjectNode): ObjectNode = Fx.call {
        val roots = SourceRoots.from(p)
        val index = if (p.hasNonNull("windowIndex")) p.get("windowIndex").asInt() else 0
        val w = Windows.byIndex(index)
        val all = nodes(w.scene.root)
        val refs = installed(w, all)
        val out = Json.obj()
        out.put("window", Windows.indexOf(w))
        val want = p.path("sheet").asText(null)?.takeIf { it.isNotEmpty() }
        if (want == null) {
            val arr = out.putArray("sheets")
            refs.forEach { arr.add(sheetObject(it, roots)) }
            return@call out
        }
        val ref = refs.firstOrNull { it.url == want } ?: refs.firstOrNull { it.id == want }
            ?: throw FcpException(Fcp.Error.BAD_REQUEST, "no sheet $want in window $index; call cssSheets without 'sheet' to list them")
        out.setAll<ObjectNode>(sheetObject(ref, roots))
        val handles = AgentRuntime.current().handles
        val list = out.putArray("ruleList")
        (ref.parsed.stylesheet?.rules ?: emptyList()).forEachIndexed { i, rule ->
            val hit = all.filter { n -> rule.selectors.any { applies(it, n) } }
            val o = list.addObject().put("index", i)
            o.put("selector", rule.selectors.joinToString(", ") { selectorText(it) })
            ref.parsed.sources[rule]?.line?.let { o.put("line", it) }
            o.put("declarations", rule.declarations.size).put("matches", hit.size)
            val hs = o.putArray("handles")
            hit.take(50).forEach { hs.add(handles.handleOf(it)) }
        }
        out
    }
}

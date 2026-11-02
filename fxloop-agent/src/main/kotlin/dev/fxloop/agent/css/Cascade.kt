package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.NodePath
import dev.fxloop.protocol.Json
import java.util.IdentityHashMap
import javafx.css.CssMetaData
import javafx.css.CssParser
import javafx.css.Declaration
import javafx.css.ParsedValue
import javafx.css.PseudoClass
import javafx.css.Rule
import javafx.css.Selector
import javafx.css.StyleOrigin
import javafx.css.Styleable
import javafx.css.StyleableProperty
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Labeled
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.util.Duration

/** A rule that matches the node, with the selector that matched best (PRD 08 §3.1 step 2). */
internal class MatchedRule(
    val ruleId: String,
    val rule: Rule,
    val selector: Selector?,
    val sheet: SheetRef?,
    val origin: StyleOrigin,
    val specificity: Int,
    val spec: String,
    val ordinal: Long,
    val selectorText: String,
    val inlineSource: RuleSource? = null,
)

/** One declaration competing for a property. `matched == null` is the "set in code" pseudo-candidate. */
internal class Candidate(
    val property: String,
    val decl: Declaration?,
    val matched: MatchedRule?,
    val origin: StyleOrigin,
    val index: Int = 0,
) {
    val important: Boolean get() = decl?.isImportant == true
    val specificity: Int get() = matched?.specificity ?: -1
    val ordinal: Long get() = matched?.ordinal ?: -1
    val ruleId: String get() = matched?.ruleId ?: "code"
}

/**
 * Reconstructs the JavaFX cascade for one node from public `javafx.css` API (PRD 08 §3.1). FX thread only.
 * [Engine] shares per-node results within one request (ancestors for inheritance and lookups).
 */
internal class Cascade(val node: Node, private val engine: Engine) {
    val sheets: List<SheetRef> = Sheets.forNode(node)
    val rules: List<MatchedRule> = match()

    private fun match(): List<MatchedRule> {
        val out = ArrayList<MatchedRule>()
        val states = node.pseudoClassStates
        for (ref in sheets) {
            val sheet = ref.parsed.stylesheet ?: continue
            sheet.rules.forEachIndexed { i, rule ->
                var best: Selector? = null
                var bestSpec = -1
                for (sel in rule.selectors) {
                    val ok = try {
                        sel.applies(node) && sel.stateMatches(node, states)
                    } catch (e: RuntimeException) {
                        false
                    }
                    if (!ok) continue
                    val s = sel.createMatch().specificity
                    if (s > bestSpec) {
                        best = sel
                        bestSpec = s
                    }
                }
                if (best != null) {
                    out += MatchedRule("${ref.id}:r$i", rule, best, ref, ref.origin, bestSpec, Specificity.of(best),
                        ref.order * 1_000_000L + i, Sheets.key(best.toString()))
                }
            }
        }
        val style = node.style
        if (!style.isNullOrBlank()) {
            val inline = try {
                CssParser().parseInlineStyle(node)
            } catch (e: RuntimeException) {
                null
            }
            val src = CssSource.scan("* { $style }").rules.firstOrNull()?.let { RuleSource(1, it.declarations) }
            inline?.rules?.forEach { r ->
                out += MatchedRule("inline", r, null, null, StyleOrigin.INLINE, 0, "inline", INLINE_ORDINAL, "style=\"${style.trim()}\"", src)
            }
        }
        return out
    }

    /** Declarations for [property] (and its [shorthand], if any) in precedence order, winner first. */
    fun candidates(property: String, shorthand: String? = null): List<Candidate> {
        val list = ArrayList<Candidate>()
        for (m in rules) {
            m.rule.declarations.forEachIndexed { i, d ->
                val name = d.property
                if (name.equals(property, ignoreCase = true) || shorthand != null && name.equals(shorthand, ignoreCase = true)) {
                    list += Candidate(name, d, m, m.origin, i)
                }
            }
        }
        return list.sortedWith(PRECEDENCE)
    }

    fun ancestorCascade(n: Node): Cascade = engine.cascade(n)

    companion object {
        const val INLINE_ORDINAL = Long.MAX_VALUE / 2

        /** important > origin (UA < code < author < inline) > specificity > source order. */
        val PRECEDENCE: Comparator<Candidate> = compareByDescending<Candidate> { it.important }
            .thenByDescending { it.origin.ordinal }
            .thenByDescending { it.specificity }
            .thenByDescending { it.ordinal }
            .thenByDescending { it.index }
    }
}

/** Per-request cache of node cascades, plus the source roots for `file` mapping. */
internal class Engine(val roots: SourceRoots) {
    private val cache = IdentityHashMap<Node, Cascade>()

    fun cascade(n: Node): Cascade = cache.getOrPut(n) { Cascade(n, this) }

    fun decl(c: Candidate, forProperty: String? = null): ObjectNode {
        val o = Json.obj()
        val m = c.matched
        o.put("ruleId", c.ruleId)
        if (m == null) {
            o.put("selector", "(set in code)").put("origin", "USER").put("text", "value set from code")
            return o
        }
        o.put("selector", m.selectorText)
        o.put("specificity", m.spec)
        o.put("origin", m.origin.name)
        if (forProperty != null && !c.property.equals(forProperty, ignoreCase = true)) o.put("property", c.property)
        val ref = m.sheet
        if (ref != null && ref.scratch) {
            o.put("file", Sheets.scratchLabel(ref, m.rule)).put("scratch", true)
        } else if (ref != null) {
            o.put("sheet", ref.url)
            o.put("file", roots.displayFile(ref.url, ref.parsed.absFile))
            roots.editFile(ref.url, ref.parsed.absFile)?.let { o.put("absFile", it.toString()) }
            val src = ref.parsed.sources[m.rule]
            if (src != null) o.put("line", lineOf(c, src))
        }
        o.put("text", textOf(c, m))
        o.put("important", c.important)
        return o
    }

    private fun sourceDecl(c: Candidate, m: MatchedRule): ScannedDecl? {
        val src = m.inlineSource ?: m.sheet?.parsed?.sources?.get(m.rule) ?: return null
        val decls = m.rule.declarations
        val idx = decls.indexOf(c.decl)
        val same = src.declarations.filter { it.property.equals(c.property, ignoreCase = true) }
        // pair by occurrence of this property within the rule (robust to declarations the parser dropped)
        val nth = decls.subList(0, maxOf(0, idx)).count { it.property.equals(c.property, ignoreCase = true) }
        return same.getOrNull(nth) ?: same.lastOrNull()
    }

    private fun lineOf(c: Candidate, src: RuleSource): Int = sourceDecl(c, c.matched!!)?.line ?: src.line

    fun textOf(c: Candidate, m: MatchedRule): String =
        sourceDecl(c, m)?.text ?: "${c.property}: (source unavailable);"

    fun location(c: Candidate): String {
        val m = c.matched ?: return "code"
        if (m.ruleId == "inline") return "inline style"
        val ref = m.sheet ?: return m.selectorText
        if (ref.scratch) return Sheets.scratchLabel(ref, m.rule)
        val file = Sheets.shortName(ref.url)
        val line = ref.parsed.sources[m.rule]?.let { lineOf(c, it) }
        return if (line != null) "$file:$line" else file
    }

    /** Why [loser] loses to [winner], in one line. */
    fun reason(loser: Candidate, winner: Candidate, property: String): String {
        val base = when {
            winner.important && !loser.important -> "overridden by !important at ${location(winner)}"
            winner.origin != loser.origin -> when (loser.origin) {
                StyleOrigin.USER_AGENT -> "user agent stylesheet (loses to ${originLabel(winner.origin)})"
                else -> "${originLabel(loser.origin)} loses to ${originLabel(winner.origin)} (${location(winner)})"
            }
            winner.specificity != loser.specificity ->
                "lower specificity (${loser.matched?.spec} < ${winner.matched?.spec})"
            else -> "earlier in cascade order (${location(loser)} before ${location(winner)})"
        }
        return if (!loser.property.equals(property, ignoreCase = true)) "$base; shorthand ${loser.property}" else base
    }

    companion object {
        fun originLabel(o: StyleOrigin?): String = when (o) {
            StyleOrigin.USER_AGENT -> "user agent"
            StyleOrigin.USER -> "set in code"
            StyleOrigin.AUTHOR -> "author stylesheet"
            StyleOrigin.INLINE -> "inline style"
            null -> "default"
        }
    }
}

/** Specificity as `"ids,classes,types"` (pseudo-classes count as classes, like CSS). */
internal object Specificity {
    fun of(sel: Selector): String {
        var ids = 0
        var classes = 0
        var types = 0
        for (s in Selectors.parts(sel)) {
            if (!s.id.isNullOrEmpty()) ids++
            classes += s.styleClasses.size + s.pseudoClasses.size
            if (s.name.isNotEmpty() && s.name != "*") types++
        }
        return "$ids,$classes,$types"
    }
}

/** One styleable property of a node: its cascade, inheritance, verification and blockers. */
internal class PropertyView(
    val cssProperty: String,
    val meta: CssMetaData<out Styleable, *>,
    val shorthand: String?,
    val property: StyleableProperty<*>,
    val inherits: Boolean,
) {
    @Suppress("UNCHECKED_CAST")
    companion object {
        /** Top-level metas, plus sub-properties (`-fx-font-size`, `-fx-background-color`) as their own views. */
        fun all(node: Node): List<PropertyView> {
            val out = ArrayList<PropertyView>()
            for (meta in node.cssMetaData) {
                val sp = (meta as CssMetaData<Styleable, *>).getStyleableProperty(node) ?: continue
                out += PropertyView(meta.property, meta, null, sp, meta.isInherits)
                meta.subProperties?.forEach { sub -> out += PropertyView(sub.property, sub, meta.property, sp, meta.isInherits || sub.isInherits) }
            }
            return out
        }
    }
}

/** Builds the JSON views (explain items, full cascade, blockers) on top of [Cascade]. */
internal class Explainer(val node: Node, val engine: Engine) {
    val cascade = engine.cascade(node)

    class Result(val winner: Candidate?, val ordered: List<Candidate>, val inheritedFrom: Pair<Node, Candidate>?)

    fun resolve(v: PropertyView): Result {
        // the shorthand's own declarations compete for a sub-property (`-fx-font` vs `-fx-font-size`)
        val shorthand = v.shorthand?.takeIf { it == "-fx-font" }
        val own = cascade.candidates(v.cssProperty, shorthand).toMutableList()
        if (v.property.styleOrigin == StyleOrigin.USER) {
            own += Candidate(v.cssProperty, null, null, StyleOrigin.USER)
            own.sortWith(Cascade.PRECEDENCE)
        }
        if (own.isNotEmpty()) return Result(own.first(), own, null)
        if (v.inherits) {
            var cur = node.parent
            while (cur != null) {
                val anc = engine.cascade(cur).candidates(v.cssProperty, shorthand)
                if (anc.isNotEmpty()) return Result(null, emptyList(), cur to anc.first())
                cur = cur.parent
            }
        }
        return Result(null, emptyList(), null)
    }

    /**
     * Adds `winner`, `overridden[]`/`overriddenCount`, `inheritedFrom`, `simulation`, `blockers` to [o]. [nearMisses]
     * (default: [full]) adds rules for this property that do not match; the cascade leaves them out for properties
     * nothing sets, where every label would otherwise list every `.label` rule meant for some other context.
     */
    fun annotate(o: ObjectNode, v: PropertyView, full: Boolean, nearMisses: Boolean = full): Result {
        val r = resolve(v)
        val w = r.winner
        if (w != null) o.set<ObjectNode>("winner", engine.decl(w, v.cssProperty))
        val losers = r.ordered.drop(1)
        if (full) {
            val arr = o.putArray("overridden")
            for (l in losers) arr.add(engine.decl(l, v.cssProperty).put("reason", engine.reason(l, w!!, v.cssProperty)))
        } else {
            o.put("overriddenCount", losers.size)
        }
        r.inheritedFrom?.let { (anc, c) ->
            o.putObject("inheritedFrom").put("path", NodePath.of(anc)).set<ObjectNode>("winner", engine.decl(c, v.cssProperty))
        }
        verify(o, v, r)
        val effective = w ?: r.inheritedFrom?.second
        val lookups = effective?.let { lookups(it, r.inheritedFrom?.first ?: node) } ?: emptyList()
        if (lookups.isNotEmpty()) {
            val arr = o.putArray("lookups")
            lookups.forEach { arr.add(it.second) }
        }
        val blockers = blockers(v, r, nearMisses, lookups)
        if (blockers.size() > 0 || full) o.set<ArrayNode>("blockers", blockers)
        return r
    }

    /** Principle 1: the engine explains, the live value is the truth. */
    private fun verify(o: ObjectNode, v: PropertyView, r: Result) {
        val live = v.property.styleOrigin
        val p = v.property
        if (p is javafx.beans.property.Property<*> && p.isBound) {
            // a bound value never comes from CSS; the `bound` blocker explains it, there is nothing to verify
            o.put("simulation", "skipped").put("simulationNote", "property is bound in code")
            return
        }
        var sim = r.winner?.origin ?: r.inheritedFrom?.second?.origin
        if (v.shorthand != null) {
            // sub-properties share the composite's origin: one sub set by an author rule makes the whole value AUTHOR
            val ok = sim == null || live != null && sim.ordinal <= live.ordinal
            o.put("simulation", if (ok) "ok" else "mismatch")
            if (!ok) o.putObject("simulated").put("origin", sim!!.name).put("liveOrigin", live?.name ?: "NONE")
            return
        }
        val subs = v.meta.subProperties
        if (sim == null && !subs.isNullOrEmpty()) {
            sim = subs.mapNotNull { sub -> resolve(PropertyView(sub.property, sub, v.cssProperty, v.property, v.inherits)).let { it.winner ?: it.inheritedFrom?.second }?.origin }
                .maxByOrNull { it.ordinal }
        }
        if (live != sim) {
            o.put("simulation", "mismatch")
            o.putObject("simulated").put("origin", sim?.name ?: "NONE").put("liveOrigin", live?.name ?: "NONE")
                .put("note", mismatchNote(live, sim))
            return
        }
        val decl = r.winner?.decl
        if (decl != null && v.shorthand == null && !decl.parsedValue.isContainsLookups) {
            val sv = convert(decl.parsedValue)
            val lv = v.property.value
            if (sv != null && lv != null && comparable(lv) && !same(sv, lv)) {
                o.put("simulation", "mismatch")
                o.putObject("simulated").put("origin", sim?.name ?: "NONE").put("value", sv.toString()).put("liveValue", lv.toString())
                    .put("note", "the winning declaration converts to a different value than the live one")
                return
            }
        }
        o.put("simulation", "ok")
    }

    private fun mismatchNote(live: StyleOrigin?, sim: StyleOrigin?): String = when {
        live == StyleOrigin.USER -> "the live value was set in code after CSS applied; the matching rule takes effect on the next CSS pass"
        sim == null -> "no matching rule found for a CSS-set value (popup/owner-window sheets, SubScene or platform sheets are approximated)"
        else -> "the reconstructed cascade disagrees with the live origin"
    }

    private fun convert(pv: ParsedValue<*, *>): Any? = try {
        @Suppress("UNCHECKED_CAST")
        (pv as ParsedValue<Any?, Any?>).convert(fontOf(node))
    } catch (e: RuntimeException) {
        null
    }

    private fun comparable(v: Any): Boolean =
        v is Paint || v is Boolean || v is Enum<*> || v is String || v is Number || v is Insets || v is Duration

    private fun same(a: Any, b: Any): Boolean = when {
        a is Number && b is Number -> Math.abs(a.toDouble() - b.toDouble()) < 0.01
        a is Color && b is Color -> a == b
        a.javaClass != b.javaClass -> true // converter returned a related type; origin already agreed
        else -> a == b
    }

    /** Looked-up values used by [c] and where each is defined (`-accent` on `.root`). */
    fun lookups(c: Candidate, from: Node): List<Pair<String, ObjectNode>> {
        val decl = c.decl ?: return emptyList()
        if (!decl.parsedValue.isContainsLookups) return emptyList()
        val text = c.matched?.let { engine.textOf(c, it) } ?: return emptyList()
        val value = text.substringAfter(':').removeSuffix(";")
        val out = ArrayList<Pair<String, ObjectNode>>()
        for (name in LOOKUP.findAll(value).map { it.groupValues[1] }.distinct()) {
            val o = Json.obj().put("name", name)
            var cur: Node? = from
            var found = false
            while (cur != null && !found) {
                val def = engine.cascade(cur).candidates(name).firstOrNull()
                if (def != null) {
                    o.put("definedOn", NodePath.of(cur)).set<ObjectNode>("definedAt", engine.decl(def))
                    found = true
                }
                cur = cur.parent
            }
            o.put("resolved", found)
            out += name to o
        }
        return out
    }

    private fun blockers(v: PropertyView, r: Result, nearMisses: Boolean, lookups: List<Pair<String, ObjectNode>>): ArrayNode {
        val arr = Json.array()
        fun add(kind: String, cause: String, next: String) = arr.addObject().put("kind", kind).put("cause", cause).put("next", next)
        val p = v.property
        if (p is javafx.beans.property.Property<*> && p.isBound) {
            add("bound", "${p.name} is bound in code; CSS cannot change a bound property",
                "remove the binding or style the binding's source (fx_explain_property shows the dependency tree)")
        }
        val w = r.winner
        val losers = r.ordered.drop(1)
        if (w != null && w.origin == StyleOrigin.INLINE && losers.any { it.origin == StyleOrigin.AUTHOR }) {
            add("inline-wins", "inline style (setStyle) beats every stylesheet rule: ${engine.location(w)}",
                "add !important to the stylesheet declaration, or remove the setStyle call (find it with fx_explain_property)")
        }
        if (w != null && w.origin == StyleOrigin.AUTHOR && !w.important) {
            val weaker = losers.firstOrNull { it.origin == StyleOrigin.AUTHOR && !it.important && it.specificity < w.specificity }
            if (weaker != null) {
                add("specificity", "a more specific rule wins: ${w.matched?.selectorText} (${w.matched?.spec}) at ${engine.location(w)} " +
                    "beats ${weaker.matched?.selectorText} (${weaker.matched?.spec})", "fx_suggest_override proposes a selector that wins")
            }
        }
        if (w != null && w.important && losers.any { !it.important && it.origin.ordinal >= StyleOrigin.AUTHOR.ordinal }) {
            add("important", "an !important declaration wins: ${engine.location(w)}", "match it with !important and higher specificity, or remove it")
        }
        for ((name, o) in lookups) {
            if (!o.path("resolved").asBoolean()) {
                add("lookup-unresolved", "looked-up value $name is not defined on this node or any ancestor",
                    "define it on .root or an ancestor, e.g. .root { $name: #3b82f6; }")
            }
        }
        if (nearMisses) nearMisses(v.cssProperty).forEach { arr.add(it) }
        return arr
    }

    /** Rules that declare [property] but do not match, with the first failing selector part (§3.3 "No rule matched"). */
    fun nearMisses(property: String, limit: Int = 5): List<ObjectNode> {
        val out = ArrayList<ObjectNode>()
        val matched = cascade.rules.map { it.rule }.toHashSet()
        for (ref in cascade.sheets) {
            if (ref.origin != StyleOrigin.AUTHOR) continue
            val sheet = ref.parsed.stylesheet ?: continue
            for (rule in sheet.rules) {
                if (rule in matched || rule.declarations.none { it.property.equals(property, ignoreCase = true) }) continue
                for (sel in rule.selectors) {
                    val why = Selectors.failure(sel, node) ?: continue
                    if (!Selectors.related(sel, node)) continue
                    val line = ref.parsed.sources[rule]?.line
                    val where = if (ref.scratch) Sheets.scratchLabel(ref, rule) else Sheets.shortName(ref.url) + (line?.let { ":$it" } ?: "")
                    out += Json.obj().put("kind", "no-match")
                        .put("cause", "rule ${Sheets.key(sel.toString())} ($where) does not match: $why")
                        .put("next", "fix the selector or add the missing class/id (node: ${Selectors.describe(node)})")
                    break
                }
                if (out.size >= limit) return out
            }
        }
        return out
    }

    companion object {
        /** `-accent`, `-fx-base` inside a value; not numbers like `-1px`, not functions like `derive(`. */
        val LOOKUP = Regex("(?<![\\w-])(-[a-zA-Z][\\w-]*)(?![\\w(-])")

        fun fontOf(n: Node): Font = when (n) {
            is Labeled -> n.font
            is Text -> n.font
            else -> Font.getDefault()
        }
    }
}

/** Selector diagnostics for blockers and suggestions. */
internal object Selectors {
    /**
     * One simple selector of a (possibly compound) selector. JavaFX 25 moved `SimpleSelector` and
     * `CompoundSelector` out of the public API, so the parts are read back from `Selector.toString()`,
     * whose shape (`Type.class#id:pseudo`, joined by `" "` or `">"`) is the same in JavaFX 21 to 27.
     */
    class Part(val text: String, val name: String, val id: String?, val styleClasses: List<String>, val pseudoClasses: List<PseudoClass>) {
        override fun toString() = text
    }

    private val SEGMENT = Regex("([.#:])([^.#:]+)")

    fun parts(sel: Selector): List<Part> =
        sel.toString().split(' ', '>').filter { it.isNotEmpty() }.map { part(it) }

    fun part(text: String): Part {
        val name = text.takeWhile { it != '.' && it != '#' && it != ':' }
        var id: String? = null
        val classes = ArrayList<String>()
        val pseudo = ArrayList<PseudoClass>()
        for (m in SEGMENT.findAll(text, name.length)) {
            val v = m.groupValues[2]
            when (m.groupValues[1]) {
                "#" -> id = v
                "." -> classes += v
                else -> pseudo += PseudoClass.getPseudoClass(v)
            }
        }
        return Part(text, name, id, classes, pseudo)
    }

    /** First reason [sel] does not match [n] (rightmost part first), or `null` when it matches. */
    fun failure(sel: Selector, n: Node): String? {
        val last = parts(sel).last()
        if (last.name.isNotEmpty() && last.name != "*" && last.name != n.typeSelector) {
            return "type selector ${last.name} but the node is ${n.typeSelector}"
        }
        if (!last.id.isNullOrEmpty() && last.id != n.id) return "missing id #${last.id}" + (n.id?.let { " (node id is #$it)" } ?: "")
        last.styleClasses.firstOrNull { it !in n.styleClass }?.let { return "missing style class '$it'" }
        val pseudo = last.pseudoClasses
        pseudo.firstOrNull { it !in n.pseudoClassStates }?.let { return "pseudo-class :${it.pseudoClassName} is not active" }
        if (sel.applies(n) && sel.stateMatches(n, n.pseudoClassStates)) return null
        val ancestors = parts(sel).dropLast(1).joinToString(" ") { Sheets.key(it.toString()) }
        return "no ancestor matches '$ancestors'"
    }

    /** The rule is about this node (shares its id, a style class or its type) — keeps near-misses relevant. */
    fun related(sel: Selector, n: Node): Boolean {
        val last = parts(sel).last()
        return !last.id.isNullOrEmpty() && last.id == n.id ||
            last.styleClasses.any { it in n.styleClass } ||
            last.name == n.typeSelector
    }

    fun describe(n: Node): String =
        n.typeSelector + (n.id?.let { "#$it" } ?: "") + n.styleClass.joinToString("") { ".$it" }

}

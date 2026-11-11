package org.lab59.fxloop.agent.css

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.agent.AgentRuntime
import org.lab59.fxloop.agent.Fx
import org.lab59.fxloop.agent.ForcedStates
import org.lab59.fxloop.agent.NodeInfo
import org.lab59.fxloop.agent.NodePath
import org.lab59.fxloop.agent.Targets
import org.lab59.fxloop.agent.setNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.protocol.Paging
import java.util.IdentityHashMap
import javafx.css.CssParser
import javafx.css.Declaration
import javafx.css.PseudoClass
import javafx.css.StyleOrigin
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.layout.Region
import javafx.scene.text.Font

/** FCP entry points of the CSS inspector (PRD 08): rule-level explain, full cascade, override suggestions. */
internal object CssInspect {
    /** `explainStyle` with `rules:true`: today's fields plus winner/overridden/blockers per property. */
    fun explainStyle(p: ObjectNode, legacy: (Node, ObjectNode) -> Unit): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val only = if (p.hasNonNull("property")) p.get("property").asText() else null
        val sim = p.path("states").map { PseudoClass.getPseudoClass(it.asText().removePrefix(":")) }.toSet()
        val ex = Explainer(node, Engine(SourceRoots.from(p), node, sim))
        val items = ArrayList<JsonNode>()
        val views = PropertyView.all(node)
        for (v in views) {
            if (only == null && (v.shorthand != null || v.property.styleOrigin == null)) continue
            if (only != null && v.cssProperty != only) continue
            val o = Json.obj()
            o.put("cssProperty", v.cssProperty)
            if (v.shorthand != null) o.put("of", v.shorthand)
            NodeInfo.putValue(o, "value", valueOf(node, v))
            o.put("origin", v.property.styleOrigin?.name ?: "NONE(default/code)")
            val r = ex.annotate(o, v, full = only != null)
            if (sim.isNotEmpty()) o.put("simulation", "assumed").remove("simulated")
            if (only != null && isDefault(v, r)) o.put("default", true).put("note", defaultNote(v))
            items.add(o)
        }
        val out = Paging.page(items, p.path("offset").asInt(0), minOf(200, p.path("limit").asInt(80)))
        legacy(node, out)
        if (sim.isNotEmpty()) out.setNode("simulatedStates", Json.strings(sim.map { it.pseudoClassName }))
        val blockers = sheetBlockers(ex)
        if (only != null && views.none { it.cssProperty == only }) skinHint(node, only)?.let { blockers.add(it) }
        if (only != null && items.isEmpty() && blockers.size() == 0) {
            ex.nearMisses(only).forEach { blockers.add(it) }
        }
        out.setNode("blockers", blockers)
        out
    }

    /** `cssCascade`: the full cascade for the browser inspector (contract "Cascade JSON"). */
    fun cascade(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val engine = Engine(SourceRoots.from(p))
        val ex = Explainer(node, engine)
        val out = Json.obj()
        out.setNode("target", Targets.summary(node))
        out.put("styleClass", node.styleClass.joinToString(" "))
        val forced = ForcedStates.forcedNames(node)
        val pcs = out.putArray("pseudoClasses")
        node.pseudoClassStates.forEach { pcs.addObject().put("name", it.pseudoClassName).put("forced", it.pseudoClassName in forced) }
        if (!node.style.isNullOrBlank()) out.put("inlineStyle", node.style)
        val props = out.putArray("properties")
        val winners = IdentityHashMap<Declaration, Boolean>()
        val results = HashMap<String, Pair<PropertyView, Explainer.Result>>()
        for (v in PropertyView.all(node)) {
            val r = ex.resolve(v)
            val o = Json.obj()
            o.put("cssProperty", v.cssProperty)
            if (v.shorthand != null) o.put("of", v.shorthand)
            NodeInfo.putValue(o, "value", valueOf(node, v))
            CssTypes.live(node, v)?.let { o.put("css", it) }
            o.put("origin", v.property.styleOrigin?.name ?: "NONE")
            val default = isDefault(v, r)
            if (default) o.put("default", true).put("note", defaultNote(v))
            ex.annotate(o, v, full = true, nearMisses = !default)
            props.add(o)
            r.winner?.decl?.let { winners[it] = true }
            if (!default) results[v.cssProperty.lowercase()] = v to r
        }
        val rules = out.putArray("rules")
        val ordered = ex.cascade.rules.sortedWith(
            compareByDescending<MatchedRule> { it.origin.ordinal }.thenByDescending { it.specificity }.thenByDescending { it.ordinal },
        )
        val allViews = PropertyView.all(node)
        val names = allViews.map { it.cssProperty.lowercase() }.toHashSet()
        for (m in ordered) {
            val ro = engine.decl(Candidate("", null, m, m.origin)).apply { remove("text"); remove("important") }
            val decls = ro.putArray("declarations")
            m.rule.declarations.forEachIndexed { i, d ->
                val c = Candidate(d.property, d, m, m.origin, i)
                val o = decls.addObject().put("property", d.property).put("text", engine.textOf(c, m)).put("important", d.isImportant)
                val view = results[d.property.lowercase()] ?: results.values.firstOrNull { it.first.shorthand?.equals(d.property, true) == true }
                when {
                    winners.containsKey(d) -> o.put("status", "winner")
                    d.property.lowercase() !in names -> o.put("status", "inactive")
                        .put("reason", "not a styleable property of ${node.typeSelector} (a looked-up value, or a property of another node type)")
                    view?.second?.winner != null -> o.put("status", "overridden")
                        .put("reason", engine.reason(c, view.second.winner!!, view.first.cssProperty))
                    else -> o.put("status", "overridden").put("reason", "superseded")
                }
            }
            rules.add(ro)
        }
        val sheets = out.putArray("sheets")
        ex.cascade.sheets.forEach { sheets.add(Sheets.describe(it, engine.roots)) }
        out.setNode("blockers", sheetBlockers(ex))
        out.setNode("types", CssTypes.of(node, allViews))
        out.setNode("scopes", Scopes.of(node))
        out
    }

    /** `cssSelector`: how many nodes of the target's scene a typed selector matches, and whether the target is one. */
    fun selectorMatches(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        Scopes.describe(p.path("selector").asText(""), node).setNode("target", Targets.summary(node))
    }

    /** No rule, ancestor or code sets it: the live value is the property's initial value (a font sub-property: the default font's). */
    private fun isDefault(v: PropertyView, r: Explainer.Result): Boolean =
        r.winner == null && r.inheritedFrom == null && (v.property.styleOrigin == null || v.shorthand != null)

    private fun defaultNote(v: PropertyView): String {
        if (v.cssProperty == "-fx-font" || v.shorthand == "-fx-font") {
            val f = Font.getDefault()
            return "no stylesheet or code sets it: JavaFX uses the system default font (Font.getDefault(): " +
                "${CssTypes.format(f.size)}px \"${f.family}\")"
        }
        if (v.shorthand != null && v.property.styleOrigin != null) return "no rule sets it: initial value (${v.shorthand} is set without it)"
        return if (v.inherits) "no rule on this node or an ancestor sets it: initial value" else "no rule or code sets it: initial value"
    }

    /** `cssSuggest`: the smallest change that would win, verified against the engine (PRD 08 §3.5). */
    fun suggest(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val property = p.path("property").asText(null) ?: throw FcpException(Fcp.Error.BAD_REQUEST, "property is required")
        val engine = Engine(SourceRoots.from(p))
        val ex = Explainer(node, engine)
        val out = Json.obj()
        out.setNode("target", Targets.summary(node))
        out.put("property", property)
        val view = PropertyView.all(node).firstOrNull { it.cssProperty == property }
        if (view == null) {
            val b = Json.array()
            val hint = skinHint(node, property)
            if (hint != null) b.add(hint) else {
                b.addObject().put("kind", "not-styleable").put("cause", "${node.typeSelector} has no CSS property $property")
                    .put("next", "check the property name in the JavaFX CSS reference for ${node.typeSelector}")
            }
            hint?.path("selector")?.asText(null)?.let { sel ->
                out.put("selector", sel).put("snippet", "$sel { $property: ${p.path("value").asText("<value>")}; }")
                    .put("needsImportant", false).put("why", "the property lives on the skin node")
            } ?: out.putNull("selector")
            return@call out.setNode("blockers", b)
        }
        val probe = Json.obj()
        val r = ex.annotate(probe, view, full = true)
        val blockers = probe.get("blockers") as ArrayNode
        if (blockers.any { it.path("kind").asText() == "bound" }) {
            out.putNull("selector")
            return@call out.setNode("blockers", blockers)
        }
        val w = r.winner
        val value = p.path("value").asText(null) ?: w?.matched?.let { valueText(engine.textOf(w, it)) }
            ?: r.inheritedFrom?.let { (_, c) -> c.matched?.let { valueText(engine.textOf(c, it)) } } ?: "<value>"
        val file = p.path("file").asText(null) ?: defaultFile(ex)
        file?.let { out.put("file", it) } ?: out.put("fileNote", "no project stylesheet applies to this node; add one to the scene or a parent")
        if (p.hasNonNull("file") && ex.cascade.sheets.none { engine.roots.displayFile(it.url, it.parsed.absFile) == file }) {
            blockers.addObject().put("kind", "sheet-not-applied").put("cause", "$file is not in any stylesheet list on this node's path")
                .put("next", "add it to the Scene or a Parent's getStylesheets(), or pick another file")
        }
        val needsImportantUpFront = w != null && (w.origin == StyleOrigin.INLINE || w.important)
        for (important in listOf(needsImportantUpFront, true).distinct()) {
            for (sel in strategies(node, w)) {
                val hyp = hypothetical(node, sel, property, value, important, ex) ?: continue
                if (w == null || Cascade.PRECEDENCE.compare(hyp, w) < 0) {
                    out.put("selector", sel).put("needsImportant", important)
                    out.put("snippet", "$sel { $property: $value${if (important) " !important" else ""}; }")
                    out.put("why", why(engine, hyp, w))
                    out.put("verified", true)
                    return@call out.setNode("blockers", blockers)
                }
            }
        }
        out.putNull("selector").put("why", "no selector built from this node's id/classes beats ${w?.let(engine::location)}")
        out.setNode("blockers", blockers)
    }

    private fun why(engine: Engine, hyp: Candidate, w: Candidate?): String = when {
        w == null -> "no rule sets this property yet"
        w.origin == StyleOrigin.INLINE -> "only !important beats the inline style (${engine.location(w)})"
        w.important -> "beats !important at ${engine.location(w)} (${hyp.matched?.spec} > ${w.matched?.spec})"
        w.origin != StyleOrigin.AUTHOR -> "author rules beat ${Engine.originLabel(w.origin)} (${engine.location(w)})"
        else -> "beats ${engine.location(w)} (${hyp.matched?.spec} > ${w.matched?.spec})"
    }

    /**
     * Reuse the winner's selector + one own class, then `#fxId`, then the narrowest selector for this node (scoped by
     * its ancestors, so a Label without an id does not get every `.label`), then the node's own classes.
     */
    private fun strategies(node: Node, w: Candidate?): List<String> {
        val out = ArrayList<String>()
        val classes = node.styleClass.reversed()
        val base = w?.matched?.takeIf { w.origin == StyleOrigin.AUTHOR }?.selectorText
        if (base != null) {
            val last = base.substringAfterLast(' ').substringAfterLast('>')
            classes.filter { ".$it" !in last.split(':')[0].let { s -> Regex("\\.[\\w-]+").findAll(s).map { m -> m.value }.toList() } }
                .forEach { out += "$base.$it" }
            node.id?.takeIf { it.isNotEmpty() }?.let { out += "$base#$it" }
        }
        node.id?.takeIf { it.isNotEmpty() }?.let { out += "#$it" }
        Scopes.narrowest(node)?.let { out += it }
        classes.forEach { out += ".$it" }
        if (classes.size > 1) out += classes.reversed().joinToString("") { ".$it" }
        return out.distinct()
    }

    /** Parses a one-rule sheet appended as the last author sheet and returns its candidate, or null if it does not match. */
    private fun hypothetical(node: Node, sel: String, property: String, value: String, important: Boolean, ex: Explainer): Candidate? {
        val css = "$sel { $property: $value${if (important) " !important" else ""}; }"
        val errors = CssParser.errorsProperty()
        val before = errors.size
        val sheet = try {
            CssParser().parse(css)
        } catch (e: RuntimeException) {
            null
        } finally {
            if (errors.size > before) errors.remove(before, errors.size)
        }
        val rule = sheet?.rules?.firstOrNull() ?: return null
        val s = rule.selectors.firstOrNull { it.applies(node) && it.stateMatches(node, node.pseudoClassStates) } ?: return null
        val decl = rule.declarations.firstOrNull() ?: return null
        val m = MatchedRule("suggest", rule, s, null, StyleOrigin.AUTHOR, s.createMatch().specificity, Specificity.of(s),
            (ex.cascade.sheets.size + 1) * 1_000_000L, sel)
        return Candidate(property, decl, m, StyleOrigin.AUTHOR)
    }

    private fun valueText(text: String): String =
        text.substringAfter(':').trim().removeSuffix(";").trim().replace(Regex("\\s*!\\s*important$", RegexOption.IGNORE_CASE), "")

    /** The author stylesheet closest to the node that the project owns (never Modena or a dependency jar). */
    private fun defaultFile(ex: Explainer): String? {
        val roots = ex.engine.roots
        val owned = ex.cascade.sheets.asReversed()
            .filter { it.origin == StyleOrigin.AUTHOR && it.parsed.absFile != null && roots.owns(it.parsed.absFile) }
        // prefer a sheet that parses cleanly: a rule appended after a syntax error may be swallowed by it
        return (owned.firstOrNull { it.parsed.parseErrors.isEmpty() } ?: owned.firstOrNull())?.let { roots.projectFile(it.parsed.absFile) }
    }

    private fun sheetBlockers(ex: Explainer): ArrayNode {
        val arr = Json.array()
        for (s in ex.cascade.sheets) {
            if (s.parsed.parseErrors.isEmpty() && s.parsed.stylesheet != null) continue
            val file = ex.engine.roots.displayFile(s.url, s.parsed.absFile)
            arr.addObject().put("kind", "sheet-error")
                .put("cause", if (s.parsed.stylesheet == null) "$file could not be loaded: ${s.parsed.reason}"
                    else "$file has ${s.parsed.parseErrors.size} parse error(s): ${s.parsed.parseErrors.first()}")
                .put("next", "fix the reported error; JavaFX skips the broken declaration or rule")
        }
        return arr
    }

    /** `-fx-fill` on a Button lives on its `.text` skin node: point there (§3.3 "Style belongs on a skin node"). */
    private fun skinHint(node: Node, property: String): ObjectNode? {
        val queue = ArrayDeque<Pair<Node, Int>>()
        (node as? Parent)?.childrenUnmodifiable?.forEach { queue.add(it to 1) }
        while (queue.isNotEmpty()) {
            val (n, depth) = queue.removeFirst()
            if (n.cssMetaData.any { it.property == property }) {
                val own = node.id?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: node.styleClass.lastOrNull()?.let { ".$it" } ?: node.typeSelector
                val child = n.styleClass.lastOrNull()?.let { ".$it" } ?: n.typeSelector
                return Json.obj().put("kind", "skin-node")
                    .put("cause", "${node.typeSelector} has no $property; it is styleable on its skin node ${n.typeSelector}$child")
                    .put("next", "use a substructure selector: $own $child { $property: …; }")
                    .put("selector", "$own $child")
                    .setNode("skinNode", Json.obj().put("h", AgentRuntime.current().handles.handleOf(n)).put("type", n.typeSelector)
                        .put("styleClass", n.styleClass.joinToString(" ")))
            }
            if (depth < 4) (n as? Parent)?.childrenUnmodifiable?.forEach { queue.add(it to depth + 1) }
        }
        return null
    }

    /** Value of a (sub-)property: font parts and background/border colors are derived from the composite value. */
    private fun valueOf(node: Node, v: PropertyView): Any? {
        val value = v.property.value
        if (v.shorthand == null) return value
        return when {
            value is Font -> when (v.cssProperty) {
                "-fx-font-size" -> value.size
                "-fx-font-family" -> value.family
                "-fx-font-weight", "-fx-font-style" -> value.style
                else -> value.name
            }
            node is Region && v.cssProperty == "-fx-background-color" -> node.background?.fills?.joinToString(", ") { it.fill.toString() }
            node is Region && v.cssProperty == "-fx-border-color" -> node.border?.strokes?.joinToString(", ") { it.topStroke.toString() }
            else -> null
        }
    }

}

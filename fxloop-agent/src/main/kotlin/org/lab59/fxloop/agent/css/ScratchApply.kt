package org.lab59.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.agent.Fx
import org.lab59.fxloop.agent.Reloads
import org.lab59.fxloop.agent.Windows
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javafx.application.Application
import javafx.scene.Parent
import javafx.scene.layout.Region

/**
 * Inspector *Apply to file* (PRD 08 §5): turns the scratch rules into edits of the project's own stylesheets, and of
 * sheets outside the project (a library's) that the developer mapped to their source. Each scratch rule goes into the
 * source rule with the same selector (the last one that already sets one of its properties, else the last one),
 * looked up in the project's sheets first, then in the mapped ones; a selector none of them has is appended to the
 * scene's first project sheet. The developer may instead pick any of these sheets per rule (`targets`), so an override
 * can always go into one of the app's own stylesheets. Existing declarations keep their position and `!important`;
 * new ones go before the rule's closing brace.
 *
 * Only plans: the result carries the edited text and a hash of what was read, and the server writes the files.
 */
internal object ScratchApply {
    /**
     * A stylesheet scratch rules can be written into. [external]: outside the project, mapped by the developer, loaded
     * by the app from [sheet]. [live]: the running app shows an edit after a CSS reload; false for a sheet it loads from
     * a jar, which keeps the old copy until the library is rebuilt.
     */
    private class Sheet(
        val abs: Path,
        val file: String,
        val text: String,
        val rules: List<ScannedRule>,
        val external: Boolean = false,
        val sheet: String? = null,
        val live: Boolean = true,
    )

    /** A sheet outside the project nobody mapped yet: rules found only there can be saved there once it is. */
    private class Unmapped(val url: String, val file: String, val from: String?, val ruleKeys: Set<String>)

    /** What [appSheets] finds in the showing windows: (source file, display name) of project sheets, mapped and unmapped sheets. */
    private class Found(val project: List<Pair<Path, String>>, val mapped: List<Sheet>, val unmapped: List<Unmapped>)

    private class Edit(val at: Int, val remove: Int, val insert: String)

    private class FilePlan(val sheet: Sheet) {
        val edits = ArrayList<Edit>()
        val changes = Json.array()

        /** New rules for the end of the file, and the offset of each inside it (for its line number). */
        val appended = StringBuilder()
        val appendedAt = ArrayList<Pair<ObjectNode, Int>>()
    }

    /**
     * FCP `cssScratchPlan` (params carry the source roots and mappings, see [SourceRoots.from]; optional `targets`
     * `{"<n>": file}` picks the sheet for scratch rule n among its `options`).
     */
    fun plan(p: ObjectNode): ObjectNode {
        val roots = SourceRoots.from(p)
        val css = Scratch.text()
        if (!Scratch.active || css.isBlank()) bad("there is no scratch CSS to apply")
        val wanted = CssSource.scan(css).rules
        val found = Fx.call { appSheets(roots) }
        val project = found.project.mapNotNull { (abs, file) -> read(abs)?.let { Sheet(abs, file, it, CssSource.scan(it).rules) } }
        val mapped = found.mapped.mapNotNull { m -> read(m.abs)?.let { Sheet(m.abs, m.file, it, CssSource.scan(it).rules, true, m.sheet, m.live) } }
        val sheets = project + mapped
        if (sheets.isEmpty()) bad("no stylesheet of this project is loaded in the app; use Copy as CSS instead")
        val targets = p.path("targets")
        val plans = LinkedHashMap<Path, FilePlan>()
        fun planFor(s: Sheet) = plans.getOrPut(s.abs) { FilePlan(s) }
        val items = Json.array()
        val keep = StringBuilder()
        for ((i, r) in wanted.withIndex()) {
            val n = i + 1
            val key = Sheets.ruleKey(r)
            val props = r.declarations.map { it.property }.toSet()
            /** The rule of [s] to merge into: the last with this selector that already sets one of the properties, else the last. */
            fun ruleIn(s: Sheet): ScannedRule? {
                val hits = s.rules.filter { Sheets.ruleKey(it) == key && it.closeBrace >= 0 }
                return hits.lastOrNull { rule -> rule.declarations.any { it.property in props } } ?: hits.lastOrNull()
            }
            val chosen = targets.path(n.toString()).asText("").ifEmpty { null }
            val target = if (chosen != null) {
                sheets.firstOrNull { it.file == chosen } ?: bad("$chosen is not a stylesheet scratch rule $n can be written into")
            } else {
                fun best(group: List<Sheet>) = group.lastOrNull { s -> ruleIn(s)?.declarations?.any { it.property in props } == true }
                    ?: group.lastOrNull { ruleIn(it) != null }
                best(project) ?: best(mapped) ?: sheets.first()
            }
            val rule = ruleIn(target)
            if (rule == null) appendRule(planFor(target), r) else mergeInto(planFor(target), rule, r)
            if (!target.live) keep.append(ruleText(r)).append('\n')
            val item = items.addObject().put("n", n).put("selector", r.selectorText).put("file", target.file)
                .put("kind", if (rule == null) "append" else "merge")
            if (target.external) item.put("external", true)
            val options = item.putArray("options")
            for (s in sheets) {
                val o = options.addObject().put("file", s.file).put("kind", if (ruleIn(s) == null) "append" else "merge")
                if (s.external) o.put("external", true)
            }
            val elsewhere = found.unmapped.filter { key in it.ruleKeys }
            if (elsewhere.isNotEmpty()) {
                val arr = item.putArray("unmapped")
                elsewhere.forEach { u -> arr.addObject().put("file", u.file).put("url", u.url).also { o -> u.from?.let { o.put("from", it) } } }
            }
        }
        return Json.obj {
            put("rules", wanted.size)
            set<com.fasterxml.jackson.databind.JsonNode>("items", items)
            if (keep.isNotEmpty()) put("keep", keep.toString())
            val out = putArray("files")
            for (fp in plans.values) {
                if (fp.appended.isNotEmpty()) fp.edits += Edit(fp.sheet.text.length, 0, fp.appended.toString())
                if (fp.edits.isEmpty()) continue
                val edited = applyEdits(fp.sheet.text, fp.edits)
                val base = edited.length - fp.appended.length
                for ((change, at) in fp.appendedAt) change.put("line", edited.substring(0, base + at).count { it == '\n' } + 1)
                val f = out.addObject()
                f.put("file", fp.sheet.file)
                f.put("absFile", fp.sheet.abs.toString())
                f.put("sha256", sha256(fp.sheet.text))
                if (fp.sheet.external) {
                    f.put("external", true)
                    fp.sheet.sheet?.let { f.put("sheet", it); Sheets.containerOf(it)?.let { c -> f.put("from", c) } }
                    f.put("live", fp.sheet.live)
                }
                f.put("text", edited)
                f.set<com.fasterxml.jackson.databind.JsonNode>("changes", fp.changes)
            }
        }
    }

    private fun read(abs: Path): String? = try {
        Files.readString(abs)
    } catch (e: Exception) {
        null
    }

    /**
     * The stylesheets of the showing app windows. Project sheets: author sheets only, scene sheets first. Sheets outside
     * the project, user agent ones included (a library control's or theme's): mapped ones, and unmapped ones that are
     * not JavaFX's own, for the hint. FX thread.
     */
    private fun appSheets(roots: SourceRoots): Found {
        val project = LinkedHashMap<Path, String>()
        val mapped = LinkedHashMap<Path, Sheet>()
        val unmapped = LinkedHashMap<String, Unmapped>()
        fun add(raw: String?, author: Boolean) {
            if (raw.isNullOrBlank() || Scratch.isScratch(raw)) return
            val url = Sheets.resolveUrl(raw)?.substringBefore('?') ?: return
            val abs = Sheets.fileOf(url)
            if (roots.owns(abs)) {
                val src = roots.sourceFile(abs) ?: return
                if (author) project.putIfAbsent(src, roots.projectFile(abs) ?: src.toString())
                return
            }
            val m = roots.mapped(url)
            if (m != null) {
                mapped.putIfAbsent(m, Sheet(m, roots.projectFile(m) ?: m.toString(), "", emptyList(), true, url, abs != null))
            } else if (!Sheets.isBuiltin(url) && !url.startsWith("data:", ignoreCase = true)) {
                unmapped.getOrPut(url) { Unmapped(url, Sheets.shortName(url), Sheets.containerOf(url), Sheets.load(url).ruleKeys.toSet()) }
            }
        }
        val windows = Windows.showing()
        windows.forEach { w -> add(Sheets.platformSheet(w.scene.userAgentStylesheet ?: Application.getUserAgentStylesheet()), false) }
        windows.forEach { w -> Reloads.allNodes(w.scene.root).forEach { n -> if (n is Region) add(n.userAgentStylesheet, false) } }
        windows.forEach { w -> w.scene.stylesheets.forEach { add(it, true) } }
        windows.forEach { w ->
            Reloads.allNodes(w.scene.root).forEach { n -> if (n is Parent) n.stylesheets.forEach { add(it, true) } }
        }
        return Found(project.map { (k, v) -> k to v }, mapped.values.toList(), unmapped.values.toList())
    }

    private fun mergeInto(fp: FilePlan, rule: ScannedRule, r: ScannedRule) {
        val text = fp.sheet.text
        val added = ArrayList<ScannedDecl>()
        for (d in r.declarations) {
            val existing = rule.declarations.lastOrNull { it.property == d.property && it.valueStart >= 0 }
            if (existing == null) {
                added += d
                continue
            }
            val old = text.substring(existing.valueStart, existing.valueEnd)
            val now = valueOf(d) + if (existing.important) " !important" else ""
            if (old == now) continue
            fp.edits += Edit(existing.valueStart, existing.valueEnd - existing.valueStart, now)
            fp.changes.addObject().put("kind", "update").put("selector", rule.selectorText).put("line", existing.line)
                .put("property", d.property).put("from", old).put("to", now)
        }
        if (added.isEmpty()) return
        val last = rule.declarations.lastOrNull()
        if (last != null && !last.text.endsWith(";") && last.valueEnd >= 0) fp.edits += Edit(last.valueEnd, 0, ";")
        val lineStart = text.lastIndexOf('\n', rule.closeBrace - 1) + 1
        val ownLine = text.substring(lineStart, rule.closeBrace).isBlank()
        val insert = if (ownLine) {
            val indent = rule.declarations.firstOrNull()?.let { indentOf(text, it) } ?: "    "
            Edit(lineStart, 0, added.joinToString("") { "$indent${declText(it)}\n" })
        } else {
            val pad = if (rule.closeBrace > 0 && text[rule.closeBrace - 1].isWhitespace()) "" else " "
            Edit(rule.closeBrace, 0, pad + added.joinToString(" ") { declText(it) } + " ")
        }
        fp.edits += insert
        for (d in added) {
            fp.changes.addObject().put("kind", "add").put("selector", rule.selectorText).put("line", rule.line)
                .put("property", d.property).put("to", valueOf(d) + if (d.important) " !important" else "")
        }
    }

    private fun appendRule(fp: FilePlan, r: ScannedRule) {
        val cur = fp.sheet.text + fp.appended
        val prefix = if (cur.isBlank() || cur.endsWith("\n\n")) "" else if (cur.endsWith("\n")) "\n" else "\n\n"
        val body = ruleText(r)
        fp.appended.append(prefix)
        val change = fp.changes.addObject().put("kind", "newRule").put("selector", r.selectorText).put("css", body.trimEnd())
        fp.appendedAt += change to fp.appended.length
        fp.appended.append(body)
    }

    /** Applies non-overlapping edits back to front; edits at the same offset keep their order. */
    private fun applyEdits(text: String, edits: List<Edit>): String {
        val sb = StringBuilder(text)
        edits.withIndex().sortedWith(compareByDescending<IndexedValue<Edit>> { it.value.at }.thenByDescending { it.index })
            .forEach { (_, e) -> sb.replace(e.at, e.at + e.remove, e.insert) }
        return sb.toString()
    }

    private fun indentOf(text: String, d: ScannedDecl): String {
        val nameStart = text.lastIndexOf(d.property, d.valueStart, ignoreCase = true).takeIf { it >= 0 } ?: return "    "
        val lineStart = text.lastIndexOf('\n', nameStart - 1) + 1
        val lead = text.substring(lineStart, nameStart)
        return if (lead.isBlank()) lead else "    "
    }

    private fun ruleText(r: ScannedRule): String = r.selectorText + " {\n" + r.declarations.joinToString("") { "    ${declText(it)}\n" } + "}\n"

    private fun declText(d: ScannedDecl): String = "${d.property}: ${valueOf(d)}${if (d.important) " !important" else ""};"

    /** The value as written, without `!important` and the trailing `;`. */
    private fun valueOf(d: ScannedDecl): String =
        d.text.substringAfter(':').trim().removeSuffix(";").trim().replace(IMPORTANT, "").trim()

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun bad(message: String): Nothing = throw FcpException(Fcp.Error.BAD_REQUEST, message)

    private val IMPORTANT = Regex("\\s*!\\s*important\\s*$", RegexOption.IGNORE_CASE)
}

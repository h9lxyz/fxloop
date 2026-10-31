package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.Fx
import dev.fxloop.agent.Reloads
import dev.fxloop.agent.Windows
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javafx.scene.Parent

/**
 * Inspector *Apply to file* (PRD 08 §5): turns the scratch rules into edits of the project's own stylesheets. Each
 * scratch rule goes into the source rule with the same selector (the last one that already sets one of its
 * properties, else the last one); a selector no project sheet has is appended to the scene's first project sheet.
 * Existing declarations keep their position and `!important`; new ones go before the rule's closing brace.
 *
 * Only plans: the result carries the edited text and a hash of what was read, and the server writes the files.
 */
internal object ScratchApply {
    private class Sheet(val abs: Path, val file: String, val text: String, val rules: List<ScannedRule>)

    private class Edit(val at: Int, val remove: Int, val insert: String)

    private class FilePlan(val sheet: Sheet) {
        val edits = ArrayList<Edit>()
        val changes = Json.array()

        /** New rules for the end of the file, and the offset of each inside it (for its line number). */
        val appended = StringBuilder()
        val appendedAt = ArrayList<Pair<ObjectNode, Int>>()
    }

    /** FCP `cssScratchPlan` (params carry the source roots, see [SourceRoots.from]). */
    fun plan(p: ObjectNode): ObjectNode {
        val roots = SourceRoots.from(p)
        val css = Scratch.text()
        if (!Scratch.active || css.isBlank()) bad("there is no scratch CSS to apply")
        val wanted = CssSource.scan(css).rules
        val files = Fx.call { projectSheets(roots) }
        val sheets = files.mapNotNull { (abs, file) ->
            val text = try {
                Files.readString(abs)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            Sheet(abs, file, text, CssSource.scan(text).rules)
        }
        if (sheets.isEmpty()) bad("no stylesheet of this project is loaded in the app; use Copy as CSS instead")
        val plans = LinkedHashMap<Path, FilePlan>()
        fun planFor(s: Sheet) = plans.getOrPut(s.abs) { FilePlan(s) }
        for (r in wanted) {
            val key = r.selectors.map(Sheets::key)
            val hits = sheets.flatMap { s -> s.rules.filter { it.selectors.map(Sheets::key) == key && it.closeBrace >= 0 }.map { s to it } }
            val props = r.declarations.map { it.property }.toSet()
            val target = hits.lastOrNull { (_, rule) -> rule.declarations.any { it.property in props } } ?: hits.lastOrNull()
            if (target == null) appendRule(planFor(sheets.first()), r) else mergeInto(planFor(target.first), target.second, r)
        }
        return Json.obj {
            put("rules", wanted.size)
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
                f.put("text", edited)
                f.set<com.fasterxml.jackson.databind.JsonNode>("changes", fp.changes)
            }
        }
    }

    /** Project-owned author stylesheets of the showing app windows as (source file, project-relative name): scene sheets first. */
    private fun projectSheets(roots: SourceRoots): List<Pair<Path, String>> {
        val out = LinkedHashMap<Path, String>()
        fun add(url: String) {
            if (Scratch.isScratch(url)) return
            val abs = Sheets.resolveUrl(url)?.let(Sheets::fileOf) ?: return
            if (!roots.owns(abs)) return
            val src = roots.sourceFile(abs) ?: return
            out.putIfAbsent(src, roots.projectFile(abs) ?: src.toString())
        }
        val windows = Windows.showing()
        windows.forEach { w -> w.scene.stylesheets.forEach(::add) }
        windows.forEach { w ->
            Reloads.allNodes(w.scene.root).forEach { n -> if (n is Parent) n.stylesheets.forEach(::add) }
        }
        return out.map { (k, v) -> k to v }
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
        val body = r.selectorText + " {\n" + r.declarations.joinToString("") { "    ${declText(it)}\n" } + "}\n"
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

    private fun declText(d: ScannedDecl): String = "${d.property}: ${valueOf(d)}${if (d.important) " !important" else ""};"

    /** The value as written, without `!important` and the trailing `;`. */
    private fun valueOf(d: ScannedDecl): String =
        d.text.substringAfter(':').trim().removeSuffix(";").trim().replace(IMPORTANT, "").trim()

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun bad(message: String): Nothing = throw FcpException(Fcp.Error.BAD_REQUEST, message)

    private val IMPORTANT = Regex("\\s*!\\s*important\\s*$", RegexOption.IGNORE_CASE)
}

package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.agent.css.ScratchApply
import org.lab59.fxloop.agent.css.SourceRoots
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.util.Locale

/**
 * Layout edits → FXML attribute patches (PRD 09 §4.2). Read-only plan; the server writes. The patcher edits attribute
 * text in place (replace the value, add ` name="value"` to the start tag, or remove the attribute), never
 * re-serializes, and refuses when the element at the located position is not the node's.
 */
internal object FxmlEdit {
    private val NAME = Regex("[a-zA-Z][a-zA-Z0-9]{0,63}")
    private val ENUM_PROPS = setOf("hgrow", "vgrow", "alignment", "halignment", "valignment")

    /** One text change: replace [from, to) with [insert]; [seq] keeps same-offset insertions in request order. */
    private class Patch(val from: Int, val to: Int, val insert: String, val seq: Int)

    private class FilePlan(val doc: FxmlDoc) {
        val patches = ArrayList<Patch>()
        val changes = Json.array()
    }

    fun plan(p: ObjectNode): ObjectNode {
        val edits = p.path("edits")
        if (!edits.isArray || edits.isEmpty) throw FcpException(Fcp.Error.BAD_REQUEST, "edits required: [{handle|path, property, value}]")
        val roots = SourceRoots.from(p)
        return Fx.call {
            val locator = FxmlLocator(roots)
            val plans = LinkedHashMap<String, FilePlan>()
            val code = Json.array()
            /** Latest edit per (element, attribute) wins. */
            val done = HashMap<Pair<Int, String>, Patch>()
            var seq = 0
            for (e in edits) {
                val property = e.path("property").asText("")
                val value: String? = e.get("value")?.takeUnless { it.isNull }?.asText()
                val constraint = Props.constraint(property)
                if (constraint == null && !NAME.matches(property)) {
                    throw FcpException(Fcp.Error.BAD_REQUEST, "invalid property '$property'")
                }
                val node = Targets.resolve(e as ObjectNode)
                fun toCode(reason: String) {
                    code.addObject().put("path", NodePath.of(node)).put("property", property).also { o ->
                        if (value == null) o.putNull("value") else o.put("value", value)
                        o.put("reason", reason)
                    }
                }
                if (constraint != null && constraint.kind == "insets") {
                    toCode("$property is an Insets: FXML needs a <$property><Insets .../></$property> element, which is not patched; edit the file by hand or set it in code")
                    continue
                }
                val hit = try {
                    locator.locate(node)
                } catch (m: FxmlMismatch) {
                    throw FcpException(Fcp.Error.BAD_REQUEST, "the FXML file no longer matches the running app (${m.message}); reload and retry")
                }
                if (hit == null) {
                    toCode("no registered FXML document created this node")
                    continue
                }
                if (hit.doc.abs == null) {
                    toCode("the document ${hit.doc.location} is not a file of the project (loaded from a jar)")
                    continue
                }
                val el = hit.el
                val id = node.id
                val fxId = hit.fxId
                if (fxId != null && id != null && id.isNotEmpty() && fxId != id) {
                    throw FcpException(Fcp.Error.BAD_REQUEST, "line ${el.line}: fx:id=\"$fxId\" is not the node's id '$id'; reload and retry")
                }
                if (!hit.doc.text.startsWith("<", el.start) || !hit.doc.text.startsWith(el.name, el.start + 1)) {
                    throw FcpException(Fcp.Error.BAD_REQUEST, "line ${el.line}: element <${el.name}> not found where the document says; reload and retry")
                }
                val plan = plans.getOrPut(hit.doc.abs.toString()) { FilePlan(hit.doc) }
                val text = hit.doc.text
                val newValue = value?.let { v ->
                    if (constraint != null && constraint.prop in ENUM_PROPS) v.trim().uppercase(Locale.ROOT) else v.trim()
                }
                val existing = el.attr(property)
                val change = Json.obj().put("line", el.line).put("element", el.name).put("attribute", property)
                existing?.let { change.put("from", it.value) }
                val patch: Patch? = when {
                    existing != null && newValue != null -> {
                        change.put("to", newValue)
                        if (existing.value == newValue) null else Patch(existing.valueStart, existing.valueEnd, XmlScan.escape(newValue), seq++)
                    }
                    existing != null -> {
                        var s = existing.nameStart
                        while (s > 0 && text[s - 1].isWhitespace() && text[s - 1] != '\n') s--
                        Patch(s, existing.valueEnd + 1, "", seq++)
                    }
                    newValue != null -> {
                        change.put("to", newValue)
                        var at = if (el.selfClosing) el.tagEnd - 1 else el.tagEnd
                        while (at > el.start && text[at - 1].isWhitespace()) at--
                        Patch(at, at, " $property=\"${XmlScan.escape(newValue)}\"", seq++)
                    }
                    else -> null
                }
                if (patch == null && existing == null) continue
                val key = el.start to property
                done.remove(key)?.let { old ->
                    plan.patches.remove(old)
                    plan.changes.removeAll { it.path("line").asInt() == el.line && it.path("attribute").asText() == property }
                }
                if (patch != null) {
                    plan.patches.add(patch)
                    done[key] = patch
                }
                plan.changes.add(change)
            }
            Json.obj {
                val files = putArray("files")
                for (fp in plans.values) {
                    if (fp.patches.isEmpty()) continue
                    val sb = StringBuilder(fp.doc.text)
                    for (pt in fp.patches.sortedWith(compareByDescending<Patch> { it.from }.thenByDescending { it.seq })) {
                        sb.replace(pt.from, pt.to, pt.insert)
                    }
                    val f = files.addObject()
                    f.put("file", fp.doc.file)
                    f.put("absFile", fp.doc.abs.toString())
                    f.put("sha256", ScratchApply.sha256(fp.doc.text))
                    f.put("text", sb.toString())
                    f.set<JsonNode>("changes", fp.changes)
                }
                set<JsonNode>("code", code)
            }
        }
    }
}

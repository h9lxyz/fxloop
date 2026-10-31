package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json

/**
 * Scene-diff engine (PRD 02 §6): compares two `snapshotState` results and returns
 * `{added, removed, changed, windowChanges, overflow}` capped at [CAP] entries.
 */
object SceneDiff {
    val WATCHED = listOf("text", "value", "visible", "disabled", "selected", "focused", "itemCount", "progress")
    const val CAP = 50

    fun diff(before: JsonNode, after: JsonNode): ObjectNode {
        val out = Json.obj()
        val added = out.putArray("added")
        val removed = out.putArray("removed")
        val changed = out.putArray("changed")
        val windows = out.putArray("windowChanges")
        var budget = CAP
        var overflow = before.path("overflow").asBoolean() || after.path("overflow").asBoolean()
        fun add(arr: ArrayNode, entry: JsonNode) {
            if (budget <= 0) overflow = true else { budget--; arr.add(entry) }
        }

        val bw = windowsBySegment(before)
        val aw = windowsBySegment(after)
        for ((seg, w) in aw) {
            val old = bw[seg]
            if (old == null) {
                add(windows, Json.obj {
                    put("change", "opened"); put("window", seg); put("type", w.path("type").asText())
                    w["title"]?.takeIf { !it.isNull }?.let { put("title", it.asText()) }
                })
            } else if (old.path("title").asText("") != w.path("title").asText("")) {
                add(windows, Json.obj { put("change", "titleChanged"); put("window", seg); put("title", w.path("title").asText()) })
            }
        }
        (bw.keys - aw.keys).forEach { add(windows, Json.obj { put("change", "closed"); put("window", it) }) }

        val bn = before.path("nodes")
        val an = after.path("nodes")
        for ((path, node) in an.fields()) {
            val old = bn[path]
            if (old == null) {
                if (parentOf(path)?.let { an.has(it) && !bn.has(it) } == true) continue // only the top of an added subtree
                add(added, Json.obj {
                    put("path", path)
                    put("type", node.path("type").asText())
                    node["text"]?.let { put("text", it.asText()) }
                })
                continue
            }
            for (prop in WATCHED) {
                val ov = old[prop]
                val nv = node[prop]
                if (ov == nv) continue
                add(changed, Json.obj {
                    put("path", path)
                    put("prop", prop)
                    set<JsonNode>("old", ov ?: Json.nullNode())
                    set<JsonNode>("new", nv ?: Json.nullNode())
                })
            }
        }
        for (path in bn.fieldNames()) {
            if (!an.has(path) && parentOf(path)?.let { bn.has(it) && !an.has(it) } != true) add(removed, Json.text(path))
        }
        out.put("overflow", overflow)
        return out
    }

    fun parentOf(path: String): String? = path.lastIndexOf('/').takeIf { it > 0 }?.let { path.substring(0, it) }

    private fun windowsBySegment(snap: JsonNode): Map<String, JsonNode> =
        snap.path("windows").associateBy { it.path("segment").asText() }

    fun isEmpty(effects: JsonNode) = listOf("added", "removed", "changed", "windowChanges").all { effects.path(it).isEmpty }

    /** One-line human summary for the text block. */
    fun summary(effects: JsonNode?): String {
        if (effects == null || effects.isMissingNode) return ""
        if (isEmpty(effects)) return "effects: none"
        val sb = StringBuilder("effects:")
        effects.path("windowChanges").forEach { sb.append(" window ${it.path("change").asText()} ${it.path("window").asText()};") }
        effects.path("changed").filter { it.path("prop").asText() != "focused" }.forEach {
            sb.append(" ${shortPath(it.path("path").asText())}.${it.path("prop").asText()} ${it.path("old")}→${it.path("new")};")
        }
        if (!effects.path("added").isEmpty) sb.append(" +${effects.path("added").size()} nodes;")
        if (!effects.path("removed").isEmpty) sb.append(" -${effects.path("removed").size()} nodes;")
        if (effects.path("overflow").asBoolean()) sb.append(" (overflow)")
        return sb.toString()
    }

    private fun shortPath(path: String) = path.substringAfterLast('/')
}

/** Server-side ordered log of successful action tools, ring of 500 (PRD 02 §5). */
class Journal(private val capacity: Int = 500) {
    private val ring = ArrayDeque<JournalEntry>()
    private var seq = 0L

    @Synchronized
    fun record(tool: String, args: ObjectNode, target: ObjectNode?, effects: ObjectNode?, windowFingerprint: String,
        source: String = "agent", redacted: Boolean = false): JournalEntry {
        val e = JournalEntry(++seq, tool, args, target, effects, windowFingerprint, System.currentTimeMillis(), source, redacted)
        ring.addLast(e)
        while (ring.size > capacity) ring.removeFirst()
        return e
    }

    @Synchronized fun entries(): List<JournalEntry> = ring.toList()

    @Synchronized fun range(from: Long, to: Long): List<JournalEntry> = ring.filter { it.seq in from..to }
}

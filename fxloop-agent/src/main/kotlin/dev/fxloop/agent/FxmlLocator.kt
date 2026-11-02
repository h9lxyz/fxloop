package dev.fxloop.agent

import dev.fxloop.agent.css.SourceRoots
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import javafx.scene.Node
import javafx.scene.Parent

/** An attribute of a scanned start tag; offsets index the document text. [value] is unescaped, [valueStart]/[valueEnd] exclude the quotes. */
internal class XAttr(val name: String, val value: String, val nameStart: Int, val valueStart: Int, val valueEnd: Int)

/** A scanned element; [start] is the `<`, [tagEnd] the `>` of its start tag. */
internal class XElem(val name: String, val line: Int, val start: Int, val parent: XElem?) {
    var tagEnd = -1
    var selfClosing = false
    val attrs = ArrayList<XAttr>()
    val children = ArrayList<XElem>()

    fun attr(n: String): XAttr? = attrs.firstOrNull { it.name == n }

    /** An object element (class name), as opposed to a property element (`children`, `GridPane.margin`) or an `fx:` tag. */
    val isObject: Boolean get() = name[0].isUpperCase() && '.' !in name && ':' !in name
}

/**
 * A tolerant FXML tag scanner: elements with line numbers and attribute offsets, so the patcher can edit text in
 * place. Skips comments, processing instructions, CDATA and doctype; returns null for text it cannot make sense of.
 */
internal object XmlScan {
    fun root(t: String): XElem? = try {
        scan(t)
    } catch (e: RuntimeException) {
        null
    }

    private fun unescape(s: String) =
        if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun scan(t: String): XElem? {
        var i = 0
        var line = 1
        var root: XElem? = null
        var cur: XElem? = null
        fun skipTo(end: String) {
            val j = t.indexOf(end, i)
            val stop = if (j < 0) t.length else j + end.length
            for (k in i until stop) if (t[k] == '\n') line++
            i = stop
        }
        while (i < t.length) {
            val c = t[i]
            if (c != '<') {
                if (c == '\n') line++
                i++
                continue
            }
            when {
                t.startsWith("<!--", i) -> skipTo("-->")
                t.startsWith("<![CDATA[", i) -> skipTo("]]>")
                t.startsWith("<?", i) -> skipTo("?>")
                t.startsWith("<!", i) -> skipTo(">")
                t.startsWith("</", i) -> {
                    if (t.indexOf('>', i) < 0) return null
                    cur = cur?.parent
                    skipTo(">")
                }
                else -> {
                    val startLine = line
                    val start = i
                    i++
                    val ns = i
                    while (i < t.length && !t[i].isWhitespace() && t[i] != '>' && t[i] != '/') i++
                    val el = XElem(t.substring(ns, i), startLine, start, cur)
                    if (el.name.isEmpty()) return null
                    while (true) {
                        while (i < t.length && t[i].isWhitespace()) {
                            if (t[i] == '\n') line++
                            i++
                        }
                        if (i >= t.length) return null
                        if (t[i] == '>') {
                            el.tagEnd = i
                            break
                        }
                        if (t[i] == '/' && i + 1 < t.length && t[i + 1] == '>') {
                            el.selfClosing = true
                            el.tagEnd = i + 1
                            i++
                            break
                        }
                        val an = i
                        while (i < t.length && t[i] != '=' && !t[i].isWhitespace() && t[i] != '>') i++
                        val name = t.substring(an, i)
                        while (i < t.length && t[i].isWhitespace()) i++
                        if (i >= t.length || t[i] != '=') return null
                        i++
                        while (i < t.length && t[i].isWhitespace()) i++
                        val q = t.getOrNull(i)
                        if (q != '"' && q != '\'') return null
                        val vs = i + 1
                        val ve = t.indexOf(q, vs)
                        if (ve < 0) return null
                        for (k in vs until ve) if (t[k] == '\n') line++
                        el.attrs.add(XAttr(name, unescape(t.substring(vs, ve)), an, vs, ve))
                        i = ve + 1
                    }
                    i++
                    cur?.children?.add(el)
                    if (root == null) root = el
                    if (!el.selfClosing) cur = el
                }
            }
        }
        return root
    }
}

/** A registered FXML document read for a node: [file] is the source (or the bare name), [abs] null for jar documents. */
internal class FxmlDoc(val location: URL, val abs: Path?, val file: String, val text: String, val root: XElem)

/** The FXML element a node was built from. */
internal class FxmlHit(val doc: FxmlDoc, val el: XElem) {
    val fxId: String? get() = el.attr("fx:id")?.value
}

/** Thrown when the document no longer describes the node (edited since the load). */
internal class FxmlMismatch(message: String) : RuntimeException(message)

/**
 * Finds the FXML element behind a node (PRD 09 §14): the registry entry whose root is the node or its nearest
 * ancestor, then `fx:id`, else the element at the node's position under the document root. Returns null when unsure.
 * One instance caches the documents it reads. FX thread.
 */
internal class FxmlLocator(private val roots: SourceRoots) {
    private val docs = HashMap<URL, FxmlDoc?>()

    fun locate(node: Node): FxmlHit? {
        val chain = ArrayList<Node>()
        var n: Node? = node
        var entry: FxmlRegistry.Entry? = null
        val live = AgentRuntime.current().fxml.live()
        while (n != null) {
            chain.add(n)
            val here = n
            entry = live.firstOrNull { it.root.get() === here }
            if (entry != null) break
            n = n.parent
        }
        if (entry == null) return null
        chain.reverse()
        val doc = doc(entry) ?: return null
        val id = node.id
        if (id != null && id.isNotEmpty()) {
            val el = find(doc.root) { it.attr("fx:id")?.value == id }
            if (el != null) {
                if (el.isObject && !nameMatches(el.name, node)) {
                    throw FxmlMismatch("<${el.name} fx:id=\"$id\"> at line ${el.line} is not a ${node.javaClass.simpleName}")
                }
                return FxmlHit(doc, el)
            }
        }
        var cur = doc.root
        if (!cur.name.startsWith("fx:") && !nameMatches(cur.name, chain[0])) return null
        for (i in 1 until chain.size) {
            val parent = chain[i - 1] as? Parent ?: return null
            cur = step(cur, parent, chain[i]) ?: return null
        }
        return FxmlHit(doc, cur)
    }

    private fun find(e: XElem, pred: (XElem) -> Boolean): XElem? {
        if (pred(e)) return e
        for (c in e.children) find(c, pred)?.let { return it }
        return null
    }

    private fun nameMatches(element: String, node: Node): Boolean {
        var c: Class<*>? = node.javaClass
        while (c != null) {
            if (c.simpleName == element) return true
            c = c.superclass
        }
        return false
    }

    private fun candidates(el: XElem): List<XElem> {
        val out = ArrayList<XElem>()
        for (c in el.children) {
            if (c.isObject) out.add(c)
            else if (c.name in CHILD_PROPS) out.addAll(c.children.filter { it.isObject })
        }
        return out
    }

    private fun step(el: XElem, parent: Parent, child: Node): XElem? {
        val cands = candidates(el)
        val kids = parent.childrenUnmodifiable
        val idx = kids.indexOf(child)
        if (idx < 0) return null
        val pick = if (cands.size == kids.size && cands.indices.all { nameMatches(cands[it].name, kids[it]) }) {
            cands[idx]
        } else {
            val same = cands.filter { nameMatches(it.name, child) }
            val sameKids = kids.filter { it.javaClass == child.javaClass }
            if (same.size == 1 && sameKids.size == 1) same[0] else return null
        }
        val id = child.id
        val fxId = pick.attr("fx:id")?.value ?: pick.attr("id")?.value
        if (id != null && id.isNotEmpty() && fxId != id) return null
        return pick
    }

    private fun doc(e: FxmlRegistry.Entry): FxmlDoc? = docs.getOrPut(e.location) {
        var abs: Path? = null
        if (e.location.protocol == "file") {
            try {
                abs = roots.sourceFile(Path.of(e.location.toURI()))?.takeIf { Files.isRegularFile(it) }
            } catch (ex: Exception) {
                abs = null
            }
        }
        val text = try {
            if (abs != null) Files.readString(abs) else e.location.openStream().use { String(it.readAllBytes(), Charsets.UTF_8) }
        } catch (ex: Exception) {
            return@getOrPut null
        }
        val el = XmlScan.root(text) ?: return@getOrPut null
        val file = if (abs != null) roots.projectFile(abs) ?: abs.toString() else e.location.path.substringAfterLast('/')
        FxmlDoc(e.location, abs, file, text, el)
    }

    companion object {
        private val CHILD_PROPS = setOf("children", "content", "top", "left", "center", "right", "bottom")
    }
}

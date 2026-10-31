package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Accordion
import javafx.scene.control.Cell
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.ComboBox
import javafx.scene.control.ListView
import javafx.scene.control.MultipleSelectionModel
import javafx.scene.control.PasswordField
import javafx.scene.control.ScrollPane
import javafx.scene.control.SelectionModel
import javafx.scene.control.TabPane
import javafx.scene.control.TableView
import javafx.scene.control.TextInputControl
import javafx.scene.control.TitledPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow

/**
 * E2 (PRD 07 §4): view-state carry-over across an FXML swap or a remount. [capture] snapshots the old subtree,
 * [restore] re-applies it to the new subtree, matched by fx:id first, then by subtree-relative node path, and only
 * when the node type matches. FX thread only.
 */
internal object ViewState {
    private const val MAX_SKIPPED = 50

    /** One restorable facet of a node; returns a skip reason, or `null` when applied. */
    internal class Part(val apply: (Node) -> String?)

    internal class Item(
        val type: Class<*>,
        val id: String?,
        val structural: String,
        val display: String,
        val parts: List<Part>,
    )

    internal class Visited(val node: Node, val item: Item)

    /** Opaque capture of one subtree. */
    class Snapshot internal constructor(private val items: List<Item>, private val focus: Int) {
        internal fun items(): List<Item> = items
        internal fun focusIndex(): Int = focus
    }

    fun capture(root: Node): Snapshot {
        val visited = ArrayList<Visited>()
        val type = NodePath.typeName(root)
        walk(root, type, segment(root, type, 0), visited, true)
        val owner = root.scene?.focusOwner?.let { LogicalTree.owner(it) }
        return Snapshot(visited.map { it.item }, visited.indexOfFirst { it.node === owner })
    }

    private fun segment(n: Node, type: String, index: Int) = if (!n.id.isNullOrEmpty()) "$type#${n.id}" else "$type[$index]"

    /** Walks the logical tree; content of virtualized controls and cells is not descended into. */
    private fun walk(n: Node, structural: String, display: String, out: MutableList<Visited>, capture: Boolean) {
        val parts = if (capture) partsOf(n) else emptyList()
        out.add(Visited(n, Item(n.javaClass, n.id?.takeIf { it.isNotEmpty() }, structural, display, parts)))
        if (n is Cell<*> || LogicalTree.isVirtualized(n)) return
        val counters = HashMap<String, Int>()
        for (c in LogicalTree.children(n)) {
            val t = NodePath.typeName(c)
            val i = counters.merge(t, 1, Int::plus)!! - 1
            walk(c, "$structural/$t[$i]", "$display/${segment(c, t, i)}", out, capture)
        }
    }

    private fun partsOf(n: Node): List<Part> {
        val parts = ArrayList<Part>()
        when (n) {
            is PasswordField -> parts.add(Part { "password" })
            is TextInputControl -> {
                val text = n.text ?: ""
                val anchor = n.anchor
                val caret = n.caretPosition
                parts.add(Part { t ->
                    t as TextInputControl
                    if (t.textProperty().isBound) return@Part "bound"
                    if ((t.text ?: "") != text) t.text = text
                    val len = (t.text ?: "").length
                    t.selectRange(anchor.coerceAtMost(len), caret.coerceAtMost(len))
                    null
                })
            }
            is TabPane -> n.selectionModel.selectedItem?.let { sel ->
                val id = sel.id?.takeIf { it.isNotEmpty() }
                val text = sel.text
                val index = n.tabs.indexOf(sel)
                parts.add(Part { t ->
                    t as TabPane
                    val tab = (if (id != null) t.tabs.firstOrNull { it.id == id } else null)
                        ?: (if (text != null) t.tabs.firstOrNull { it.text == text } else null)
                        ?: t.tabs.getOrNull(index)
                    if (tab == null) "out of bounds" else { t.selectionModel.select(tab); null }
                })
            }
            is ScrollPane -> {
                val h = n.hvalue
                val v = n.vvalue
                parts.add(Part { t ->
                    t as ScrollPane
                    if (t.hvalueProperty().isBound || t.vvalueProperty().isBound) return@Part "bound"
                    t.hvalue = h
                    t.vvalue = v
                    null
                })
            }
            is TitledPane -> {
                val expanded = n.isExpanded
                parts.add(Part { t ->
                    t as TitledPane
                    if (t.expandedProperty().isBound) "bound" else { t.isExpanded = expanded; null }
                })
            }
            is Accordion -> {
                val pane = n.expandedPane
                val index = if (pane == null) -1 else n.panes.indexOf(pane)
                val id = pane?.id?.takeIf { it.isNotEmpty() }
                val text = pane?.text
                parts.add(Part { t ->
                    t as Accordion
                    if (index < 0) { t.expandedPane = null; return@Part null }
                    val target = (if (id != null) t.panes.firstOrNull { it.id == id } else null)
                        ?: (if (text != null) t.panes.firstOrNull { it.text == text } else null)
                        ?: t.panes.getOrNull(index)
                    if (target == null) "out of bounds" else { t.expandedPane = target; null }
                })
            }
            is ToggleButton -> {
                val sel = n.isSelected
                parts.add(Part { t ->
                    t as ToggleButton
                    if (t.selectedProperty().isBound) "bound" else { t.isSelected = sel; null }
                })
            }
            is CheckBox -> {
                val sel = n.isSelected
                parts.add(Part { t ->
                    t as CheckBox
                    if (t.selectedProperty().isBound) "bound" else { t.isSelected = sel; null }
                })
            }
        }
        selectionPart(n)?.let { parts.add(it) }
        if (LogicalTree.isVirtualized(n)) {
            val first = flowOf(n as Parent)?.firstVisibleCell?.index ?: 0
            if (first > 0) {
                parts.add(Part { t ->
                    t as Parent
                    if (t.scene == null) return@Part "not laid out"
                    t.applyCss()
                    t.layout()
                    val flow = flowOf(t) ?: return@Part "not laid out"
                    flow.scrollTo(first)
                    null
                })
            }
        }
        return parts
    }

    private fun sizeOf(n: Node): Int = when (n) {
        is ListView<*> -> n.items?.size ?: 0
        is TableView<*> -> n.items?.size ?: 0
        is TreeView<*> -> n.expandedItemCount
        is ComboBox<*> -> n.items?.size ?: 0
        is ChoiceBox<*> -> n.items?.size ?: 0
        else -> 0
    }

    private fun modelOf(n: Node): SelectionModel<*>? = when (n) {
        is ListView<*> -> n.selectionModel
        is TableView<*> -> n.selectionModel
        is TreeView<*> -> n.selectionModel
        is ComboBox<*> -> n.selectionModel
        is ChoiceBox<*> -> n.selectionModel
        else -> null
    }

    private fun selectionPart(n: Node): Part? {
        val model = modelOf(n) ?: return null
        val indices: List<Int> = if (model is MultipleSelectionModel<*>) model.selectedIndices.toList()
        else listOf(model.selectedIndex).filter { it >= 0 }
        if (indices.isEmpty()) return null
        return Part { t ->
            val m = modelOf(t) ?: return@Part "no selection model"
            val size = sizeOf(t)
            if (indices.any { it >= size }) return@Part "out of bounds"
            if (m is MultipleSelectionModel<*> && indices.size > 1) {
                m.clearSelection()
                m.selectIndices(indices[0], *indices.drop(1).toIntArray())
            } else {
                m.clearAndSelect(indices[0])
            }
            null
        }
    }

    private fun flowOf(control: Parent): VirtualFlow<*>? =
        control.lookupAll(".virtual-flow").firstOrNull { it is VirtualFlow<*> } as? VirtualFlow<*>

    /** @return `{restored: n, skipped: [{path, reason}]}` (`skippedTotal` when the list was truncated) */
    fun restore(snapshot: Snapshot, root: Node): ObjectNode {
        val out = empty()
        var restored = 0
        val skipped = ArrayList<Pair<String, String>>()
        val fresh = ArrayList<Visited>()
        try {
            val type = NodePath.typeName(root)
            walk(root, type, segment(root, type, 0), fresh, false)
        } catch (e: Exception) {
            out.withArray("skipped").addObject().put("path", "").put("reason", "traversal failed: $e")
            return out
        }
        val old = snapshot.items()
        val oldIds = old.mapNotNull { it.id }.groupingBy { it }.eachCount()
        val newIds = fresh.mapNotNull { it.item.id }.groupingBy { it }.eachCount()
        val byId = fresh.filter { it.item.id != null && newIds[it.item.id] == 1 }.associateBy { it.item.id }
        val byPath = fresh.associateBy { it.item.structural }
        val matched = arrayOfNulls<Node>(old.size)
        for ((i, o) in old.withIndex()) {
            if (o.parts.isEmpty() && i != snapshot.focusIndex()) continue
            val target = (if (o.id != null && oldIds[o.id] == 1) byId[o.id] else null) ?: byPath[o.structural]
            if (target == null) {
                skipped.add(o.display to "no matching node")
                continue
            }
            if (target.node.javaClass != o.type) {
                skipped.add(o.display to "type changed (${o.type.simpleName} -> ${target.node.javaClass.simpleName})")
                continue
            }
            matched[i] = target.node
        }
        val focusNode = matched.getOrNull(snapshot.focusIndex())
        if (focusNode != null) {
            try {
                focusNode.requestFocus()
                restored++
            } catch (e: Exception) {
                skipped.add(old[snapshot.focusIndex()].display to "focus failed: ${e.javaClass.simpleName}")
            }
        }
        for ((i, o) in old.withIndex()) {
            val target = matched[i] ?: continue
            for (part in o.parts) {
                val reason = try {
                    part.apply(target)
                } catch (e: Exception) {
                    "failed: ${e.javaClass.simpleName}: ${e.message}"
                }
                if (reason == null) restored++ else skipped.add(o.display to reason)
            }
        }
        out.put("restored", restored)
        val arr = out.withArray("skipped")
        for ((path, reason) in skipped.take(MAX_SKIPPED)) arr.addObject().put("path", path).put("reason", reason)
        if (skipped.size > MAX_SKIPPED) out.put("skippedTotal", skipped.size)
        return out
    }

    /** Adds [add] (`{restored, skipped}`) into [into]. */
    fun merge(into: ObjectNode, add: ObjectNode) {
        into.put("restored", into.path("restored").asInt(0) + add.path("restored").asInt(0))
        val skipped = (into.get("skipped") as? ArrayNode ?: into.putArray("skipped"))
        add.path("skipped").forEach { skipped.add(it) }
    }

    fun empty(): ObjectNode = Json.obj().put("restored", 0).also { it.putArray("skipped") }
}

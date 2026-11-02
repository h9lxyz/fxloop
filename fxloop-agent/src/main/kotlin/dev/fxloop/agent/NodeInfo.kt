package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Cell
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.ComboBox
import javafx.scene.control.ComboBoxBase
import javafx.scene.control.IndexedCell
import javafx.scene.control.Labeled
import javafx.scene.control.ListView
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollBar
import javafx.scene.control.Slider
import javafx.scene.control.Spinner
import javafx.scene.control.TabPane
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TextInputControl
import javafx.scene.control.Toggle
import javafx.scene.control.TreeTableRow
import javafx.scene.control.TreeTableView
import javafx.scene.control.TreeView
import javafx.scene.paint.Color
import javafx.scene.text.Text

/** Per-type text/value extraction (the small mapping table of PRD 01 §5). */
internal object NodeInfo {
    fun text(n: Node): String? = when (n) {
        is TableRow<*>, is TreeTableRow<*> -> rowText(n as Parent)
        is Labeled -> n.text
        is TextInputControl -> n.text
        is Text -> n.text
        else -> null
    }

    private fun rowText(row: Parent): String {
        val sb = StringBuilder()
        for (c in row.childrenUnmodifiable) {
            if (c is Cell<*> && c.isVisible) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(cellText(c) ?: "")
            }
        }
        return sb.toString()
    }

    /**
     * A cell's text, or for a graphic-only custom cell the texts inside its graphic. Only for summaries (rows,
     * `visibleCells`): the cell's own `text` stays what `getText()` returns, so assertions built from it hold.
     */
    fun cellText(c: Node): String? {
        if (c !is Cell<*> || c is TableRow<*> || c is TreeTableRow<*>) return text(c)
        if (!c.text.isNullOrEmpty()) return c.text
        val g = c.graphic ?: return c.text
        val parts = NodePath.descendantsInclusive(g).mapNotNull { n -> text(n)?.takeIf { it.isNotBlank() } }
        return if (parts.isEmpty()) c.text else parts.joinToString(" ")
    }

    /** Type-specific value, or `null`. */
    fun value(n: Node): Any? = when (n) {
        is Toggle -> n.isSelected
        is CheckBox -> if (n.isIndeterminate) "indeterminate" else n.isSelected
        is ComboBoxBase<*> -> oneLine(n.value)
        is ChoiceBox<*> -> oneLine(n.value)
        is Slider -> n.value
        is ScrollBar -> n.value
        is ProgressIndicator -> n.progress
        is Spinner<*> -> oneLine(n.value)
        is TabPane -> n.selectionModel.selectedItem?.text
        else -> null
    }

    fun itemCount(n: Node): Int? = when (n) {
        is ListView<*> -> n.items?.size ?: 0
        is TableView<*> -> n.items?.size ?: 0
        is TreeView<*> -> n.expandedItemCount
        is TreeTableView<*> -> n.expandedItemCount
        is ComboBox<*> -> n.items.size
        else -> null
    }

    fun selectedIndex(n: Node): Int? = when (n) {
        is ListView<*> -> n.selectionModel.selectedIndex
        is TableView<*> -> n.selectionModel.selectedIndex
        is TreeView<*> -> n.selectionModel.selectedIndex
        is IndexedCell<*> -> if (n.isSelected) n.index else null
        else -> null
    }

    /**
     * Whether [n] is rendered: in a scene, with it and every ancestor (skin nodes included) visible and not fully
     * transparent. A node's own `visible` is not enough: a ribbon or tab pane hides inactive pages through a skin
     * region, and their nodes keep stale bounds stacked under the active page.
     */
    fun shown(n: Node): Boolean = n.scene != null && shownBelow(n, null)

    /** [n] and its ancestors up to, not including, [ancestor] are visible and not fully transparent. */
    fun shownBelow(n: Node, ancestor: Node?): Boolean {
        var c: Node? = n
        while (c !== ancestor) {
            if (c == null) return false
            if (!c.isVisible || c.opacity == 0.0) return false
            c = c.parent
        }
        return true
    }

    /** Why [n] is not rendered, naming the hidden or transparent node; `null` when it is rendered. */
    fun notShownReason(n: Node): String? {
        if (n.scene == null) return "not in a scene"
        var c: Node? = n
        while (c != null) {
            if (!c.isVisible || c.opacity == 0.0) {
                val state = if (c.isVisible) "fully transparent" else "hidden"
                if (c === n) return "it is $state"
                val at = if (LogicalTree.isLogical(c)) NodePath.of(c) else "a skin node (${NodePath.typeName(c)}) of ${NodePath.of(c)}"
                return "$at is $state"
            }
            c = c.parent
        }
        return null
    }

    fun screenBounds(n: Node): ObjectNode? {
        val b = n.localToScreen(n.boundsInLocal) ?: return null
        val o = Json.obj()
        o.put("x", Math.round(b.minX))
        o.put("y", Math.round(b.minY))
        o.put("w", Math.round(b.width))
        o.put("h", Math.round(b.height))
        return o
    }

    fun clip(s: String?, max: Int): String? {
        if (s == null) return null
        return if (s.length <= max) s else s.substring(0, max) + "…"
    }

    /** Compact, JSON-friendly rendering of an arbitrary property value. */
    fun oneLine(v: Any?): Any? = when (v) {
        null, is Boolean, is Number -> v
        is String -> clip(v, 200)
        is Enum<*> -> v.name
        is Color -> web(v)
        is Node -> {
            val path = NodePath.of(v)
            NodePath.typeName(v) + (if (path == null) "" else " @ $path")
        }
        is Collection<*> -> "[${v.size} items]"
        is Map<*, *> -> "{${v.size} entries}"
        else -> clip(v.toString(), 200)
    }

    fun web(c: Color): String = String.format(
        "#%02x%02x%02x%02x", Math.round(c.red * 255), Math.round(c.green * 255),
        Math.round(c.blue * 255), Math.round(c.opacity * 255),
    )

    fun putValue(o: ObjectNode, key: String, v: Any?) {
        when (val x = oneLine(v)) {
            null -> o.putNull(key)
            is Boolean -> o.put(key, x)
            is Int -> o.put(key, x)
            is Long -> o.put(key, x)
            is Number -> o.put(key, x.toDouble())
            else -> o.put(key, x.toString())
        }
    }

    fun strings(values: List<String>): ArrayNode = Json.strings(values)
}

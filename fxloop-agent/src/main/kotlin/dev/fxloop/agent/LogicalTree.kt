package dev.fxloop.agent

import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Accordion
import javafx.scene.control.ButtonBar
import javafx.scene.control.Cell
import javafx.scene.control.Control
import javafx.scene.control.IndexedCell
import javafx.scene.control.Labeled
import javafx.scene.control.ListView
import javafx.scene.control.MenuBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.control.TabPane
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TitledPane
import javafx.scene.control.ToolBar
import javafx.scene.control.TreeTableRow
import javafx.scene.control.TreeTableView
import javafx.scene.control.TreeView

/**
 * The agent's view of the scene graph: layout containers expose their children, built-in controls are
 * leaves (skin internals are noise for an LLM), composite controls expose their logical content, and
 * virtualized controls expose only their visible, non-empty cells (PRD 01 §5).
 */
internal object LogicalTree {
    fun isVirtualized(n: Node): Boolean = n is ListView<*> || n is TableView<*> || n is TreeView<*> || n is TreeTableView<*>

    fun children(node: Node): List<Node> {
        if (node !is Parent) return emptyList()
        return when {
            node is ScrollPane -> listOfNotNull(node.content)
            node is TabPane -> node.tabs.mapNotNull { it.content }
            node is SplitPane -> node.items.toList()
            node is TitledPane -> listOfNotNull(node.content)
            node is ToolBar -> node.items.toList()
            node is ButtonBar -> node.buttons.toList()
            node is Accordion -> node.panes.toList()
            isVirtualized(node) -> visibleCells(node)
            node is MenuBar -> descendantsOfType(node, Labeled::class.java)
            node is Cell<*> -> if (node is TableRow<*> || node is TreeTableRow<*>) rowCells(node) else emptyList()
            node is Control && node.javaClass.name.startsWith("javafx.scene.control.") -> emptyList()
            else -> node.childrenUnmodifiable.toList()
        }
    }

    /** Visible, non-empty cells of a virtualized control, in index order. */
    fun visibleCells(control: Parent): List<Node> {
        val rowType: Class<*> = when (control) {
            is TableView<*> -> TableRow::class.java
            is TreeTableView<*> -> TreeTableRow::class.java
            else -> IndexedCell::class.java
        }
        val out = ArrayList<IndexedCell<*>>()
        for (n in control.lookupAll(".indexed-cell")) {
            if (rowType.isInstance(n) && n is IndexedCell<*> && n.isVisible && !n.isEmpty &&
                n.index >= 0 && owningVirtualized(n) === control
            ) {
                out.add(n)
            }
        }
        out.sortBy { it.index }
        return out
    }

    private fun owningVirtualized(n: Node): Node? {
        var p = n.parent
        while (p != null && !isVirtualized(p)) p = p.parent
        return p
    }

    private fun rowCells(row: Parent): List<Node> = row.childrenUnmodifiable.filter { it is Cell<*> && it.isVisible }

    private fun descendantsOfType(p: Parent, type: Class<*>): List<Node> {
        val out = ArrayList<Node>()
        collect(p, type, out)
        return out
    }

    private fun collect(p: Parent, type: Class<*>, out: MutableList<Node>) {
        for (n in p.childrenUnmodifiable) {
            if (type.isInstance(n)) out.add(n) else if (n is Parent) collect(n, type, out)
        }
    }

    /**
     * Logical parent, or `null` for a scene root or a node hidden inside a control skin. When the first ancestor listing
     * the node is skin plumbing (ScrollPane viewport, tab content region ...), the enclosing built-in control that
     * lists it as logical content wins.
     */
    fun parent(node: Node): Node? {
        if (node.scene != null && node.scene.root === node) return null
        var candidate: Node? = node.parent
        while (candidate != null) {
            if (children(candidate).contains(node)) return enclosingContentOwner(candidate, node) ?: candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun enclosingContentOwner(found: Node, node: Node): Node? {
        var c: Node? = found.parent
        while (c != null) {
            if (c is Control && c.javaClass.name.startsWith("javafx.scene.control.")) {
                return if (children(c).contains(node)) c else null
            }
            c = c.parent
        }
        return null
    }

    fun isLogical(node: Node): Boolean {
        if (node.scene == null) return false
        var cur: Node? = node
        while (cur != null) {
            if (cur.scene.root === cur) return true
            cur = parent(cur) ?: return false
        }
        return false
    }

    /** Nearest ancestor-or-self that is addressable in the logical tree (maps skin nodes to their control). */
    fun owner(node: Node): Node? {
        var cur: Node? = node
        while (cur != null && !isLogical(cur)) cur = cur.parent
        return cur
    }
}

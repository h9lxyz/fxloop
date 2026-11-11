package org.lab59.fxloop.agent

import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Accordion
import javafx.scene.control.ButtonBar
import javafx.scene.control.Cell
import javafx.scene.control.ComboBox
import javafx.scene.control.Control
import javafx.scene.control.IndexedCell
import javafx.scene.control.Labeled
import javafx.scene.control.ListView
import javafx.scene.control.MenuBar
import javafx.scene.control.Pagination
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumnBase
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
 * virtualized controls expose only their visible, non-empty cells (PRD 01 §5). User nodes a built-in control
 * hosts in its skin (graphics of labeled controls, cells, tabs and columns; a showing placeholder) are content too.
 */
internal object LogicalTree {
    fun isVirtualized(n: Node): Boolean = n is ListView<*> || n is TableView<*> || n is TreeView<*> || n is TreeTableView<*>

    fun children(node: Node): List<Node> {
        if (node !is Parent) return emptyList()
        // hosted content follows the primary content, so the paths of the primary content keep their indices
        return when {
            node is ScrollPane -> listOfNotNull(node.content)
            node is TabPane -> node.tabs.mapNotNull { it.content } + node.tabs.mapNotNull { hosted(it.graphic, node) }
            node is SplitPane -> node.items.toList()
            node is TitledPane -> listOfNotNull(node.content, hosted(node.graphic, node))
            node is ToolBar -> node.items.toList()
            node is ButtonBar -> node.buttons.toList()
            node is Accordion -> node.panes.toList()
            isVirtualized(node) -> visibleCells(node) + listOfNotNull(hosted(placeholder(node), node)) + columnGraphics(node)
            node is MenuBar -> descendantsOfType(node, Labeled::class.java)
            node is Cell<*> ->
                (if (node is TableRow<*> || node is TreeTableRow<*>) rowCells(node) else emptyList()) +
                    listOfNotNull(hosted(node.graphic, node), hosted(treeItemGraphic(node), node))
            node is Labeled && isBuiltIn(node) -> listOfNotNull(hosted(node.graphic, node))
            // the current page sits in the skin's `.page` stack panes (documented substructure)
            node is Pagination -> node.childrenUnmodifiable.filter { it.isVisible && "page" in it.styleClass }
                .flatMap { (it as? Parent)?.childrenUnmodifiable.orEmpty() }
            node is ComboBox<*> -> listOfNotNull(hosted(node.buttonCell?.graphic, node))
            isBuiltIn(node) -> emptyList()
            else -> node.childrenUnmodifiable.toList()
        }
    }

    private fun isBuiltIn(n: Node): Boolean = n is Control && n.javaClass.name.startsWith("javafx.scene.control.")

    /**
     * [n] when `owner`'s skin currently displays it: a descendant of `owner` with no hidden node in between (a
     * placeholder's region stays in the skin, hidden, while the control has items). [n]'s own `visible` flag does not
     * matter: a hidden graphic is still content, reported as `visible:false`.
     */
    private fun hosted(n: Node?, owner: Node): Node? {
        var p = n?.parent ?: return null
        while (p !== owner) {
            if (!p.isVisible) return null
            p = p.parent ?: return null
        }
        return n
    }

    /** A tree table row's skin draws the tree item's graphic itself, as a direct child next to the row's cells. */
    private fun treeItemGraphic(c: Cell<*>): Node? = (c as? TreeTableRow<*>)?.treeItem?.graphic?.takeIf { it.parent === c }

    private fun placeholder(n: Node): Node? = when (n) {
        is ListView<*> -> n.placeholder
        is TableView<*> -> n.placeholder
        is TreeTableView<*> -> n.placeholder
        else -> null
    }

    /** Header graphics of all (nested) columns, in column order. */
    private fun columnGraphics(n: Node): List<Node> {
        val columns: List<TableColumnBase<*, *>> = when (n) {
            is TableView<*> -> n.columns
            is TreeTableView<*> -> n.columns
            else -> return emptyList()
        }
        val out = ArrayList<Node>()
        fun add(cs: List<TableColumnBase<*, *>>) {
            for (c in cs) {
                hosted(c.graphic, n)?.let { out.add(it) }
                add(c.columns)
            }
        }
        add(columns)
        return out
    }

    /**
     * Items of a virtualized control without a rendered cell. The logical tree, and everything walking it (paths,
     * search, lint, snapshots), cannot see them until they are scrolled into view.
     */
    fun unrenderedItems(n: Node, kids: List<Node> = children(n)): Int {
        if (!isVirtualized(n)) return 0
        val items = NodeInfo.itemCount(n) ?: return 0
        return maxOf(0, items - kids.count { it is IndexedCell<*> })
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
            if (isBuiltIn(c)) {
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

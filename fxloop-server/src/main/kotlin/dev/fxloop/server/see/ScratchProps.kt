package dev.fxloop.server.see

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json

/**
 * The inspector's scratch property set (PRD 09): layout and property edits the developer tries live, each remembering
 * the value it replaced so it can be reverted. The page always sends the whole desired state ([replace]); the diff
 * against the current set decides what to write and what to revert. [setter] writes one property and returns the value
 * it replaced: `(handle, path, property, value) -> before` (null value clears the property or constraint).
 */
class ScratchProps(private val setter: (h: Int?, path: String?, property: String, value: String?) -> String?) {
    /** One edit: [before] is the value ahead of the first change, [after] the value now held. */
    data class Item(val h: Int?, val path: String?, val property: String, val before: String?, val after: String?) {
        val key: String get() = key(path, h, property)
    }

    /** A desired edit as sent by the page. */
    data class Want(val h: Int?, val path: String?, val property: String, val value: String?) {
        val key: String get() = key(path, h, property)
    }

    /** What [replace] would do: [set] new or changed edits, [revert] edits that are gone from the desired state. */
    data class Plan(val set: List<Want>, val revert: List<Item>)

    data class Error(val path: String?, val property: String, val message: String)
    data class Result(val items: List<Item>, val changed: List<Item>, val errors: List<Error>)

    private val items = LinkedHashMap<String, Item>()

    @Synchronized
    fun list(): List<Item> = items.values.toList()

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    /** The diff of [desired] against the current set (pure; nothing is written). */
    @Synchronized
    fun plan(desired: List<Want>): Plan {
        val want = LinkedHashMap<String, Want>()
        desired.forEach { want[it.key] = it }
        return Plan(want.values.filter { w -> items[w.key]?.after != w.value || w.key !in items }, items.values.filter { it.key !in want })
    }

    /**
     * Makes the set equal to [desired]: new and changed edits are written (a changed one keeps its original `before`),
     * removed ones are reverted to their `before`. A failing write is reported in `errors`; the rest still applies.
     */
    @Synchronized
    fun replace(desired: List<Want>): Result {
        val errors = ArrayList<Error>()
        val p = plan(desired)
        for (item in p.revert) {
            try {
                setter(item.h, item.path, item.property, item.before)
                items.remove(item.key)
            } catch (e: RuntimeException) {
                errors += Error(item.path, item.property, e.message ?: e.javaClass.simpleName)
            }
        }
        val changed = ArrayList<Item>()
        for (w in p.set) {
            try {
                val old = items[w.key]
                val before = setter(w.h, w.path, w.property, w.value)
                val item = Item(w.h, w.path, w.property, if (old != null) old.before else before, w.value)
                items[w.key] = item
                changed += item
            } catch (e: RuntimeException) {
                errors += Error(w.path, w.property, e.message ?: e.javaClass.simpleName)
            }
        }
        return Result(items.values.toList(), changed, errors)
    }

    /** Reverts every edit (failures are skipped: the node may be gone) and empties the set. */
    @Synchronized
    fun clear() {
        for (item in items.values) runCatching { setter(item.h, item.path, item.property, item.before) }
        items.clear()
    }

    /** Forgets the set without writing (the app restarted: its nodes are gone). */
    @Synchronized
    fun reset() {
        items.clear()
    }

    /** Drops [applied] edits (written into FXML) without reverting them. */
    @Synchronized
    fun drop(applied: Collection<Item>) {
        applied.forEach { items.remove(it.key) }
    }

    companion object {
        /** An edit is identified by its node's path when known (handles die with the app), else by handle. */
        fun key(path: String?, h: Int?, property: String): String = (path?.takeIf { it.isNotEmpty() } ?: "#$h") + "\u0000" + property

        fun toJson(items: List<Item>): ArrayNode = Json.array().also { a ->
            items.forEach { i ->
                a.addObject().apply {
                    i.h?.let { put("h", it) }
                    i.path?.let { put("path", it) }
                    put("property", i.property)
                    put("before", i.before)
                    put("after", i.after)
                }
            }
        }

        /** `{items:[…]}` as published to the page (SSE `props`) and returned by `GET /api/props`. */
        fun envelope(items: List<Item>): ObjectNode = Json.obj { set<JsonNode>("items", toJson(items)) }
    }
}

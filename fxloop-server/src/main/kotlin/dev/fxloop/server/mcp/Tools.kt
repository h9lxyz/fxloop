package dev.fxloop.server.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp

/** Text summary + structured content + optional PNG image blocks (dual-channel rule, PRD 00 §4.8). */
class ToolResult private constructor(val text: String, val structured: ObjectNode?, val isError: Boolean) {
    val images = ArrayList<ByteArray>()

    fun image(png: ByteArray) = apply { images += png }

    companion object {
        fun ok(text: String, structured: ObjectNode?) = ToolResult(text, structured, false)
        fun error(text: String, structured: ObjectNode?) = ToolResult(text, structured, true)
    }
}

/** Server-side tool failure, reported in-result as `isError:true` with cause + next step. */
class ToolException(val code: String, message: String, val hint: String?, val data: JsonNode? = null) :
    RuntimeException(message)

/** One MCP tool; [kind] drives the annotations. */
data class ToolDef(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    val kind: Kind,
    val handler: (ObjectNode) -> ToolResult,
) {
    enum class Kind { READ, WRITE, IDEMPOTENT_WRITE, DESTRUCTIVE }
}

/** One-line "next step" for each FCP error code (PRD 00 §4.4). */
object Hints {
    fun forCode(code: String): String? = when (code) {
        Fcp.Error.FX_TIMEOUT -> "the UI thread is blocked (see stack); fix the blocking code, then fx_restart_app"
        Fcp.Error.NODE_NOT_FOUND -> "re-locate the node with fx_find_nodes or fx_get_scene_graph"
        Fcp.Error.AMBIGUOUS_PATH -> "pick one of data.candidates and pass it as path"
        Fcp.Error.WINDOW_NOT_FOUND -> "list windows with fx_get_app_status"
        Fcp.Error.PROPERTY_NOT_FOUND -> "list available properties with fx_get_node_details"
        Fcp.Error.PROPERTY_BOUND -> "inspect the binding with fx_explain_property; write to its source"
        Fcp.Error.COERCION_FAILED -> "pass a value matching the property type (see fx_get_property.type)"
        Fcp.Error.UNSUPPORTED_TARGET -> "choose a different target or tool as described"
        Fcp.Error.REDEFINE_REJECTED -> "call fx_reload {allowRestart:true} or fx_restart_app {replay:true}"
        Fcp.Error.DISABLED -> "enable the feature as described or use the suggested alternative"
        Fcp.Error.BAD_REQUEST -> "fix the parameters as described"
        Fcp.Error.DISCONNECTED -> "the app went away; check fx_get_app_status and fx_get_logs"
        else -> null
    }
}

/** Tiny JSON-schema builder for tool input schemas. */
class Schema {
    private val props = LinkedHashMap<String, Any>()
    private val required = ArrayList<String>()

    fun str(name: String, d: String) = prop(name, mapOf("type" to "string", "description" to d))
    fun strEnum(name: String, d: String, vararg values: String) =
        prop(name, mapOf("type" to "string", "description" to d, "enum" to values.toList()))
    fun integer(name: String, d: String) = prop(name, mapOf("type" to "integer", "description" to d))
    fun integer(name: String, d: String, min: Int, max: Int) =
        prop(name, mapOf("type" to "integer", "description" to d, "minimum" to min, "maximum" to max))
    fun number(name: String, d: String) = prop(name, mapOf("type" to "number", "description" to d))
    fun bool(name: String, d: String) = prop(name, mapOf("type" to "boolean", "description" to d))
    fun any(name: String, d: String) = prop(name, mapOf("description" to d))
    fun strArray(name: String, d: String) =
        prop(name, mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to d))
    fun array(name: String, d: String, items: Map<String, Any>) =
        prop(name, mapOf("type" to "array", "items" to items, "description" to d))

    /** Standard node target params: handle | path | query (+ windowIndex for query scope). */
    fun target() = apply {
        integer("handle", "Session node handle (from fx_get_scene_graph / fx_find_nodes)")
        str("path", "Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn")
        str("query", "CSS selector that must match exactly one node, e.g. #addBtn")
        integer("windowIndex", "Window index (see fx_get_app_status.windows); default: focused window")
    }

    fun effects() = bool("effects", "Attach the scene-diff 'effects' of this action (default true)")

    fun required(vararg names: String) = apply { required += names }

    private fun prop(name: String, def: Map<String, Any>) = apply { props[name] = def }

    fun build(): Map<String, Any> = buildMap {
        put("type", "object")
        put("properties", props)
        if (required.isNotEmpty()) put("required", required)
        put("additionalProperties", false)
    }

    companion object {
        fun obj(build: Schema.() -> Unit = {}) = Schema().apply(build)
    }
}

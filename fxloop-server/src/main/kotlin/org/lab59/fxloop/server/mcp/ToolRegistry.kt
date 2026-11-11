package org.lab59.fxloop.server.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import java.util.Base64

/** Tool table shared by the MCP transport and in-process callers (tests, replay). */
class ToolRegistry {
    private val log = LoggerFactory.getLogger(ToolRegistry::class.java)
    private val tools = LinkedHashMap<String, ToolDef>()

    fun add(def: ToolDef) {
        tools[def.name] = def
    }

    fun all(): List<ToolDef> = tools.values.toList()

    /** Sees every call before (`done=false`) and after (`done=true`) it runs, e.g. for the dev bar's activity text. */
    @Volatile
    var observer: ((name: String, args: ObjectNode, done: Boolean) -> Unit)? = null

    /** Executes a tool with uniform error mapping: every failure becomes an in-result error with a next step. */
    fun call(name: String, args: ObjectNode? = null): ToolResult {
        val def = tools[name] ?: return ToolResult.error("Unknown tool $name", Json.obj { put("code", "UNKNOWN_TOOL") })
        val t0 = System.nanoTime()
        val a = args ?: Json.obj()
        notify(name, a, false)
        return try {
            def.handler(a.deepCopy())
        } catch (e: ToolException) {
            error(e.code, e.message.orEmpty(), e.hint, e.data)
        } catch (e: FcpException) {
            error(e.code, e.message.orEmpty(), Hints.forCode(e.code), e.data)
        } catch (e: RuntimeException) {
            log.warn("tool {} failed", name, e)
            error("INTERNAL", e.toString(), "check fx_get_logs and the server stderr", null)
        } finally {
            notify(name, a, true)
            log.debug("{} took {} ms", name, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun notify(name: String, args: ObjectNode, done: Boolean) {
        try {
            observer?.invoke(name, args, done)
        } catch (e: RuntimeException) {
            log.debug("tool observer failed: {}", e.toString())
        }
    }

    private fun error(code: String, message: String, hint: String?, data: JsonNode?): ToolResult {
        val s = Json.obj {
            put("code", code)
            put("message", message)
            hint?.let { put("hint", it) }
            data?.let { set<JsonNode>("data", it) }
        }
        val text = StringBuilder("$code: $message")
        hint?.let { text.append("\nNext: ").append(it) }
        data?.get("fxThreadStack")?.let { stack ->
            text.append("\nFX thread stack (top):")
            stack.take(25).forEach { text.append("\n  at ").append(it.asText()) }
        }
        return ToolResult.error(text.toString(), s)
    }

    fun toMcp(): List<SyncToolSpecification> = tools.values.map { def ->
        val annotations = McpSchema.ToolAnnotations.builder()
            .title(def.title)
            .readOnlyHint(def.kind == ToolDef.Kind.READ)
            .destructiveHint(def.kind == ToolDef.Kind.DESTRUCTIVE)
            .idempotentHint(def.kind == ToolDef.Kind.READ || def.kind == ToolDef.Kind.IDEMPOTENT_WRITE)
            .openWorldHint(false)
            .build()
        val tool = McpSchema.Tool.builder()
            .name(def.name)
            .title(def.title)
            .description(def.description)
            .inputSchema(def.inputSchema)
            .annotations(annotations)
            .build()
        SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, req -> toMcpResult(call(def.name, Json.MAPPER.valueToTree(req.arguments() ?: emptyMap<String, Any>()))) }
            .build()
    }

    companion object {
        fun toMcpResult(r: ToolResult): McpSchema.CallToolResult {
            val content = ArrayList<McpSchema.Content>()
            content += McpSchema.TextContent(null, r.text, null)
            r.images.forEach { content += McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(it), "image/png", null) }
            val structured = r.structured?.let { Json.MAPPER.convertValue(it, Map::class.java) }
            return McpSchema.CallToolResult(content, r.isError, structured, null)
        }
    }
}

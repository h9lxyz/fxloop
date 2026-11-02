package dev.fxloop.server.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/** MCP client config files in a workspace (list from fxloop-skills `fxloop-connect/references/mcp-clients.md`). */
internal enum class McpClient(val id: String, val label: String, val file: String, val toml: Boolean = false, val serversKey: String = "mcpServers") {
    CLAUDE_CODE("claude-code", "Claude Code", ".mcp.json"),
    CURSOR("cursor", "Cursor", ".cursor/mcp.json"),
    VSCODE("vscode", "VS Code", ".vscode/mcp.json", serversKey = "servers"),
    GEMINI("gemini", "Gemini CLI", ".gemini/settings.json"),
    CODEX("codex", "Codex CLI", ".codex/config.toml", toml = true);

    companion object {
        fun parse(s: String): McpClient? = entries.firstOrNull { it.id.equals(s.trim(), true) || it.name.equals(s.trim(), true) }
    }
}

/** What was found for one client in the workspace. [file] is null when there is no config file. */
internal data class ClientState(
    val client: McpClient,
    val file: Path?,
    val hasFxloop: Boolean,
    /** Jar / bootstrap paths named by the fxloop entry that do not exist (after expanding the common variables). */
    val missingPaths: List<String>,
)

internal object McpClients {
    private val mapper = ObjectMapper()

    fun scan(workspaces: List<Path>, home: Path): List<ClientState> = McpClient.entries.map { c ->
        val dir = workspaces.firstOrNull { Files.isRegularFile(it.resolve(c.file)) }
        if (dir == null) ClientState(c, null, false, emptyList())
        else inspect(c, dir.resolve(c.file), dir, home)
    }

    private fun inspect(c: McpClient, file: Path, workspace: Path, home: Path): ClientState {
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        val args: List<String>? = if (c.toml) tomlArgs(text) else jsonArgs(c, text)
        if (args == null) return ClientState(c, file, false, emptyList())
        val missing = args.mapNotNull { a ->
            val x = expand(a, workspace, home)
            // an unexpanded ${...} we do not know is not evidence of a problem
            if ("\${" in x || !(x.endsWith(".jar") || x.endsWith("FxloopBootstrap.java"))) null
            else if (Files.exists(Path.of(x))) null else x
        }
        return ClientState(c, file, true, missing)
    }

    /** The fxloop entry's `args`, or null when there is no fxloop entry. Empty list for an entry without args. */
    private fun jsonArgs(c: McpClient, text: String): List<String>? {
        val node: JsonNode = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        val entry = node.path(c.serversKey).path("fxloop")
        if (entry.isMissingNode || !entry.isObject) return null
        return entry.path("args").takeIf { it.isArray }?.map { it.asText() }.orEmpty()
    }

    private fun tomlArgs(text: String): List<String>? {
        val header = Regex("""(?m)^\s*\[mcp_servers\.["']?fxloop["']?]\s*$""").find(text) ?: return null
        val body = text.substring(header.range.last + 1).substringBefore("\n[")
        val arr = Regex("""(?m)^\s*args\s*=\s*\[([^\]]*)]""").find(body)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""["']([^"']*)["']""").findAll(arr).map { it.groupValues[1] }.toList()
    }

    private fun expand(s: String, workspace: Path, home: Path): String = s
        .replace("\${HOME}", home.toString()).replace("\${userHome}", home.toString()).replace("\${USERPROFILE}", home.toString())
        .replace("\${workspaceFolder}", workspace.toString()).replace("\${workspaceRoot}", workspace.toString())
        .let { if (it.startsWith("~/")) home.resolve(it.substring(2)).toString() else it }

    /** The config file text to add for [c]. Claude Code starts servers in the workspace; the others get the project dir. */
    fun snippet(c: McpClient, jar: String, projectDir: Path): String {
        fun q(s: String) = mapper.writeValueAsString(s)
        val ws = when (c) {
            McpClient.CURSOR, McpClient.VSCODE -> "\${workspaceFolder}"
            else -> projectDir.toString()
        }
        return when (c) {
            McpClient.CLAUDE_CODE -> """
                {
                  "mcpServers": {
                    "fxloop": { "command": "java", "args": ["-jar", ${q(jar)}] }
                  }
                }
            """.trimIndent()
            McpClient.CODEX -> """
                [mcp_servers.fxloop]
                command = "java"
                args = ["-jar", ${q(jar)}]
                env = { FXLOOP_PROJECT_DIR = ${q(ws)} }
            """.trimIndent()
            else -> """
                {
                  "${c.serversKey}": {
                    "fxloop": {${if (c == McpClient.VSCODE) "\n      \"type\": \"stdio\"," else ""}
                      "command": "java",
                      "args": ["-jar", ${q(jar)}],
                      "env": { "FXLOOP_PROJECT_DIR": ${q(ws)} }
                    }
                  }
                }
            """.trimIndent()
        }
    }
}

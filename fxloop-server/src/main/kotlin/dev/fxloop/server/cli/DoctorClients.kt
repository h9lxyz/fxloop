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

/**
 * What was found for one client. [file] is null when there is no config file. [scope] is set when the entry comes from
 * Claude Code's user file `~/.claude.json` (`claude mcp add` without `-s project`): `local` (this directory) or `user`.
 */
internal data class ClientState(
    val client: McpClient,
    val file: Path?,
    val hasFxloop: Boolean,
    /** Jar / bootstrap paths named by the fxloop entry that do not exist (after expanding the common variables). */
    val missingPaths: List<String>,
    val scope: String? = null,
) {
    /** Where the entry lives, for the summary line. */
    val where: String get() = if (scope == null) client.file else "~/.claude.json, $scope scope"
}

internal object McpClients {
    private val mapper = ObjectMapper()

    fun scan(workspaces: List<Path>, home: Path): List<ClientState> = McpClient.entries.map { c ->
        val dir = workspaces.firstOrNull { Files.isRegularFile(it.resolve(c.file)) }
        val state = if (dir == null) ClientState(c, null, false, emptyList()) else inspect(c, dir.resolve(c.file), dir, home)
        if (c == McpClient.CLAUDE_CODE && !state.hasFxloop) claudeUserConfig(workspaces, home) ?: state else state
    }

    private fun inspect(c: McpClient, file: Path, workspace: Path, home: Path): ClientState {
        val text = runCatching { Files.readString(file) }.getOrDefault("")
        val args: List<String>? = if (c.toml) tomlArgs(text) else jsonArgs(c, text)
        if (args == null) return ClientState(c, file, false, emptyList())
        return ClientState(c, file, true, missingPaths(args, workspace, home))
    }

    /**
     * `claude mcp add` writes `~/.claude.json`: `projects.<absolute workspace>.mcpServers` for the default local scope,
     * top-level `mcpServers` for `-s user`. Neither is in the workspace, so the README's own register line would
     * otherwise look undone.
     */
    private fun claudeUserConfig(workspaces: List<Path>, home: Path): ClientState? {
        val file = home.resolve(".claude.json")
        if (!Files.isRegularFile(file)) return null
        val node = runCatching { mapper.readTree(Files.readString(file)) }.getOrNull() ?: return null
        for (ws in workspaces) {
            val entry = node.path("projects").path(ws.toAbsolutePath().normalize().toString()).path("mcpServers").path("fxloop")
            if (entry.isObject) return ClientState(McpClient.CLAUDE_CODE, file, true, missingPaths(entryArgs(entry), ws, home), "local")
        }
        val user = node.path("mcpServers").path("fxloop")
        if (user.isObject) return ClientState(McpClient.CLAUDE_CODE, file, true, missingPaths(entryArgs(user), workspaces.first(), home), "user")
        return null
    }

    private fun entryArgs(entry: JsonNode): List<String> = entry.path("args").takeIf { it.isArray }?.map { it.asText() }.orEmpty()

    private fun missingPaths(args: List<String>, workspace: Path, home: Path): List<String> = args.mapNotNull { a ->
        val x = expand(a, workspace, home)
        // an unexpanded ${...} we do not know is not evidence of a problem
        if ("\${" in x || !(x.endsWith(".jar") || x.endsWith("FxloopBootstrap.java"))) null
        else if (Files.exists(Path.of(x))) null else x
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

    /** `<home>/.fxloop/x.jar` as `<variable>/.fxloop/x.jar` (forward slashes, which Java accepts on Windows too); else [jar] unchanged. */
    fun homeRelative(jar: String, home: Path?, variable: String): String {
        if (home == null) return jar
        val h = home.toAbsolutePath().normalize()
        val j = runCatching { Path.of(jar).toAbsolutePath().normalize() }.getOrNull() ?: return jar
        if (!j.startsWith(h)) return jar
        return variable + "/" + h.relativize(j).toString().replace('\\', '/')
    }

    private fun expand(s: String, workspace: Path, home: Path): String = s
        .replace("\${HOME}", home.toString()).replace("\${userHome}", home.toString()).replace("\${USERPROFILE}", home.toString())
        .replace("\${workspaceFolder}", workspace.toString()).replace("\${workspaceRoot}", workspace.toString())
        .let { if (it.startsWith("~/")) home.resolve(it.substring(2)).toString() else it }

    /**
     * The config file text to add for [c]. Claude Code starts servers in the workspace; the others get the project dir.
     * Clients that expand a home variable get the jar relative to it when it lives under [home], so the file holds no
     * machine-specific path (`${HOME}`, `${USERPROFILE}` on Windows, `${userHome}` for Cursor and VS Code); Codex and
     * Gemini get the absolute path. [java] is `java` or an absolute java executable, rewritten the same way.
     */
    fun snippet(c: McpClient, jar: String, projectDir: Path, home: Path? = null, windows: Boolean = false, java: String = "java"): String {
        fun q(s: String) = mapper.writeValueAsString(s)
        val ws = when (c) {
            McpClient.CURSOR, McpClient.VSCODE -> "\${workspaceFolder}"
            else -> projectDir.toString()
        }
        fun local(path: String) = when (c) {
            McpClient.CLAUDE_CODE -> homeRelative(path, home, if (windows) "\${USERPROFILE}" else "\${HOME}")
            McpClient.CURSOR, McpClient.VSCODE -> homeRelative(path, home, "\${userHome}")
            else -> path
        }
        val jarPath = local(jar)
        val cmd = if (java == "java") java else local(java)
        return when (c) {
            McpClient.CLAUDE_CODE -> """
                {
                  "mcpServers": {
                    "fxloop": { "command": ${q(cmd)}, "args": ["-jar", ${q(jarPath)}] }
                  }
                }
            """.trimIndent()
            McpClient.CODEX -> """
                [mcp_servers.fxloop]
                command = ${q(cmd)}
                args = ["-jar", ${q(jar)}]
                env = { FXLOOP_PROJECT_DIR = ${q(ws)} }
            """.trimIndent()
            else -> buildString {
                appendLine("{")
                appendLine("  \"${c.serversKey}\": {")
                appendLine("    \"fxloop\": {")
                if (c == McpClient.VSCODE) appendLine("      \"type\": \"stdio\",")
                appendLine("      \"command\": ${q(cmd)},")
                appendLine("      \"args\": [\"-jar\", ${q(jarPath)}],")
                appendLine("      \"env\": { \"FXLOOP_PROJECT_DIR\": ${q(ws)} }")
                appendLine("    }")
                appendLine("  }")
                append("}")
            }
        }
    }
}

# Registering the FXLoop MCP server

The server is the jar at `~/.fxloop/fxloop-server.jar` (downloaded in step 0). Every client gets the same command,
`java -jar <absolute jar path>`. The path is per machine, so tell the developer the file shouldn't be committed. Use a
client's home variable only where it is known to expand it (Claude Code, Cursor, VS Code); elsewhere write the absolute
path. The server runs in the workspace when the client starts it there; otherwise add `FXLOOP_PROJECT_DIR`.

Merge into an existing file; never drop other servers. If an `fxloop` entry already exists with the same command,
leave it.

## Which client

You know which agent you are running in; use that. If you can't tell, look for an existing config from the table in
the workspace. If there is still no answer, ask the developer which agent they use. Don't write configs for several
clients "just in case".

| Client | File (workspace root unless noted) | Reload after writing |
|---|---|---|
| Claude Code | `.mcp.json` | Restart the session (or `/mcp` → fxloop → Reconnect). Approve the project server when asked |
| Cursor | `.cursor/mcp.json` | Settings → MCP → enable/refresh `fxloop`, or reload the window |
| VS Code (Copilot agent mode) | `.vscode/mcp.json` | Start the server from the `mcp.json` code lens, or reload the window |
| Codex CLI | `.codex/config.toml` (trusted projects), else `~/.codex/config.toml` | Restart `codex` |
| Gemini CLI | `.gemini/settings.json` | Restart `gemini` |
| Anything else | The client's stdio MCP server config | Its own reload |

## Claude Code — `.mcp.json`

Claude Code starts project servers in the workspace and expands `${VAR}`.

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["-jar", "${HOME}/.fxloop/fxloop-server.jar"]
    }
  }
}
```

Windows: `HOME` is often unset. Use `${USERPROFILE}` instead, or the absolute path
(`C:\\Users\\me\\.fxloop\\fxloop-server.jar` in JSON; quote paths with spaces on the command line).

## Cursor — `.cursor/mcp.json`

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["-jar", "${userHome}/.fxloop/fxloop-server.jar"],
      "env": { "FXLOOP_PROJECT_DIR": "${workspaceFolder}" }
    }
  }
}
```

## VS Code — `.vscode/mcp.json`

```json
{
  "servers": {
    "fxloop": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "${userHome}/.fxloop/fxloop-server.jar"],
      "env": { "FXLOOP_PROJECT_DIR": "${workspaceFolder}" }
    }
  }
}
```

## Codex CLI — `config.toml`

No variables: absolute paths (`/home/me` stands for the home directory, `/Users/<name>` on macOS). The project file
only applies when the project is trusted. If you write the user file, the entry is pinned to this workspace, so say so
in the report.

```toml
[mcp_servers.fxloop]
command = "java"
args = ["-jar", "/home/me/.fxloop/fxloop-server.jar"]
env = { FXLOOP_PROJECT_DIR = "/home/me/work/my-app" }
```

## Gemini CLI — `.gemini/settings.json`

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["-jar", "/home/me/.fxloop/fxloop-server.jar"],
      "env": { "FXLOOP_PROJECT_DIR": "/home/me/work/my-app" }
    }
  }
}
```

## Any other client

A stdio server: command `java`, arguments `-jar <absolute path to ~/.fxloop/fxloop-server.jar>`. Optional environment:
`FXLOOP_PROJECT_DIR=<app project>` when the client doesn't start the server in it.

The Claude Code setup is tested with this repository. The other formats follow each client's documentation. If a
client ignores the file, check its current docs for the config location and fix the entry rather than guessing
further.

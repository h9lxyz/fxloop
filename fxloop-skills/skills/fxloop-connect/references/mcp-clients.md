# Registering the FXLoop MCP server

The server is started by `~/.fxloop/FxloopBootstrap.java` (copied there in step 0), which finds the server jar and runs
it in the workspace. Every client gets the same command, `java <home>/.fxloop/FxloopBootstrap.java`, plus the workspace
when the client doesn't start servers in it. Use the client's home/workspace variables where it has them, so the file
holds no machine-specific paths and can be committed. Where it has none, write absolute paths and tell the developer
the file is per-machine.

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
      "args": ["${HOME}/.fxloop/FxloopBootstrap.java"]
    }
  }
}
```

Windows: `HOME` is often unset. Use `${USERPROFILE}` instead.

## Cursor — `.cursor/mcp.json`

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["${userHome}/.fxloop/FxloopBootstrap.java"],
      "env": { "FXLOOP_WORKSPACE": "${workspaceFolder}" }
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
      "args": ["${userHome}/.fxloop/FxloopBootstrap.java"],
      "env": { "FXLOOP_WORKSPACE": "${workspaceFolder}" }
    }
  }
}
```

## Codex CLI — `config.toml`

No variables: absolute paths. The project file only applies when the project is trusted. If you write the user
file, the entry is pinned to this workspace, so say so in the report.

```toml
[mcp_servers.fxloop]
command = "java"
args = ["/home/me/.fxloop/FxloopBootstrap.java"]
env = { FXLOOP_WORKSPACE = "/home/me/work/my-app" }
```

## Gemini CLI — `.gemini/settings.json`

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["/home/me/.fxloop/FxloopBootstrap.java"],
      "env": { "FXLOOP_WORKSPACE": "/home/me/work/my-app" }
    }
  }
}
```

## Any other client

A stdio server: command `java`, argument `<home>/.fxloop/FxloopBootstrap.java`, environment
`FXLOOP_WORKSPACE=<workspace>`. Optional: `FXLOOP_SERVER_JAR=<path to fxloop-server-*-all.jar>` to skip jar lookup.

The Claude Code setup is tested with this repository. The other formats follow each client's documentation. If a
client ignores the file, check its current docs for the config location and fix the entry rather than guessing
further.

# Decision: skills ship as plain skill folders, not a Claude Code plugin

**Status:** accepted 2026-10-31. Supersedes the packaging in PRD 06 §3.1 and §7 (plugin, `.mcp.json` in the plugin,
marketplace).

## Context

Stage D shipped the skills as a Claude Code plugin with a marketplace entry. The plugin's `.mcp.json` started the
server, and skill names came from the plugin namespace (`/fxloop:connect`). That ties FXLoop to one agent and one
install mechanism, while the skills themselves are agent-neutral: they call MCP tools and edit files.

## Decision

- Skills live in `fxloop-skills/skills/fxloop-*/`, one self-contained folder each, in the open `SKILL.md` format.
  Installing means copying folders into an agent's skills directory.
- The `fxloop-` name prefix replaces the plugin namespace.
- Shared references have one source (`fxloop-skills/references/`). `sync-references.sh` copies each into the skills
  that link it, so a single skill folder works on its own. A test fails on stale copies.
- `fxloop-connect` registers the MCP server itself. It copies `FxloopBootstrap.java` to `~/.fxloop/`, runs
  `--install` to make sure a server jar exists, and writes the MCP config for the agent it runs in (Claude Code,
  Cursor, VS Code, Codex CLI, Gemini CLI, or generic stdio). Configs use the client's home/workspace variables
  where it has them.
- The FXLoop version the skill pins lives in the `fxloop-connect` frontmatter (`metadata.fxloop-version`). A test
  ties it to the server version.

## Consequences

- One extra step on first use: the agent has to reload its MCP servers after `fxloop-connect` writes the config. The
  skill stops there and resumes on "continue".
- No plugin hooks (PRD 06 §7 mentioned an optional SessionStart hook; it was never built).
- Only the Claude Code config is exercised in this repository. The other clients' formats follow their docs and are
  flagged as such in `mcp-clients.md`.

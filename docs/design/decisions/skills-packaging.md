# Decision: skills ship as plain skill folders, not a Claude Code plugin

**Status:** accepted 2026-10-31. The bootstrap part is superseded by PRD 11 (see the note below). Supersedes the packaging in PRD 06 §3.1 and §7 (plugin, `.mcp.json` in the plugin,
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

## Update 2026-11-02: bootstrap superseded

[PRD 11](../prd/11-prd-onboarding.md) removes `FxloopBootstrap.java`. `fxloop-connect` now downloads
`fxloop-server.jar` from the GitHub release (`edge` for a SNAPSHOT) into `~/.fxloop/`, runs `doctor`, and writes an
MCP entry of `java -jar <absolute jar path>`. Nothing else goes into the home directory. Skills are installed from
the `fxloop-skills.zip` release asset. The decision above (plain folders, one reference source, version pinned in the
frontmatter) stands; the original text is kept for the record.

## Update 2026-11-06: `npx skills add`, one command per step

The quickstart installs the skills with `npx skills add h9lxyz/fxloop -g -y`, the
[`skills` CLI](https://github.com/vercel-labs/skills). It clones `main` and finds the plain folders by its recursive
search, so nothing is published and no layout change is needed. FXLoop can't have its own `npx fxloop`: that npm name
belongs to another package. `-g -y` matters: without it the CLI asks four questions, and its skill picker starts empty,
so a user could leave out `fxloop-connect`. The CLI needs Node.js 22.20 or newer and collects anonymous usage data
unless `DISABLE_TELEMETRY=1` is set, so `fxloop-skills.zip` stays a release asset and INSTALL.md stays the route that
needs only a JDK.

A skill installed from `main` says `fxloop-version: 1.0.0-SNAPSHOT` even when the jar is a release, because only the
release zip stamps a real version. `fxloop-connect` therefore takes every `dev.fxloop` coordinate from the version the
installed jar reports, which is also what `--seed-local-repo` writes, and seeds and adds `mavenLocal()` on every version
until FXLoop is on Central.

The docs give one command per step instead of bash and PowerShell twins, replacing PRD 11 principle 4. `curl.exe`,
`--create-dirs` and a quoted `"$HOME/.fxloop/fxloop-server.jar"` work the same in bash, zsh and PowerShell.
`InstallGuideTest` keeps the twins from coming back.

## Update 2026-11-07: the build plugin is opt-in

`fxloop-connect` no longer adds the build plugin and the guard by default. The server's plugin-free prepare builds the
same launch descriptor as the Maven plugin and applies its bundled copy of the Gradle plugin through an init script, so
the agent loses nothing. Only `fxloop:run` / `fxloopRun`, starting the app yourself, need the plugin in the build.
Until FXLoop is on Central and the Plugin Portal, a committed `id("dev.fxloop")` fails every Gradle build on a machine
that hasn't run `--seed-local-repo`, which is a poor first change to make in someone's project.

Connect now leaves the build files alone, writes nothing to `~/.m2`, and pins an ambiguous main class in
`fxloop.properties`. On request ("I want to run the app myself", step 6) it seeds the local repository and adds the
plugin and the guard. It also copies a pinned main class into the plugin config, because the run goals don't read
`fxloop.properties`. The guard comes with the plugin because it only matters once a `dev.fxloop` artifact is in the
build. This changes PRD 11 path B and acceptance A-O2: the default diff no longer contains the plugin and the guard.
The connect evals follow, with a fifth eval for the opt-in request.

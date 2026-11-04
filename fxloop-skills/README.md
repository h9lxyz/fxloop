# FXLoop skills

Agent skills that use FXLoop to **improve the UI and UX of your own JavaFX app** (Java or Kotlin, Maven or Gradle)
(PRD 06). They are plain `SKILL.md` folders in the open skills format, not tied to one agent or a plugin system. They
aren't for developing FXLoop itself. What each skill does and returns: [skills overview](../docs/skills.md).

## Install

Unzip the skill folders into your agent's skills directory. Each folder is self-contained, so you can also keep only
the ones you want. The zip is a release asset.

```sh
# bash / zsh
curl -fsSL -o /tmp/fxloop-skills.zip \
  https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-skills.zip \
  && unzip -o /tmp/fxloop-skills.zip -d ~/.claude/skills/      # Claude Code, all projects
```

```powershell
# PowerShell
Invoke-WebRequest -OutFile "$env:TEMP\fxloop-skills.zip" `
  https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-skills.zip
Expand-Archive -Force "$env:TEMP\fxloop-skills.zip" "$HOME\.claude\skills\"
```

For one project only, unzip into `.claude/skills/` instead. Other agents that read `SKILL.md` folders: the same zip
into that agent's skills directory. From a clone: copy `fxloop-skills/skills/fxloop-*`.

The server jar needs no separate step: `fxloop-connect` downloads it to `~/.fxloop/fxloop-server.jar`.

## First run

In your app's project, ask: **"connect FXLoop to this project and show me the app"**. `fxloop-connect`:

1. registers the FXLoop MCP server for the agent you're using (Claude Code, Cursor, VS Code, Codex CLI, Gemini CLI,
   or any stdio MCP client), as `java -jar ~/.fxloop/fxloop-server.jar`, after downloading the jar and checking your JDK;
2. adds the FXLoop build plugin and a production guard, and an `## FXLoop` section in `AGENTS.md`/`CLAUDE.md`;
3. asks you to reload the agent's MCP servers once, since no agent loads a new server mid-session (everything the
   server reads at startup is written first, so it is one reload);
4. after you say "continue", launches the app and shows a screenshot.

After that, just describe what you want. The skills trigger from plain requests:

| Skill | PRD | Ask it… |
|---|---|---|
| `fxloop-connect` | S0 | "connect FXLoop and show me the app" |
| `fxloop-ui-review` | S1 | "review this screen — what looks off?" |
| `fxloop-fix-layout` | S2 | "the panel doesn't resize", "buttons are squashed" |
| `fxloop-style` | S3 | "make it look modern", "add dark mode", "fix the hover state" |
| `fxloop-accessibility` | S4 | "is this keyboard friendly? run a contrast check" |
| `fxloop-responsive` | S5 | "does it work on 1024×640? in German? RTL?" |
| `fxloop-flow-polish` | S6 | "this flow feels clunky", "it freezes when I refresh" |
| `fxloop-forms-dialogs` | S7 | "clean up this dialog", "Escape doesn't close it" |
| `fxloop-consistency` | S8 | "make all screens look the same" |
| `fxloop-lock-in` | S9 | "make sure this doesn't regress" |

## How the MCP server starts

The MCP config that `fxloop-connect` writes runs `java -jar <absolute path to ~/.fxloop/fxloop-server.jar>` in the
workspace. The path is per machine. If the jar is missing or the JDK is wrong, `java -jar ~/.fxloop/fxloop-server.jar
doctor` says what to fix. Project-specific server settings go in an optional `fxloop.properties` at the workspace root
(keys are the server flags, e.g. `project-dir=app`, `app-args=--seed`). See the configuration doc.

## Layout (for contributors)

| Path | What |
|---|---|
| `skills/fxloop-*/` | The installable skills. `fxloop-connect` also carries the MCP config reference and the build-file snippets |
| `references/` | Shared reference docs: evidence rule, JavaFX CSS, layout containers, FX thread, accessibility, FXML, TestFX, Kotlin notes |
| `sync-references.sh` | Copies each shared reference into the skills that link it. Run it after editing `references/` |
| `evals/` | One eval file per skill (skill-creator format plus a `setup` field naming the sample app and flags) and `triggers.json` (should/shouldn't trigger phrasings) |

`SkillsLayoutTest` (server module) fails when a bundled copy is stale, a skill links outside its own folder, or the
versions in `fxloop-connect` drift from the server. The eval demo bed is `sample-apps/fxloop-showcase-app`, whose
answer key is its `ISSUES.md`; don't give that file to the agent.

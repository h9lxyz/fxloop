# FXLoop

FXLoop is an MCP server plus an in-JVM agent that gives coding agents (Claude Code and any other MCP client) eyes, ears and hands on a running JavaFX application: screenshots and scene-graph inspection, clicks and typing, logs and UI exceptions, "why" explanations for properties, styles and layout (rule-level CSS cascade with `file:line`, override suggestions, and a browser CSS inspector opened from the dev bar's **Inspect CSS** button or `fx_open_inspector`), and a fast edit loop (CSS and FXML live reload, Java HotSwap, restart with journal replay). The tooling is written in Kotlin; the apps it drives are ordinary Java/JavaFX projects built with Maven or Gradle.

**Status:** `1.0.0-SNAPSHOT`, built from source (not yet on Maven Central). See [docs/05-implementation-tracking.md](docs/05-implementation-tracking.md) for per-feature status. Publishing, macOS/Windows CI runs and soak tests are CI/human steps.

## Quickstart (agent skills)

```sh
./mvnw install -DskipTests                            # until FXLoop is on Maven Central
cp -r fxloop-skills/skills/fxloop-* ~/.claude/skills/ # or your agent's skills directory
```

Then, in your app's project, ask your agent: "connect FXLoop to this project and show me the app". The `fxloop-connect` skill registers the MCP server for your agent (Claude Code, Cursor, VS Code, Codex CLI, Gemini CLI or any stdio MCP client), adds the build plugin and a production guard, asks you to reload the agent's MCP servers once, then launches your app and shows a screenshot. To explore the app yourself first, run it with `mvn fxloop:run` (or `gradle fxloopRun`): it starts with the dev bar, and your agent connects to it whenever you ask ([details](docs/configuration.md#running-the-app-yourself)). Nine more [skills](fxloop-skills/README.md) cover UI review, layout, styling, accessibility, responsiveness, flows, forms, consistency and regression tests; they trigger from plain requests.

## Quickstart (manual MCP setup)

```sh
./mvnw install -DskipTests                       # builds fxloop-server/target/fxloop-server-1.0.0-SNAPSHOT-all.jar
claude mcp add fxloop -e FXLOOP_PROJECT_DIR=/abs/path/to/your-app -- \
  java -jar /abs/path/to/fxloop/fxloop-server/target/fxloop-server-1.0.0-SNAPSHOT-all.jar
```

Or put this in your project's `.mcp.json` (see [.mcp.json.example](.mcp.json.example)):

```json
{ "mcpServers": { "fxloop": {
    "command": "java",
    "args": ["-jar", "/abs/path/to/fxloop/fxloop-server/target/fxloop-server-1.0.0-SNAPSHOT-all.jar"],
    "env": { "FXLOOP_PROJECT_DIR": "/abs/path/to/your-app" } } } }
```

Then ask your agent to call `fx_get_app_status`: the first call builds the app, launches it with the agent and connects. If your project has no `fxloop-maven-plugin`/`dev.fxloop` Gradle plugin, set `FXLOOP_MAIN_CLASS` ([configuration](docs/configuration.md)).

**jbang:** `jbang-catalog.json` defines the alias `fxloop` (`dev.fxloop:fxloop-server:1.0.0-SNAPSHOT:all`). After `./mvnw install`, `jbang fxloop@<path-to-this-repo-or-catalog-url>` runs the server; use `"command": "jbang", "args": ["fxloop@..."]` in `.mcp.json`.

## Modules

| Module | What it is |
|---|---|
| `fxloop-protocol` | FCP (FXLoop Control Protocol) DTOs, NDJSON codec, error codes, token/handshake helpers, `launch.json` schema (Kotlin) |
| `fxloop-agent` | In-JVM agent (`-javaagent`/attach): scene graph, input, reload, FXML registry (Kotlin; ByteBuddy, Jackson and Kotlin stdlib relocated) |
| `fxloop-dev` | Optional dev dependency for IDE-launched apps (`FxLoopDev.install/track/load`); see [decision](docs/decisions/fxml-load-registry.md) |
| `fxloop-server` | The stdio MCP server (shaded `-all.jar`); builds, launches, supervises, reloads (Kotlin) |
| `fxloop-maven-plugin` | `fxloop:prepare` writes `target/fxloop/launch.json` ([README](fxloop-maven-plugin/README.md)) |
| `fxloop-gradle-plugin` | Gradle plugin `dev.fxloop`, task `fxloopPrepare` ([README](fxloop-gradle-plugin/README.md)); standalone Gradle build |
| `fxloop-skills` | Agent skills S0–S9 (plain `SKILL.md` folders), MCP bootstrap, shared references, skill evals ([README](fxloop-skills/README.md)) |
| `sample-apps/*` | Test targets: `fxloop-sample-app` (contacts), `fxloop-gauntlet-app` (dialogs, menus, tabs...), `fxloop-sample-app-gradle`, `fxloop-sample-app-kotlin` (Kotlin twin, Gradle Kotlin DSL); `fxloop-showcase-app` (standalone Kotlin/Maven helpdesk with planted UI/UX issues for the skills demo, answer key in its `ISSUES.md`) |
| `fxloop-acceptance` | End-to-end suite driving the server over MCP (`-Pacceptance`) |

## Documentation

[Configuration](docs/configuration.md) · [Tools reference](docs/tools.md) · [Security](docs/security.md) · [Limitations](docs/limitations.md) · [Troubleshooting](docs/troubleshooting.md) · [CI templates](docs/ci/README.md) · [Monocle headless](docs/ci/monocle.md) · Design: [overview](docs/00-overview.md), [PoC](docs/01-prd-poc.md), [MVP](docs/02-prd-mvp.md), [production](docs/03-prd-production.md), [visual feedback](docs/04-prd-visual-feedback.md), [skills](docs/06-prd-skills.md), [live dev loop](docs/07-prd-live-dev.md), [CSS inspector](docs/08-prd-css-inspector.md) · Decisions: [FXML load registry](docs/decisions/fxml-load-registry.md), [Kotlin](docs/decisions/kotlin.md), [skills packaging](docs/decisions/skills-packaging.md)

Build the docs site with `mkdocs serve` (see `mkdocs.yml`).

## License

Apache-2.0, see [LICENSE](LICENSE).

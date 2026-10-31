# FXLoop plugin for Claude Code

Skills that use FXLoop to **improve the UI and UX of your own JavaFX app** (Java or Kotlin, Maven or Gradle), plus the
FXLoop MCP server they drive (PRD 06). This plugin isn't for developing FXLoop itself.

## Install

```
/plugin marketplace add fxloop/fxloop      # or a local clone: /plugin marketplace add /path/to/fxloop
/plugin install fxloop@fxloop
```

Then, in your app's project: `/fxloop:connect`. It adds the FXLoop build plugin and a production guard, launches the
app and shows a first screenshot, with no manual build-file or MCP edits. Requirements: a JDK (17+) and the
project's Maven/Gradle wrapper.

## Skills

| Command | PRD | Ask it… |
|---|---|---|
| `/fxloop:connect` | S0 | "connect FXLoop and show me the app" |
| `/fxloop:ui-review` | S1 | "review this screen — what looks off?" |
| `/fxloop:fix-layout` | S2 | "the panel doesn't resize", "buttons are squashed" |
| `/fxloop:style` | S3 | "make it look modern", "add dark mode", "fix the hover state" |
| `/fxloop:accessibility` | S4 | "is this keyboard friendly? run a contrast check" |
| `/fxloop:responsive` | S5 | "does it work on 1024×640? in German? RTL?" |
| `/fxloop:flow-polish` | S6 | "this flow feels clunky", "it freezes when I refresh" |
| `/fxloop:forms-dialogs` | S7 | "clean up this dialog", "Escape doesn't close it" |
| `/fxloop:consistency` | S8 | "make all screens look the same" |
| `/fxloop:lock-in` | S9 | "make sure this doesn't regress" |

The skills also trigger from plain requests like these, without the slash command. Shared knowledge lives in
`references/`: the evidence rule, JavaFX CSS, layout containers, the FX thread, accessibility, FXML, TestFX, Kotlin
notes and the tool reference, which is generated from the server's registry (`--tool-reference`; a unit test keeps
it in sync).

## How the MCP server starts

`.mcp.json` runs `java bin/FxloopBootstrap.java` (a single-file Java program). It finds the server jar via
`FXLOOP_SERVER_JAR` → `~/.fxloop/` → the local Maven repository → `jbang` → the project's own wrapper
(`mvnw dependency:copy`, or `gradlew` on a throwaway resolve build) into `~/.fxloop/`, then runs it in the project
directory. Project-specific server settings go in an optional `fxloop.properties` at the workspace root (keys are the
server flags, e.g. `project-dir=app`, `app-args=--seed`). See the configuration doc.

## Evals

Each skill has `evals/evals.json` (skill-creator format plus a `setup` field naming the sample app and flags), with
tasks against `sample-apps/*`. The demo bed is `fxloop-showcase-app`, whose answer key is its `ISSUES.md`; don't give
that file to the agent. `evals/triggers.json` holds should-trigger and should-not-trigger phrasings per skill.

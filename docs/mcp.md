# The FXLoop MCP server

FXLoop is a stdio [MCP](https://modelcontextprotocol.io) server that gives a coding agent eyes, ears and hands on the
JavaFX app you are building. It builds your project, launches the app with a small in-JVM agent, and exposes about
three dozen `fx_*` tools: screenshots and the scene graph, clicks and typing, logs and UI exceptions, "why" answers for
layout, CSS and bindings, live reload of CSS, FXML and code, and TestFX export of what was clicked.

It works with any MCP client: Claude Code, Cursor, VS Code (Copilot agent mode), Codex CLI, Gemini CLI and the rest.
This page is the overview. Every parameter of every tool is in the generated [tool reference](tools.md).

```
┌──────────────┐  stdio (MCP)  ┌─────────────────────┐  loopback TCP, token   ┌───────────────────────────┐
│ your agent   │◄─────────────►│ fxloop-server       │◄──────────────────────►│ your app's JVM            │
│ (MCP client) │               │ builds, launches,   │                        │  fxloop-agent (-javaagent)│
└──────────────┘               │ reloads, diffs,     │      spawns, watches   │  scene graph, robot input,│
                               │ journals, exports   │───────────────────────►│  reload, error capture    │
                               └─────────────────────┘                        └───────────────────────────┘
```

The MCP session belongs to the server, not to the app, so the app can crash, restart or be hot-swapped without the
agent losing its connection. Your app needs no FXLoop dependency: the agent is injected with `-javaagent`.

## What the agent can do

### Run and connect

| Tool | For |
|---|---|
| [`fx_get_app_status`](tools.md#fx_get_app_status) | The usual first call. Builds and launches the app if needed, or connects to the one you already run. Reports windows, mode, control and anything that needs your action |
| [`fx_start_app`](tools.md#fx_start_app) · [`fx_restart_app`](tools.md#fx_restart_app) · [`fx_stop_app`](tools.md#fx_stop_app) | Lifecycle. `restart{replay:true}` clicks its way back to the same screen afterwards |
| [`fx_connect_existing`](tools.md#fx_connect_existing) | Connect to an app started by `mvn fxloop:run`, `gradle fxloopRun` or an IDE launch with `FxLoopDev.install()` |
| [`fx_attach`](tools.md#fx_attach) | Attach to any local JavaFX JVM by pid (inspection and input only) |
| [`fx_set_control_mode`](tools.md#fx_set_control_mode) | `agent` (real mouse and keyboard) or `shared` (synthetic events, your mouse stays yours) |

### See

| Tool | For |
|---|---|
| [`fx_take_screenshot`](tools.md#fx_take_screenshot) | A window or node, optionally with numbered markers whose legend maps to node paths. Every frame is saved to disk and its path returned |
| [`fx_compare_screenshots`](tools.md#fx_compare_screenshots) | Pixel diff of two frames: `changedPct`, changed regions, a verdict and a diff image |
| [`fx_capture_states`](tools.md#fx_capture_states) | One node rendered as `none`, `hover`, `focused`, `pressed` and `disabled` in a single strip |
| [`fx_get_screenshot`](tools.md#fx_get_screenshot) | Re-attach an earlier frame without re-capturing |
| [`fx_get_picked`](tools.md#fx_get_picked) | The node *you* clicked in pick mode, with its details and a screenshot |

### Read the scene

| Tool | For |
|---|---|
| [`fx_get_scene_graph`](tools.md#fx_get_scene_graph) | A compact JSON tree with ids, style classes, text, state and screen bounds, budgeted by depth and size |
| [`fx_find_nodes`](tools.md#fx_find_nodes) | Find by CSS selector, visible text or type, across all windows and popups |
| [`fx_get_property`](tools.md#fx_get_property) · [`fx_get_node_details`](tools.md#fx_get_node_details) | One property (and whether it is bound), or all of them |
| [`fx_describe_at`](tools.md#fx_describe_at) | Which node is at this screen point |

### Ask why

| Tool | For |
|---|---|
| [`fx_explain_layout`](tools.md#fx_explain_layout) | Why is it there and that size: bounds, insets, min/pref/max, grow, margins, anchors, grid cell, plus hints such as "below pref width" |
| [`fx_explain_style`](tools.md#fx_explain_style) | Which CSS rule won for each property (selector and `file:line`), which rules lost and why, and what blocks an override |
| [`fx_suggest_override`](tools.md#fx_suggest_override) | The smallest CSS rule that would make a value stick, ready to paste |
| [`fx_explain_property`](tools.md#fx_explain_property) | Why a property has its value: the binding dependency tree and the CSS origin |
| [`fx_open_inspector`](tools.md#fx_open_inspector) | Opens a DevTools-style CSS inspector in *your* browser, next to the running app |
| [`fx_wait_for_prompt`](tools.md#fx_wait_for_prompt) | Waits for a request you type into the inspector's *Ask agent* box, with the node and declaration it is about |
| [`fx_reply_to_prompt`](tools.md#fx_reply_to_prompt) | Shows the agent's answer (or a progress note) under your prompt in the inspector |

### Find problems

| Tool | For |
|---|---|
| [`fx_lint_visual`](tools.md#fx_lint_visual) | Truncated text, overlapping siblings, zero-size or off-screen nodes, low contrast (WCAG ratio) |
| [`fx_lint_fxml`](tools.md#fx_lint_fxml) | FXML vs. controller mismatches (missing `fx:id` fields, wrong types, missing handlers, bad imports), with line numbers, before the app even starts |
| [`fx_get_ui_errors`](tools.md#fx_get_ui_errors) | Uncaught exceptions on the FX Application Thread, deduplicated |
| [`fx_get_logs`](tools.md#fx_get_logs) | The app's stdout/stderr and, optionally, its `java.util.logging` records |

### Drive

| Tool | For |
|---|---|
| [`fx_click_node`](tools.md#fx_click_node) | Click a node or a point. Tabs and scroll panes are brought into view first |
| [`fx_type_keys`](tools.md#fx_type_keys) | Key presses and shortcuts by `KeyCode` name (`TAB`, `ESCAPE`, `ENTER`, with modifiers such as `SHORTCUT`) |
| [`fx_set_text`](tools.md#fx_set_text) | Fill a text field, independent of the keyboard layout |
| [`fx_set_property`](tools.md#fx_set_property) | Set any writable property, including `window.width` and `window.height` to resize the stage |
| [`fx_eval_js`](tools.md#fx_eval_js) | Run JavaScript in a `WebView` (off unless `FXLOOP_WEBVIEW=true`) |

### Apply edits

| Tool | For |
|---|---|
| [`fx_reload`](tools.md#fx_reload) | Apply whatever changed since the last reload, through the cheapest tier that can do it (below) |
| [`fx_reload_fxml`](tools.md#fx_reload_fxml) | Force a live swap of one FXML document |
| [`fx_set_watch_mode`](tools.md#fx_set_watch_mode) | Auto-apply on save for *your* edits: CSS only (default), everything, or off |

### Lock it in

| Tool | For |
|---|---|
| [`fx_get_journal`](tools.md#fx_get_journal) | The ordered log of every successful action, the agent's and yours |
| [`fx_export_test`](tools.md#fx_export_test) | Turn the journal into a JUnit 5 + TestFX test with assertions synthesized from what each action changed |

## Ideas that run through every tool

**Stable node addresses.** Tools take a node as a session `handle`, a stable `path` such as
`Stage[0]/Scene/BorderPane/HBox/Button#saveBtn`, a CSS `query` that must match exactly one node, or `picked:true` for
the node you picked. Paths survive restarts, so a replay or an exported test finds the same button again.

**Every action reports its effects.** Clicks, typing, property writes and reloads return a scene diff: which nodes
appeared or went away and which properties changed. The agent learns that the dialog opened, or that nothing happened,
without taking another screenshot.

**Paths always, pixels on demand.** Every screenshot is saved under `target/fxloop/screens/` (Maven) or
`build/fxloop/screens/` (Gradle) with an `index.html` gallery, and its path is in the result, so you can open exactly
what the agent saw. Images are returned to the model only when it asks for them, which keeps long sessions cheap.
`FXLOOP_VIEWER=<port>` adds a live, token-protected browser view, handy for headless and remote runs.

**Bounded, explained output.** Trees, lists and logs are paged and budgeted with explicit truncation markers. Errors
come back as results with a one-line cause and a next step; an FX-thread timeout includes the FX thread's stack.

## Reload tiers

`fx_reload` looks at what changed and takes the cheapest route that works:

| You changed | What happens |
|---|---|
| A stylesheet | Re-applied live in milliseconds, with a before/after pixel diff (`changedPct: 0` usually means the selector doesn't match) |
| An FXML file | The document is reloaded and swapped in place. Selection, scroll, text, tabs and focus are carried over |
| A method body (Java or Kotlin) | Compiled and HotSwapped into the running JVM. Compile errors come back as data; nothing is swapped |
| A code-built view wrapped in `FxLoopDev.view(...)` | Rebuilt in place after the swap, with view state carried over |
| A new field, method or class shape, or the build file | Restart, then replay the journal back to the same screen |

Restarts wait while you are using the app. The details, including JetBrains Runtime enhanced redefinition, are in
[limitations](limitations.md#hot-reload-hotswap) and the [fxloop-dev API](configuration.md#live-dev-loop-fxloop-dev-api).

## You and the agent in the same app

- **Dev bar.** A thin overlay on the app window shows the mode, who is driving, the last reload ("CSS applied · 12 ms")
  and anything that needs you ("Restart needed · pom.xml changed" with a **Restart** button). It is invisible to the
  tools, so it never shows up in screenshots or layout measurements.
- **Pick.** Click **Pick** (or press `Ctrl+Shift+F12`), click a node, and ask "why is *this* grey?". The agent reads
  it with `fx_get_picked`, and every node tool accepts `picked:true`.
- **Open CSS inspector.** The bar's **Open CSS inspector** button opens the browser inspector: live scene tree, the
  cascade with `file:line` for each rule, and layout boxes. Under `fxloop:run` / `fxloopRun` (or with
  `FXLOOP_INSPECTOR_EDIT=true`) you can also try changes live: edit a value, untick a declaration or add a rule. They go
  into a scratch stylesheet in the running app. *Apply to file…* shows which lines of which stylesheet would change and,
  once you confirm, writes them there, reloads the CSS and clears the scratch; *Ask agent to apply* hands them to the
  agent instead ([configuration](configuration.md)).
- **Ask agent (prototype).** The inspector's *Ask agent* box sends a request about the selected node; click a property
  name first to scope it to that declaration (selector and `file:line` go along). An agent that is listening
  (`fx_wait_for_prompt`, a long poll it calls in a loop) receives your text, the node's path and a screenshot of it,
  plus any unapplied scratch CSS, and answers with `fx_reply_to_prompt`; answers appear under your prompt. The box
  shows whether an agent is listening right now; prompts sent while none is wait in the queue (in memory, last 50).
- **Watching the agent.** The bar always says what the agent is doing ("Agent: clicking Button#save") and whether you
  have access. Before it clicks through the UI like a person, the agent can lock your input with `fx_lock_input`: your
  clicks and keys in the app are dropped, inspector editing pauses, and the bar reads "you're watching". **Take over**
  (bar or inspector) ends the lock at once; it also expires on its own.
- **Shared control.** With `FXLOOP_CONTROL=auto` (the default), the first time you touch the app the agent switches to
  synthetic events and stops moving your mouse. Your own clicks and typing go into the journal too (password fields
  never), so `fx_export_test` can turn a flow *you* clicked through into a test.

## Ways to run

| Mode | Started by | Live reload | Restart |
|---|---|---|---|
| Launched | The server, on the first tool call | CSS, FXML, HotSwap | Yes, with replay |
| Developer-run | You, with `mvn fxloop:run` or `gradle fxloopRun`. The agent connects on its first call | CSS, FXML, HotSwap | Re-run the goal |
| Embedded | Your IDE run config, with `FxLoopDev.install()` (`fxloop-dev` dependency) | CSS, FXML, HotSwap | From your IDE |
| Attached | `fx_attach` by pid | No | No |
| Headless | `FXLOOP_HEADLESS=monocle` or `xvfb` on a launched app | CSS, FXML, HotSwap | Yes, with replay |

Headless runs make FXLoop usable in devcontainers, over SSH and in CI. There the screenshot *is* the app's face. See
[CI templates](ci/README.md) and [Monocle](ci/monocle.md).

## Setup

The [`fxloop-connect` skill](skills.md#fxloop-connect) registers the server for your agent, adds the build plugin and a
production guard, and launches the app. To do it by hand, point your client at the server jar and your project:

```sh
./mvnw install -DskipTests     # in this repository, until FXLoop is on Maven Central
claude mcp add fxloop -e FXLOOP_PROJECT_DIR=/abs/path/to/your-app -- \
  java -jar /abs/path/to/fxloop/fxloop-server/target/fxloop-server-1.0.0-SNAPSHOT-all.jar
```

Config snippets for Cursor, VS Code, Codex CLI, Gemini CLI and generic stdio clients are in the
[MCP clients reference](../fxloop-skills/skills/fxloop-connect/references/mcp-clients.md). The build plugins
([Maven](../fxloop-maven-plugin/README.md), [Gradle](../fxloop-gradle-plugin/README.md)) describe the launch so you
don't have to. Without one, set `FXLOOP_MAIN_CLASS`.

Common knobs, all optional (full list in [configuration](configuration.md)):

| Variable | Use |
|---|---|
| `FXLOOP_PROJECT_DIR` | The app project, if it is not the server's working directory |
| `FXLOOP_APP_ARGS` | Program arguments, e.g. `--seed` for demo data |
| `FXLOOP_HEADLESS` | `monocle` or `xvfb` for runs without a display |
| `FXLOOP_WATCH` | `css-only` (default), `all` or `off`: what your own saves apply live |
| `FXLOOP_EXTERNAL_COMPILE` | Let your IDE or `gradle -t classes` compile; FXLoop only hot-swaps the output |
| `FXLOOP_VIEWER` | Port for the live screenshot viewer |

## Safety

FXLoop is a local developer tool for one trusted user. The app-side agent listens on loopback only and requires a
random per-run token. Tool file paths are confined to the project directory, `fx_eval_js` is off by default, `fx_attach`
is audit-logged, and there is no telemetry. The agent is a no-op without its launch arguments, and
`-Dfxloop.enabled=false` disables it outright. Keep `fxloop-dev` out of shipped artifacts; the connect skill adds a
guard that fails the production build if it leaks. Details: [security](security.md).

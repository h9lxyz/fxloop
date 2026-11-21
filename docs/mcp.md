# The FXLoop MCP server

FXLoop is a stdio [MCP](https://modelcontextprotocol.io) server that gives a coding agent eyes, ears and hands on the
JavaFX app you are building. It builds your project, launches the app with a small in-JVM agent (or connects to an app
you started yourself), and exposes 40 `fx_*` tools (39 by default, plus `fx_eval_js` when `FXLOOP_WEBVIEW=true`).

It works with any MCP client: Claude Code, Cursor, VS Code (Copilot agent mode), Codex CLI, Gemini CLI and the rest.
This page is the overview; every parameter of every tool is in the generated [tool reference](tools.md).

[Setup](#setup) · [Typical loop](#typical-loop) · [Tools by job](#tools-by-job) · [Key concepts](#key-concepts) · [Reload tiers](#reload-tiers) · [Dev bar & inspector](#dev-bar-and-inspector) · [Ways to run](#ways-to-run) · [Safety](#safety)

<p align="center">
  <img src="assets/fxloop-how-it-works.svg" width="840" alt="Diagram: your agent talks MCP over stdio to the FXLoop server, which launches your JavaFX app with fxloop-agent, or connects to it when you start it yourself and run attach. The MCP session ends at the server, so your app can crash or restart while your agent stays connected.">
</p>

The MCP session belongs to the server, not to the app, so the app can crash, restart or be hot-swapped without the
agent losing its connection. Your app needs no FXLoop dependency: the agent is injected with `-javaagent` (or loaded on
demand by `attach`).

## Setup

The fastest route is the [`fxloop-connect` skill](skills.md#fxloop-connect), which downloads the server jar, registers
it for your agent and launches the app without changing your build files. Without skills, any agent can follow
[INSTALL.md](../INSTALL.md) unattended.

To register it manually:

1. Download the server jar once (on Windows PowerShell, type `curl.exe`):

   ```sh
   curl -fsSL --create-dirs -o "$HOME/.fxloop/fxloop-server.jar" https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-server-all.jar
   ```

2. Print the exact config for your client (with the right `java` path filled in) and add it to the project:

   ```sh
   java -jar "$HOME/.fxloop/fxloop-server.jar" doctor --client=<claude-code|cursor|vscode|codex|gemini>
   ```

   For Claude Code, you can register it directly with:

   ```sh
   claude mcp add -s project fxloop -- java -jar '${HOME}/.fxloop/fxloop-server.jar'
   ```

Config snippets for all supported clients are in the
[MCP clients reference](../fxloop-skills/skills/fxloop-connect/references/mcp-clients.md). Add
`-e FXLOOP_PROJECT_DIR=/abs/path/to/your-app` if the client does not start the server in your project directory, then
start a new agent session (or reload MCP servers) so the `fx_*` tools appear.

No build plugin is needed: the server works out the launch itself, and uses the
[Maven](../fxloop-maven-plugin/README.md) or [Gradle](../fxloop-gradle-plugin/README.md) plugin when the build has one.
Pin an ambiguous main class with `main-class=<fqcn>` in `fxloop.properties`. If something doesn't work, run
`java -jar "$HOME/.fxloop/fxloop-server.jar" doctor` in the project.

Common knobs, all optional (full list in [configuration](configuration.md)):

| Variable | Use |
|---|---|
| `FXLOOP_PROJECT_DIR` | The app project, if it is not the server's working directory |
| `FXLOOP_APP_ARGS` | Program arguments, e.g. `--seed` for demo data |
| `FXLOOP_HEADLESS` | `monocle` or `xvfb` for runs without a display |
| `FXLOOP_WATCH` | `css-only` (default), `all` or `off`: what your own saves apply live |
| `FXLOOP_EXTERNAL_COMPILE` | Let your IDE or `gradle -t classes` compile; FXLoop only hot-swaps the output |
| `FXLOOP_JAVA_HOME` | The JDK that runs the app. Point it at a JetBrains Runtime and new methods, lambdas and fields hot-swap instead of restarting |
| `FXLOOP_VIEWER` | Port for the live screenshot viewer |

## Typical loop

Most agent sessions follow a four-step loop:

1. **Start or connect:** call `fx_get_app_status` to build and launch the app (or attach to one you already run).
2. **See and diagnose:** capture frames (`fx_take_screenshot`), inspect the scene (`fx_get_scene_graph`, `fx_find_nodes`), lint (`fx_lint_visual`, `fx_lint_css`, `fx_lint_fxml`), and ask *why* (`fx_explain_layout`, `fx_explain_style`, `fx_explain_property`).
3. **Act and live-reload:** drive flows (`fx_click_node`, `fx_set_text`, `fx_type_keys`), edit CSS/FXML/Java/Kotlin files, and apply them with `fx_reload`.
4. **Verify and lock in:** check pixel diffs (`fx_compare_screenshots`), pseudo-states (`fx_capture_states`), and UI errors (`fx_get_ui_errors`), then export regressions as a TestFX test (`fx_export_test`).

## Tools by job

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
| [`fx_lint_css`](tools.md#fx_lint_css) | Unused rules, misspelt classes/ids and parse errors in the open windows' stylesheets, with `file:line` |
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
| [`fx_lock_input`](tools.md#fx_lock_input) | Drop your clicks and keys for a moment while the agent clicks through a flow. The dev bar says why and offers **Take over**; the lock expires on its own |

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

## Key concepts

- **Stable node addresses.** Tools take a node as a session `handle`, a stable `path` such as
  `Stage[0]/Scene/BorderPane/HBox/Button#saveBtn`, a CSS `query` that must match exactly one node, or `picked:true` for
  the node you picked. Paths survive restarts, so a replay or an exported test finds the same button again.
- **Every action reports its effects.** Clicks, typing, property writes and reloads return a scene diff: which nodes
  appeared or went away and which properties changed. The agent learns that the dialog opened, or that nothing happened,
  without taking another screenshot.
- **Paths always, pixels on demand.** Every screenshot is saved under `target/fxloop/screens/` (Maven) or
  `build/fxloop/screens/` (Gradle) with an `index.html` gallery, and its path is in the result, so you can open exactly
  what the agent saw. Images are returned to the model only when it asks for them, which keeps long sessions cheap.
  `FXLOOP_VIEWER=<port>` adds a live, token-protected browser view, handy for headless and remote runs.
- **Bounded, explained output.** Trees, lists and logs are paged and budgeted with explicit truncation markers. Errors
  come back as results with a one-line cause and a next step; an FX-thread timeout includes the FX thread's stack.

## Reload tiers

`fx_reload` looks at what changed and takes the cheapest route that works:

| You changed | What happens |
|---|---|
| A stylesheet | Re-applied live in milliseconds, with a before/after pixel diff (`changedPct: 0` usually means the selector doesn't match) |
| An FXML file | The document is reloaded and swapped in place. Selection, scroll, text, tabs and focus are carried over |
| A method body (Java or Kotlin) | Compiled and HotSwapped into the running JVM. Compile errors come back as data; nothing is swapped |
| A code-built view wrapped in `FxLoopDev.view(...)` | Rebuilt in place after the swap, with view state carried over |
| A new method, lambda or field | HotSwapped too when the app runs on JetBrains Runtime (detected from `FXLOOP_JAVA_HOME`, `FXLOOP_JBR` overrides). Elsewhere: restart |
| A class shape change, or the build file | Restart, then replay the journal back to the same screen |

A restart relaunches only the JVM when the reload has already compiled and copied every edit into the output directory;
build-file changes, deletions and edits no reload has seen run the full build first. The same applies to
`fx_restart_app` and the dev bar's **Restart**. Restarts wait while you are using the app. An edit that watch mode
already applied is not compiled again by `fx_reload`. The details are in [limitations](limitations.md#hot-reload-hotswap)
and the [fxloop-dev API](configuration.md#live-dev-loop-fxloop-dev-api).

## Dev bar and inspector

### Dev bar and Pick

- **Dev bar:** a thin overlay on the app window shows the mode, who is driving, the last reload (`CSS applied · 12 ms`),
  and anything that needs you (`Restart needed · pom.xml changed` with a **Restart** button). It is invisible to the
  tools, so it never shows up in screenshots or layout measurements. Drag it to move the window, double-click it to
  maximize; on a narrow window overflow buttons move into `⋯`, and `⌄` collapses it to a pill.
- **Pick:** click **Pick** (or press `Ctrl+Shift+F12`), click a node, and ask your agent *"why is this grey?"*. The
  agent reads it with `fx_get_picked`, and every node tool accepts `picked:true`.

### Browser CSS inspector

The bar's **Open CSS inspector** button opens a browser DevTools view next to your running app with the live scene
tree, the CSS cascade (`file:line` per rule), and layout box metrics.

Under `fxloop:run` / `fxloopRun` or [`attach`](attach.md) (or with `FXLOOP_INSPECTOR_EDIT=true`):

- **Try changes live:** edit a property value, untick a declaration, or add a rule into a scratch stylesheet in the
  running app.
- **Save to source:** *Apply to file…* previews which stylesheet lines will change, writes them on confirm, reloads the
  CSS, and clears the scratch; you can also route each rule to a different stylesheet or hand them off with
  *Ask agent to apply* ([configuration](configuration.md)).
- **Library stylesheets:** rules loaded from a jar or external module show their origin; *Map to source…* points FXLoop
  at the source file so changes can be saved there too.

### Ask agent (prototype)

The inspector's *Ask agent* box sends a request about the selected node; click a property name first to scope it to that
declaration (selector and `file:line` go along).

- An agent listening in a loop (`fx_wait_for_prompt`) receives your prompt, the node path, a screenshot, and any
  unapplied scratch CSS, and responds with `fx_reply_to_prompt` directly under your prompt.
- The box shows whether an agent is listening right now; prompts sent while none is wait in an in-memory queue (last
  50).
- Under `fxloop:run` / `fxloopRun` or `attach`, the inspector helper forwards your prompts to the agent session's server
  even before that session attaches to the app; once it connects, the page switches to the agent session's inspector on
  the same node.

### Shared control and input locking

- **Watching the agent:** the bar always shows what the agent is doing (`Agent: clicking Button#save`). Before a
  multi-step flow, the agent can call `fx_lock_input` to drop your clicks and keys and pause inspector editing while the
  bar reads *"you're watching"*. Click **Take over** (bar or inspector) to end the lock immediately, or wait for it to
  expire.
- **Shared control:** with `FXLOOP_CONTROL=auto` (the default), touching the app switches the agent to synthetic events
  and stops it from moving your mouse. Your own clicks and typing also enter the journal (password fields are never
  recorded), so `fx_export_test` can turn a flow *you* clicked through into a test.

## Ways to run

| Mode | Started by | Live reload | Restart |
|---|---|---|---|
| Launched | The server, on the first tool call | CSS, FXML, HotSwap | Yes, with replay |
| Developer-run | You, with `mvn fxloop:run` or `gradle fxloopRun`. The agent connects on its first call | CSS, FXML, HotSwap | Re-run the goal |
| Developer-run via `attach` | You, any way you like, then [`fxloop-server.jar attach`](attach.md). The agent connects on its first call | CSS, HotSwap; FXML after a restart | You restart; `attach` follows |
| Embedded | Your IDE run config, with `FxLoopDev.install()` (`fxloop-dev` dependency) | CSS, FXML, HotSwap | From your IDE |
| Attached | `fx_attach` by pid | No | No |
| Headless | `FXLOOP_HEADLESS=monocle` or `xvfb` on a launched app | CSS, FXML, HotSwap | Yes, with replay |

In the developer-run and embedded modes the build tool or IDE owns the JVM, so the agent cannot restart it. Once that
app has exited (window closed, build stopped), the session notices and `fx_start_app` launches a supervised copy, so a
structural change no longer leaves the agent stuck.

Headless runs make FXLoop usable in devcontainers, over SSH and in CI. There the screenshot *is* the app's face. See
[CI templates](ci/README.md) and [Monocle](ci/monocle.md).

## Safety

FXLoop is a local developer tool for one trusted user. The app-side agent listens on loopback only and requires a
random per-run token. Tool file paths are confined to the project directory, `fx_eval_js` is off by default, `fx_attach`
and the `attach` command are audit-logged, and there is no telemetry. The agent is a no-op without its launch arguments, and
`-Dfxloop.enabled=false` disables it outright. Keep `fxloop-dev` out of shipped artifacts: the guard in
[INSTALL.md, "Add the build plugin"](../INSTALL.md#add-the-build-plugin-optional) fails the production build if it leaks,
and the connect skill adds it along with the build plugin. Details: [security](security.md).

# FXLoop tool reference

_Generated from the tool registry (`java -jar fxloop-server-*-all.jar --tool-reference`). Do not edit by hand._

| Tool | Kind | Summary |
|---|---|---|
| [`fx_get_app_status`](#fx_get_app_status) | read | App status |
| [`fx_set_control_mode`](#fx_set_control_mode) | idempotent_write | Set control mode |
| [`fx_start_app`](#fx_start_app) | write | Start app |
| [`fx_restart_app`](#fx_restart_app) | destructive | Restart app |
| [`fx_stop_app`](#fx_stop_app) | destructive | Stop app |
| [`fx_get_logs`](#fx_get_logs) | read | Get logs |
| [`fx_get_ui_errors`](#fx_get_ui_errors) | read | Get UI errors |
| [`fx_get_scene_graph`](#fx_get_scene_graph) | read | Get scene graph |
| [`fx_find_nodes`](#fx_find_nodes) | read | Find nodes |
| [`fx_get_property`](#fx_get_property) | read | Get property |
| [`fx_get_node_details`](#fx_get_node_details) | read | Get node details |
| [`fx_describe_at`](#fx_describe_at) | read | Describe at point |
| [`fx_click_node`](#fx_click_node) | write | Click node |
| [`fx_type_keys`](#fx_type_keys) | write | Type keys |
| [`fx_set_text`](#fx_set_text) | write | Set text |
| [`fx_set_property`](#fx_set_property) | write | Set property |
| [`fx_get_journal`](#fx_get_journal) | read | Get journal |
| [`fx_export_test`](#fx_export_test) | write | Export TestFX test |
| [`fx_take_screenshot`](#fx_take_screenshot) | read | Take screenshot |
| [`fx_get_picked`](#fx_get_picked) | read | Get picked node |
| [`fx_get_screenshot`](#fx_get_screenshot) | read | Get screenshot |
| [`fx_compare_screenshots`](#fx_compare_screenshots) | read | Compare screenshots |
| [`fx_lint_visual`](#fx_lint_visual) | read | Lint visual |
| [`fx_capture_states`](#fx_capture_states) | write | Capture pseudo-states |
| [`fx_reload`](#fx_reload) | idempotent_write | Reload |
| [`fx_reload_fxml`](#fx_reload_fxml) | idempotent_write | Reload FXML |
| [`fx_set_watch_mode`](#fx_set_watch_mode) | idempotent_write | Set watch mode |
| [`fx_lint_fxml`](#fx_lint_fxml) | read | Lint FXML |
| [`fx_connect_existing`](#fx_connect_existing) | write | Connect existing |
| [`fx_attach`](#fx_attach) | write | Attach to JVM |
| [`fx_explain_property`](#fx_explain_property) | read | Explain property |
| [`fx_explain_style`](#fx_explain_style) | read | Explain style |
| [`fx_explain_layout`](#fx_explain_layout) | read | Explain layout |
| [`fx_eval_js`](#fx_eval_js) | write | Evaluate JavaScript |

## fx_get_app_status

Application state (starting/running/exited/stopped), pid, uptime, mode, FCP connection, showing windows (index, title, type STAGE|POPUP|DIALOG) and the FXML load registry. Auto-starts a never-started app.

_No parameters._

## fx_set_control_mode

Who may drive the UI: agent (the agent owns mouse and keyboard; human input during an action is a conflict) or shared (human and agent interleave; agent clicks/keys use synthetic events and restarts wait for the developer). Ask the developer before switching to agent while they are present. Returns the status.

| Param | Type | Required | Description |
|---|---|---|---|
| `mode` | string (agent\|shared) | yes | agent or shared |

## fx_start_app

Build (compile + launch descriptor) and launch the app with the fxloop agent; waits for the FCP handshake. Error if already running.

_No parameters._

## fx_restart_app

Kill and relaunch the app (MCP session survives). replay=true re-executes the action journal afterwards (restart-and-restore). Stage geometry is restored automatically.

| Param | Type | Required | Description |
|---|---|---|---|
| `replay` | boolean |  | Replay the action journal after restart (default false) |

## fx_stop_app

Stop the app (destroy, escalate to force-kill after 3 s).

_No parameters._

## fx_get_logs

Tail of the app's process stdout/stderr (source=process) and/or captured in-app java.util.logging records (source=app, needs FXLOOP_APP_LOGS=true).

| Param | Type | Required | Description |
|---|---|---|---|
| `lines` | integer |  | Lines to return (default 100, max 1000) |
| `stream` | string (both\|stdout\|stderr) |  | Process stream (default both) |
| `source` | string (process\|app\|both) |  | process | app | both (default process) |

## fx_get_ui_errors

Uncaught exceptions captured on the JavaFX Application Thread (class, message, top frames, dedupCount). Pass sinceSeq=lastSeq to poll for new ones.

| Param | Type | Required | Description |
|---|---|---|---|
| `sinceSeq` | integer |  | Return errors with seq > sinceSeq (default 0) |
| `limit` | integer |  | Max errors (default 20) |

## fx_get_scene_graph

Bounded JSON of the logical scene graph of a window (or subtree): h (handle), path, type, fxId, styleClass, text, value, visible (only when false), disabled/focused (only when true), boundsScreen, kids. Output is budgeted (depth, nodes, ~56 KB) with {truncated:true} markers. Virtualized controls report itemCount and visibleCells only. Skin internals of built-in controls are omitted.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `rootHandle` | integer |  | Subtree root handle |
| `maxDepth` | integer |  | Depth budget (default 12) |
| `maxNodes` | integer |  | Node budget (default 400) |

## fx_find_nodes

Find nodes by CSS selector (query, e.g. '#addBtn', '.button'), case-insensitive text contains (text), or simple type name (type). Searches all showing windows incl. popups unless windowIndex is set. Paginated.

| Param | Type | Required | Description |
|---|---|---|---|
| `query` | string |  | CSS selector |
| `text` | string |  | Text contains (case-insensitive) |
| `type` | string |  | Simple class name, e.g. Button |
| `windowIndex` | integer |  | Restrict to one window |
| `limit` | integer |  | Page size (default 20) |
| `offset` | integer |  | Page offset |

## fx_get_property

Read a JavaFX property of a node: value, type, bound (true if bound — writes would fail), writable.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `name` | string | yes | Property name, e.g. text, disable, value, prefWidth |

## fx_get_node_details

Reflective dump of every property of a node (name, one-line value, type, bound, styleable). Paginated.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `limit` | integer |  | Page size (default 60) |
| `offset` | integer |  | Page offset |

## fx_describe_at

Deepest addressable node at a screen point (from a screenshot or boundsScreen): path, ancestors, bounds.

| Param | Type | Required | Description |
|---|---|---|---|
| `x` | number | yes | Screen x |
| `y` | number | yes | Screen y |

## fx_click_node

Click a node (handle/path/query) or screen point (x,y). mode=robot (default in agent control) moves the real mouse via javafx Robot — picking, hover and focus behave like a user; enclosing tabs/scroll panes are brought into view first. mode=event (default when control is shared) fires synthetic events (no real input, leaves the developer's mouse alone). Returns effects (scene diff), mode and conflicts (human input during the action).

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `x` | number |  | Screen x (instead of a target) |
| `y` | number |  | Screen y |
| `button` | string (primary\|secondary\|middle) |  | Mouse button (default primary) |
| `clickCount` | integer |  | 1 or 2 (default 1) |
| `mode` | string (robot\|event) |  | robot (default) or event |
| `effects` | boolean |  | Attach the scene-diff 'effects' of this action (default true) |

## fx_type_keys

Press key codes (FX KeyCode names: ENTER, TAB, ESCAPE, DOWN, A, DIGIT1 …; modifiers SHIFT, CONTROL, ALT, META, SHORTCUT). mode=robot (default in agent control) uses the real keyboard via Robot; mode=event (default when control is shared) fires synthetic key events. For free text use fx_set_text (layout-independent).

| Param | Type | Required | Description |
|---|---|---|---|
| `keys` | array | yes | Key presses in order |
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `mode` | string (robot\|event) |  | robot or event (default follows the control mode) |
| `effects` | boolean |  | Attach the scene-diff 'effects' of this action (default true) |

## fx_set_text

Programmatically set the text of a TextInputControl (TextField/TextArea/editable ComboBox). clearFirst=false appends.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `text` | string | yes | Text to set |
| `clearFirst` | boolean |  | Replace (default true) or append |
| `effects` | boolean |  | Attach the scene-diff 'effects' of this action (default true) |

## fx_set_property

Write a JavaFX property (coerces strings to boolean/number/enum/Color/Insets). Bound properties fail with PROPERTY_BOUND — write to the binding's source instead.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `name` | string | yes | Property name |
| `value` | any | yes | New value |
| `effects` | boolean |  | Attach the scene-diff 'effects' of this action (default true) |

## fx_get_journal

The ordered log of successful actions (replayed after restarts and exported as tests). Each entry has source: agent (an MCP action tool) or human (the developer's own input in the app; redacted:true for password fields, whose value is never recorded).

| Param | Type | Required | Description |
|---|---|---|---|
| `limit` | integer |  | Newest N entries (default 50) |

## fx_export_test

Generate a JUnit 5 + TestFX test from the journal (optionally a seq range) into src/test/java. Assertions are synthesized from recorded effects; unclear spots get TODO(fxloop) markers. Generated draft — review it.

| Param | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Test name, e.g. AddContact → AddContactGeneratedTest |
| `package` | string |  | Java package (default: main class package) |
| `fromSeq` | integer |  | First journal seq |
| `toSeq` | integer |  | Last journal seq |
| `sources` | array |  | Which entries to export: agent, human (default both) |
| `write` | boolean |  | Write files (default true); false returns the source only |

## fx_take_screenshot

Capture a window or node. Always persisted to target/fxloop/screens/<seq>-<label>.png (path in result — cite it to the human) and returned as an image unless includeImage=false. scale=2 on a node supersamples for legible small text. annotate=interactive|all|<css> overlays numbered markers; legend maps markers → node paths.

| Param | Type | Required | Description |
|---|---|---|---|
| `target` | string (window\|node) |  | window (default) or node |
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `maxWidth` | integer |  | Max width in px (default 1200, window hard cap 1600) |
| `scale` | number |  | 0.25–3.0 render scale (node shots) |
| `label` | string |  | File label |
| `annotate` | string |  | none (default) | interactive | all | <css selector> |
| `includeImage` | boolean |  | Return the image block (default true) |
| `settleMs` | integer |  | Wait before capture (default 150) |

## fx_get_picked

The node the developer picked in the running app (dev bar pick button / pick hotkey): target, property details, a short explanation and a screenshot of the node (persisted like fx_take_screenshot). Other tools accept picked:true as a target. Fails with PICK_STALE when nothing recent is picked — ask the developer to pick again.

| Param | Type | Required | Description |
|---|---|---|---|
| `includeImage` | boolean |  | Return the image block (default true) |

## fx_get_screenshot

Re-attach a persisted frame (screenshot, diff composite, state strip) as an image: by seq, path, or latest.

| Param | Type | Required | Description |
|---|---|---|---|
| `seq` | integer |  | Frame seq |
| `path` | string |  | PNG path inside the project |
| `latest` | boolean |  | Newest frame |
| `includeImage` | boolean |  | Return the image block (default true) |

## fx_compare_screenshots

Pixel diff of two frames (seq or path; after may be capture:true for a fresh capture). Returns changedPct, clustered regions, verdict none|minor|major and a persisted diff composite (diffPath). Image only on request.

| Param | Type | Required | Description |
|---|---|---|---|
| `before` | any | yes | seq or path |
| `after` | any |  | seq or path |
| `capture` | boolean |  | Capture a fresh 'after' frame |
| `threshold` | number |  | Per-pixel RGB distance 0..1 (default 0.06) |
| `includeImage` | boolean |  | Return the diff image (default false) |

## fx_lint_visual

Heuristic visual checks on a window or subtree: textTruncated, siblingOverlap, zeroSizeVisible, outOfScene, lowContrast (WCAG ratio vs nearest solid background). Pair with an annotated screenshot.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `checks` | array |  | Subset of checks (default all) |
| `limit` | integer |  | Page size (default 50) |
| `offset` | integer |  | Page offset |

## fx_capture_states

CSS workbench: render one node in each pseudo-state (none, hover, focused, pressed, disabled) via pseudoClassStateChanged, restoring the original state afterwards; returns one captioned strip image.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `states` | array |  | States (default none,hover,focused,pressed,disabled) |
| `scale` | number |  | Render scale (default 2) |

## fx_reload

Apply your edits: detects changed files since the last launch/reload (or takes files=[…]) and routes them through the cheapest tiers — css (live re-apply + visualDiff), fxml (live swap of registered documents), java (in-process javac → HotSwap; compile errors returned as data, nothing swapped), then remount (after a successful HotSwap or FXML swap the registered FxLoopDev.view mounts are rebuilt in place, view state carried over; tier restage with FXLOOP_RESTAGE), restart (+replay) when needed — but a restart waits while the developer is using the app. Returns tiersApplied, compileErrors?, stateCarried?{restored,skipped}, needsRestart?, restarted?, restartDeferred? ("humanActive"), restartFailed?, waitingForBuild?, replay?, visualDiff?, effects.

| Param | Type | Required | Description |
|---|---|---|---|
| `files` | array |  | Explicit changed files (project-relative); default: auto-detect |
| `allowRestart` | boolean |  | Restart automatically when required (default true) |
| `replay` | boolean |  | Replay the journal after a restart (default FXLOOP_REPLAY, true) |
| `visualDiff` | boolean |  | Before/after pixel diff for the css tier (default true) |
| `remount` | boolean |  | Rebuild registered views after a HotSwap (default true) |
| `carryState` | boolean |  | Carry view state (selection, scroll, text, …) across a remount/FXML swap (default true) |
| `restartWhenHumanActive` | boolean |  | Restart even while the developer is using the app (default false: the restart is deferred) |
| `effects` | boolean |  | Attach the scene-diff 'effects' of this action (default true) |

## fx_reload_fxml

Force a live swap of one FXML document (project-relative path): re-copies it, re-runs FXMLLoader and replaces the old root in place. The controller is re-created; view state (selection, scroll, text, …) is carried over unless carryState=false — see stateCarried in the result.

| Param | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | FXML file path, e.g. src/main/resources/app/main.fxml |
| `carryState` | boolean |  | Carry view state across the swap (default true) |

## fx_set_watch_mode

Human-facing auto-apply on save: css-only (default) re-applies CSS instantly; all also hot-swaps Java/FXML; off disables. Never restarts.

| Param | Type | Required | Description |
|---|---|---|---|
| `mode` | string (css-only\|all\|off) | yes | css-only | all | off |

## fx_lint_fxml

Static FXML check against compiled controllers (works before the app starts): missingFxIdField, typeMismatch, missingHandlerMethod, handlerSignatureInvalid, unresolvedImport, duplicateFxId — with line numbers and fixes.

| Param | Type | Required | Description |
|---|---|---|---|
| `path` | string |  | One FXML file (project-relative) |
| `all` | boolean |  | Lint every FXML under the source/resource roots |

## fx_connect_existing

Connect to an app started outside fxloop (e.g. from the IDE) that called FxLoopDev.install(); reads its owner-only handshake file. No supervision: restart is unavailable in this mode.

| Param | Type | Required | Description |
|---|---|---|---|
| `handshakePath` | string |  | Handshake file (default target/fxloop/handshake.json) |

## fx_attach

Secondary mode: list local JVMs (list=true) or attach the agent to a running JavaFX app by pid. No reload tiers and no restart in this mode. JDK 21+ targets print a dynamic-agent warning (JEP 451).

| Param | Type | Required | Description |
|---|---|---|---|
| `pid` | integer |  | Target process id |
| `list` | boolean |  | List attachable JVMs |

## fx_explain_property

Why does a property have its value? value, type, bound, binding dependency tree (depth ≤ 5, owners as node paths) and CSS styleOrigin. Bidirectional peers are not discoverable via public API (reported as unknown-peer).

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `name` | string | yes | Property name, e.g. disable |

## fx_explain_style

CSS-styled properties of a node with their origin (USER_AGENT/AUTHOR/INLINE/USER), inline style, style classes, pseudo-classes and candidate stylesheets along the parent chain. Paginated.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `property` | string |  | One CSS property, e.g. -fx-text-fill |
| `limit` | integer |  | Page size |
| `offset` | integer |  | Page offset |

## fx_explain_layout

Why is it there/that size? layout/parent/screen bounds, insets, min/pref/max sizes, parent constraints (grow, margins, anchors, grid cell) and anomaly hints (unmanaged, translated, below pref width).

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |

## fx_eval_js

Run JavaScript in a WebView's engine on the FX thread; result is JSON-serialized.

| Param | Type | Required | Description |
|---|---|---|---|
| `handle` | integer |  | Session node handle (from fx_get_scene_graph / fx_find_nodes) |
| `path` | string |  | Stable node path, e.g. Stage[0]/Scene/VBox/Button#addBtn or Stage[0]//Button#addBtn |
| `query` | string |  | CSS selector that must match exactly one node, e.g. #addBtn |
| `windowIndex` | integer |  | Window index (see fx_get_app_status.windows); default: focused window |
| `picked` | boolean |  | Target the node the developer picked in pick mode (see fx_get_picked) |
| `script` | string | yes | JavaScript expression |
| `timeoutMs` | integer |  | Timeout (default 3000) |

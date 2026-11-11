# PRD 07 — Live Dev Loop: "DevTools for JavaFX" (Stage E)
**Objective:** let a developer keep the running app open, click through it, and watch the agent's edits appear **in place, within a second, without losing where they are** — the browser-DevTools experience for a JavaFX desktop app. The agent edits; the developer explores; neither waits for a restart unless the JVM truly requires one, and even then the app comes back on the screen the developer was looking at.

**Audience:** developers who use FXLoop (P2) together with their coding agent (P1), at the same time, on the same running app.

**Entry gate:** MVP reload subsystem (PRD 02 §3–5) and the FXML load registry are done. This PRD closes the gaps between "the tiers exist" and "it feels live".

---

## 1. Where we are

| Change | Today | Feels live? |
|---|---|---|
| CSS | Re-applied in place, `visualDiff` returned (`Reloader.cssTier`) | ✅ Yes |
| FXML (registered document) | Re-loaded and swapped in place (`Reloads.swap`); controller re-created | ⚠️ Visible at once, but text typed, selections, scroll position and selected tab inside the document reset |
| Java method body | Compiled in-process, HotSwapped | ⚠️ Only if the method runs again on its own (handlers, listeners, cell factories). UI built in `start()`, constructors or `initialize()` does **not** change on screen ([limitations](../../limitations.md#hot-reload-hotswap)) |
| Structural Java | Restart + journal replay (or JBR enhanced redefinition) | ⚠️ Replay restores the **agent's** path only; where the developer clicked to is lost |
| Feedback to the developer | Tool result goes to the agent; the app shows nothing | ❌ The developer can't tell which mode the session is in, whether a reload happened or failed, or whether the app needs a rebuild FXLoop cannot do itself |
| Shared input | Action tools default to `mode=robot`, which moves the real pointer and types into the focused window | ❌ Agent and developer fight over the mouse when both use the app |
| "This button here" | Developer must describe the node in words | ❌ No way to point at a node |

The gaps, in order of impact:

- **G1 — Code-built views don't re-render after HotSwap.** The most common "it didn't work" in practice; JavaFX apps very often build views in code.
- **G2 — Swaps throw away view state.** FXML swaps (and the remount in §3) start from a fresh tree.
- **G3 — Human navigation isn't journaled.** Replay after a restart doesn't land on the developer's screen.
- **G4 — Restarts happen under the developer's hands.** `fx_reload` may restart while the developer is typing.
- **G5 — The session's state is invisible to the developer.** Mode (dev loop, embedded, attached, headless), who is driving, the last reload, and "this needs a rebuild/restart" live only in the agent's context.
- **G6 — No pointing.** The developer can't select a node for the agent.
- **G7 — No shared control.** Robot input takes over the developer's pointer and keyboard focus without warning.

## 2. Target scenario

1. The developer asks: "open the app, I'll click around". The agent calls `fx_start_app` (real display). A thin dev bar on top of the window reads `dev loop · You can interact · Saves apply CSS live`.
2. The developer opens *Contacts → Edit* and types into a field. They say "the Save button should be primary and the form needs more spacing".
3. The agent edits CSS and the Java method that builds the form, then calls `fx_reload`.
4. Within ~1 s the form re-renders in place with the new spacing; the text the developer typed is still there; the bar shows "CSS applied · hot-swapped 1 class · remounted edit-form · 380 ms" for a few seconds.
5. The agent checks the change by clicking Save. Because the developer is using the app, it does so with synthetic events: the developer's pointer doesn't move.
6. The developer clicks *Pick* on the bar (or presses the pick hotkey), clicks the Cancel button, and says "this one too". The agent calls `fx_get_picked` and knows exactly which node.
7. A later edit adds a field to a class. `fx_reload` **does not restart** while the developer is using the app; the bar turns red: "Restart needed · fields added in EditForm [Restart]". When the developer clicks Restart, replay brings the app back to *Contacts → Edit*, including the developer's own clicks. In an IDE-launched (embedded) app, where FXLoop cannot restart the JVM, the bar says "Rebuild and restart from your IDE" with the reason instead.

## 3. E1 — Remount: re-run code-built views after HotSwap (G1)

**Mechanism.** The app opts in by registering *view factories*. After a successful `hotswap` tier, the agent re-invokes each registered factory on the FX thread and swaps the old node for the new one with the existing `Reloads.swap` (same parent kinds supported: scene root, `ScrollPane`, `TabPane`, `TitledPane`, `SplitPane`, `BorderPane`, `Pane`, `Group`).

**API (`fxloop-dev`).** A plain passthrough when FXLoop is absent or disabled, so it is safe to leave in dev builds:

```java
tabs.getTabs().add(new Tab("Edit", FxLoopDev.view("edit-form", this::buildEditForm)));
// or, for things that are not a single node:
FxLoopDev.onReload(() -> table.refresh());
```

- `view(key, Supplier<Node>)` calls the supplier once, records `{key, supplier, WeakReference<Node>}` and returns the node. Re-registering a key (e.g. the view was rebuilt by app code) replaces the record.
- `onReload(Runnable)` runs after every successful hotswap or FXML tier, on the FX thread; exceptions are caught and reported, not thrown into the app.
- Mounts whose node is no longer in a scene are skipped (and pruned once their reference is cleared).

**Why method references.** A lambda body is a synthetic method of the declaring class; *adding* a lambda is a structural change that stock HotSwap rejects. Docs and skills recommend a stable builder method (`this::buildEditForm`); editing its body is a plain method-body swap.

**Which mounts re-run.** All live mounts after any successful hotswap (views are few; precise class→view dependency tracking is not worth its complexity in v1). `fx_reload{remount:false}` skips it. The result gains a `remount` tier:

```json
{"tier":"remount","ms":41,"details":{"remounted":["edit-form"],"skipped":[{"key":"settings","reason":"not in a scene"}],"failed":[{"key":"x","error":"NullPointerException at EditForm.java:42"}]}}
```

A factory that throws leaves the old node in place and reports the root cause (same unwrapping as `ErrorRing`).

**Zero-config fallback (opt-in, experimental).** `FXLOOP_RESTAGE=true`: when no mounts are registered, re-run `Application.start(stage)` on the existing primary stage after clearing its scene. Off by default: `start()` often starts services, timers or opens connections, and running it twice is app-specific. Reported as tier `restage`.

**Embedded and attach modes.** Remount needs only Instrumentation plus the registry, so it works wherever hotswap works.

## 4. E2 — State carry-over across swaps (G2)

Before any FXML swap or remount, the agent snapshots **view state** in the old subtree and re-applies it to the new subtree, matched by fx:id first, then by node path, and only when the node type matches.

| Captured | Restored as |
|---|---|
| `TextInputControl` text, caret, selection | same (skipped for `PasswordField`; see §5 privacy) |
| `ListView`/`TableView`/`TreeView`/`ComboBox`/`ChoiceBox` selected indices | same, if within bounds |
| `TabPane` selected tab | by tab id, else text, else index |
| `ScrollPane` h/v value; virtualized controls' first visible index | same |
| `TitledPane`/`Accordion` expanded, `CheckBox`/`ToggleButton`/`RadioButton` selected | same |
| Focus owner | re-focused if it maps to a node |

This is **view** state only. Controller fields, models and services are not carried (controller re-created on FXML swap; factory re-run on remount); the docs say so. Results gain `stateCarried: {restored: n, skipped: [{path, reason}]}`. Opt out with `fx_reload{carryState:false}`.

Setting a restored value can fire the app's listeners (e.g. selection listeners loading data). That is intended — the new view should end up in the state the developer had — but it is listed as a risk (§11).

## 5. E3 — Journal the developer's own input (G3)

**Capture.** In launched and embedded modes the agent adds scene event filters on every window (existing and new): mouse clicks, key presses with modifiers / Enter / Escape / Tab, and text commits (focus loss or Enter on a `TextInputControl`). Filters only observe; they never consume events.

**Telling human from agent.** The agent's own actions produce the same events: robot input through `javafx.scene.robot.Robot`, and synthetic events (`mode=event`, the default in `shared` control, §7.2) through the very scene filters used for capture. `Interaction` brackets every agent action, in both modes, with an "agent action in progress" flag plus a short settle window (100 ms); events inside it are not recorded as human.

**Mapping to journal entries.** Each human event becomes an ordinary journal entry, so replay and `fx_export_test` need no new paths:

| Human event | Journal entry |
|---|---|
| Click on a node | `fx_click_node` with the nearest addressable target (control with fx:id, else node path), button, click count |
| Text edits in a field | coalesced into one `fx_set_text` at commit |
| Shortcut / special key | `fx_type_keys` |
| Click on empty area / non-addressable target | not recorded (counted in `skippedHuman`) |

Entries carry `source: "human" | "agent"` (new field on `JournalEntry`; FCP event `humanAction`). `fx_get_journal` shows it; `fx_export_test` includes both by default (`sources` filter). Replay runs both in order.

**Privacy.** `PasswordField` content is never recorded (entry stored with `redacted: true`, skipped on replay). The journal stays in the server's memory ring (500) as today; nothing new is persisted to disk. `FXLOOP_RECORD_HUMAN=false` disables capture.

**Awareness.** `fx_get_app_status` gains `human: {lastActivityMs, focusedWindow}` so the agent can screenshot what the developer is looking at instead of guessing.

## 6. E4 — Restart etiquette (G4)

- In control mode `shared` (§7.2), or in `agent` mode when human input was seen within `FXLOOP_HUMAN_IDLE_MS` (default 10 000), the restart tier is **deferred**: `fx_reload` returns `needsRestart: true, restartDeferred: "humanActive"` and does not restart. `fx_reload{restartWhenHumanActive:true}` or `fx_restart_app` overrides it.
- The dev bar (§7) shows "Restart needed" with the reasons and a **Restart** button; a hotkey (default `Ctrl+Shift+F5`, configurable) does the same. That restart replays the journal like any other (FCP event `humanRestartRequest` → server).
- Watch mode is unchanged: it never restarts.

This keeps Principle 5 ("no surprise background mutations"): nothing restarts that the agent or the developer did not ask for at a moment the developer is not busy.

## 7. E5 — Dev bar: mode, control, reloads, "rebuild needed" (G5, G7)

A thin status bar the agent lays over the top edge of each app window. It works like Chrome's "controlled by automated test software" infobar, and it also carries the reload story. It is the developer's channel for what the agent already gets in tool results (dual-channel rule, Principle 8).

```
 ⠿ ● FXLoop  dev loop · You can interact · Saves apply CSS live │ CSS applied · 12 ms     Restart needed · pom.xml changed [Restart] [Pick] ⋯ ⌄
          └── session (left) ─────────────────────────┘ └ last reload (fades) ┘     └── action needed (right, stays) ──┘
```

### 7.1 Contents

**Session (always shown):**

| Segment | Values |
|---|---|
| Mode | `dev loop` (launched by FXLoop: all tiers and restart) · `embedded` (IDE-launched with `FxLoopDev`: no restart) · `attached` (pid attach: no reload tiers, no restart) |
| Control (§7.2) | `You can interact` (shared) · `Agent mode · you can interact` (agent mode, idle) · `Agent is driving — hands off` (agent mode while robot input or replay runs) · `Agent is using your mouse` (a robot action in shared mode, for its duration) · `Agent is driving — you're watching` (input lock held) |
| Activity | `Agent: clicking Button#save` · `Agent: taking a screenshot` … while a tool call runs (2 s linger) |
| Watch | `Saves apply CSS live` (css-only) / `Saves apply CSS, FXML and code` (all) / `Saves apply on reload` (off) |

**Last reload (transient, fades after 3 s):** `CSS applied · 12 ms` · `Hot-swapped 2 classes · remounted edit-form` · `FXML main-view.fxml swapped (7 states restored)` · `No visual change — selector may not match`.

**Action needed (stays until resolved or dismissed with Escape).** Shown only when FXLoop cannot finish the job itself. Each entry names the reason and the next step:

| Situation | Bar says | Button |
|---|---|---|
| Restart required (structural change, unregistered FXML, build file changed) and deferred (§6), dev-loop mode | `Restart needed · <reason>` | **Restart** (with replay) |
| Restart required in embedded or attached mode (FXLoop does not own the JVM) | `Rebuild and restart from your IDE · <reason>` | **Copy reason** |
| Any source edit in attached mode | `Edits don't apply live when attached · relaunch with FXLoop or your IDE` | — |
| Compile errors | `Compile error · EditForm.java:42` (+ count) | — |
| Classes come from an external build (Kotlin, `FXLOOP_EXTERNAL_COMPILE`) and sources changed with no new class files | `Waiting for a build · run your IDE build or gradle -t classes` | — |
| Build-tool compile or `fxloop:prepare` failed after a build-file change | `Rebuild failed · <first error line>` | **Copy log path** |
| Remount factory threw (§3) | `Remount failed · edit-form: <root cause>` | — |

`fx_get_app_status` gains `actionNeeded: [{kind, reason, next}]` with the same entries, so the agent and the developer always see the same list.

### 7.2 Control modes

Robot input moves the real pointer and sends real keystrokes to the focused window, so agent and developer collide when both use the app. New, explicit control mode:

- **`agent`** — the agent may drive with robot input (today's behavior). While a robot action or a replay runs, the bar turns amber: "Agent is driving — hands off". Human input during that time is not journaled and is reported as `conflicts: n` in the action's result.
- **`shared`** — the developer is using the app. Action tools default to `mode=event` (synthetic events: no pointer movement, no focus theft). An explicit `mode=robot` is still allowed (hover styles, native drag and some popups need it) and makes the bar show "Agent is using your mouse" for its duration. Restarts are always deferred (§6).

**Input lock.** Before it drives the UI like a person, the agent may call `fx_lock_input {locked, reason?, seconds?}` (default 60 s, max 600). While it holds, the agent drops the developer's mouse, key, scroll, touch and drag input to app windows (its own robot and synthetic events pass; the dev bar stays usable), inspector editing answers `423`, and the bar turns amber with "Agent is driving — you're watching" and a **Take over** button (the inspector shows the same). Take over, `locked:false`, expiry or a lost server connection end it. After a take-over the next action result tells the agent, and `fx_get_app_status.inputLock.releasedBy` is `developer`.

Switching: the bar's toggle ("Let me drive" / "Hand back to agent"), the new tool `fx_set_control_mode {mode}`, or `FXLOOP_CONTROL=auto|agent|shared`. `auto` (default) starts in `agent` and switches to `shared` on the first human input; it never switches back on its own. `fx_get_app_status` reports `control`. The PRD 06 skills tell the agent to ask before switching to `agent` while the developer is present.

### 7.3 Form and invisibility

- **Not part of the app's scene graph.** A bar inserted into the scene would change the app's layout, which is exactly what the agent measures, lints and diffs. Instead it is an agent-owned window anchored to the top edge of each showing app stage: a `Popup`, or on Linux an owned transparent `Stage`, because GTK popups are override-redirect windows the window manager never stacks, so they would float above every other app. It follows moves and resizes, is 24 px tall and translucent, and keeps no focus: the Linux stage hands focus straight back to the app window, on show and on every click. It can be collapsed to a corner pill (`[⌄]`, remembered in `target/fxloop/state.json`).
- **Where it docks.** Above the window; when that is off screen (a maximized window or one at the top of the screen), over the title bar, leaving the window's own buttons clear (the right end, the left on macOS). When neither fits (an undecorated window at the top of the screen) it starts as a pill, and expanding that pill lays the bar over the top edge of the window.
- **Narrow windows.** Segments get the width in a fixed order: action buttons, the action-needed reason, the brand, the session text, then Clear, Pick, activity, the control toggle, Open CSS inspector, scratch and last reload. What does not fit leaves the bar: buttons move into a `⋯` menu, texts are listed there too. A button is never squeezed into "…"; texts truncate with a tooltip holding the full text.
- **Like a title bar.** Dragging the bar (anywhere but its buttons) moves the window and a double-click maximizes or restores it. Both are off while the agent's input lock holds, so the window stays where the agent expects it.
- **Styles** come from the agent's `devbar.css`, scoped by id: a popup inherits the app's stylesheets, and an id outranks the app's class selectors, so the app's `.button`/`.label` rules never restyle the bar.
- **Invisible to the tools.** Excluded from window lists, scene-graph tools, finders, lint and effects, and hidden during every screenshot capture (agent-side, before the snapshot, restored after).
- **Colors:** neutral (nothing pending), amber (agent driving), red (action needed). Text always carries the meaning; color is never the only signal.
- `FXLOOP_BAR=auto|on|pill|off`; `auto` = on with a real display.

### 7.4 Headless runs and the live viewer

With Monocle or Xvfb nobody sees the window, so the bar is not rendered. The same state is published to the live viewer (PRD 04 §3.7) as a status strip (SSE `status` event next to `frame`), with mode shown as `headless (monocle)` / `headless (xvfb)`. A developer watching a headless run in the browser sees mode, control and action-needed. The viewer stays read-only: no buttons.

## 8. E6 — Pick mode (G6)

- The dev bar's **Pick** button or a hotkey (default `Ctrl+Shift+F12`, configurable via `FXLOOP_PICK_KEY`) toggles pick mode on the focused window.
- While active: hover draws an outline plus a label (`Button#saveBtn .button.primary 120×32`); the click is **consumed** (not delivered to the app) and selects the node; Escape leaves pick mode. Same overlay mechanism and tool-invisibility as the dev bar.
- The outline stays on the selected node after the pick, also while another window has focus, and follows the node when the window moves or resizes. It ends when another node is selected (a new pick, or a row in the CSS inspector) or the developer presses in an app window.
- The selection becomes the server's *picked target* (FCP event `humanPick`). New read-only tool **`fx_get_picked`** returns node details, path, explain-style summary and an annotated, persisted screenshot of the node. All target-taking tools accept `{"picked": true}` as a target.
- MCP is pull-based: the developer picks, then tells the agent ("fix this one"). The skills from PRD 06 (S1–S3) learn to call `fx_get_picked` for "this", "here", "that one". Picks older than 10 minutes, or whose node left the scene, report `PICK_STALE`.

## 9. Tool and config surface delta

| Item | Change |
|---|---|
| `fx_reload` | new tiers `remount`, `restage`; params `remount`, `carryState`, `restartWhenHumanActive`; results `stateCarried`, `restartDeferred` |
| `fx_reload_fxml` | gains `carryState`, `stateCarried` |
| `fx_get_journal` / `fx_export_test` | entries carry `source`; export gains `sources` filter |
| `fx_get_app_status` | gains `human`, `control`, `actionNeeded` |
| Action tools (`fx_click_node`, `fx_type_keys`, `fx_set_text`) | default input mode follows control mode (`event` in `shared`); results gain `conflicts` |
| `fx_get_picked` | **new**, read-only |
| `fx_set_control_mode` | **new**, idempotent write: `agent` / `shared` |
| Targets | accept `{"picked": true}` |
| `fxloop-dev` | `FxLoopDev.view(key, supplier)`, `FxLoopDev.onReload(runnable)` |
| FCP | methods `REMOUNT_VIEWS`, `GET_PICKED`, `SET_BAR_STATE`; events `humanAction`, `humanPick`, `humanRestartRequest`, `controlModeChanged` |
| Live viewer | SSE `status` event + status strip |
| Config | `FXLOOP_RESTAGE`, `FXLOOP_RECORD_HUMAN`, `FXLOOP_HUMAN_IDLE_MS`, `FXLOOP_CONTROL`, `FXLOOP_BAR`, `FXLOOP_PICK_KEY`, `FXLOOP_RESTART_KEY` |

## 10. Acceptance workflows (all via MCP, scripted + one manual Claude Code run)

Test beds: the gauntlet app (code-built tabs: wrap its tab builders with `FxLoopDev.view`) and the contacts sample (FXML).

- **A-E1 Remount.** Change the body of `GauntletApp.dialogs()` (a label text) → `fx_reload` → tier `remount` lists `dialogs`; the label is visible in a fresh screenshot; no restart; app PID unchanged.
- **A-E2 State carry-over.** In the contacts app, type into the search field, select row 3, scroll the list → edit `main-view.fxml` → `fx_reload` → text, selection and scroll position are preserved; `stateCarried.restored ≥ 3`.
- **A-E3 Human journal.** Simulated human input (synthetic events injected *outside* the robot bracket) navigates to the gauntlet *Menus* tab and opens a menu → structural Java change → restart with replay → the app is on the *Menus* tab; journal shows `source: human` entries; a `PasswordField` edit is redacted.
- **A-E4 Etiquette.** Human event 2 s before a structural change → `fx_reload` returns `restartDeferred: "humanActive"`, PID unchanged; the restart hotkey triggers restart + replay.
- **A-E5a Bar invisibility.** Bar visible → `fx_take_screenshot`, `fx_get_scene_graph`, `fx_lint_visual` and `effects` show no trace of it; the app's layout bounds are identical with `FXLOOP_BAR=on` and `off`.
- **A-E5b Action needed.** Embedded mode (`FxLoopDev.install()` + `fx_connect_existing`) + a structural Java change → `fx_reload` reports the rejection; `actionNeeded` contains `Rebuild and restart from your IDE` with the reason; the bar shows the same text (asserted through an agent-side debug query, since the bar is invisible to normal tools).
- **A-E5c Shared control.** `fx_set_control_mode{mode:"shared"}` → `fx_click_node` without `mode` reports `mode: "event"` and the pointer position is unchanged; with `mode:"robot"`, `controlModeChanged`/bar state shows "Agent is using your mouse" during the action.
- **A-E5d Headless status.** Monocle run with the viewer on → the viewer's SSE stream carries `status` events with `mode: "headless (monocle)"`; no bar popup exists.
- **A-E6 Pick.** Pick mode on → click `saveBtn` (not delivered: button's action counter unchanged) → `fx_get_picked` returns `#saveBtn`; `fx_set_property{target:{picked:true}}` works.
- **A-E-Manual.** Section 2 scenario end to end with a real developer and Claude Code, timed: CSS + method-body edits visible in ≤ 1 s p50 on the sample app.

## 11. Risks

- **Restored state fires listeners** (E2) → app side effects (e.g. a reload from the database). Mitigation: per-call opt-out, `stateCarried` report, document the behavior.
- **Robot/human disambiguation** (E3) is timing-based; a human click during an agent action is lost or misattributed. Acceptable: worst case is a missing journal entry, reported in `skippedHuman`.
- **Hotkey collisions** with app shortcuts. Mitigation: configurable keys, filters run before the app's handlers only for the exact chord, and the chords are rarely used by apps.
- **Overlays on exotic platforms** (Wayland popups, macOS fullscreen spaces, tiling window managers). Mitigation: bar failure is logged and falls back to the corner pill, then to off; it never blocks a reload. The same state is always available in `fx_get_app_status` and the viewer.
- **Event-mode fidelity** (§7.2). Synthetic events skip hover, real picking and some native behaviors, so a check that passes in `shared` mode can differ from a real click. Mitigation: results always report the `mode` used; skills re-check critical flows in `agent` mode before claiming success.
- **Remount of views holding external resources** (web views, media players) may leak the old instance's resources. Mitigation: documented; `onReload` lets the app clean up.

## 12. Milestones

Delivery order (feature IDs are the section IDs above):

1. **E1 Remount** — `FxLoopDev.view/onReload`, `REMOUNT_VIEWS`, `remount` tier, gauntlet wiring. DoD: A-E1.
2. **E5 Dev bar, part 1** — overlay, tool invisibility, mode + last reload + action-needed segments, `actionNeeded` in status, viewer `status` strip. DoD: A-E5a, A-E5b, A-E5d. (Early: it makes every later feature visible to the developer, and the "rebuild from your IDE" message is useful on its own.)
3. **E2 State carry-over** — capture/restore table, FXML + remount integration. DoD: A-E2.
4. **E3 + E4 Human journal and etiquette** — filters, robot bracket, `source`, restart deferral, Restart button and hotkey. DoD: A-E3, A-E4.
5. **E5 Dev bar, part 2: control modes** — `fx_set_control_mode`, event-mode defaults, conflicts, bar toggle. DoD: A-E5c.
6. **E6 Pick mode** — overlay, `fx_get_picked`, `{picked:true}` targets, skill updates (PRD 06 S1–S3). DoD: A-E6, then A-E-Manual.

## 13. Non-goals

An in-app property editor or styles panel (the agent is the editor); a banner inside the app's own scene graph (it would shift the layout the agent measures, §7.3); controlling the agent from the bar beyond Restart, Pick and the control toggle; writing in-app edits back to source; auto-reload on the agent's file edits (Principle 5 — the agent calls `fx_reload`); class→view dependency tracking for selective remount; carrying controller/model state; Kotlin in-process compile (unchanged, PRD 06 §6); remote or multi-user sessions.

## 14. Open questions

1. Should `FXLOOP_RECORD_HUMAN` default to on? (Default: on in launched/embedded modes — it only observes and the ring stays in memory; revisit if users object.)
2. Should the gauntlet and contacts sample depend on `fxloop-dev` permanently, or only in an acceptance profile? (Default: gauntlet yes, contacts stays FXML-only to cover the zero-dependency path.)
3. Is `restage` worth shipping at all, or does it create more confusion than it solves? (Default: ship behind the flag, collect feedback.)
4. Can MCP resource-update notifications push picks to clients that support them, instead of relying on the developer to say "this one"? (Default: pull only in v1.)
5. Should `FXLOOP_CONTROL=auto` ever switch back from `shared` to `agent` on its own (e.g. after long idle)? (Default: no. A silent switch back to robot input is the kind of surprise Principle 5 rules out.)

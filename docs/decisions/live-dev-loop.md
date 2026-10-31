# Decision: live dev loop architecture (Stage E)

**Status:** accepted. **PRD:** 07.

This note fixes the contracts between the agent (in the app JVM) and the server for PRD 07, so remount, state
carry-over, the human journal, the dev bar, control modes and pick mode can be built and reasoned about separately.

## Ownership of state

| State | Owner | Why |
|---|---|---|
| View mounts (`FxLoopDev.view/onReload`) | agent `MountRegistry` | holds live node and supplier references |
| View-state snapshots (E2) | agent `ViewState`, transient | only lives for one swap |
| Human input capture, agent-action bracket, conflicts | agent `HumanInput` | must see raw FX events |
| Overlay windows (dev bar, pick outline) | agent `DevBar`, `PickMode`, marked via `Overlays` | they are FX windows |
| Picked node | agent `PickMode` (weak reference + timestamp) | `{"picked":true}` targets resolve in the agent |
| Control mode, human activity, action-needed list, last reload | server `LiveState` | the server decides restarts and is the single source of truth; it pushes a full bar state to the agent with `setBarState` |
| Journal (`source: human/agent`) | server `Journal` | unchanged ring, humans feed it via the `humanAction` event |

## FCP surface (additive; protocol version unchanged)

Methods (server → agent):

| Method | Params | Result |
|---|---|---|
| `remountViews` | `{remount=true, carryState=true, restage=false}` | `{mounts, remounted:[key], skipped:[{key,reason}], failed:[{key,error}], stateCarried:{restored,skipped:[{path,reason}]}, callbacks:{ran, failed:[{error}]}, restaged?, restageError?}` |
| `reloadFxml` | gains `carryState` (default true) | each `swapped[]` entry gains `stateCarried`; top level gains merged `stateCarried` |
| `setBarState` | full bar state, see below | `getBarState` result |
| `getBarState` | — | debug view of what the bar renders: `{bar, rendered, windows, texts:{session,lastReload,actionNeeded}, controlHistory:[…], collapsed}` |
| `getPicked` | — | `{target, details, explain, ageMs, windowOrigin, outputScale, w, h, pngB64}` or error `PICK_STALE` |
| `setPickMode` | `{on, windowIndex?}` | `{active}` |
| `testHumanInput` | test only (`-Dfxloop.test=true`): `{kind: click|text|keys, target…, text?, keys?}` | `{injected:true}` — synthetic events fired *outside* the agent-action bracket |

Events (agent → server): `humanAction {tool, args, target, redacted?, ts}`, `humanPick {target, ts}`,
`humanRestartRequest {via: bar|hotkey}`, `controlModeChanged {mode, via: bar}`.

`setBarState` payload (always the full state, idempotent):

```json
{"bar":"on|pill|off", "mode":"dev loop|embedded|attached|headless (monocle)|headless (xvfb)",
 "control":"agent|shared", "driving":false, "watch":"css-only|all|off",
 "lastReload":{"text":"CSS applied · 12 ms","ok":true,"seq":3},
 "actionNeeded":[{"kind":"restart","reason":"fields added in EditForm","next":"Restart","button":"restart"}],
 "config":{"recordHuman":true,"pickKey":"Ctrl+Shift+F12","restartKey":"Ctrl+Shift+F5"}}
```

`actionNeeded[].kind`: `restart` (button `restart`), `rebuildIde` (button `copy`), `attachedEdits`, `compileError`,
`waitingBuild`, `rebuildFailed` (button `copy`, copies `next`), `remountFailed`.

## Invisibility of overlays

Overlay windows carry `window.properties["fxloop.overlay"] = true`. `Windows.showing()` (the root of every window
list, finder, scene-graph, lint, snapshot and effects path) excludes them; `HumanInput` ignores their events.
Screenshots render the scene root via `Node.snapshot`, which never includes another window, so the bar cannot
leak into a capture.

## Agent-action bracket

`Interaction` wraps every click / key / text action in `HumanInput.agentAction(robot)`; events seen while the
bracket is open, and for 100 ms after it closes, are not journaled as human. Human-looking events inside the
bracket in `agent` control are counted and returned as `conflicts`. While a robot bracket is open the dev bar shows
"Agent is driving — hands off" (`agent`) or "Agent is using your mouse" (`shared`).

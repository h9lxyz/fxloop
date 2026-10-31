# PRD 01 — FXLoop PoC (Stage A)
**Objective:** prove the end-to-end architecture with the smallest credible tool surface, so that a coding agent in Claude Code can drive a real JavaFX app — see it, act on it, hear its errors, restart it — via MCP over a Maven project.

**Explicitly a de-risking build.** Polish, plugin packaging, reload tiers, diffs, and cross-OS guarantees are **out** (they are MVP). What must be *in* is everything whose failure would invalidate the concept.

---

## 1. Risk hypotheses to validate (PoC exit questions)

| ID | Hypothesis | Validated when |
|---|---|---|
| H1 | A stdio MCP server (Java, official SDK) can spawn/supervise/kill/relaunch a JavaFX child launched from a Maven project | `fx_restart_app` works 10/10; MCP session survives every restart |
| H2 | An in-JVM agent loaded via `-javaagent` can detect FX startup, marshal onto the FX thread safely, and never deadlock silently | 0 hangs across acceptance runs; induced deadlock returns FX-thread stack in error |
| H3 | Scene graph → bounded JSON is small enough and rich enough for an LLM to navigate a real UI | Agent completes acceptance script using only tool output (no human hints) |
| H4 | `javafx.scene.robot.Robot` input driven over FCP behaves like a user (picking, focus, hover) | Robot-mode clicks pass on controls where `event` mode is insufficient (ComboBox popup) |
| H5 | `Node.snapshot` → PNG → MCP image block round-trips at useful quality/size | Claude correctly answers a visual question about a screenshot ≤ 300 KB |
| H6 | FX-thread uncaught exceptions can be captured and surfaced as data | "Boom" scenario appears in `fx_get_ui_errors` with class+message+top frames |

## 2. Scope

**In:** modules `fxloop-protocol`, `fxloop-agent`, `fxloop-server`, `fxloop-sample-app`; Maven-shelled launch; 12 MCP tools (§7); FCP v1 (§6); handshake; supervision; error ring; screenshots; Robot+event interaction; property read/write; single-window focus (multi-window *listing* only); Linux `xvfb` CI smoke of the acceptance script.

**Out (deferred, do not build):** Maven plugin; Gradle; reload of any tier; diffs/journal/replay; FXML lint; attach mode; Monocle; SLF4J capture; module-path launches; pagination beyond the scene-graph budget; Windows/macOS CI (manual smoke only).

## 3. Runtime & launch contract

- **Server config (env or CLI flags):** `FXLOOP_PROJECT_DIR` (required), `FXLOOP_MAIN_CLASS` (required), `FXLOOP_JVM_ARGS` (optional), `FXLOOP_MVN_CMD` (default: `./mvnw` if present else `mvn`), `FXLOOP_STARTUP_TIMEOUT_MS` (default 30000).
- **Launch sequence** (`fx_start_app` / implicit on first tool call / `fx_restart_app`):
  1. `mvn -q -DskipTests compile`
  2. `mvn -q dependency:build-classpath -Dmdep.outputFile=target/fxloop/cp.txt -Dmdep.pathSeparator=<os-sep>`
  3. Generate token (128-bit hex) + handshake path `target/fxloop/handshake.json`; delete stale file.
  4. Spawn: `java <FXLOOP_JVM_ARGS> -javaagent:<agent.jar>=handshake=<path>,token=<token> -cp target/classes<sep><cp.txt> <MAIN_CLASS>` with `workingDir=PROJECT_DIR`; pipe stdout/stderr into a 2000-line ring buffer (per stream).
  5. Poll for handshake file (atomic write by agent: temp+rename), then FCP `hello`. Timeout → kill child, return actionable error incl. last 50 log lines.
- **Classpath-launch caveat (documented):** PoC supports classpath-launched JavaFX apps (Launcher-delegates-to-Application pattern, as in the sample app). Module-path apps → error message pointing to MVP.
- **Stop:** `Process.destroy()`, escalate to `destroyForcibly()` after 3 s.

## 4. Agent (`fxloop-agent`) requirements

- `premain(String args, Instrumentation inst)`: parse args; if `handshake` absent → no-op return (zero-footprint guarantee). Store `Instrumentation` for MVP use.
- **FX readiness:** daemon thread polls `Platform.runLater(noop)` (catch `IllegalStateException`) every 100 ms until toolkit is up, max 60 s; then binds ServerSocket on `127.0.0.1:0`, writes handshake `{fcpVersion:1, pid, port, startedAt}` (0600), accepts **one** FCP connection at a time (reconnect allowed).
- **FX marshaling contract (core of H2):** every UI operation runs via `callOnFx(Callable, timeoutMs=5000)` → `CompletableFuture` + `Platform.runLater`. On timeout: locate FX thread via `ThreadMXBean`, capture its stack, return FCP error `{code:"FX_TIMEOUT", data:{fxThreadStack:[...]}}` — never block the FCP socket thread indefinitely.
- **Uncaught-exception capture:** on first FX-thread touch, chain `Thread.currentThread().setUncaughtExceptionHandler` (preserve+delegate to prior). Ring buffer 200 entries: `{seq, ts, threadName, exceptionClass, message, frames[≤15], dedupCount}` (dedup on class+message+top frame within 2 s).
- **Node handle table:** `int → WeakReference<Node>`; handles are session-scoped; every serialized node also carries a best-effort **path** string (index-chain + `#fxId` when present) — *emitted* in PoC, *resolvable* from MVP.
- Shade/relocate all agent deps (jackson) under `fxloop.internal.*` to avoid classpath pollution of the target app.

## 5. Scene-graph serialization (H3)

Per node: `{h, path, type, fxId?, styleClass?, text?, value?, visible, disabled, focused, boundsScreen:{x,y,w,h}, kids:n, children:[...]}`.
- `text` for `Labeled`/`TextInputControl` (≤ 80 chars + `…`); `value` for `CheckBox.selected`, `ComboBox.value`, `Slider.value`, `TableView.items.size` etc. (small per-type mapping table in code).
- **Budgets (params, with defaults):** `maxDepth=12`, `maxNodes=400`; truncated branches emit `{truncated:true, kids:n}`. Virtualized controls (`ListView/TableView/TreeView`): never serialize cells beyond the visible ones; emit `itemCount` + first 10 visible cell texts.
- Windows array separately: `[{index,title,type:STAGE|POPUP|DIALOG,focused,x,y,w,h,showing}]` — popups/context menus/tooltips are windows and must appear here (H4 depends on it).

## 6. FCP v1 (server ↔ agent)

NDJSON frames over TCP `127.0.0.1`. Request `{"id":n,"method":"...","params":{}}` → `{"id":n,"result":{}}` | `{"id":n,"error":{"code","message","data"}}`. Async events (agent→server, unsolicited): `{"event":"uiError","payload":{...}}` — server buffers into the error ring it exposes via MCP. First frame must be `{"method":"hello","params":{"token","fcpVersion":1}}`; wrong token → close. Methods mirror the MCP tool set 1:1 (`getSceneGraph`, `click`, …) so the server layer stays a thin adapter + policy (timeouts, budgets, image re-encoding).

## 7. MCP tool surface (12 tools)

All tools: `openWorldHint:false`. Errors in-result with `isError:true`, one-line cause + suggested next step. Text summary + `structuredContent` on every success.

| Tool | Params (defaults) | Returns (structured) | Hints |
|---|---|---|---|
| `fx_get_app_status` | — | `{state:starting|running|exited, pid, uptimeSec, exitCode?, fcpConnected, windows:[...]}` | RO, idem |
| `fx_start_app` | — | status (error if already running) | write |
| `fx_restart_app` | — | `{restarted:true, startupMs}` + status | destructive |
| `fx_stop_app` | — | `{stopped:true, exitCode}` | destructive |
| `fx_get_logs` | `lines=100 (≤1000), stream=both|stdout|stderr` | `{lines:[...], truncated}` | RO |
| `fx_get_ui_errors` | `sinceSeq=0, limit=20` | `{errors:[...], lastSeq, has_more}` | RO |
| `fx_get_scene_graph` | `windowIndex=focused, rootHandle?, maxDepth=12, maxNodes=400` | `{window, root:<node tree §5>, truncated}` | RO |
| `fx_find_nodes` | `query` (CSS selector via `Node.lookupAll`) OR `text` (contains, case-insens.) OR `type` (simple class name); `windowIndex?, limit=20` | paginated `{items:[node-summaries], …}` | RO |
| `fx_click_node` | `handle? | path? | x,y?` (screen), `button=primary`, `clickCount=1`, `mode=robot|event (robot)` | `{clicked:true, mode, target:{path,type,text}}` | write |
| `fx_type_keys` | `keys:[{code:"ENTER", modifiers:["SHORTCUT"...]}]` (Robot press/release, FX `KeyCode` names) | `{typed:n, focusedAfter:{path,type}}` | write |
| `fx_set_text` | `handle|path`, `text`, `clearFirst=true` | `{set:true, target, valueAfter}` — programmatic on `TextInputControl` only (D7); actionable error otherwise | write |
| `fx_get_property` / `fx_set_property` | `handle|path`, `name` (+`value` for set; coercion: String→boolean/int/double/enum/`Color.web`) | `{name, value, type, bound}`; set on bound property → error `PROPERTY_BOUND` with hint "inspect the binding; write to its source" | RO / write |
| `fx_take_screenshot` | `target=window|node`, `handle|path?`, `windowIndex?`, `maxWidth=1200`, `label?`, `settleMs=150` | **Dual-channel (PRD 04):** MCP **image** content (PNG) *and* persisted file `target/fxloop/screens/<seq>-<label>.png`, path in `structuredContent` `{seq,file,w,h,scale,bytes}`; node snapshot on FX thread, downscale via `SnapshotParameters` transform, PixelReader→BufferedImage (no `javafx.swing` dep) | RO |

Implementation notes: `mode=robot` = FX `Robot` on FX thread: `localToScreen` center (HiDPI-aware), `mouseMove`→press→release; `mode=event` = `Button.fire()` / synthesized `MouseEvent` via `Node.fireEvent`. `robot` is default; on headless CI lane, acceptance script runs under `xvfb` so Robot still applies.

## 8. Server internals

Single-threaded MCP dispatch (SDK default) → per-call FCP request with 10 s outer timeout; process supervisor thread; log ring; error-event drain; PNG re-encode/downscale if agent returns oversized image; all logging to stderr (`FXLOOP_LOG=debug|info`).

## 9. Sample app spec (`fxloop-sample-app`) — the test target

Small contact-manager, deliberately covering the risk surface:
- `TextField #nameField`, `ComboBox #categoryBox` (3 items — popup exercise H4), `Button #addBtn` (disabled while name empty — binding), `TableView #contactTable` (2 columns), `Button #deleteBtn` (context: acts on selection), `Button #boomBtn` (throws `IllegalStateException("boom")` in its handler — H6), `Label #statusLabel` (shows "n contacts"), one CSS file (`app.css`, used by MVP later), a `Launcher` class delegating to the `Application` subclass (classpath-launch pattern), fx:ids everywhere, JavaFX 21, Java 17.

## 10. Acceptance script (executable spec; must pass 10/10 locally and in `xvfb` CI)

Driven purely through MCP tools (a scripted MCP client in `fxloop-server/src/test` **and** manually via Claude Code):
1. `fx_get_app_status` → auto-start → `running`, 1 window.
2. `fx_take_screenshot` (window, `label:"baseline"`) → PNG < 300 KB at `maxWidth=1200`; file exists at the returned `target/fxloop/screens/` path (dual-channel, PRD 04).
3. `fx_find_nodes {query:"#nameField"}` → 1 hit; `fx_set_text` → "Ada Lovelace".
4. `fx_get_property {#addBtn, disabled}` → `false` (was `true` before typing — read both to prove binding visibility).
5. `fx_click_node {#categoryBox}` (robot) → popup window appears in `windows[]`; `fx_find_nodes {text:"Work"}` in popup; robot-click it; verify `fx_get_property {#categoryBox, value} == "Work"`.
6. `fx_click_node {#addBtn}` → `fx_get_scene_graph` on `#contactTable` shows `itemCount==1`, visible cell "Ada Lovelace"; `#statusLabel.text == "1 contacts"`.
7. `fx_click_node {#boomBtn}` → `fx_get_ui_errors` returns 1 entry `IllegalStateException: boom`; app still `running` (H6 + resilience).
8. `fx_restart_app` → `running` again, table `itemCount==0`, prior FCP session cleanly replaced, total < 15 s.
9. Negative: `fx_set_property {#statusLabel, text}` while bound → `PROPERTY_BOUND` error with hint.
10. Deadlock probe (test-only FCP method `blockFxThread(8000)`): any UI tool during block → `FX_TIMEOUT` with non-empty `fxThreadStack`.

## 11. Eval suite (`evals/poc.xml`, per mcp-builder format)

5 read-only Q&A pairs against a pre-seeded sample-app state (3 contacts loaded via a `--seed` flag), each answerable only via tools, verified by string match. Examples: *"What is the text of the status label after start with seed data?"* → `3 contacts`; *"Which button is disabled when the name field is empty?"* → `addBtn`; *"What exception class does the Boom button raise?"* → `IllegalStateException`. Coding agent must author all 5 + verify answers before PoC sign-off.

## 12. Milestones & Definition of Done

- **M1 — Skeleton & supervision (H1):** parent POM, protocol module, server starts via stdio, launches/kills/restarts sample app, `fx_get_app_status`/`fx_get_logs` pass MCP Inspector. DoD: restart loop 10/10.
- **M2 — Agent & scene (H2, H3):** handshake, FCP, FX marshaling with timeout+stack capture, `fx_get_scene_graph`, `fx_find_nodes`, properties. DoD: script steps 1, 3–4, 9–10 green.
- **M3 — Act & see (H4, H5):** Robot/event clicks, `fx_type_keys`, `fx_set_text`, screenshots. DoD: steps 2, 5–6 green.
- **M4 — Hear & harden (H6):** error ring + events, negative paths, `xvfb` CI job, evals authored & passing, README quickstart with `.mcp.json` snippet. DoD: full script 10/10 local + CI; all 6 hypotheses ticked with evidence links in `docs/decisions/poc-exit.md`.

## 13. Risks & fallbacks

| Risk | Fallback |
|---|---|
| Robot flaky under `xvfb` | CI lane may pin `mode=event` for steps where semantics allow; keep robot mandatory locally |
| ComboBox popup not found via lookup | Popup windows are separate windows — search `windows[]` then their scene graphs (spec already mandates this); fallback: keyboard selection via `fx_type_keys` |
| `dependency:build-classpath` quirks (Win separators, multi-module) | `-Dmdep.pathSeparator` set explicitly; PoC constraint: single-module app documented |
| Handshake race on slow start | File polling + 30 s timeout + log-tail in the error |

## 14. Open questions (answer during build, log decisions)

1. Auto-start on first tool call vs explicit `fx_start_app` only? (Default: auto-start with a note in the result.)
2. Include `fx_get_node_details` (full reflective property dump) now or fold into MVP? (Default: fold into MVP; `fx_get_property` suffices.)
3. Minimum JavaFX for sample app — 21 confirmed?

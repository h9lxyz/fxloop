# PRD 02 — FXLoop MVP (Stage B)
**Objective:** turn the validated PoC into the thing developers actually run every day: a **tiered-reload dev loop** on Maven with first-class plugin support, semantic diffs after every action, restart-and-restore, and a publishable 0.x on Maven Central.

**Entry gate:** PoC exit criteria (PRD 01 §12 M4) fully met. **Frame:** everything here serves the edit→see→verify cycle (Principle 1).

---

## 1. What MVP adds (feature map)

| # | Feature | Persona value |
|---|---|---|
| F1 | `fxloop-maven-plugin` — `fxloop:prepare` | P2: zero manual config; P1: reliable launch descriptor |
| F2 | Reload subsystem — watcher + router + tiers (CSS / FXML / HotSwap / restart) | P1+P2: seconds instead of restarts |
| F3 | Compile pipeline with **structured diagnostics** | P1: fix compile errors without leaving the loop |
| F4 | FXML live swap via `FXMLLoader` interception **[R&D]** with manual fallback | P1+P2: view iteration without restart |
| F5 | Restart-and-restore — journal, replay, stage-geometry persistence | P1: keeps its place across structural changes |
| F6 | **Scene-diff engine** — `effects` attached to every action/reload result | P1: the single biggest token/iteration saver |
| F7 | Addressing v2 — resolvable stable paths, `fx_describe_at` | P1: replay-safe targeting |
| F8 | `fx_lint_fxml` | P1: kills a whole runtime-error class at edit time |
| F9 | Embedded mode (`fxloop-dev` compile dependency) | P2: IDE-launched apps join the loop |
| F10 | Log capture upgrade (JUL/SLF4J bridge, opt-in) + `fx_get_node_details` | P1: fuller ears/eyes |
| F11 | Packaging: Maven Central, docs, `.mcp.json` templates, cross-OS CI | Adoption |
| F12 | **Visual feedback pillar** — annotated screenshots, visual diff on CSS reload, `fx_compare_screenshots`/`fx_get_screenshot`, gallery, `fx_lint_visual` basics (full spec: **PRD 04**) | P1: catches CSS/visual bugs the scene graph cannot express; P2: sees the app in chat without running it |

## 2. F1 — Maven plugin (`fxloop:prepare`)

- Goal binds by invocation (not lifecycle). Writes `target/fxloop/launch.json`:
  `{mainClass, classpath[], modulePath[], addModules[], jvmArgs[], workingDir, sourceRoots[], resourceRoots[], outputDir, fcp:{...reserved}}`.
- `mainClass` resolution order: plugin `<configuration>` → `javafx-maven-plugin` config → `exec-maven-plugin` config → jar manifest `Main-Class` → error listing candidates found via class scan (`extends Application` / Launcher pattern).
- **Module-path support lands here:** if the project configures JavaFX on the module path, emit `modulePath`/`addModules`; server launches accordingly (`--module-path … --add-modules …`). Launcher-pattern classpath launches remain supported.
- Server integration: replaces PoC's shelled `dependency:build-classpath` — sequence becomes `mvn -q fxloop:prepare` → read `launch.json` → spawn JVM directly. `FXLOOP_MAIN_CLASS` env becomes optional override. Multi-module: run in the app module (`FXLOOP_PROJECT_DIR` points there); reactor handled by Maven itself.

## 3. F2/F3 — Reload subsystem (the heart of MVP)

**Watcher:** `java.nio.file.WatchService` on `sourceRoots` + `resourceRoots`, 150 ms debounce, classified change-sets (`.css`, `.fxml`, `.java`, `.properties`, other-resource).

**Primary mode is agent-driven:** the coding agent calls **`fx_reload`** after editing; the server routes the accumulated change-set through the tiers and returns exactly what happened. **Secondary human mode:** `watchMode=css-only|all|off` (default `css-only`) applies CSS instantly on save for P2's eyes; `all` also performs hotswap-tier reloads on save (never auto-restarts — restarts only ever happen via explicit tool call or `fx_reload{allowRestart:true}`).

**Tier routing (per change-set, cheapest wins; mixed sets execute tiers in this order):**

1. **CSS (< 300 ms):** copy changed file `resourceRoots→outputDir`, then agent-side: for every Scene/Parent whose `stylesheets` contains the matching URL → remove+re-add (cache-busting query param on `file:` URL). Requires the URL↔source-file mapping the plugin emitted. State fully preserved.
2. **FXML (F4):** re-copy resource, re-run `FXMLLoader` for affected document, swap old root in its parent (`replaceChild` semantics; if root of Scene → `scene.setRoot`). New controller instance → *view-local state resets* (documented). Requires load-registry, see §4. If document unregistered → fall through to restart suggestion.
3. **Java HotSwap (< 2 s):** compile changed files via `javax.tools.JavaCompiler` in-process (classpath from `launch.json`, `-d outputDir`, release from project). On diagnostics → **return them as structured data** `{file,line,col,kind,message}[]` and stop (no partial swap). On success → FCP `redefineClasses {name, bytesB64}[]` → `Instrumentation.redefineClasses`. Because the agent loaded at startup (D3), no JEP 451 warnings. Classify JVM rejections (`UnsupportedOperationException`, `ClassFormatError`, added/removed members) as `needsRestart` with the offending class + reason. Fallback compile path: `mvn -q -o compile` if in-process javac fails abnormally (annotation processors etc.) — then hotswap from refreshed `outputDir` by re-reading class bytes.
4. **Restart (< 6 s incl. replay):** only when tier 3 classified `needsRestart` or change-set includes build files (`pom.xml`). Executes F5 restore.

**`fx_reload` result shape:** `{tiersApplied:[{tier, files, ms, details}], compileErrors?, needsRestart?, restarted?, replay?, effects}` — one call, full story.

## 4. F4 — FXML load-registry **[R&D, timeboxed 3 days]**

- **Plan A:** ByteBuddy `AgentBuilder` retransform of `javafx.fxml.FXMLLoader` (`Advice.OnMethodExit` on `load*`): record `{location → WeakRef(root), controllerClass}`; no added members (retransform-safe). Must handle module-path (`--add-opens` not required for transform; verify on JavaFX 17/21).
- **Plan B (ships regardless, is also the public API):** `FxLoopDev.track(loader)` / `FxLoopDev.load(location)` helper in `fxloop-dev` (F9) registering the same mapping explicitly.
- Tool `fx_reload_fxml {path, targetHandle?}` exists for manual swaps either way. Registry contents visible via `fx_get_app_status.fxml[]`.

## 5. F5 — Restart-and-restore

- **Journal:** server-side ordered log of successful *action* tools (`fx_click_node`, `fx_type_keys`, `fx_set_text`, `fx_set_property`) storing resolved **paths** + params + a pre-action window fingerprint. Ring 500. `fx_get_journal {limit}` for transparency.
- **Replay:** after restart (and only on request: `fx_restart_app{replay:true}` or via reload tier 4 default-on, configurable): re-execute journal entries whose target path resolves; before each step wait for path-visible (poll ≤ 3 s); stop at first failure → result reports `{replayed:n, failedAt:{step, reason}}`. Replay entries are not re-journaled.
- **Stage geometry:** agent persists `{x,y,w,h,maximized,screenId}` per stage-index to `target/fxloop/state.json` on change (debounced); restores after `Stage.show()` on next start.

## 6. F6 — Scene-diff engine (`effects`)

- Server keeps the last scene snapshot (path-indexed, capped per §PoC budgets) per window. After every action tool and every `fx_reload`, re-snapshot (same budgets) and diff:
  `effects: {added:[{path,type,text?}], removed:[path], changed:[{path,prop,old,new}], windowChanges:[opened/closed/titleChanged], overflow:boolean}` — hard cap 50 entries + `overflow`.
- Watched props for `changed`: `text, value, visible, disabled, selected, focused, itemCount, progress` (extensible table).
- Diff computation must stay < 150 ms on the sample app; skip via param `effects:false`.
- **This is the flagship feature — acceptance workflow W2 exercises it explicitly.**

## 7. F7 — Addressing v2

- Path grammar (formalize + document): `Stage[i]/Scene/Type#fxId` segments; child step `Type[i]` (index among same-type siblings) with `#fxId` shortcut segment allowed at any depth (`…//Button#saveBtn` = first match under prefix). Resolver in agent; ambiguous → error listing candidates.
- All tools accept `path` everywhere `handle` is accepted (PoC promised emission; MVP delivers resolution).
- New: `fx_describe_at {x,y}` → deepest pickable node at screen point (`path`, chain of ancestors, bounds) — for "what did I just see in the screenshot".

## 8. F8 — `fx_lint_fxml`

Params `{path?|all:true}`. Static: parse FXML (namespace-aware), load controller class from `outputDir` classpath (isolated `URLClassLoader`, no static init side-effects → `Class.forName(name, false, cl)`): report `{missingFxIdField, typeMismatch, missingHandlerMethod, handlerSignatureInvalid, unresolvedImport, duplicateFxId}` each `{file,line?,detail,suggestion}`. Also runnable headlessly before app start. (Line numbers via SAX locator where available.)

## 9. F9/F10 — Embedded mode & richer senses

- `fxloop-dev` artifact: `FxLoopDev.install()` (or auto via `ServiceLoader` when sysprop `fxloop.dev=true`) starts the identical agent core in-process for IDE-launched apps; server gains `fx_connect_existing {handshakePath}` (dev-loop trust model: same user, same machine — this is *not* the Stage-C attach mode).
- Log capture: optional JUL handler + SLF4J `ServiceLoader` bridge (only if slf4j present) feeding the same ring with `{level, logger}` fields; `fx_get_logs` gains `source=process|app|both`.
- `fx_get_node_details {handle|path}` — full reflective property dump (name, type, value one-liner, bound, styleable), paginated.

## 10. Tool-surface delta (vs PoC)

New: `fx_reload`, `fx_reload_fxml`, `fx_get_journal`, `fx_describe_at`, `fx_lint_fxml`, `fx_get_node_details`, `fx_connect_existing`, `fx_set_watch_mode`, and from F12 (PRD 04): `fx_compare_screenshots`, `fx_get_screenshot`, `fx_lint_visual`. Changed: all action tools + `fx_restart_app` gain `effects`/`replay`; `fx_get_logs` gains `source`; `fx_take_screenshot` gains `annotate` (Set-of-Mark markers + legend) and `scale` (supersampled node zoom); `fx_reload` CSS tier gains auto `visualDiff` (changedPct, regions, diffPath — incl. the 0 %-change "selector didn't match" hint). Annotations per §Conventions; `fx_reload` = write+idempotent-ish (documented), `fx_lint_fxml`/`fx_lint_visual` = RO.

## 11. Non-functional requirements

- **OS:** Windows 11, macOS 14+, Ubuntu 22.04+ — full acceptance on all three (CI: Linux `xvfb` + macOS runner + Windows runner for the scripted client; Robot lanes may run non-headless on mac/win runners).
- **Matrix:** JDK 17 & 21 × JavaFX 17 & 21 (sample app pinned per lane).
- **Latency budgets** as in Overview §9 — enforced by tests on the sample app.
- **Packaging:** publish `fxloop-{protocol,agent,server,maven-plugin,dev}` 0.x to Maven Central (sources+javadoc, GPG-signed); server also as executable shaded jar + `jbang` alias; README quickstart ≤ 10 lines to first tool call; `.mcp.json` template documented for Claude Code.
- **Stability:** 2-hour scripted soak (edit/reload/act loop) — zero handle leaks (WeakRef table bounded), zero FCP disconnects.

## 12. Acceptance workflows (all via MCP, scripted + manual Claude Code run)

- **W1 CSS loop:** edit `app.css` color → `fx_reload` → tier css < 300 ms → screenshot shows change → app state (typed text) preserved.
- **W2 Logic loop + effects:** edit `addContact()` body (append "!" to name) → `fx_reload` → hotswap < 2 s → `fx_click_node #addBtn` → `effects.added` contains new row with "Ada!"; `effects.changed` contains `#statusLabel.text 0→1 contacts`.
- **W3 Compile-error loop:** introduce syntax error → `fx_reload` → structured diagnostic with file/line; fix → hotswap ok.
- **W4 Structural + replay:** add a new field to controller → `fx_reload` → `needsRestart` → auto restart+replay returns UI to pre-edit view (journal ≥ 3 steps), geometry preserved.
- **W5 FXML loop:** edit label text in FXML → `fx_reload` → tier fxml swap (Plan A or via `FxLoopDev.load`) → visible without restart.
- **W6 Lint:** rename `fx:id` in FXML only → `fx_lint_fxml` reports `missingFxIdField` with suggestion.
- **A-V1 Visual bug hunt (PRD 04):** sample app's `--broken-css` flag seeds a misaligned button + a white-on-white label → one annotated `fx_take_screenshot` + `fx_lint_visual` names both node paths.
- **A-V2 Selector miss (PRD 04):** edit `app.css` with a non-matching selector → `fx_reload.visualDiff.changedPct == 0` + selector hint; corrected selector → change confined to the expected region.
- **A-V3 Human channel (PRD 04):** W1's color change verified by `visualDiff` *and* a persisted frame (`fx_get_screenshot`) the developer opens without ever focusing the app window.
- **Evals:** `evals/mvp.xml` — 10 task-style Q&A pairs (read-only where possible) covering diffs, lint output, journal introspection; authored + self-verified per mcp-builder guide.

## 13. Milestones

- **M5 Plugin & launch v2** (F1, module-path) → W-launch on 3 OS.
- **M6 Reload core** (F2/F3 tiers css+java, diagnostics) → W1–W3.
- **M7 Restore & effects** (F5, F6, F7, F12: dual-channel persistence, `fx_compare_screenshots`/`fx_get_screenshot`, CSS-tier `visualDiff`) → W2 effects assertions + W4 + A-V2/A-V3.
- **M8 FXML & lint** (F4 spike→decision doc, F8) → W5, W6.
- **M9 Senses & embedded** (F9, F10, F12: `annotate`+legend, `scale`, gallery, `fx_lint_visual` basics) → A-V1.
- **M10 Ship 0.x** (F11: publish, docs, matrix CI, soak) → all workflows green on matrix; evals pass.

## 14. Risks

| Risk | Mitigation |
|---|---|
| HotSwap limits confuse users ("why restart?") | Always name the offending class + change kind; docs table of swap-safe edits |
| FXMLLoader retransform blocked on some setups | Plan B is first-class API, not a stopgap; decision doc after spike |
| In-process javac vs annotation processors (Lombok!) | Detect APs on classpath → route straight to `mvn -o compile` fallback; document |
| Replay flakiness on timing-dependent UIs | Per-step visibility wait + fail-fast report; replay optional flag |
| Diff cost on big graphs | Same budgets as serialization; `effects:false` escape hatch |
| Windows file-lock on `target/classes` during copy/compile | Retry-with-backoff writes; tests on Windows runner |

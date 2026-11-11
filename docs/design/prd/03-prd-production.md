# PRD 03 — FXLoop Production 1.0 (Stage C)
**Objective:** make FXLoop dependable enough for teams and CI: **Gradle parity**, headless pipelines, attach-to-running-app as a controlled secondary mode, test export that makes agent work durable, deep "why?" introspection, and a hardened, versioned 1.0 release.

**Entry gate:** MVP M10 shipped; ≥ 2 external projects ran the MVP loop (dogfooding feedback folded into backlog triage below).

---

## 1. Feature map

| # | Feature | Notes |
|---|---|---|
| G1 | **Gradle plugin** (`fxloop-gradle-plugin`) | The "later on Gradle" requirement — full parity with Maven flow |
| G2 | Attach mode (`fx_attach {pid}`) | Secondary, dev/test framing, JEP 451-aware |
| G3 | Headless CI (Monocle) + pipeline templates | GitHub Actions first; xvfb recipes retained |
| G4 | **TestFX export** — journal → JUnit 5 test source | Durable output of agent sessions |
| G5 | Introspection pro: `fx_explain_property`, `fx_explain_style`, `fx_explain_layout` | The "why is this disabled/styled/misaligned" tools |
| G6 | `fx_eval_js` (WebView bridge) | Opt-in, WebView-containing apps |
| G7 | Security & safety hardening | Threat model, guards, supply chain |
| G8 | Robustness matrix | Dialogs, menus, multi-stage, HiDPI, multi-monitor |
| G9 | Performance at scale | Large graphs, virtualized controls, image budgets |
| G10 | JBR enhanced-hotswap opt-in | Structural swaps without restart where available |
| G11 | Release engineering & compatibility policy | 1.0 semver, FCP negotiation, signed, docs site |
| G12 | **Visual pro** (spec: PRD 04 §3.5–3.7) — `fx_capture_states` pseudo-state strips, `lowContrast` lint, opt-in live localhost viewer, HiDPI annotation-coordinate gauntlet rows | Completes the visual pillar: CSS state workbench + "watch the app without the app" |

## 2. G1 — Gradle parity

- Plugin id `org.lab59.fxloop` (Gradle Plugin Portal + Central). Task `fxloopPrepare` emits the **identical** `build/fxloop/launch.json` schema (single source of truth: schema lives in `fxloop-protocol`, both plugins depend on it; JSON-schema file published).
- Resolution order: plugin extension → `org.openjfx.javafxplugin` config → `application.mainClass` → manifest → candidate scan (mirrors Maven order).
- Server autodetects build tool: `pom.xml` vs `build.gradle(.kts)`/`settings.gradle*`; prefers wrapper (`gradlew`/`mvnw`); override `FXLOOP_BUILD_CMD`.
- Reload pipeline is build-tool-independent by design (in-process javac from `launch.json` classpath), so tiers 1–3 need **zero** Gradle-specific work; tier-4 restart runs `gradlew fxloopPrepare` + relaunch. Optional: document `gradle -t classes` continuous mode as an alternative compile source (watcher then treats `outputDir` changes as hotswap triggers — feature-flagged `FXLOOP_EXTERNAL_COMPILE=true`, also usable with IDE auto-build).
- Acceptance: entire MVP workflow suite W1–W6 re-run on a Gradle-built twin of the sample app (`fxloop-sample-app-gradle`), all three OS.

## 3. G2 — Attach mode (secondary)

- `fx_attach {pid?|list:true}`: `list` enumerates JVMs (`VirtualMachine.list()`) flagging those with JavaFX on the classpath where detectable. Attach path: `VirtualMachine.attach → loadAgent(agentJar, "handshake=…,token=…")` → same FCP handshake.
- **JEP 451 handling:** detect target JDK ≥ 21 → result warns that the target prints a dynamic-agent warning and recommends `-XX:+EnableDynamicAgentLoading` for clean runs; detect failure modes (no `java.instrument` in jlinked runtime, attach disabled) → actionable errors naming the fix (relaunch via dev-loop mode).
- Constraints: attach implies **no reload tiers** (no `launch.json`, no source mapping) and **no supervision** (`fx_restart_app` disabled → error explains why); journal/effects/screenshots/act tools all work. Server marks session `mode:attached` in every `fx_get_app_status`.
- Consent & audit: attaching requires explicit tool call (never automatic), logs an audit line, and refuses if `fxloop.attach=false` sysprop is set on the *server*.

## 4. G3 — Headless CI

- Monocle lane: launch flag `FXLOOP_HEADLESS=monocle` adds `-Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw -Dprism.text=t2k(where needed)` + the pinned `openjfx-monocle` artifact per JavaFX line (**maintain our own compatibility table**; if no artifact exists for a JavaFX version, document xvfb as the supported lane for it — honest gap, not a silent break).
- Robot + snapshot verified under Monocle (sw pipeline); acceptance script + W1–W4 run headless in CI.
- Ship `docs/ci/`: GitHub Actions workflow templates (xvfb lane, Monocle lane, Windows/mac lanes), cache guidance, flaky-quarantine pattern.

## 5. G4 — TestFX export

- `fx_export_test {name, journalRange?, package?}` → generates `src/test/java/**/<Name>GeneratedTest.java`: JUnit 5 + TestFX `ApplicationExtension`, app bootstrap from `launch.json` mainClass.
- Mapping: journal click/type/setText → `clickOn("#fxId")`/`write(...)`/lookup-based interactions; targets without fx:id → generated `FxPath.lookup(root, "<path>")` helper (small runtime-free utility emitted once into the test sources).
- Assertions synthesized from recorded `effects`: text/disabled/visible/itemCount changes → `Assertions`/`FxAssert` checks; ambiguous effects → `// TODO(fxloop): verify` markers. Header comment: *generated draft — review before trusting*.
- DoD: exported test from acceptance W2 compiles and passes green in the sample project's own `mvn test` (headless lane), no manual edits.

## 6. G5 — Introspection pro ("why?")

- `fx_explain_property {handle|path, name}` → `{value, type, bound, bindingClass?, dependencyTree?}`: walk `Binding.getDependencies()` recursively (public API), depth ≤ 5, each dep rendered as owner-node path + property name where identifiable. Bidirectional bindings: flagged `bidirectional:unknown-peer` (not discoverable via public API — stated, not guessed). For styleables: include `styleOrigin` (`StyleableProperty.getStyleOrigin()`).
- `fx_explain_style {handle|path, property?}` → per styleable property: `{cssProperty, value, origin:USER_AGENT|AUTHOR|INLINE|USER, inlineStyle?, candidateStylesheets:[...scene+parent chain...]}` via `Node.getCssMetaData()`. Rule-level source mapping (exact selector/line) is **out of scope 1.0** — origin + candidates only, documented.
- `fx_explain_layout {handle|path}` → `{layoutBounds, boundsInParent, boundsInScreen, insets/padding, margins?, HGrow/VGrow/anchors (per parent type table), managed, resizable, min/pref/max}` + one-line anomaly hints (e.g., "unmanaged node ignored by layout").
- Each returns compact text + structured; each capped and paginated where list-like.

## 7. G6 — WebView bridge

`fx_eval_js {handle|path, script, timeoutMs=3000}` → runs `webEngine.executeScript` on FX thread, JSON-serialize result (primitives, JSON via `JSON.stringify` wrapping for objects), errors surfaced with JS message. Guard: only on `WebView` nodes; disabled unless `FXLOOP_WEBVIEW=true` (keeps default tool list lean).

## 8. G7 — Security & safety hardening (ships as `docs/security.md` + enforcement)

- **Threat model (stated):** same-user local processes are trusted-ish; defenses target cross-user localhost access, accidental exposure, and accidental production shipment. FCP: loopback-only, token mandatory (already), constant-time compare, handshake file 0600, frame cap 8 MB, per-session rate cap on screenshots (default 2/s) to bound resource abuse.
- **Prod-guard:** agent no-ops without handshake arg (PoC guarantee) **plus** refuses when `fxloop.enabled=false`; `fxloop-dev` auto-install additionally requires `fxloop.dev=true`. Docs: scope guidance (never `compile` scope for `fxloop-dev` in shipped apps → `provided`/`test`/dev-profile), jlink exclusion note, enforcer-rule snippet that fails the build if `fxloop-*` leaks into a production classifier.
- Input validation sweep: path params canonicalized + confined to project dir; property-name allowlist pattern; `fx_eval_js` behind flag; no secrets ever logged (token redaction in all logs).
- Supply chain: GPG-signed artifacts, provenance/SBOM (CycloneDX) attached to releases, ByteBuddy/jackson pinned + Dependabot.

## 9. G8 — Robustness matrix (test-backed, not aspirational)

Dedicated `fxloop-gauntlet-app` + suite covering: modal `Alert`/`Dialog` (scene-graph reachable — verify act+see), `ContextMenu`/`MenuBar`/`Tooltip` windows, multi-`Stage` (target by `windowIndex`, focus handling), `Popup` positioning, HiDPI 125 %/150 %/200 % coordinate correctness (robot click centers), multi-monitor negative-coordinate spaces, `TabPane`/`ScrollPane` (auto-scroll-to-node before robot click — new behavior: `ensureVisible` best effort), fullscreen stages, RTL locale smoke. Each row = at least one automated test; known-impossible rows (OS-native `FileChooser`) documented in a *Limitations* page with the mock-factory recipe.

## 10. G9 — Performance at scale

Benchmarks in CI (thresholds fail the build): scene serialize ≤ 150 ms and diff ≤ 200 ms on a 5 000-node graph (budget-truncated output ≤ 64 KB JSON); `TableView` with 100 k items serializes visible-window only; screenshot pipeline ≤ 250 ms at 1200 px on reference runner; agent steady-state heap overhead ≤ 16 MB on gauntlet app; soak: 8 h scripted loop, zero leaks (handles, listeners — verify uncaught-handler and geometry listeners are weak/removed on FCP disconnect).

## 11. G10 — JBR enhanced hotswap (opt-in)

If launched JVM reports JetBrains Runtime with `-XX:+AllowEnhancedClassRedefinition` (or user sets `FXLOOP_JBR=true` + toolchain path), tier-3 classifier upgrades: structural changes attempt redefine first, restart only on JVM rejection. Zero behavior change on stock JVMs; matrix lane optional/allowed-to-skip when JBR unavailable.

## 12. G11 — Release engineering & compatibility

- **Versioning:** SemVer from 1.0; **FCP version negotiation** in `hello` (server supports N and N-1; mismatch → precise upgrade error). Tool-surface changes follow deprecation policy (≥ 1 minor with warnings).
- Support matrix published & CI-enforced: JDK 17/21/25 × JavaFX 17/21/latest-GA × Win/mac/Linux × Maven/Gradle (pruned to a sane 10-lane grid, documented).
- Docs site (mkdocs or similar): quickstart (Maven, Gradle, Claude Code), tool reference generated from code annotations, limitation pages, security page, CI recipes, troubleshooting (top-10 error codes with fixes).
- Release automation: tag → CI builds, signs, publishes Central + Plugin Portal, generates notes; `evals/production.xml` (10 tasks incl. attach-mode read-only tasks and an export-test task) must pass on the release candidate.

## 13. Milestones

- **M11 Gradle** (G1) → MVP workflows on Gradle twin, 3 OS.
- **M12 Headless & perf** (G3, G9) → Monocle lanes green, benchmarks enforced.
- **M13 Why-tools & WebView** (G5, G6, G12: `fx_capture_states`, contrast lint, live viewer).
- **M14 Attach & hardening** (G2, G7) → threat-model review checklist signed off.
- **M15 Gauntlet & export** (G8, G4) → exported test green in CI.
- **M16 1.0** (G10, G11) → soak passed, docs live, evals green, tag shipped.

## 14. Deferred beyond 1.0 (tracked, not committed)

Streamable-HTTP multi-client transport; Swing interop; visual-regression assertions; recorder UI for humans; IntelliJ plugin surface; Kotlin/Compose-for-Desktop exploration; rule-level CSS source mapping; localization-aware text lookup.

# FXLoop — Implementation Tracking (Document 5)

Feature/task mapping for PRDs 00–04 and 06 with live status. One row per deliverable; IDs reference the PRD section that specifies it.

**Status legend:** ⬜ todo · 🟨 in progress · ✅ done · ⚠️ done with documented deviation · ⏭️ deferred / out of local scope (reason given)

## Technology choices ("don't reinvent the wheel")

| Concern | Library / tool | Version |
|---|---|---|
| MCP server + test client | `io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson2` (official Java SDK) | 2.1.0 |
| JSON (FCP, launch.json) | Jackson databind (shaded/relocated in agent) | 2.21.x |
| Class instrumentation (FXMLLoader registry) | ByteBuddy (`byte-buddy`, `byte-buddy-agent` for attach) | 1.18.x |
| File watching (cross-OS, macOS-native) | `io.methvin:directory-watcher` | 0.19.x |
| In-process compile | `javax.tools.JavaCompiler` (JDK) | JDK |
| JVM attach | `jdk.attach` (`VirtualMachine`) | JDK |
| Live viewer HTTP | `com.sun.net.httpserver` (`jdk.httpserver`, supported public API) | JDK |
| Image encode/diff/annotate | `javax.imageio` + `java.awt.Graphics2D` (server-side only) | JDK |
| UI toolkit (provided scope) | OpenJFX | 21.0.13 |
| Testing | JUnit 5, AssertJ, TestFX, Monocle (`org.testfx:openjfx-monocle`) | 5.x / 4.0.18 / 21.0.2 |
| Packaging | maven-shade-plugin, maven-plugin-plugin, Gradle `java-gradle-plugin` | latest |
| SBOM / formatting | cyclonedx-maven-plugin, spotless | latest |

Baseline: Java 17 bytecode (`--release 17`), built and tested locally on JDK 25 + JavaFX 21.

---

## Stage A — PoC (PRD 01)

| ID | Feature / task | PRD ref | Module | Status | Notes |
|---|---|---|---|---|---|
| A0 | Multi-module Maven parent, wrapper, Apache-2.0 license | 00 §6 | root | ⬜ | |
| A1 | FCP v1 DTOs, NDJSON codec, error codes, handshake record | 01 §6 | protocol | ⬜ | |
| A2 | Server config (env + CLI flags) | 01 §3 | server | ⬜ | |
| A3 | Launch pipeline: compile, build-classpath, token, spawn, log rings | 01 §3 | server | ⬜ | |
| A4 | Handshake polling + FCP `hello` + timeout w/ log tail | 01 §3 | server | ⬜ | |
| A5 | Stop/restart supervision (destroy → forcibly after 3 s) | 01 §3 | server | ⬜ | |
| A6 | Agent `premain`, zero-footprint no-op, FX readiness, loopback socket, 0600 handshake | 01 §4 | agent | ⬜ | |
| A7 | `callOnFx` with timeout + FX-thread stack capture (`FX_TIMEOUT`) | 01 §4 | agent | ⬜ | |
| A8 | Uncaught FX exception ring (200, dedup 2 s) + `uiError` events | 01 §4 | agent | ⬜ | |
| A9 | Node handle table (weak) + path emission | 01 §4 | agent | ⬜ | |
| A10 | Scene-graph serializer with budgets, virtualized summaries, windows array | 01 §5 | agent | ⬜ | |
| A11 | Agent shading (jackson relocated under `fxloop.internal`) | 01 §4 | agent | ⬜ | |
| A12 | Tools: `fx_get_app_status`, `fx_start_app`, `fx_restart_app`, `fx_stop_app` | 01 §7 | server | ⬜ | |
| A13 | Tools: `fx_get_logs`, `fx_get_ui_errors` | 01 §7 | server | ⬜ | |
| A14 | Tools: `fx_get_scene_graph`, `fx_find_nodes` | 01 §7 | server+agent | ⬜ | |
| A15 | Tools: `fx_click_node` (robot/event), `fx_type_keys`, `fx_set_text` | 01 §7 | server+agent | ⬜ | |
| A16 | Tools: `fx_get_property`, `fx_set_property` (coercion, `PROPERTY_BOUND`) | 01 §7 | server+agent | ⬜ | |
| A17 | Tool: `fx_take_screenshot` dual-channel (image + persisted PNG, label, settleMs) | 01 §7, 04 §1 | server+agent | ⬜ | |
| A18 | Tool annotations, in-result errors w/ hints, text + structuredContent | 00 §7 | server | ⬜ | |
| A19 | Sample app (contact manager, Launcher, fx:ids, `--seed`) | 01 §9 | sample-app | ⬜ | |
| A20 | Acceptance script steps 1–10 as automated test (scripted MCP client) | 01 §10 | acceptance | ⬜ | |
| A21 | Test-only `blockFxThread` FCP method (gated by sysprop) | 01 §10 | agent | ⬜ | |
| A22 | `evals/poc.xml` (5 Q&A) | 01 §11 | evals | ⬜ | |
| A23 | xvfb CI workflow, README quickstart + `.mcp.json` | 01 §12 | ci/docs | ⬜ | |
| A24 | `docs/decisions/poc-exit.md` (H1–H6 evidence) | 01 §12 | docs | ⬜ | |

## Stage B — MVP (PRD 02 + PRD 04 MVP parts)

| ID | Feature / task | PRD ref | Module | Status | Notes |
|---|---|---|---|---|---|
| B1 | `launch.json` schema in protocol (+ JSON schema file) | 02 §2, 03 §2 | protocol | ⬜ | |
| B2 | `fxloop-maven-plugin` `prepare` goal, mainClass resolution order, module-path | 02 §2 (F1) | maven-plugin | ⬜ | |
| B3 | Server launch v2: `fxloop:prepare` → read launch.json → spawn; module-path launch | 02 §2 | server | ⬜ | |
| B4 | Watcher (debounce 150 ms, classified change-sets), `fx_set_watch_mode` | 02 §3 (F2) | server | ⬜ | |
| B5 | Reload router + `fx_reload` result shape | 02 §3 | server | ⬜ | |
| B6 | Tier 1 CSS reload (copy + agent stylesheet re-add, cache-bust) | 02 §3 | server+agent | ⬜ | |
| B7 | Tier 3 in-process javac + structured diagnostics + `redefineClasses` + needsRestart classification | 02 §3 (F3) | server+agent | ⬜ | |
| B8 | Fallback compile via build tool (annotation processors detected) | 02 §3 | server | ⬜ | |
| B9 | Tier 4 restart route (build files / needsRestart) | 02 §3 | server | ⬜ | |
| B10 | FXML load-registry Plan A (ByteBuddy advice on `FXMLLoader.load`) + decision doc | 02 §4 (F4) | agent | ⬜ | |
| B11 | FXML Plan B `FxLoopDev.track/load` + `fx_reload_fxml` + tier 2 swap | 02 §4 | dev+agent | ⬜ | |
| B12 | Journal (ring 500) + `fx_get_journal` | 02 §5 (F5) | server | ⬜ | |
| B13 | Replay after restart (path-visible wait, fail-fast report) | 02 §5 | server | ⬜ | |
| B14 | Stage geometry persistence `state.json` | 02 §5 | agent | ⬜ | |
| B15 | Scene-diff engine → `effects` on all actions + reload | 02 §6 (F6) | server | ⬜ | |
| B16 | Path grammar + resolver (`Type[i]`, `#id`, `//` descendant), ambiguity errors | 02 §7 (F7) | agent | ⬜ | |
| B17 | `fx_describe_at` | 02 §7 | agent | ⬜ | |
| B18 | `fx_lint_fxml` (SAX + isolated classloader) | 02 §8 (F8) | server | ⬜ | |
| B19 | `fxloop-dev` embedded mode + `fx_connect_existing` | 02 §9 (F9) | dev+server | ⬜ | |
| B20 | JUL / SLF4J log capture → `fx_get_logs source=` | 02 §9 (F10) | agent+server | ⬜ | |
| B21 | `fx_get_node_details` (reflective dump, paginated) | 02 §9 | agent | ⬜ | |
| B22 | Screenshot `annotate` (Set-of-Mark + legend, server-side composite) + `scale` | 04 §3.1 | server+agent | ⬜ | |
| B23 | `fx_compare_screenshots` (threshold, clustered regions, diff composite, verdict) | 04 §3.2 | server | ⬜ | |
| B24 | `fx_get_screenshot` | 04 §3.3 | server | ⬜ | |
| B25 | CSS-tier `visualDiff` + selector hints | 04 §3.4 | server | ⬜ | |
| B26 | Gallery `index.html` + retention 100 | 04 §1 | server | ⬜ | |
| B27 | `fx_lint_visual` basic (textTruncated, siblingOverlap, zeroSizeVisible, outOfScene) | 04 §3.5 | agent | ⬜ | |
| B28 | Sample app `--broken-css` (misaligned button, white-on-white label) | 04 §5 | sample-app | ⬜ | |
| B29 | Acceptance W1–W6, A-V1–A-V3 automated | 02 §12 | acceptance | ⬜ | |
| B30 | `evals/mvp.xml` (10 + 3 visual) | 02 §12 | evals | ⬜ | |
| B31 | Packaging: shaded server jar, jbang alias, sources/javadoc, release profile (GPG) | 02 §11 (F11) | root | ⬜ | |
| B32 | Cross-OS CI matrix workflows | 02 §11 | ci | ⬜ | |
| B33 | Soak test harness (scripted loop) | 02 §11 | acceptance | ⬜ | |

## Stage C — Production 1.0 (PRD 03 + PRD 04 production parts)

| ID | Feature / task | PRD ref | Module | Status | Notes |
|---|---|---|---|---|---|
| C1 | `fxloop-gradle-plugin` (`dev.fxloop`, `fxloopPrepare`, same schema) | 03 §2 (G1) | gradle-plugin | ⬜ | |
| C2 | Server build-tool autodetect, wrapper preference, `FXLOOP_BUILD_CMD` | 03 §2 | server | ⬜ | |
| C3 | `FXLOOP_EXTERNAL_COMPILE` (outputDir changes → hotswap) | 03 §2 | server | ⬜ | |
| C4 | Gradle twin of sample app | 03 §2 | sample-app-gradle | ⬜ | |
| C5 | `fx_attach {pid|list}` + JEP 451 warnings, audit, `fxloop.attach=false` | 03 §3 (G2) | server | ⬜ | |
| C6 | Monocle headless lane (`FXLOOP_HEADLESS=monocle`) + compat table | 03 §4 (G3) | server+docs | ⬜ | |
| C7 | CI templates in `docs/ci/` | 03 §4 | docs | ⬜ | |
| C8 | `fx_export_test` (journal → TestFX JUnit 5 + `FxPath` helper) | 03 §5 (G4) | server | ⬜ | |
| C9 | `fx_explain_property` (binding dependency tree) | 03 §6 (G5) | agent | ⬜ | |
| C10 | `fx_explain_style` (origin + candidate stylesheets) | 03 §6 | agent | ⬜ | |
| C11 | `fx_explain_layout` (bounds, insets, constraints, anomaly hints) | 03 §6 | agent | ⬜ | |
| C12 | `fx_eval_js` (WebView, behind `FXLOOP_WEBVIEW=true`) | 03 §7 (G6) | agent | ⬜ | |
| C13 | Security: constant-time token compare, frame cap, screenshot rate cap, `fxloop.enabled=false`, path confinement, token redaction | 03 §8 (G7) | all | ⬜ | |
| C14 | `docs/security.md` + enforcer snippet; CycloneDX SBOM | 03 §8 | docs/root | ⬜ | |
| C15 | Gauntlet app + robustness tests (dialogs, menus, multi-stage, tabs/scroll ensureVisible) | 03 §9 (G8) | gauntlet-app | ⬜ | |
| C16 | `ensureVisible` before robot click (ScrollPane/TabPane) | 03 §9 | agent | ⬜ | |
| C17 | Limitations page (FileChooser mock-factory recipe) | 03 §9 | docs | ⬜ | |
| C18 | Performance benchmarks (5 000-node serialize/diff thresholds) | 03 §10 (G9) | server tests | ⬜ | |
| C19 | JBR enhanced hotswap opt-in | 03 §11 (G10) | server | ⬜ | |
| C20 | FCP version negotiation (N, N-1) | 03 §12 (G11) | protocol+server | ⬜ | |
| C21 | Docs site skeleton (mkdocs), tool reference generated from registry, troubleshooting | 03 §12 | docs | ⬜ | |
| C22 | `evals/production.xml` | 03 §12 | evals | ⬜ | |
| C23 | `fx_capture_states` (pseudo-class strip, restore in finally) | 04 §3.6 (G12) | agent+server | ⬜ | |
| C24 | `fx_lint_visual` `lowContrast` (WCAG) | 04 §3.5 | agent | ⬜ | |
| C25 | Live viewer (`FXLOOP_VIEWER`, token, `/latest.png`, `/index`, `/sse`) | 04 §3.7 | server | ⬜ | |

## Stage D — Developer skills (PRD 06)

| ID | Feature / task | PRD ref | Module | Status | Notes |
|---|---|---|---|---|---|
| D1 | Plugin skeleton: `.mcp.json` bootstrap (jbang / wrapper-resolved shaded jar), skills dir, generated tool reference | 06 §3.1, §7 | plugin | ⬜ | |
| D2 | S0 `fxloop-connect`: detect build tool/language, add plugin + guard, prepare, launch, first screenshot, CLAUDE.md trail | 06 §3.2 | plugin | ⬜ | DoD: < 2 min, zero manual edits, idempotent |
| D3 | S1 `fxloop-ui-review` | 06 §4 | plugin | ⬜ | eval on `--broken-css` |
| D4 | S2 `fxloop-fix-layout` | 06 §4 | plugin | ⬜ | |
| D5 | S3 `fxloop-style` | 06 §4 | plugin | ⬜ | |
| D6 | References 1–4 (evidence, javafx-css, layout-containers, fx-threading) | 06 §5 | plugin | ⬜ | |
| D7 | Kotlin sample twin (`fxloop-sample-app-kotlin`) + Kotlin notes verified | 06 §6 | sample-app-kotlin | ⬜ | |
| D8 | S4 `fxloop-accessibility`, S5 `fxloop-responsive` | 06 §4 | plugin | ⬜ | needs lowContrast lint (C24) |
| D9 | S6 `fxloop-flow-polish`, S7 `fxloop-forms-dialogs`, S8 `fxloop-consistency` | 06 §4 | plugin | ⬜ | needs explainers (C9–C11) |
| D10 | S9 `fxloop-lock-in` (journal → TestFX, harden, headless run) | 06 §4 | plugin | ⬜ | needs export (C8) |
| D11 | References 5–8 (accessibility, fxml, testfx, tool-reference) | 06 §5 | plugin | ⬜ | tool-reference generated (C21) |
| D12 | Skill evals (3–5 tasks per skill on sample apps) + trigger tests | 06 §7 | evals | ⬜ | |

## Items requiring external resources (cannot be completed in this local session)

Publishing to Maven Central / Gradle Plugin Portal (needs credentials + GPG key), running CI on Windows/macOS runners, dogfooding by external projects, 2 h / 8 h soak runs. The build/config for these is provided; execution is a human/CI step.

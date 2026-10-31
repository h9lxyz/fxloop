# FXLoop — Agentic Dev-Loop for JavaFX
## Product Overview, Architecture & Roadmap (Document 0 of 5)

> **Working name:** `fxloop` (rename freely; grep-safe token used consistently across all PRDs).
> **License:** Apache-2.0 (deliberate differentiator — the closest prior art, `fxgraph-mcp-server`, ships without a license).
> **Audience of this document set:** a coding agent (e.g., Claude Code) implementing the system, and the human product owner reviewing scope.

---

## 1. Vision

When an AI coding agent builds a **web** app, it has eyes and hands: Playwright/DevTools MCP servers expose the DOM, console, screenshots, and input. When the same agent builds a **JavaFX** app, it is blind — it writes code, compiles, and must ask the human what happened on screen.

**FXLoop closes that loop.** It is an MCP server plus an in-JVM agent that gives a coding agent:

- **Eyes** — scene-graph as JSON **and pixels as first-class evidence**: screenshots the developer sees in chat without running or watching the app themselves, annotated frames, visual diffs, and semantic *diffs after every action* (spec: PRD 04). This also makes headless/remote JavaFX development (devcontainers, CI boxes) viable — there the screenshot *is* the app's face.
- **Ears** — FX-thread exceptions and process logs (the "browser console" of JavaFX)
- **Hands** — OS-level input via `javafx.scene.robot.Robot` plus programmatic control
- **A steady workspace** — supervised app lifecycle with tiered reload (CSS live → FXML swap → HotSwap → restart-with-replay)
- **Memory** — (production stage) export of verified interaction flows as TestFX tests

**Positioning:** *dev-loop-first*. FXLoop instruments the app **you are building** (compile dep or `-javaagent`). Attaching to arbitrary third-party apps is a later, secondary mode. This framing sidesteps JEP 451 dynamic-attach friction and matches the highest-value use case.

## 2. Prior art & differentiation

| Project | What it is | Why FXLoop is different |
|---|---|---|
| `k7t3/fxgraph-mcp-server` | MCP + Attach-API injection; scene graph, click/type via FX events, screenshots | No license, JP-only docs, event-level input only, unstable node IDs, no dev-loop (no reload/errors/diff/test-gen) |
| M. Hoffer talk (2025) | Embed MCP into your own JavaFX app | App-specific tools; FXLoop is generic tooling for any app under development |
| MARS platform | Commercial JavaFX test automation, MCP planned | Closed, test-automation framing, not agent-dev-loop |
| TestFX / JavaFXLibrary | Test framework / Robot-Framework agent-port pattern | Not MCP; but validates Robot input + javaagent+socket architecture. FXLoop *generates* TestFX code in prod stage |
| ScenicView / CSSFX | Human GUI inspector / CSS live reload | Human-only, not agent-accessible; FXLoop absorbs both ideas as MCP tools |

## 3. Personas

1. **P1 — The coding agent** (primary "user" of the MCP tools). Optimises for: token-efficient structured output, deterministic tool semantics, actionable errors, no hidden state.
2. **P2 — The human JavaFX developer** running the agent. Optimises for: one-line setup, watching the app window update live, never shipping the agent to production by accident.

## 4. Product principles (binding for all stages)

1. **Dev-loop-first.** Every feature is judged by "does it shorten the edit→see→verify cycle for P1?"
2. **Token-efficient by default.** Bounded outputs everywhere: depth/count budgets, truncation markers, pagination (`limit`, `has_more`, `next_offset`), scaled screenshots.
3. **Public API only** in the core (`javafx.*`, `java.instrument`, `javax.tools`). Anything touching `com.sun.*` is isolated behind a feature flag and may degrade gracefully.
4. **Fail loud, explain, suggest.** Tool errors are returned *in-result* (`isError: true`) with a one-line cause and a concrete next step. FX-thread timeouts include the captured FX-thread stack trace.
5. **Deterministic over magical.** Reload happens when the agent calls `fx_reload` (plus optional human-facing CSS watch mode). No surprise background mutations of app state.
6. **One language, one repo.** Java 17+, multi-module Maven build, official `io.modelcontextprotocol.sdk:mcp` (see Decision Log for why not TypeScript).
7. **Never in production.** The agent module is a dev/test-scoped concern; guards + docs enforce it (see 03 §Security).
8. **Pixels and tree are complementary evidence.** The scene graph answers *what is it exactly*; screenshots answer *does it look right* — CSS and layout bugs are only observable in pixels. **Dual-channel rule:** every image is returned as MCP image content (model + capable clients render it inline) *and* persisted to a stable path the human can always open; **paths always, pixels on demand** governs context budget (see PRD 04).

## 5. System architecture

```
┌──────────────┐  stdio (MCP/JSON-RPC)  ┌──────────────────────┐  FCP: NDJSON over TCP  ┌───────────────────────────┐
│ Claude Code  │◄──────────────────────►│  fxloop-server        │◄──────127.0.0.1───────►│  target app JVM           │
│ (MCP client) │                        │  (MCP server, Java)   │   token-authenticated  │  ┌─────────────────────┐  │
└──────────────┘                        │  • supervises child   │                        │  │ fxloop-agent        │  │
                                        │  • build/compile ops  │                        │  │ (-javaagent / dep)  │  │
                                        │  • diff/journal/replay│                        │  │ • FX-thread bridge  │  │
                                        │  • MCP tool surface   │                        │  │ • scene graph, robot│  │
                                        └──────────┬────────────┘                        │  │ • error ring buffer │  │
                                                   │ spawns & monitors                   │  └─────────────────────┘  │
                                                   └────────────────────────────────────►│  JavaFX application       │
                                                                                         └───────────────────────────┘
```

**Key decisions embodied above:**
- **Two processes.** The MCP session (stdio, owned by the client) must survive app restarts; therefore the app is a *child* of the server, never the host of the MCP endpoint.
- **FCP (FXLoop Control Protocol)** between server and agent: newline-delimited JSON frames over a loopback TCP socket. Versioned (`fcpVersion`), token-authenticated via a handshake file (0600 perms) written by the agent at startup. Full spec in PRD-PoC §6.
- **Server does the heavy thinking** (diffing, journaling, compiling, Maven interop); the in-JVM agent stays thin (marshal to FX thread, execute, serialize) to minimise footprint and version-compat risk inside the target app.

## 6. Repository layout (multi-module Maven)

```
fxloop/
├── pom.xml                      # parent: Java 17, JavaFX 21 BOM for tests, spotless, enforcer
├── fxloop-protocol/             # FCP DTOs + JSON (shared by agent & server). Zero deps beyond jackson.
├── fxloop-agent/                # in-JVM agent. Shaded/relocated jar. Deps: protocol (+ByteBuddy from MVP).
├── fxloop-server/               # MCP server (stdio). Deps: protocol, mcp-sdk, jackson.
├── fxloop-maven-plugin/         # (MVP) `fxloop:prepare` — emits target/fxloop/launch.json
├── fxloop-gradle-plugin/        # (Production) parity task `fxloopPrepare`
├── sample-apps/                 # test targets (Java): fxloop-sample-app (PRD-PoC §9), fxloop-gauntlet-app, fxloop-sample-app-gradle
├── evals/                       # per-stage eval XML + runner notes
└── docs/
```

## 7. Conventions (from MCP best practices — apply everywhere)

- **Server name:** `fxloop`. **Tool names:** `fx_{action}_{resource}` snake_case (`fx_get_scene_graph`, `fx_click_node`).
- **Annotations on every tool:** `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint:false` (local-only).
- **stdio hygiene:** server logs to **stderr only**; stdout carries MCP frames exclusively.
- **List-like outputs** paginate: `{"total","count","offset","items","has_more","next_offset"}`.
- **Structured + readable:** tools return a short human-readable text block *and* `structuredContent` JSON.
- **Input validation:** schema-validate all params; sanitise paths (no traversal outside project dir); cap sizes.

## 8. Roadmap at a glance

| | **Stage A — PoC** (PRD 01) | **Stage B — MVP** (PRD 02) | **Stage C — Production 1.0** (PRD 03) |
|---|---|---|---|
| Goal | De-risk architecture end-to-end | Daily-usable dev loop, Maven-native, publish 0.x | Team/CI-ready 1.0, Gradle parity |
| Launch | Server shells `mvn` for classpath; `-javaagent`; supervise | `fxloop-maven-plugin` (`fxloop:prepare` → `launch.json`) | + Gradle plugin; + attach-to-PID mode |
| See | Scene graph, **dual-channel screenshots** (persisted + in-chat), node details | + semantic **diff after every action**; **annotated screenshots + visual diff** (PRD 04); addressing v2 (stable paths) | + pseudo-state capture, contrast lint, **live viewer**; virtualized-control summaries at scale; layout/CSS/binding explainers |
| Act | Robot click, key codes, set_text, properties | + full action journal | + TestFX test export from journal |
| Hear | FX uncaught-exception ring, process logs | + JUL/SLF4J capture (opt-in), compile diagnostics as data | + long-session soak guarantees |
| Loop | restart only | **reload router**: CSS live, FXML swap, HotSwap, restart+replay+geometry | + JBR enhanced-hotswap opt-in; headless Monocle CI |
| Build tool | Maven (shelled) | Maven (first-class plugin) | **Gradle** (first-class) + Maven |
| Distribution | local build | Maven Central 0.x, quickstart docs | 1.0 semver, signed artifacts, docs site, CI templates |

## 9. Cross-cutting non-functional requirements

- **Runtime matrix:** JDK 17 (baseline) & 21; JavaFX 17 & 21 at MVP; add JDK/JavaFX 25 lane at Production. Core uses only APIs present since JavaFX 11 (`Robot`, `Window.getWindows()`, snapshot, public CSS/metadata APIs).
- **OS:** Windows + macOS + Linux (X11) from MVP; PoC develops on one primary OS with a Linux `xvfb` CI smoke lane.
- **Latency budgets (MVP+):** CSS reload < 300 ms; single-class HotSwap < 2 s; restart incl. replay < 6 s on the sample app; any read tool < 500 ms p95.
- **Footprint:** agent jar < 2 MB PoC (< 5 MB with ByteBuddy at MVP); no background threads beyond FCP acceptor + watcher; zero overhead when handshake arg absent.
- **Security baseline (all stages):** FCP binds `127.0.0.1` only; random 128-bit token in handshake file `chmod 600`; max FCP frame 8 MB; no telemetry, ever. Hardening ladder in PRD 03.

## 10. Non-goals (v1.x)

Swing/SWT support; RPA against apps the user cannot relaunch (until attach mode, and even then dev/test framing); remote multi-client HTTP transport (stdio local-first; streamable HTTP is a tracked candidate, not committed); visual pixel-assertion testing; Windows native dialogs (`FileChooser` etc.) — documented wall, mock-factory pattern recommended instead.

## 11. Decision log

| # | Decision | Rationale |
|---|---|---|
| D1 | **Java server**, not TypeScript (deviates from generic MCP-skill default) | Server must invoke `javax.tools` javac in-process, share DTOs with the in-JVM agent, parse Maven/Gradle models, and redefine classes — all JVM-native. Single toolchain for the target audience (Java devs). stdio-local anyway, so TS packaging advantages don't apply. |
| D2 | stdio transport | Local, single-client dev loop; simplest; matches Claude Code. HTTP revisit-later. |
| D3 | `-javaagent`/compile-dep first, dynamic attach later | JEP 451 warnings & future default-deny; jlink apps often lack `java.instrument`; dev-loop doesn't need attach. |
| D4 | Two-process (server supervises app) | MCP session survives restarts; server can compile while app is down; clean kill/relaunch semantics. |
| D5 | NDJSON/TCP for FCP (not shared-mem, not embedding MCP in-app) | Debuggable with `nc`, trivial in both processes, restart-friendly. |
| D6 | Robot input as default click mode, event-level as fallback | Fidelity (picking, hover, focus traversal) — fixes fxgraph's documented weakness; event mode retained for headless/velocity. |
| D7 | Free-text typing = programmatic `set_text`; Robot reserved for key *codes* | Robot text typing is keyboard-layout-dependent (DE layouts!) — honest split avoids a classic flake source. |
| D8 | Apache-2.0 | Adoption + clear differentiation vs unlicensed prior art. |

## 12. How a coding agent should consume these PRDs

1. Read 00 (this doc) fully; then implement **PRD 01 milestones in order** (M1→M4). Do not start PRD 02 features early — PoC exit criteria gate it.
2. Each milestone has a **Definition of Done**; treat acceptance scripts and eval suites as executable specs — build them as tests first where possible.
3. When a PRD marks something **[R&D]**, timebox a spike, record findings in `docs/decisions/`, and fall back to the specified Plan B without waiting for human input.
4. Anything ambiguous: prefer the option that is (a) public-API, (b) more token-efficient for P1, (c) simpler to delete later.

## 13. Glossary

**FCP** — FXLoop Control Protocol (server↔agent NDJSON). **Handle** — session-scoped integer node reference. **Path** — stable structural node address (replay-safe), e.g. `Stage[0]/Scene/BorderPane/children[2]/Button#saveBtn`. **Journal** — ordered log of executed action tools. **Diff/Effects** — scene-graph delta attached to action results. **Tiered reload** — CSS→FXML→HotSwap→restart routing. **Monocle** — headless Glass platform for CI.

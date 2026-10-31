# PRD 06 — FXLoop Skills for JavaFX Developers (Stage D)
**Objective:** give JavaFX developers (Java and Kotlin) a set of agent skills that use FXLoop to **improve the UI and UX of their own application** — review a screen, fix a layout, restyle, make it accessible, polish a flow, and lock the result in as a test — with onboarding that takes one command and no manual build-file edits.

**Audience:** developers who *use* FXLoop. These skills are **not** for developing the FXLoop toolset itself (that remains the concern of PRDs 00–05).

**Entry gate:** MVP artifacts published to Maven Central / Gradle Plugin Portal (PRD 02 §11) so the connect skill can add real coordinates. Skills that need only CSS/FXML/screenshots can ship with 0.x; skills that need the production tools (states, contrast lint, test export, explainers) ship with 1.0.

---

## 1. Positioning

| Layer | Owns | Example |
|---|---|---|
| **MCP server** (PRDs 01–04) | Eyes, hands, ears: tools with tight descriptions and deterministic semantics | `fx_take_screenshot`, `fx_reload`, `fx_explain_layout` |
| **Skills** (this PRD) | Judgment: *when* to use which tool, JavaFX-specific know-how, what "verified" means, what a good result looks like | "review this screen", "add dark mode", "make it keyboard-friendly" |

Skills keep tool descriptions lean and put workflow knowledge where it loads on demand. The same SKILL.md files are usable by any agent that supports the open skills format; Claude Code is the first-class target.

## 2. Personas and triggers

- **P2 — the JavaFX developer.** Asks in plain language about their app's UI ("the dialog looks cramped", "does this work on a small laptop", "make it match our brand"). Expects the agent to look at the running app, propose concrete changes, apply them, and show before/after evidence they can open themselves.
- **P1 — the coding agent.** Needs a procedure per task type, a stable evidence rule, and reference material the base model gets wrong for JavaFX (CSS property names, layout containers, FX-thread rules).

Each skill declares trigger phrases in its description. Triggers follow the **task**, never the language: Java vs Kotlin is detected from the project and handled by reference notes inside the skill (§6).

## 3. Onboarding — the `fxloop-connect` skill (S0)

**Requirement:** a new developer goes from "never heard of FXLoop" to "the agent just showed me a screenshot of my app" in one plugin install and one command, with **zero manual edits** to `pom.xml` / `build.gradle(.kts)` / MCP config.

### 3.1 Distribution

- One Claude Code **plugin** (`fxloop`) bundles: the MCP server entry (`.mcp.json`), all skills in §4, the shared references in §5, and the generated tool reference (PRD 03 C21).
- The MCP entry launches the published **shaded server jar**. Resolution order: `jbang` alias if `jbang` is on PATH → otherwise a bundled bootstrap script that resolves `dev.fxloop:fxloop-server:<version>` through the project's own wrapper (`./mvnw dependency:copy` / `./gradlew` equivalent) into `~/.fxloop/` and runs `java -jar`. No tool beyond a JDK and the project's wrapper is required.
- Libraries and plugins come from **Maven Central** (`fxloop-agent`, `fxloop-dev`, `fxloop-maven-plugin`) and the **Gradle Plugin Portal** (`dev.fxloop`). The skill pins the version matching the installed plugin.

### 3.2 What `/fxloop:connect` does (idempotent, re-runnable)

1. **Detect** build tool (`pom.xml` vs `build.gradle(.kts)`), language (`src/main/kotlin`, Kotlin plugin), JavaFX setup (`javafx-maven-plugin` / `org.openjfx.javafxplugin`, `module-info.java`), multi-module layout (which module holds the `Application`), existing main-class config.
2. **Add the build plugin** (`fxloop-maven-plugin` or `id("dev.fxloop")`) with the pinned version; copy the main class into the plugin config only when auto-detection would be ambiguous (Launcher pattern, Kotlin top-level `main` → `FooKt`, several `Application` subclasses).
3. **Guard production:** `fxloop-*` only in dev/test scope; add the enforcer / Gradle verification snippet from PRD 03 §8 so the agent can never ship. Explain in one line why.
4. **Prepare and launch:** run the prepare goal, read back `launch.json`, start the app via `fx_start_app`, take one labeled screenshot, and show it. Failure modes have scripted fixes: missing `--add-modules`, "JavaFX runtime components are missing", ambiguous main class, annotation processors (Lombok / kapt / KSP → build-tool compile fallback), Kotlin sources (external-compile mode, §6).
5. **Leave a trail:** a short `CLAUDE.md` section (how the app is launched, which flags seed demo data, where screenshots land) and a `.gitignore` entry for `target/fxloop` / `build/fxloop`.
6. **Report:** what was added where, how to undo it, and the first three things to try ("ask me to review the main window").

**Definition of done:** on the three sample apps (Maven Java, Gradle Java, and a future Kotlin twin, §6) a fresh clone + plugin install + `/fxloop:connect` ends with a screenshot in chat in under two minutes, with no manual edits. Re-running changes nothing.

## 4. Skill catalog (S1–S9)

All skills share the **evidence rule** (§5.1): no UI improvement is claimed without `effects` or a labeled, persisted screenshot the developer can open; exact values (colors, sizes) come from the explain/property tools, never from eyeballing pixels.

| # | Skill | Developer asks… | Procedure (condensed) | FXLoop tools leaned on | Output |
|---|---|---|---|---|---|
| S1 | `fxloop-ui-review` | "review this screen", "what looks off", "give me a UI audit" | Enumerate windows/tabs → annotated screenshot per view → `fx_lint_visual` (+ contrast) → design checklist (alignment, spacing rhythm, hierarchy, truncation, control sizing, orphans/overlap) → rank findings | screenshot (annotate, scale), lint_visual, explain_layout/style, scene graph | Findings list keyed by node path with severity + suggested fix; gallery of frames |
| S2 | `fxloop-fix-layout` | "button is misaligned", "panel doesn't resize", "everything is squashed" | Locate node (find / describe_at) → `fx_explain_layout` → classify cause (wrong container, missing grow/anchor, min/pref/max, padding vs margin, unmanaged) → apply code/FXML fix → reload → before/after compare + effects | explain_layout, reload, compare_screenshots, effects | Fix applied + diff evidence; container cheat-sheet citation |
| S3 | `fxloop-style` | "make it look modern", "add dark mode", "match our brand", "style this control's states" | CSS live tier loop → theme variables via `.root` lookups, Modena overrides → `fx_capture_states` strip for hover/focus/pressed/disabled → read `visualDiff` verdicts (0 % = selector miss; too broad = selector too wide) → `fx_explain_style` for origin | reload (css tier), visualDiff, capture_states, explain_style | Stylesheet changes + state strip + before/after |
| S4 | `fxloop-accessibility` | "make it keyboard friendly", "is this accessible", "contrast check" | Contrast audit → Tab-walk the window with `fx_type_keys` and record focus order + focus visibility → accessible text/role per control → mnemonics, shortcuts, default/cancel buttons → target sizes → fix → re-walk | lint_visual lowContrast, type_keys, get_property (accessibleText, focusTraversable), explain_style | Per-node fixes; focus-order table before/after |
| S5 | `fxloop-responsive` | "does it work on a small screen", "HiDPI looks wrong", "long German strings break it" | Resize stage via properties to a size ladder → screenshot each → truncation/clipping/overflow checks → RTL toggle + long-string stress → fix constraints | set_property (stage w/h), screenshot, lint_visual, compare | Size matrix with findings; fixes |
| S6 | `fxloop-flow-polish` | "the add-contact flow feels clunky", "users get lost here" | Drive the task end to end with robot input → count steps, note missing feedback, late validation, unclear disabled states, missing empty/loading/error states, FX-thread freezes (`FX_TIMEOUT`, long handlers) → propose + apply → replay the flow | click/type/set_text, effects, ui_errors, screenshot, journal | Friction list → changes → replayed flow evidence |
| S7 | `fxloop-forms-dialogs` | "clean up this form", "dialog sizing is off" | Tab order, Enter/Escape, inline validation timing, label/field alignment, dialog sizing and owner/modality, native dialog wall (mock-factory recipe) | find, type_keys, explain_layout, windows[] | Form checklist with fixes |
| S8 | `fxloop-consistency` | "make all screens look the same" | Capture every window → compare style origins, fonts, spacing, button styles, icon sizes → propose shared stylesheet/components → apply → re-capture | screenshot, explain_style, get_node_details | Divergence table + shared-style refactor |
| S9 | `fxloop-lock-in` | "make sure this doesn't regress" | After a UX change: `fx_export_test` for the exercised flow → replace generated paths with fx:ids → assertions from effects → run headless (Monocle) → commit | export_test, journal | Green TestFX test in the project |

**Shipping order:** S0, S1, S2, S3 with 0.x (they need only PoC/MVP tools). S4–S9 with 1.0 (states, contrast lint, explainers, export). S9 can ship earlier as a "write a TestFX test for this flow" skill without the export tool.

## 5. Shared references (progressive disclosure)

Each skill's SKILL.md stays short (procedure + triggers) and links reference files loaded only when needed:

1. **`evidence.md`** — the evidence rule; paths always, pixels on demand; `scale=2` node shots for small text; annotate markers as the pixels↔tree bridge; pair screenshots with explain tools for exact values.
2. **`javafx-css.md`** — `-fx-` property names, no `margin`, `-fx-background-insets`/`-radius` stacking, `.root` variables, Modena defaults, pseudo-classes, substructure selectors (`.table-view .column-header`), `lookup` vs `styleClass`, inline vs stylesheet origin.
3. **`layout-containers.md`** — when to use VBox/HBox/GridPane/BorderPane/AnchorPane/StackPane/FlowPane, grow/anchor/constraints, min/pref/max, `managed`, common anti-patterns (AnchorPane everywhere, hard-coded sizes).
4. **`fx-threading.md`** — FX thread rules, `Platform.runLater`, `Task`/`Service`, "Not on FX application thread", what an `FX_TIMEOUT` stack tells you.
5. **`accessibility.md`** — WCAG contrast, focus traversal, accessible roles/text, mnemonics, dialog defaults, target sizes.
6. **`fxml.md`** — fx:id ↔ field naming, handler signatures, `fx:include`, resource bundles, the FXML swap caveat (controller re-created, view-local state resets).
7. **`testfx.md`** — TestFX idioms, async waits, Monocle headless run, flake patterns, Kotlin test variant.
8. **`tool-reference.md`** — generated from the server's tool registry (PRD 03 C21), never hand-written.

## 6. Java and Kotlin

The UI/UX skills are language-agnostic: CSS, FXML, screenshots and explainers are identical. Language shows up in two places, handled by reference notes rather than separate skills:

- **Where the fix goes.** Kotlin notes per skill: `@FXML lateinit var` and private fields needing the annotation, handler method shapes, property delegates, `Dispatchers.JavaFx` for coroutine work off the FX thread, top-level `main` → `FooKt` as the Launcher.
- **How the loop runs.** The hotswap tier compiles Java in-process; Kotlin sources use external-compile mode (`FXLOOP_EXTERNAL_COMPILE=true` with `gradle -t classes` or IDE auto-build) or the build-tool fallback. `fxloop-connect` configures this automatically when it detects Kotlin and says so in its report. CSS and FXML tiers are unaffected, which covers most of S1–S5.

**Required test bed:** a Kotlin twin of the sample app (`sample-apps/fxloop-sample-app-kotlin`, Gradle Kotlin DSL, same node ids) so S0 and the Kotlin notes are tested, not assumed.

## 7. Packaging, evals, quality bar

- **Plugin layout:** `skills/<name>/SKILL.md` (+ `references/`), `.mcp.json`, optional hooks (SessionStart: run prepare if `launch.json` is stale — never auto-reload or auto-restart, Principle 5).
- **Evals:** per skill, 3–5 tasks against the sample apps using their seed/broken flags (`--seed`, `--broken-css`, gauntlet tabs) in the skill-creator eval format; the existing `evals/*.xml` tasks are reused where they overlap. Example: S1 on `--broken-css` must name `helpBtn` overlap and `hintLabel` white-on-white; S4 on the gauntlet Visual tab must flag `lowContrastLabel`.
- **Description quality:** triggers tested for both false positives (plain Java questions must not load `fxloop-style`) and misses (the developer phrasings in §4).
- **Size:** each SKILL.md under ~150 lines; references carry the bulk.

## 8. Non-goals

Skills for building or debugging FXLoop itself; CI pipeline setup (covered by PRD 03 docs, not a skill); hotswap-limit tutoring as a standalone skill (a note in `fx-threading.md` / the connect report suffices); visual-regression assertions; Swing/Compose; IDE plugins.

## 9. Milestones

- **D1 Connect** — S0 on Maven Java + Gradle Java sample apps, plugin skeleton, `.mcp.json` bootstrap, enforcer guard. DoD: §3.2 definition of done on both.
- **D2 Review & restyle** — S1, S2, S3 + references 1–4. DoD: evals green on `--broken-css` and the gauntlet Visual tab.
- **D3 Kotlin twin** — sample app in Kotlin, S0 passes on it, Kotlin notes verified.
- **D4 Pro skills** — S4–S9 + references 5–8 once the 1.0 tools exist. DoD: S9 produces a green test from the S6 flow on the sample app.

## 10. Open questions

1. Plugin marketplace vs. a raw GitHub plugin URL for first release (default: raw URL, marketplace later).
2. Does `fxloop-connect` commit its edits or leave them unstaged? (Default: leave unstaged, print the diff.)
3. Should S1 produce a shareable HTML report from the gallery, or stay chat-only? (Default: chat + gallery; report later.)

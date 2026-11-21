# FXLoop agent skills

The [MCP server](mcp.md) gives an agent the tools to see and drive your JavaFX app. The skills teach it how to use
them for real UI and UX work: where to look, which numbers to collect, how a JavaFX fix is usually shaped, and what
counts as proof. They are ten plain `SKILL.md` folders in the open skills format, so they work with any agent that
reads skills, and they trigger from ordinary requests — you never have to name them.

They work with **your** app: Java or Kotlin, Maven or Gradle, FXML or code-built UI.

[Install](#install) · [At a glance](#at-a-glance) · [How a session flows](#how-a-session-flows) · [The evidence rule](#the-evidence-rule) · [The skills](#the-skills) · [Shared references](#shared-references) · [Try them](#try-them)

## Install

1. Install all ten skills globally into each coding agent on your machine (requires Node.js 22.20+ and `git`):

   ```sh
   npx skills add h9lxyz/fxloop -g -y
   ```

2. Open your agent in your JavaFX project and ask:

   > Connect FXLoop to this project and show me the app.

The [`fxloop-connect`](#fxloop-connect) skill downloads the server jar itself, registers the MCP server, and launches
your app.

<details>
<summary>More install options, updates, and uninstall</summary>

- **This project only:** drop `-g` (`npx skills add h9lxyz/fxloop -y`).
- **Update skills:** `npx skills update -g`.
- **Remove skills:** `npx skills remove h9lxyz/fxloop -g`.
- **Without Node.js:** unzip `fxloop-skills.zip` from the
  [latest release](https://github.com/h9lxyz/fxloop/releases/latest) into your agent's skills directory.
- **Without skills:** the MCP tools still work on their own. Follow [INSTALL.md](../INSTALL.md) (or ask your agent to
  follow its raw URL).

Contributor layout and eval instructions live in the [skills README](../fxloop-skills/README.md).

</details>

## At a glance

| Skill | Ask it... | You get |
|---|---|---|
| [fxloop-connect](#fxloop-connect) | "connect FXLoop and show me the app" | MCP server registered, app launched, first screenshot, build files untouched. On request, the build plugin and a production guard |
| [fxloop-ui-review](#fxloop-ui-review) | "review this screen, what looks off?" | Ranked findings keyed by node, each with evidence and a suggested fix |
| [fxloop-fix-layout](#fxloop-fix-layout) | "the panel doesn't resize", "the buttons are squashed" | Root cause from the layout values, the fix, before/after frames |
| [fxloop-style](#fxloop-style) | "make it look modern", "add dark mode", "fix the hover state" | A restyled app with a palette in `.root` variables, state strips and visual diffs |
| [fxloop-accessibility](#fxloop-accessibility) | "is this keyboard friendly? run a contrast check" | Contrast, focus order and naming tables, before and after |
| [fxloop-responsive](#fxloop-responsive) | "does it work at 1024×640? in German? RTL?" | A size matrix with findings per window size, fixes, the matrix again |
| [fxloop-flow-polish](#fxloop-flow-polish) | "this flow feels clunky", "it freezes when I refresh" | The flow walked end to end, friction fixed, step count and freezes before vs. after |
| [fxloop-forms-dialogs](#fxloop-forms-dialogs) | "clean up this dialog", "Escape doesn't close it" | A checklist per dialog: keys, validation, alignment, sizing, modality |
| [fxloop-consistency](#fxloop-consistency) | "make all screens look the same" | A divergence table across windows, one shared stylesheet, re-captured screens |
| [fxloop-lock-in](#fxloop-lock-in) | "make sure this doesn't regress" | A green JUnit 5 + TestFX test, run headless |

## How a session flows

1. **Connect:** `fxloop-connect` sets up the MCP server and takes the first screenshot.
2. **Audit:** `fxloop-ui-review` inspects the visible screens and produces a ranked list of findings mapped to specific skills.
3. **Fix:** the layout, styling, accessibility, responsive, flow, dialog, and consistency skills apply targeted edits, live-reload them, and show before/after proof.
4. **Lock in:** `fxloop-lock-in` turns the verified flow or fix into a headless JUnit 5 + TestFX regression test.

## The evidence rule

Every skill follows one [evidence rule](../fxloop-skills/references/evidence.md): no "fixed" or "looks better"
without something you can check.

- Exact values (colors, sizes, insets, contrast ratios) come from the explain and lint tools, never from eyeballing a
  screenshot.
- Every fix has a labeled before and after frame saved under `target/fxloop/screens/` (or `build/fxloop/screens/`),
  cited by path and compared by pixel diff (`changedPct` and region counts, not adjectives).
- Anything no tool can check (screen-reader output, GPU vs. software rendering, native dialogs) is reported as
  *"not verified"* instead of implied.

## The skills

### Setup

#### fxloop-connect

[SKILL.md](../fxloop-skills/skills/fxloop-connect/SKILL.md) — From "never used FXLoop" to a screenshot of your app,
with no manual edits and no change to your build files.

> "Connect FXLoop to this project and show me the app."

- Detects the build tool, app module, language, JavaFX setup, main class, and demo-data flags such as `--seed`.
- Registers the MCP server for Claude Code, Cursor, VS Code, Codex CLI, Gemini CLI, or any stdio client
  ([configs](../fxloop-skills/skills/fxloop-connect/references/mcp-clients.md)), writes `fxloop.properties` only when
  needed, and adds a short `## FXLoop` note in `AGENTS.md` or `CLAUDE.md`.
- Asks you to reload MCP servers once, launches the app, and shows a first screenshot; on request ("I want to run the
  app myself"), adds the Maven/Gradle plugin and a production guard. Idempotent, and other skills fall back to it when
  no setup exists yet.

### Audit

#### fxloop-ui-review

[SKILL.md](../fxloop-skills/skills/fxloop-ui-review/SKILL.md) — A non-destructive audit of what is on screen.

> "Review this screen — what looks off?"

- Screenshots the named views (or the main window and views one click away, avoiding destructive actions) with numbered
  markers and runs visual lint for truncation, overlap, zero-size, off-screen nodes, and low contrast.
- Checks alignment, spacing rhythm, visual hierarchy, primary actions, and control sizing, confirming surprising lint
  hits with explain tools before reporting them.
- Returns a High / Medium / Low table keyed by node (`#saveBtn`) with supporting values and the recommended skill;
  nothing is edited until you say which findings to fix.

### Fix and polish

#### fxloop-fix-layout

[SKILL.md](../fxloop-skills/skills/fxloop-fix-layout/SKILL.md) — Fixes misaligned, squashed, cut-off, non-resizing, or
gappy layouts at the source.

> "The detail panel doesn't resize and the buttons are squashed."

- Locates the target node (from your description, a pick, or an annotated screenshot) and inspects bounds and
  constraints with `fx_explain_layout`.
- Classifies the cause (container choice, missing grow/anchor, min/pref/max sizes, padding vs. margin, unmanaged or
  translated nodes) and fixes it in FXML, code, or CSS without `translateX` or magic numbers.
- Live-reloads and verifies with updated layout values, a before/after pixel diff, and a resize check when resizing was
  broken.

#### fxloop-style

[SKILL.md](../fxloop-skills/skills/fxloop-style/SKILL.md) — JavaFX CSS styling with live reload: modern look, dark mode,
brand colors, and Modena overrides.

> "Add dark mode" / "Make it look modern" / "Why is the Save button grey?"

- Builds a `.root` color palette (including Modena hooks like `-fx-base` and `-fx-accent`) and component classes with
  full state sets, moving inline styles out into stylesheets.
- Diagnoses styling bugs from the CSS cascade (`file:line` of the winning rule, plus inline styles or code-set values
  blocking overrides).
- Verifies `:hover`, `:focused`, `:pressed`, and `:disabled` with `fx_capture_states` strips, visual diffs, and contrast
  checks.

#### fxloop-accessibility

[SKILL.md](../fxloop-skills/skills/fxloop-accessibility/SKILL.md) — Keyboard navigation, WCAG contrast, and accessible
names.

> "Is this app keyboard friendly? Run a contrast check."

- Audits WCAG contrast including cases visual lint cannot compute automatically (chips inside table cells, headers,
  gradients).
- Walks every window with `Tab` to record focus order and focus indicator visibility, plus accessible names for
  icon-only controls, mnemonics, default/cancel buttons, and target sizes.
- Fixes issues and proves them with a before/after Tab walk and contrast table.

#### fxloop-responsive

[SKILL.md](../fxloop-skills/skills/fxloop-responsive/SKILL.md) — Adapts screens for small laptops, big monitors, long
strings, RTL, and HiDPI risks.

> "Does it work at 1024×640? What about in German or RTL?"

- Resizes the stage across a size ladder (1600×1000 down to 800×600, plus any target size) and lints every frame.
- Stresses labels with your longest locale (or 40 % longer strings) and checks RTL layout live via `nodeOrientation`,
  flagging HiDPI risks in code (fixed pixel sizes, bitmaps without `@2x`) as *"not verified"* since OS scale cannot be
  changed.
- Fixes min sizes, grow rules, and text overrun, re-runs the size matrix, and restores the original window size.

#### fxloop-flow-polish

[SKILL.md](../fxloop-skills/skills/fxloop-flow-polish/SKILL.md) — Walks an end-to-end user task to remove friction and
UI freezes.

> "This flow feels clunky" / "It freezes when I refresh."

- Clicks and types through the full task while inspecting each action's effects, timing, and FX-thread stalls.
- Fixes extra steps, missing feedback, late validation, unclear disabled states, missing empty/loading/error states,
  blocking work on the FX thread, and exceptions.
- Replays the flow to compare step counts, freezes, and UI errors before vs. after.

#### fxloop-forms-dialogs

[SKILL.md](../fxloop-skills/skills/fxloop-forms-dialogs/SKILL.md) — Makes forms and dialogs predictable and ergonomic.

> "Clean up this dialog — Escape doesn't close it."

- Audits each dialog against a checklist: Tab order, Enter to submit, Escape to cancel, inline validation timing, label
  and field alignment, sizing, owner/modality, and platform button order.
- Introduces test seams for native `FileChooser` dialogs, which no tool can see or click.
- Lists product decisions (such as turning a free-text input into a combo box) for your approval instead of guessing.

#### fxloop-consistency

[SKILL.md](../fxloop-skills/skills/fxloop-consistency/SKILL.md) — Unifies typography, spacing, and controls across all
windows.

> "Make all screens look the same."

- Captures every window and compares fonts, colors, spacing, button styles, titles, and icon sizes in a divergence
  table.
- Refactors shared tokens and component classes into one stylesheet and cleans up stray inline styles.
- Re-captures every screen and explicitly calls out intentional differences.

### Prevent regressions

#### fxloop-lock-in

[SKILL.md](../fxloop-skills/skills/fxloop-lock-in/SKILL.md) — Turns a verified fix or user flow into a headless
regression test.

> "Make sure this doesn't regress."

- Exports the recorded journal (your actions and the agent's) into a JUnit 5 + TestFX draft (`fx_export_test`).
- Hardens it with `fx:id` lookups, assertions derived from recorded effects, and proper waits instead of sleeps (porting
  to Kotlin if the project doesn't compile Java tests).
- Runs the test headless on Monocle until green, then temporarily reverts the fix once to confirm the test catches the
  regression.

## Shared references

The skills carry short JavaFX references so the agent doesn't have to guess. They are useful reading for people, too:

| Reference | Covers |
|---|---|
| [evidence.md](../fxloop-skills/references/evidence.md) | The evidence rule and techniques (annotated shots, diffs, state strips) |
| [javafx-css.md](../fxloop-skills/references/javafx-css.md) | What differs from web CSS: property names, layered backgrounds, looked-up colors, pseudo-classes, precedence |
| [layout-containers.md](../fxloop-skills/references/layout-containers.md) | Which container for which job, the size model, padding vs. margin vs. spacing, anti-patterns |
| [fxml.md](../fxloop-skills/references/fxml.md) | FXML and controller wiring, layout in FXML, what a live swap keeps |
| [fx-threading.md](../fxloop-skills/references/fx-threading.md) | FX Application Thread rules, Kotlin coroutines, how freezes show up in FXLoop |
| [accessibility.md](../fxloop-skills/references/accessibility.md) | Checklist and thresholds for contrast, focus, names and targets |
| [testfx.md](../fxloop-skills/references/testfx.md) | A JUnit 5 skeleton, idioms, headless Monocle runs, flake patterns |
| [kotlin.md](../fxloop-skills/references/kotlin.md) | Where a fix goes in Kotlin, and the fastest Kotlin reload loop |

## Try them

[`sample-apps/fxloop-showcase-app`](../sample-apps/fxloop-showcase-app/README.md#running-a-demo) (**Ticket Desk**) is a
Kotlin + Maven helpdesk app with planted UI and UX bugs and no FXLoop setup yet, so every demo starts naturally with
`fxloop-connect`. Its answer key is `ISSUES.md`; keep that file away from the agent during a demo.

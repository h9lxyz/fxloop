# FXLoop agent skills

The [MCP server](mcp.md) gives an agent the tools to see and drive your JavaFX app. The skills teach it how to use
them for real UI and UX work: where to look, which numbers to collect, how a JavaFX fix is usually shaped, and what
counts as proof. They are ten plain `SKILL.md` folders in the open skills format, so they work with any agent that
reads skills, and they trigger from ordinary requests. You don't name them.

They are for **your** app, Java or Kotlin, Maven or Gradle, FXML or code-built.

## Install

```sh
./mvnw install -DskipTests                              # in this repository, until FXLoop is on Maven Central
cp -r fxloop-skills/skills/fxloop-* ~/.claude/skills/   # Claude Code; other agents: their skills directory
```

Then, in your app's project: **"connect FXLoop to this project and show me the app"**. Install details, the MCP
bootstrap and the layout for contributors are in the [skills README](../fxloop-skills/README.md).

## At a glance

| Skill | Ask it... | You get |
|---|---|---|
| [fxloop-connect](#fxloop-connect) | "connect FXLoop and show me the app" | MCP server registered, build plugin and production guard added, app launched, first screenshot |
| [fxloop-ui-review](#fxloop-ui-review) | "review this screen, what looks off?" | Ranked findings keyed by node, each with evidence and a suggested fix |
| [fxloop-fix-layout](#fxloop-fix-layout) | "the panel doesn't resize", "the buttons are squashed" | Root cause from the layout values, the fix, before/after frames |
| [fxloop-style](#fxloop-style) | "make it look modern", "add dark mode", "fix the hover state" | A restyled app with a palette in `.root` variables, state strips and visual diffs |
| [fxloop-accessibility](#fxloop-accessibility) | "is this keyboard friendly? run a contrast check" | Contrast, focus order and naming tables, before and after |
| [fxloop-responsive](#fxloop-responsive) | "does it work at 1024×640? in German? RTL?" | A size matrix with findings per window size, fixes, the matrix again |
| [fxloop-flow-polish](#fxloop-flow-polish) | "this flow feels clunky", "it freezes when I refresh" | The flow walked end to end, friction fixed, step count and freezes before vs. after |
| [fxloop-forms-dialogs](#fxloop-forms-dialogs) | "clean up this dialog", "Escape doesn't close it" | A checklist per dialog: keys, validation, alignment, sizing, modality |
| [fxloop-consistency](#fxloop-consistency) | "make all screens look the same" | A divergence table across windows, one shared stylesheet, re-captured screens |
| [fxloop-lock-in](#fxloop-lock-in) | "make sure this doesn't regress" | A green JUnit 5 + TestFX test, run headless |

A typical session runs **connect → ui-review → the fixing skills → lock-in**. The review names the skill each
finding belongs to, and the fixing skills offer to lock their result in as a test.

## The evidence rule

Every skill follows one [evidence rule](../fxloop-skills/references/evidence.md): no "fixed" or "looks better"
without something you can check.

- Exact values (colors, sizes, insets, contrast ratios) come from the explain and lint tools, never from eyeballing a
  screenshot.
- Every fix has a labeled before and after frame, saved under `target/fxloop/screens/` (or `build/...`), cited by
  path and compared by pixel diff. You get `changedPct` and region counts, not adjectives.
- What no tool can check (screen-reader output, GPU vs. software rendering, native dialogs) is reported as
  "not verified" instead of implied.

## The skills

### fxloop-connect

[SKILL.md](../fxloop-skills/skills/fxloop-connect/SKILL.md) · From "never used FXLoop" to a screenshot of your app,
with no manual edits.

- Detects the build tool, app module, language, JavaFX setup, main class and demo-data flags such as `--seed`.
- Registers the MCP server for the agent it runs in: Claude Code, Cursor, VS Code, Codex CLI, Gemini CLI or any
  stdio client ([configs](../fxloop-skills/skills/fxloop-connect/references/mcp-clients.md)).
- Adds the FXLoop Maven or Gradle plugin and a production guard that fails the package build if FXLoop leaks onto the
  runtime classpath, and leaves a short `## FXLoop` section in `AGENTS.md` or `CLAUDE.md`.
- Asks you to reload the agent's MCP servers once, then launches the app and shows it.

Idempotent: re-running it changes nothing that is already in place. Other skills fall back to it when they find no
working setup.

### fxloop-ui-review

[SKILL.md](../fxloop-skills/skills/fxloop-ui-review/SKILL.md) · An audit of what is on screen.

- Screenshots the views you name, or the main window and every view one click away (never through destructive
  actions), with numbered markers, and runs the visual lint: truncation, overlap, zero-size, off-screen and low
  contrast.
- Checks alignment, spacing rhythm, hierarchy, primary actions and control sizing.
- Confirms each surprising lint hit with an explain tool before reporting it.

You get a table ranked High / Medium / Low, keyed by node (`#saveBtn`), with the tool and value behind each finding
and the skill that would fix it. Nothing is changed until you say which ones to fix.

### fxloop-fix-layout

[SKILL.md](../fxloop-skills/skills/fxloop-fix-layout/SKILL.md) · Misaligned, squashed, cut off, not resizing, big
empty gaps.

- Finds the node (from your words, a pick or an annotated screenshot) and reads its bounds and constraints with
  `fx_explain_layout`.
- Classifies the cause: wrong container, missing grow or anchor, min/pref/max sizes, padding vs. margin, unmanaged
  or translated nodes.
- Fixes it at the source in the smallest correct change (FXML, code or CSS, never `translateX` or magic numbers),
  live-reloads, and proves it with the layout values, a before/after diff and, for "doesn't resize", a resize check.

### fxloop-style

[SKILL.md](../fxloop-skills/skills/fxloop-style/SKILL.md) · JavaFX CSS with live reload: a modern look, dark mode,
brand colors, Modena overrides.

- Builds a palette in `.root` variables (plus Modena hooks such as `-fx-base` and `-fx-accent`) and component
  classes with full state sets, and moves inline styles into them.
- Answers "why is this color wrong" from the cascade: which rule set it, with `file:line`, and whether an inline
  style or a code-set value keeps the theme from applying.
- Verifies hover, focus, pressed and disabled states with state strips, and every change with a visual diff and a
  contrast check.

### fxloop-accessibility

[SKILL.md](../fxloop-skills/skills/fxloop-accessibility/SKILL.md) · Keyboard and contrast first, then names.

- WCAG contrast audit, including the spots the lint can't see (chips in table cells, headers, gradients).
- A Tab walk through every window with focus order and focus visibility recorded per step.
- Accessible names for icon-only controls, mnemonics, default and cancel buttons, target sizes.
- Fixes, then a re-walk and re-audit as proof.

### fxloop-responsive

[SKILL.md](../fxloop-skills/skills/fxloop-responsive/SKILL.md) · Small laptops, big monitors, long strings, RTL, HiDPI.

- Resizes the stage through a size ladder (1600×1000 down to 800×600, plus your target size) and lints every frame.
- Stresses labels with your longest locale, or with 40 % longer strings when there are no locales, and checks
  right-to-left (live, via `nodeOrientation`, if the app has no RTL switch).
- Reports HiDPI risks it finds in the code (fixed pixel sizes, bitmap icons without `@2x`) as "not verified", since
  it can't change the OS scale.
- Fixes minimum sizes, grow rules and text overrun, then re-runs the matrix. It restores the window size it changed.

### fxloop-flow-polish

[SKILL.md](../fxloop-skills/skills/fxloop-flow-polish/SKILL.md) · A user task walked end to end, like a user would.

- Clicks and types through the task, reading each action's effects and timing.
- Finds the friction: extra steps, missing feedback, late validation, unclear disabled states, missing empty, loading
  and error states, UI freezes from work on the FX thread, exceptions.
- Fixes it and replays the flow as proof: steps, freezes and errors before vs. after.

### fxloop-forms-dialogs

[SKILL.md](../fxloop-skills/skills/fxloop-forms-dialogs/SKILL.md) · Forms and dialogs that behave.

- Tab order, Enter to submit and Escape to cancel, inline validation timing, label and field alignment.
- Dialog sizing, owner and modality, platform button order.
- Test seams for native `FileChooser` dialogs, which no tool can see or click.

Product decisions (say, a free-text field that should be a combo box) are listed for you instead of applied.

### fxloop-consistency

[SKILL.md](../fxloop-skills/skills/fxloop-consistency/SKILL.md) · Every screen looking like the same product.

- Captures every window and compares fonts, colors, spacing, button styles, titles and icon sizes.
- Refactors to one shared stylesheet with `.root` variables and component classes, and moves inline styles into it.
- Re-captures every screen. Intentional differences are listed as such.

### fxloop-lock-in

[SKILL.md](../fxloop-skills/skills/fxloop-lock-in/SKILL.md) · A fix or flow turned into a regression test.

- Exports the recorded journal (the agent's actions and yours) as a JUnit 5 + TestFX draft.
- Hardens it: `fx:id` lookups, assertions about the fix from the recorded effects, proper waits instead of sleeps.
  Ports it to Kotlin if the build doesn't compile Java tests.
- Runs it headless on Monocle until it is green, then reverts the fix once to see the test fail.

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

[`sample-apps/fxloop-showcase-app`](../sample-apps/fxloop-showcase-app/README.md) is a Kotlin + Maven helpdesk app
with planted UI and UX problems and no FXLoop setup yet, so a demo starts with `fxloop-connect`. Its answer key is
`ISSUES.md`; keep it away from the agent.

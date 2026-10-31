---
name: style
description: Restyle a running JavaFX app with JavaFX CSS and FXLoop's live CSS reload. Covers modernizing the look, dark mode, brand colors via .root variables, Modena overrides, and hover/focus/pressed/disabled states verified with state strips and visual diffs. Use when the developer says "make it look modern", "add dark mode", "match our brand colors", "style this button's hover state", "the theme doesn't apply", or "why is this color wrong" in a JavaFX/Kotlin desktop app. Not for web CSS or Swing look-and-feel.
---

# Style

References: [javafx-css.md](../../references/javafx-css.md) (read it before writing CSS; JavaFX isn't web CSS),
[evidence.md](../../references/evidence.md), [accessibility.md](../../references/accessibility.md) (contrast and
focus must survive restyling).

## Procedure

1. **Map the current styling.**
   - Find the stylesheets (resource roots, `getStylesheets().add` in code, `stylesheets="@..."` in FXML).
   - Pick 2–4 representative nodes (primary button, title, a field, a table header) and run `fx_explain_style` on
     each: origin per property, inline style, classes, candidate stylesheets. Note **INLINE** origins: those nodes
     ignore any theme until the inline style moves into a class.
   - Look for palette sprawl: several near-identical hex values and no `.root` variables.
   - `fx_take_screenshot label=style-before`, plus `fx_capture_states` on the primary button →
     `style-before-states`.
2. **Plan in one short list** for the developer: palette variables, which rules change, which inline styles move.
   For a large restyle, confirm before applying.
3. **Edit in this order:**
   1. `.root` variables: palette (`-brand`, `-surface`, `-text-muted`) and Modena hooks (`-fx-base`, `-fx-accent`,
      `-fx-focus-color`, `-fx-background`, `-fx-control-inner-background`).
   2. Component classes (`.primary-button`, `.danger-button`, `.card`, `.section-title`) with full state sets:
      `:hover`, `:pressed`, `:focused` (keep a visible ring), `:disabled`, `:selected` for toggles.
   3. Move inline styles (FXML `style=`, `setStyle` in code) to classes. Code edits reload by hot-swap or restart.
   4. Substructure for complex controls (`.table-view .column-header .label`, `.combo-box .list-cell`).
4. **Apply each stylesheet change with `fx_reload` and read `visualDiff`:**
   - `changedPct` ≈ 0 with a hint: the selector matched nothing (typo, wrong id, invalid pseudo-class like
     `:hovered`, a property name without `-fx-`, or an INLINE style on the target). Check with `fx_explain_style`
     on the intended node.
   - More than 30 % when you meant one control: the selector is too broad (`.label`, `.button` without a scope).
   - Otherwise, check that the `regions` sit where you expected.
5. **States:** `fx_capture_states` on each restyled interactive control. Every state must differ visibly from
   `none`, focus must stay visible, and disabled must look disabled. Save the strip path.
6. **Contrast:** `fx_lint_visual checks=["lowContrast"]`, plus explain-tool checks for the lint's blind spots. Fix
   until body text is at least 4.5:1.

## Dark mode

Add the `.root.dark` overrides (javafx-css.md), plus a toggle that adds or removes the `dark` style class on the
scene root and persists the preference (reuse an existing setting if the app has one). Check it on every main view:
switch it on (click the toggle, or `fx_set_property` on a preference if it's exposed), take `label=dark-<view>`
screenshots and run the contrast lint. Hard-coded light colors (inline styles, `Color.WHITE` in code) are the usual
leftovers, so list them.

## Report

```
Changed: <stylesheet>: palette (5 vars), .primary-button states, .card border radius; moved 3 inline styles → classes
Evidence: style-before <path> → style-after <path> (visualDiff 18.2 %, 4 regions) · states <strip path> · contrast: 0 findings
Not verified: <e.g. Windows high-contrast mode>
```

Keep restyling separate from layout changes. If padding or sizing changes move things around, say so and show the
layout diff separately.

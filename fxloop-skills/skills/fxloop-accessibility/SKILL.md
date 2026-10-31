---
name: fxloop-accessibility
description: Make a running JavaFX app accessible with FXLoop. Contrast audit (WCAG), keyboard Tab-walk with focus order and focus visibility, accessible names for icon-only controls, mnemonics, default/cancel buttons and target sizes, then fixes and a re-walk as proof. Use when the developer asks "is this accessible", "make it keyboard friendly", "run a contrast check", "can I use this without a mouse", "screen reader support" for a JavaFX/Kotlin desktop app.
---

# Accessibility

The checklist and thresholds are in [accessibility.md](references/accessibility.md). Follow
[evidence.md](references/evidence.md): every fix needs a before/after value (ratio, focus order, property).

## Procedure

1. **Scope:** the window(s) the developer named, otherwise the main window plus each dialog reachable without side
   effects. One pass per window (`windowIndex`).
2. **Contrast audit:**
   - `fx_lint_visual checks=["lowContrast"]`. Record node, ratio and colors.
   - Lint blind spots: status chips or labels with their own background inside table cells, table headers, text on
     gradients or progress bars, WebView content. Read the colors with `fx_explain_style property=-fx-text-fill` and
     `-fx-background-color` on the node and its parent (or `fx_eval_js getComputedStyle(...)` for WebView) and
     compute the ratio.
   - Color-only meaning: dots or chips without text or a tooltip.
3. **Tab walk:**
   - Click a neutral spot, or focus the first control (`fx_click_node` on the first field), then press
     `fx_type_keys ["TAB"]` repeatedly. After each press, record the focused node from `effects` (a `focused`
     true change). If that's unclear, use `fx_find_nodes query=":focused"`.
   - Stop when focus cycles back. Build the table: step → node → visible focus? Spot-check visibility with
     `fx_capture_states states=["none","focused"]` on representative controls.
   - Compare with the reading order (top-left to bottom-right) and list what's **unreachable**: interactive nodes
     from `fx_find_nodes` that never got focus (look for `focusTraversable=false` with
     `fx_get_property name=focusTraversable`).
   - Try `ENTER` and `ESCAPE` in every dialog (`defaultButton` / `cancelButton`), then `SHIFT+TAB` back.
4. **Names:** for buttons without text and graphic-only controls, read `accessibleText` and `tooltip`. Read
   `labelFor` on form labels. Also note missing mnemonics on frequent actions.
5. **Target sizes:** `fx_explain_layout` on small controls (icon buttons, checkboxes, close buttons). Flag anything
   under 24 × 24 px.
6. **Fix**, most severe first:
   - Contrast: adjust the palette variable (not each node) and keep the brand hue. Re-lint after the reload.
   - Focus ring: restore `-fx-focus-color` / `-fx-faint-focus-color`, and remove `focusTraversable="false"` from
     real controls.
   - Order: reorder children in FXML/code to match the reading order.
   - Names: `accessibleText` and a `Tooltip` on icon buttons, `labelFor` on labels, `_` mnemonics on actions.
   - Dialogs: `defaultButton="true"` on the confirming action, `cancelButton="true"` on Cancel.
   - Targets: padding or `minWidth/minHeight` ≥ 24.
   - Status shown by color only: add text, or an icon plus a tooltip.
7. **Re-verify:** reload, re-lint, re-walk the Tab order, re-capture the focused state.

## Output

```
### Contrast   (lint: <n> → <m> findings)
| Node | Before | After | Fix |
### Focus order   (before → after)
| # | Before | After |
### Names, keys, targets
| Node | Issue | Fix | Evidence |
Not verified: screen-reader announcements (Narrator/VoiceOver), Windows high-contrast theme.
```

# Accessibility for JavaFX desktop apps

## Contrast (WCAG 2.2 AA)

| Content | Minimum ratio |
|---|---|
| Body text (< 24 px, or < 18.66 px bold) | **4.5 : 1** |
| Large text (≥ 24 px, or ≥ 18.66 px bold) | 3 : 1 |
| UI component boundaries, icons, focus indicators | 3 : 1 against neighbours |
| Disabled controls | exempt, but they must *look* disabled |

`fx_lint_visual checks=["lowContrast"]` computes the ratio against the nearest solid background. It can't see text on
images or gradients, labels with their own background inside table cells, or WebView content. For those, read the
colors with `fx_explain_style property=-fx-text-fill` / `-fx-background-color` and compute the ratio yourself
(relative luminance L = 0.2126 R + 0.7152 G + 0.0722 B on linearized sRGB; ratio = (L1 + 0.05) / (L2 + 0.05)).
For WebView, read `getComputedStyle(el).color` with `fx_eval_js` (needs `webview=true` in `fxloop.properties`).

Common fixes: darken secondary text to around `#5f6368` on white, and never use color as the only carrier of meaning:
add text or an icon with a tooltip to colored priority dots and status chips.

## Keyboard

- **Every action must be reachable by keyboard.** Walk the window with `fx_type_keys ["TAB"]` repeatedly and record
  `focused` from the effects (or `fx_find_nodes query=":focused"`). Note the order and what's skipped.
- **Traversal order is scene-graph order** (child order inside each parent), not visual position. Fix the order by
  reordering children (or the FXML elements). `viewOrder` only changes painting.
- `focusTraversable="false"` removes a control from Tab traversal. That's only right for pure decoration. Search
  fields and navigation must stay traversable.
- **Focus must be visible.** Don't set `-fx-focus-color` / `-fx-faint-focus-color` to `transparent` or remove the
  focus background insets. Check `fx_capture_states states=["none","focused"]`; the focused frame must differ
  visibly (3:1).
- **Dialogs:** a `defaultButton="true"` button (Enter) and a `cancelButton="true"` button (Escape). Put the initial
  focus on the first field (`Platform.runLater(field::requestFocus)` after showing).
- **Mnemonics:** `text="_Save"` with `mnemonicParsing="true"` (the default on Buttons and MenuItems, not on Labels).
  Pair a `Label` with its field: `label.setLabelFor(field)` (`labelFor="$field"` in FXML) plus a mnemonic on the
  label.
- **Accelerators:** menu items get `accelerator="Shortcut+S"`. Scene-wide:
  `scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::refresh)`.

## Screen readers (Narrator, VoiceOver)

- Icon-only buttons (⟳, ⚙, ✕) need `accessibleText` and a `Tooltip`. Check them with
  `fx_get_property name=accessibleText`.
- Use `accessibleHelp` for extra context and `accessibleRole` only on custom controls built from plain nodes.
- Labels must be connected to fields (`labelFor`), or the field reads as "edit text" with no name.
- Real screen-reader output can't be checked with FXLoop. Report it as not verified.

## Target size

At least **24 × 24 px** (WCAG 2.2 AA 2.5.8). Aim for 32 px on desktop toolbars. Read the size with
`fx_explain_layout`; it comes from padding plus the graphic, not the hit area you think.

## Checklist per window

1. Contrast lint and manual checks of the lint's blind spots.
2. Tab walk: order matches reading order, nothing unreachable, no traps, focus always visible.
3. Every control has a name (text, `labelFor`, or `accessibleText`).
4. Enter/Escape on dialogs, mnemonics on frequent actions, accelerators on menu commands.
5. Targets are at least 24 px. Disabled controls look disabled (opacity, not `-fx-opacity: 1` on `:disabled`).
6. Information isn't carried by color alone.

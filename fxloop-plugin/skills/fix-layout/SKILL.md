---
name: fix-layout
description: Diagnose and fix JavaFX layout problems in the running app with FXLoop. Finds the node, explains its bounds and constraints, classifies the cause (wrong container, missing grow/anchor, min/pref/max, padding vs margin, unmanaged), applies the FXML/code fix, live-reloads it and proves it with before/after screenshots. Use when the developer says "button is misaligned", "panel doesn't resize", "everything is squashed", "text is cut off", "there's a big empty gap", "the dialog layout is broken" in a JavaFX or Kotlin desktop app.
---

# Fix layout

References: [layout-containers.md](../../references/layout-containers.md) (the cheat sheet; cite the row you used),
[fxml.md](../../references/fxml.md), [kotlin.md](../../references/kotlin.md),
[evidence.md](../../references/evidence.md).

## Procedure

1. **Locate the node.** Use `fx_find_nodes query=#id` / `text=...`, or `fx_describe_at x y` for a point the
   developer points at, or the marker number from an `annotate=interactive` screenshot.
2. **Before frame:** `fx_take_screenshot label=before-<topic>`. Note the seq.
3. **Explain:** `fx_explain_layout` on the node **and its parent** (the cause is usually one level up). Read:
   layout bounds vs. pref, min/pref/max, parent type, the parent's constraints on the child (`hgrow`, `vgrow`,
   margin, anchors, grid cell), and anomaly hints (`unmanaged`, `translated`, `below pref width`).
4. **Classify** (one line to the developer, citing the values):

   | Cause | Signal |
   |---|---|
   | Wrong container | AnchorPane/Pane with fixed `layoutX/Y`; HBox rows used as a form grid |
   | Missing grow/anchor | parent has free space, child at pref size, `hgrow`/`vgrow` null, no anchors |
   | min/pref/max | hard `prefWidth`/`prefHeight`; `maxWidth` caps; a Button not filling because max = pref; ellipsis = width < pref |
   | Padding vs margin vs spacing | uneven gaps, `margin` on some children only, `margin` attempted in CSS |
   | Unmanaged / translated | `managed=false` or `translateX/Y` used for placement → overlap |
   | Window too small | the stage has no `minWidth`/`minHeight`; content clipped at small sizes |

5. **Fix at the source, in the smallest correct change:**
   - FXML: change the container, add `HBox.hgrow="ALWAYS"` / `VBox.vgrow`, remove the fixed `prefWidth`, add
     `ColumnConstraints`, a spacer `Region`. FXML reloads live.
   - Code-built views (Java/Kotlin): the same constraints via static setters (`HBox.setHgrow(...)`). Code that runs
     once (constructor, `initialize()`) applies after the restart, which `fx_reload` triggers by itself with replay.
   - CSS only for padding/spacing/sizes (`-fx-padding`, `-fx-spacing`, `-fx-pref-width`). Never `margin`.
   - Don't fix symptoms with `translateX`, magic `layoutX` numbers or shortening texts.
6. **Apply:** `fx_reload`. Read `tiersApplied`, `compileErrors` (fix and retry) and `restartReasons`.
7. **Verify:**
   - `fx_explain_layout` again: the values now match the intent (e.g. width grows with the parent, no ellipsis).
   - `fx_compare_screenshots before=<seq> capture=true`, and cite `changedPct`, regions and `diffPath`.
   - Resize check for "doesn't resize" bugs: `fx_set_property name=window.width value=<w±300>` on any node of the
     window, screenshot (`label=after-<topic>-w<w>`), `fx_lint_visual`, then restore the original width.
   - Re-run `fx_lint_visual` on the subtree: the original finding is gone and nothing new appeared.

## Report

```
Cause: <class> — <values from explain_layout>   (cheat sheet: <row>)
Fix: <file:line> <what changed>
Evidence: before <path> → after <path> (changedPct x %, n regions); explain_layout width 300 → 612 at 1100 px; lint clean
```

Several problems in one view: fix the container structure first (it often resolves the rest), re-explain, then fix
the remainders one at a time with their own evidence.

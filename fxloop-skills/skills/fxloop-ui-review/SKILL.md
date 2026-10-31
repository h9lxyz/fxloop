---
name: fxloop-ui-review
description: Audit the UI of a running JavaFX app with FXLoop. Screenshots every window and tab, runs the visual lint (truncation, overlap, zero-size, off-screen, contrast), checks alignment, spacing, hierarchy and control sizing, and returns ranked findings keyed by node path with suggested fixes. Use when the developer asks to "review this screen", "what looks off", "give me a UI audit", "critique the layout", or "is anything broken in the UI" for a JavaFX/TornadoFX/Kotlin desktop app. Not for reviewing Java code style.
---

# UI review

Read-only by default: find and rank problems, propose fixes, and change nothing until the developer picks what to
fix (then hand over to `fxloop-fix-layout`, `fxloop-style` or `fxloop-accessibility`). Follow the evidence rule in
[references/evidence.md](references/evidence.md).

Needs a connected app: if the `fx_*` tools are missing, or `fx_get_app_status` fails with a config or build error, run the
`fxloop-connect` skill first.

## Procedure

1. **Inventory.** Call `fx_get_app_status` to list the windows. For each window, `fx_find_nodes type=TabPane` and
   navigation toggles (sidebar buttons) find the views. The review covers what the developer named. If they named
   nothing, cover the main window and every view one click away. Don't open destructive actions (delete, send).
2. **Per view:**
   - `fx_take_screenshot annotate=interactive label=review-<view>`. The legend gives node paths for every marker.
   - `fx_lint_visual` on that window (`windowIndex`) or subtree. Every finding is a candidate.
   - For anything that looks off in the shot but isn't in the lint: `fx_explain_layout` / `fx_explain_style` on that
     node to get exact values. Small text: `target=node scale=2`.
3. **Design checklist** (use the screenshot plus explain tools):
   - **Alignment:** left edges of labels and fields line up; buttons in a row share a baseline and height; numeric
     columns are right-aligned.
   - **Spacing rhythm:** one spacing scale (e.g. 4/8/12/16). Report odd values (13, 6 vs 8 side by side) from
     `fx_explain_layout` insets and spacing.
   - **Hierarchy:** one clear title per view, a primary action visually distinct from secondary ones, destructive
     actions separated and not styled as primary.
   - **Truncation:** an ellipsis (`…`) on buttons, labels or headers (`textTruncated`), and clipped table columns.
   - **Control sizing:** consistent heights in a row, at least 24 px targets, fields wide enough for typical content,
     no giant empty areas next to cramped ones (fixed sizes in a resizable window).
   - **Orphans/overlap:** `siblingOverlap`, `outOfScene` and `zeroSizeVisible` lint findings, badges covering
     controls, invisible "loading" indicators.
   - **Contrast:** `lowContrast` lint, plus the blind spots listed in accessibility.md (chips inside cells, headers on
     gradients).
   - **States:** empty table placeholder text, disabled controls that look enabled. Skip anything that would need
     destructive actions to reach.
4. **Rank** each finding: **High** (unreadable, unreachable, broken or misleading), **Medium** (clearly off: truncation,
   overlap, inconsistent primary actions), **Low** (polish: rhythm, minor alignment). Merge duplicates: the same
   root cause on 5 buttons is one finding with 5 nodes.

## Output

```
## UI review — <window/view>   (frames: <paths>; gallery: <screens dir>/index.html)

| # | Sev | Node | Finding (evidence) | Suggested fix |
|---|---|---|---|---|
| 1 | High | #hintLabel | white on white, 1.00:1 (lint lowContrast) | -fx-text-fill: derive(-fx-text-base-color, 40%) → ≥ 4.5:1 |
| 2 | Med | #helpBtn | overlaps #boomBtn by 32×21 px (siblingOverlap; explain_layout: translateX=-30) | drop the translate; spacing on the HBox |
```

Then: "Want me to fix 1–N?" and name the skill each fix belongs to. Cite the node path (prefer `#fx:id`), the tool
that proves the finding, and exact values. Never "looks a bit off" without a number or a lint finding behind it.

## Notes

- `fx_lint_visual` is heuristic. Before reporting, confirm a surprising finding with an explain tool or a zoomed node
  shot. Drop it if it doesn't hold up, and say what you dropped.
- Big trees: lint a subtree (`path`/`query`) and page with `offset`.
- Don't resize the window or switch locale here. That's the `fxloop-responsive` skill.

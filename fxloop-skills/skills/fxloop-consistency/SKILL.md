---
name: fxloop-consistency
description: Make all screens of a running JavaFX app look like one product, using FXLoop to capture every window and compare fonts, colors, spacing, button styles, titles and icon sizes, then refactor to a shared stylesheet with .root variables and component classes and re-capture as proof. Use when the developer says "make all screens look the same", "the settings window looks different", "unify the styling", "create a design system / shared theme" for a JavaFX/Kotlin desktop app.
---

# Consistency

References: [javafx-css.md](references/javafx-css.md), [evidence.md](references/evidence.md). Use the
`fxloop-style` skill's verification loop (visualDiff, state strips) for each change.

## Procedure

1. **Capture every screen:** the main window, each navigation view, each dialog and secondary stage (open through
   the UI and close afterwards). Use `fx_take_screenshot label=cons-<screen>` for each.
2. **Sample the same roles on every screen.** For each role (page title, section title, body label, primary button,
   secondary button, text field, table header, card/panel), pick the node on each screen and record the facts from
   `fx_explain_style` (font family/size/weight, text fill, background, radius, padding, origin) and
   `fx_explain_layout` (height, spacing and padding of the container). `fx_get_node_details` helps when a value isn't
   CSS-styled (set in code).
3. **Divergence table:**

   | Role | Main | Customers | Reports | Settings | Target |
   |---|---|---|---|---|---|
   | Primary button | #2a6ef2 r4 System 12 (AUTHOR) | #3b7cff r0 pad 12/24 (INLINE) | … | … | `-brand`, r4, pad 6/14 |

   Also note: spacing scales in use, font families, icon sizes, and where styles are **INLINE** or set from code
   (these are the refactor targets).
4. **Propose the shared system** in about 10 lines: palette and spacing variables on `.root`, component classes
   (`.page-title`, `.section-title`, `.primary-button`, `.secondary-button`, `.danger-button`, `.card`, `.form`), one
   font family, and where the stylesheet gets attached (the scene of every Stage/Dialog, via a small helper such as
   `Styles.apply(scene)`). Confirm with the developer before a broad refactor.
5. **Refactor screen by screen.** Replace inline styles and code-set fonts/colors with style classes. Attach the
   shared stylesheet to secondary stages and dialogs. Delete duplicate rules from per-screen stylesheets. After each
   screen: `fx_reload`, then `fx_compare_screenshots before=<cons-screen seq> capture=true`.
6. **Re-capture all screens** and rebuild the divergence table. Every role should now be one value, with origin
   AUTHOR from the shared stylesheet.

## Output

The before and after divergence tables, the list of files touched (inline styles removed: n), and the frame paths
per screen. Remaining intentional differences (e.g. a report header color) are listed as such.

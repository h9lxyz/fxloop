# JavaFX CSS: what differs from web CSS

JavaFX CSS looks like web CSS but is a separate language. Most wrong fixes come from carrying web habits over.

## Property names

- Every property has the `-fx-` prefix: `-fx-background-color`, `-fx-text-fill`, `-fx-font-size`, `-fx-padding`,
  `-fx-border-color`, `-fx-opacity`, `-fx-cursor`, `-fx-effect`.
- **There is no `margin`.** Unknown properties are silently ignored. Margins are layout constraints, set in FXML or
  code: `VBox.margin`, `HBox.margin`, `GridPane.margin`, `StackPane.margin`, `BorderPane.margin`. In CSS, use
  `-fx-padding` on the parent or `-fx-spacing` on a VBox/HBox (`-fx-hgap` / `-fx-vgap` on GridPane, FlowPane and
  TilePane).
- Text color: Labeled controls and text inputs use `-fx-text-fill`. A `Text` node uses `-fx-fill`.
- No `width` or `height`. Use `-fx-pref-width`, `-fx-min-width`, `-fx-max-width` (and the `-height` forms). Prefer
  layout constraints to fixed sizes.
- No `display: none` either. Use `visible` / `managed` in code. `-fx-opacity: 0` keeps the space and the input.
- Sizes: `px`, `em` (relative to the font size; `.root { -fx-font-size: 13px }` scales the whole app), `%` in a few
  places. Colors: hex, `rgb()`/`rgba()`, `hsb()`, named colors, `derive(c, ±n%)`, `ladder()`,
  `linear-gradient(...)`, `radial-gradient(...)`.

## Backgrounds and borders are layered lists

```css
.card {
  -fx-background-color: #d0d4da, white;      /* 2 layers: outline, fill */
  -fx-background-insets: 0, 1;               /* one value per layer */
  -fx-background-radius: 6, 5;               /* radius per layer */
  -fx-border-color: #d0d4da;                 /* or use a border instead of the outline layer */
  -fx-border-radius: 6;                      /* must match the background radius, or the corners look square */
}
```

A rounded background with a square border means `-fx-border-radius` is missing. Check it with a node shot at
`scale=2`.

## Theme variables (looked-up colors)

Modena (the default user-agent stylesheet) derives almost everything from a few colors on `.root`:

| Variable | Drives |
|---|---|
| `-fx-base` | control base color (buttons, combo boxes); most other colors derive from it |
| `-fx-background` | window / pane background |
| `-fx-control-inner-background` | text fields, lists, tables |
| `-fx-accent` | selection, default button, progress |
| `-fx-focus-color`, `-fx-faint-focus-color` | focus ring (**never** set them to transparent; see accessibility.md) |
| `-fx-text-base-color`, `-fx-text-background-color` | text on controls and on backgrounds (derived automatically) |

Define your own palette variables on `.root` and reference them everywhere:

```css
.root {
  -brand: #2a6ef2;
  -brand-dark: derive(-brand, -20%);
  -fx-accent: -brand;
  -fx-focus-color: -brand;
}
.primary-button { -fx-background-color: -brand; -fx-text-fill: white; }
.primary-button:hover { -fx-background-color: derive(-brand, 10%); }
.primary-button:pressed { -fx-background-color: -brand-dark; }
```

Variables must be defined on `.root` or an ancestor of the node that uses them.

**Dark mode:** don't restyle every control. Override the `.root` variables in a second stylesheet, or under a root
style class:

```css
.root.dark {
  -fx-base: #2b2d31;
  -fx-background: #1e1f22;
  -fx-control-inner-background: #2b2d31;
  -fx-accent: #4e8cff;
  -fx-focus-color: #4e8cff;
}
```

Toggle it with `scene.getRoot().getStyleClass().add("dark")` / `.remove("dark")` and persist the choice. Then
recheck contrast (`fx_lint_visual checks=["lowContrast"]`), since hard-coded colors elsewhere will now clash.

## Pseudo-classes

Valid ones include `:hover`, `:pressed`, `:focused`, `:focus-visible` and `:focus-within` (JavaFX 19+),
`:disabled`, `:selected`, `:armed`, `:default`, `:cancel`, `:showing`, `:empty`, `:filled`, `:odd`, `:even`,
`:checked` / `:indeterminate` (CheckBox), `:readonly`, `:editable`, `:vertical` / `:horizontal`. **Unknown ones
(for example `:hovered` or `:active`) silently match nothing.** Verify states with `fx_capture_states`, which shows
the node as none / hover / focused / pressed / disabled side by side.

Selected toggles (sidebar navigation): `.nav-button:selected { ... }` on a ToggleButton. A plain Button never gets
`:selected`.

## Selectors and substructure

- `#id` matches `fx:id` (or `setId`). `.class` matches `styleClass`, and built-in controls carry their own: `.button`,
  `.label`, `.text-field`, `.table-view`, `.combo-box`, `.check-box`, `.list-view`, `.scroll-pane`.
- Use substructure for skin parts: `.table-view .column-header .label`, `.table-view .column-header-background`,
  `.table-row-cell:selected`, `.combo-box .list-cell`, `.check-box .box`, `.scroll-bar .thumb`,
  `.tab-pane .tab:selected`, `.progress-bar .bar`, `.titled-pane > .title`, `.text-area .content`.
- **Broad type selectors are dangerous:** `.label { -fx-font-size: 11px }` shrinks every label, including those inside
  controls. Scope rules with a class (`.form .label`), or fix the size at `.root`.
- `lookup("#x")` / `lookupAll(".y")` in code find nodes by selector. They are not styling.

## Precedence (origin)

USER_AGENT (Modena) < USER (values set from code, e.g. `setTextFill`) < AUTHOR (scene/parent stylesheets) < INLINE
(`setStyle` / FXML `style="..."`). Consequences:

- An author stylesheet **overrides** a value set from code, which surprises people.
- **Inline styles beat every stylesheet.** A theme can't reach a node styled inline. Move inline styles into classes.
- `fx_explain_style` reports the origin per property (`INLINE`, `AUTHOR`, ...) and the candidate stylesheets, but not
  the exact rule. Search the candidate files for the selector.
- Parent stylesheets (`parent.getStylesheets()`) apply only to that subtree, which is useful for dialogs. A new
  `Stage` doesn't inherit the main scene's stylesheets: add them to the dialog's scene or `DialogPane`.

## Reload behavior

Saving a `.css` file and calling `fx_reload` re-applies it live and returns `visualDiff`. Stylesheets added from code
are re-applied too, as long as they come from the project's resource roots. A file outside the resource roots, or
one built with a `data:` URL, isn't watched.

# Layout containers: choosing and fixing

Run `fx_explain_layout` first. It gives the layout bounds, insets, min/pref/max, parent constraints (grow, margin,
anchors, grid cell) and anomaly hints (`unmanaged`, `translated`, `below pref width`). Then pick from this table.

## Which container

| Need | Use | Key constraints |
|---|---|---|
| Stack vertically / horizontally | `VBox` / `HBox` | `spacing`, `alignment`, `fillWidth`; per child `VBox.vgrow` / `HBox.hgrow` (`ALWAYS`/`SOMETIMES`), `margin` |
| Header / sidebar / content / footer | `BorderPane` | `top/left/center/right/bottom`; center gets the leftover space |
| Label + field forms | `GridPane` | `ColumnConstraints` (label column `hgrow=NEVER`, `halignment=RIGHT`; field column `hgrow=ALWAYS`), `hgap`/`vgap`, `GridPane.columnIndex/rowIndex` |
| Overlay (badge, spinner over content) | `StackPane` | `StackPane.alignment`, `StackPane.margin` |
| Resizable split | `SplitPane` | `dividerPositions`; `SplitPane.setResizableWithParent(node, false)` for a fixed side |
| Wrapping chips / tags | `FlowPane` / `TilePane` | `hgap`, `vgap`, `prefWrapLength` |
| Pin to edges | `AnchorPane` | `AnchorPane.top/right/bottom/leftAnchor`. Use it only for genuine pinning, never as a default |
| Push to the end of a row | `Region` spacer with `HBox.hgrow="ALWAYS"` | e.g. title … spacer … buttons |
| Scrollable content | `ScrollPane` | `fitToWidth="true"` for vertical-only lists |

## Size model

- Each node reports **min / pref / max**. The parent decides the final size within those. `USE_COMPUTED_SIZE`
  (-1) is the default, `USE_PREF_SIZE` (-Infinity) pins min or max to pref.
- A hard-coded `prefWidth` works like a fixed size inside an AnchorPane or a plain Pane. In a VBox/HBox it is only a
  starting point unless `max` is pinned too.
- A `Button` doesn't grow by default (`maxWidth = pref`). To fill a row, set `maxWidth="Infinity"` and `hgrow`.
- **Ellipsis (`…`) on a button or label** means it got less than its pref width. Give it room (grow, fewer siblings,
  a wider column), or stop it shrinking with `minWidth` = `USE_PREF_SIZE` (FXML `minWidth="-Infinity"`, code
  `setMinWidth(Region.USE_PREF_SIZE)`). Don't shorten the text instead: translations will be longer.
- A `TableColumn` without width policy keeps a fixed `prefWidth`. Use
  `columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` (JavaFX 20+) or
  `CONSTRAINED_RESIZE_POLICY`, and set `minWidth` on columns that must stay readable.
- Stage: set `stage.setMinWidth/MinHeight` to the smallest size the layout supports. Below it, content clips.

## Padding vs. margin vs. spacing

- `padding` (the container's own inset, `-fx-padding` in CSS) is space inside the container.
- `spacing` / `hgap` / `vgap` is uniform space between children. Prefer it to per-child margins.
- `margin` is a per-child constraint for exceptions only. There is no CSS for it.

## Anti-patterns and their fixes

| Smell (what explain_layout shows) | Fix |
|---|---|
| AnchorPane with children at fixed `layoutX/Y` and `prefWidth/Height`, nothing grows | Replace it with BorderPane/HBox/SplitPane plus grow constraints, or at least anchors on all needed sides |
| `translateX/Y` used for placement (`translated` hint) | Translate is for animation. Place the node through the layout instead (e.g. StackPane alignment + margin) |
| `managed=false` (`unmanaged` hint) on a visible node | The parent ignores it, so it overlaps. Make it managed, or position it deliberately in an overlay |
| Rows of HBoxes with label `prefWidth` 60/80/50 | A GridPane with one label column |
| Fixed `prefHeight` on a TextArea or list in a VBox | `VBox.vgrow="ALWAYS"` and drop the fixed height |
| A Region/Pane with no size (`zeroSizeVisible` lint) | Give it a pref size or content, or remove it |
| `maxWidth`/`maxHeight` caps on charts or content | Remove the caps and let the parent decide |
| Fixed `ColumnConstraints` widths clipping long values | `hgrow=ALWAYS` plus `minWidth` on the value column |
| Node at x > scene width (`outOfScene` lint) | Usually a `layoutX` or anchor on the wrong side; anchor right instead |

## FXML vs. code

Constraints are static properties: in FXML `<Button HBox.hgrow="ALWAYS">`; in Java `HBox.setHgrow(btn, Priority.ALWAYS)`;
in Kotlin `HBox.setHgrow(btn, Priority.ALWAYS)`. FXML edits reload live (FXML tier). Code edits hot-swap only if they
change method bodies; constraint changes in `initialize()` or a constructor need the view rebuilt (restart with replay,
which `fx_reload` does by itself when needed).

## RTL

With `NodeOrientation.RIGHT_TO_LEFT` on the root, HBox, BorderPane, GridPane and the controls mirror automatically
(including container alignments like `CENTER_LEFT`). Absolute positioning (`layoutX`, AnchorPane offsets computed for
LTR, `translateX`) doesn't mirror sensibly, which is one more reason to replace it with real layout.

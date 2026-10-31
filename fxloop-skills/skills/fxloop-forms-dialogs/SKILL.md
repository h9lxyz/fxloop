---
name: fxloop-forms-dialogs
description: Clean up forms and dialogs in a running JavaFX app with FXLoop. Tab order, Enter/Escape, inline validation timing, label/field alignment, dialog sizing, owner and modality, button order, and native FileChooser test seams. Use when the developer says "clean up this form", "the dialog sizing is off", "Enter doesn't submit", "Escape doesn't close", "the settings window opens in a weird place", "validation is annoying" in a JavaFX/Kotlin desktop app.
---

# Forms and dialogs

References: [layout-containers.md](references/layout-containers.md) (GridPane forms),
[accessibility.md](references/accessibility.md) (keys, labels), [evidence.md](references/evidence.md).
Native dialogs: see FXLoop's limitations doc (FileChooser seam).

## Procedure

1. **Open each form/dialog** the developer means, through the UI (`fx_click_node`). `fx_get_app_status` then lists
   the new window: note `index`, `type` (`DIALOG` / `STAGE`) and title. Run `fx_take_screenshot windowIndex=<i>
   annotate=interactive label=form-<name>`.
2. **Checklist** (record evidence for each item):

   | Area | Check | How |
   |---|---|---|
   | Ownership | modal and owned by the main window (stays on top, centered on the owner, one instance) | open it twice: does a second window appear in `fx_get_app_status`? Compare the window `boundsScreen` with the owner's |
   | Size | fits its content, resizable when content can grow, min size set | `fx_lint_visual windowIndex=<i>` (textTruncated); `fx_explain_layout` on the root vs. the scene size |
   | Alignment | labels in one column, fields aligned, consistent widths | `fx_explain_layout` on the label/field pairs; a GridPane is better than HBox rows |
   | Tab order | follows the visual order, starts on the first field | Tab walk (`fx_type_keys ["TAB"]`, read `focused` in effects) |
   | Keys | Enter = confirm (`defaultButton`), Escape = cancel/close (`cancelButton`) | `fx_type_keys ["ENTER"]` / `["ESCAPE"]`, read the effects and the window list |
   | Buttons | platform order (Windows/Linux: OK Cancel; macOS: Cancel OK; or `ButtonBar` handles it), right-aligned, destructive actions separated | screenshot; `ButtonBar` with `ButtonData` gets this right per OS |
   | Validation | inline next to the field, on input or field exit, the confirm button disabled until valid, no chain of modal Alerts | type invalid input with `fx_set_text`, read the effects; try submitting |
   | Inputs | right control per value (ComboBox for known choices, Spinner or formatted field for numbers, DatePicker), sensible max lengths | screenshot plus the field types from `fx_find_nodes` |
   | Styling | the dialog uses the app stylesheet | `fx_explain_style` on a dialog button: AUTHOR origin from the app's css, or only USER_AGENT? |
   | Native dialogs | FileChooser/DirectoryChooser behind a seam so automation and tests work | grep `FileChooser`; apply the limitations recipe |

3. **Fix:**
   - Ownership: `stage.initOwner(owner)` + `initModality(Modality.WINDOW_MODAL)`, or use `Dialog<T>`/`Alert`, which
     do it for you. Center with `centerOnScreen()` or rely on the owner. Reuse a single instance.
   - Layout: GridPane with a `ColumnConstraints` label column (`halignment=RIGHT` or LEFT, used consistently) and a
     growing field column. Remove fixed scene sizes, then `sizeToScene()`, and set `setMinWidth/Height` after the
     first layout.
   - Keys and order: `defaultButton` / `cancelButton`; children in visual order; first-field focus via
     `Platform.runLater(field::requestFocus)` once the window is showing.
   - Validation: a listener or binding per field that drives an error label and `confirm.disableProperty()`.
   - Styling: `dialog.getDialogPane().getStylesheets().add(...)` or `scene.getStylesheets().addAll(owner.getScene().getStylesheets())`.
4. **Reload and re-check** each changed item with the same tool you used to find it. Close the dialogs you opened
   (Escape or Cancel) so the journal and the next skill start clean.

## Output

A checklist table per dialog: area, before → after, evidence (frame path, effects, values). End with any product
decisions (e.g. "free-text customer → ComboBox of known customers?") that you left open instead of applying.

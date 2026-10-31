# Ticket Desk — answer key

Planted issues, grouped by the skill expected to find and fix them. **✓** means confirmed against the running app with
the FXLoop tools (default locale unless noted). **code** means present in the source but not detected by a tool today
(see "Tool gaps" at the end).

Paths are relative to `src/main/`.

## S0 — connect

| # | Issue | Where | Notes |
|---|---|---|---|
| 0.1 | No FXLoop build plugin, no dev/test scope guard, no `CLAUDE.md` section | `build.gradle.kts` | connect must add `id("dev.fxloop")` |
| 0.2 | Kotlin top-level `main` → main class `MainKt` | `kotlin/.../Main.kt` | resolved via `application.mainClass` |
| 0.3 | Kotlin sources: HotSwap tier needs external-compile mode | — | connect should set `FXLOOP_EXTERNAL_COMPILE=true` and say so |

## S1 — UI review (lint_visual, screenshots)

| # | Issue | Node | Detected by |
|---|---|---|---|
| 1.1 | Sidebar labels truncated (fixed `prefWidth="90"`) | `#navKnowledge` (de: all nav buttons) | ✓ `textTruncated` |
| 1.2 | Detail action buttons squashed to `…` (5 buttons in a 300 px card) | `#saveBtn #deleteBtn #assignBtn #clearBtn #attachBtn` | ✓ `textTruncated` |
| 1.3 | Table columns too narrow, subjects/dates/headers cut off | `#subjectCol #customerCol #updatedCol` | ✓ `textTruncated` |
| 1.4 | Notification badge overlaps the settings button (`translateX=-14`) | `#notificationBadge` | ✓ `siblingOverlap` |
| 1.5 | Sync indicator never visible (Region with no size) | `#syncIndicator` | ✓ `zeroSizeVisible` (0×16) |
| 1.6 | Export button placed off-screen (`AnchorPane.leftAnchor=1180`) | `#exportBtn` | ✓ `outOfScene` |
| 1.7 | Footer text unreadable (#bbb on #f4f5f7, 1.76:1) | `#statusLabel #versionLabel` | ✓ `lowContrast` |
| 1.8 | Header buttons not right-aligned (no spacer), mixed heights, top-aligned | `#header` | screenshot |
| 1.9 | Large empty area right of the detail card at 1100 px width | `#ticketsView` | screenshot |

## S2 — fix layout (explain_layout)

| # | Issue | Node | Fix |
|---|---|---|---|
| 2.1 | Absolute layout: table and card at fixed `layoutX/Y` + `prefWidth/Height`, nothing grows | `#ticketsView` (AnchorPane) | SplitPane/HBox + `HBox.hgrow`, or anchors |
| 2.2 | Detail card is a fixed 300 px wide (`explain_layout`: pref 300×315, parent AnchorPane, no constraints) | `#detailPane` | ✓ explain_layout |
| 2.3 | Form rows are HBoxes with ragged label widths (60/80/50/70/64) | `#detailPane` rows | GridPane with a label column |
| 2.4 | Description stuck at `prefHeight=60` with no `VBox.vgrow` | `#descriptionArea` | `VBox.vgrow="ALWAYS"` |
| 2.5 | Search field fixed at 120 px, doesn't grow | `#searchField` | `HBox.hgrow` + spacer |
| 2.6 | Action row has no spacing and no alignment, Delete sits next to Save | `#detailActions` | spacing, right-align, separate destructive action |
| 2.7 | CSS rule targets `#detailsPane` but the node is `#detailPane`, so it never applies | `app.css` | selector typo, visualDiff 0 % |
| 2.8 | Pie chart capped at 300×300 (`maxWidth/maxHeight`), doesn't use the space | `#statusChart` | remove caps |
| 2.9 | Customers detail grid has hard-coded 70/140 px columns, so the long e-mail is clipped | `#customerDetails` | ColumnConstraints with hgrow |

## S3 — style (capture_states, explain_style, visualDiff)

| # | Issue | Where | Detected by |
|---|---|---|---|
| 3.1 | `:hovered` is not a pseudo-class, so primary buttons have no hover | `app.css .primary-button:hovered` | `fx_capture_states #newTicketBtn` (hover = none) |
| 3.2 | `:pressed` uses the normal color, so there's no press feedback | `app.css` | capture_states |
| 3.3 | Delete is styled like the primary (blue) button | `.danger-button` | screenshot |
| 3.4 | `margin: 4px` is not a JavaFX CSS property (ignored) | `.nav-button` | javafx-css reference |
| 3.5 | Rounded background with square border (no `-fx-border-radius`) | `.card` | `scale=2` node shot |
| 3.6 | `.label { -fx-font-size: 11px }` shrinks every label in the app | `app.css` | explain_style |
| 3.7 | Title styled inline from FXML, overriding any theme | `#appTitle` | ✓ explain_style origin `INLINE` |
| 3.8 | Status chips / Customers buttons / CSV button styled inline from Kotlin | `MainController.statusChip`, `CustomersView`, `ReportsView` | explain_style `INLINE` |
| 3.9 | Three slightly different blues (#2a6ef2, #1e5bd8, #3b7cff), no `.root` color variables | `app.css`, inline styles | — |
| 3.10 | "Dark mode" checkbox only saves a preference, there's no dark theme | `SettingsController.onOk` | S3 "add dark mode" task |
| 3.11 | Active sidebar item has no selected style | `.nav-button` (no `:selected`) | capture_states |

## S4 — accessibility

| # | Issue | Node | Detected by |
|---|---|---|---|
| 4.1 | Focus ring removed globally (`-fx-focus-color: transparent`) | `app.css .button:focused` | capture_states focused |
| 4.2 | Icon-only buttons (⟳, ⚙) with no tooltip or `accessibleText`, 18×18 px targets | `#refreshBtn #settingsBtn` | get_property |
| 4.3 | Search and sidebar are `focusTraversable=false`, so they can't be reached by Tab | `#searchField #nav*` | Tab walk |
| 4.4 | Priority is shown as a colored dot only (no text, no tooltip) | `#priorityCol` | screenshot |
| 4.5 | Status chip text contrast ~1.6–1.9:1 (e.g. #c8c8c8 on #f0f0f0) | status column | **code** (lint misses it, see gaps) |
| 4.6 | Table header text #a0a8b0 on gradient | `.column-header .label` | **code** |
| 4.7 | Settings hint text #ccc on #fbfaf7 (1.54:1) | `#hintLabel` (Settings) | ✓ `lowContrast` |
| 4.8 | Disabled Apply looks enabled (`:disabled { -fx-opacity: 1 }`) | `#applyBtn` (Settings) | capture_states disabled |
| 4.9 | No mnemonics, no `labelFor`, no default/cancel buttons anywhere | forms, dialogs | get_property |
| 4.10 | Knowledge Base links #7fa7f5 and meta text #cfcfcf on white | `knowledge-base.html` | fx_eval_js computed style |
| 4.11 | KPI "87%" is white text on the progress bar | `#satisfactionValue` | screenshot |

## S5 — responsive (`--de`, `--rtl`, resize)

| # | Issue | Detected by |
|---|---|---|
| 5.1 | German strings truncate every nav item and action button (`Formular zurücksetzen`, `Wissensdatenbank`, ...) | ✓ `textTruncated` with `--de` (47 findings) |
| 5.2 | `--rtl`: the absolute layout mirrors badly, and the export button lands at x=−276 | ✓ `outOfScene` with `--rtl` |
| 5.3 | No `minWidth/minHeight` on the stage; below ~900 px the detail card is clipped | resize ladder (see gaps) |
| 5.4 | New ticket dialog is fixed 320×240 and non-resizable | ✓ `textTruncated` on all four dialog labels |
| 5.5 | Settings content (≈372 px) taller than the fixed 360 px scene | screenshot |

## S6 — flow polish (effects, ui_errors, journal)

| # | Issue | Where | Detected by |
|---|---|---|---|
| 6.1 | Refresh blocks the FX thread for 2.5 s (`Thread.sleep`) | `onRefresh` | ✓ click took 2.6 s |
| 6.2 | "Generate PDF report" updates UI and creates an `Alert` from a background thread, so the alert never appears | `ReportsView` | ✓ `fx_get_logs source=process`: `IllegalStateException: Not on FX application thread` + off-thread `setText` |
| 6.3 | "Assign to me" is enabled only when the **search field** has text (copy-paste binding) | `#assignBtn` | ✓ `fx_explain_property disable` → depends on `#searchField.text` |
| 6.4 | "Assign to me" with no selection → NPE | `onAssignToMe` | ✓ ui_errors `NullPointerException` |
| 6.5 | Delete has no confirmation; with no selection it deletes the first ticket; with a search filter it deletes the wrong ticket (filtered index used on the source list) | `onDelete` | ✓ effects: itemCount 14→13, row 1042 gone |
| 6.6 | Save gives no feedback, and silently does nothing without a selection | `onSave` | effects |
| 6.7 | Switching rows discards unsaved edits without warning | `showTicket` | flow |
| 6.8 | Clicking the active sidebar item deselects it (ToggleGroup without a guard) | `#nav*` | ✓ effects: `navReports` selected true→false |
| 6.9 | "Add customer" does nothing | `#addCustomerBtn` | ✓ effects: only a focus change |
| 6.10 | New ticket: added at the end, not selected, footer count not updated | `NewTicketDialog` | effects |
| 6.11 | Search is case-sensitive and matches the subject only | `searchField` listener | flow |
| 6.12 | Column sorting silently fails (items are a `FilteredList`, not a `SortedList`) | `#ticketTable` | **code** (not exercised yet) |
| 6.13 | Empty state (`--empty`): default "No content in table", editable blank form | `#ticketTable` | screenshot |
| 6.14 | Knowledge Base search does nothing: the script looks up `#serach`, the input is `#kbSearch` | `knowledge-base.html` | ✓ `fx_eval_js` |
| 6.15 | "Attach file…" opens a native `FileChooser` with no test seam (blocks automation) | `onAttach` | limitations.md recipe |

## S7 — forms & dialogs

| # | Issue | Where |
|---|---|---|
| 7.1 | New ticket and Settings stages have no owner and no modality; several can be open at once | `NewTicketDialog`, `SettingsWindow` |
| 7.2 | Settings opens at a fixed (40, 40) on top of the main window's sidebar (✓ the robot's nav click hit Settings instead) | `SettingsWindow.open` |
| 7.3 | Tab order differs from visual order: rows added Subject → Customer(row 2) → Priority(row 1) | `NewTicketDialog` |
| 7.4 | Validation only on Create, one modal `Alert` per field, max length checked last | `NewTicketDialog` |
| 7.5 | Enter doesn't create, Escape doesn't cancel (no default/cancel button); Cancel comes before Create, left-aligned | `NewTicketDialog` |
| 7.6 | Dialog doesn't use `app.css` (unstyled) | `NewTicketDialog` |
| 7.7 | Settings: OK / Apply / Cancel order, Apply never enabled, Escape doesn't close | `settings.fxml` |
| 7.8 | Customer is free text instead of a choice from known customers | `NewTicketDialog` |

## S8 — consistency

| # | Divergence |
|---|---|
| 8.1 | Fonts: System (main), Arial (Customers title/button), Georgia (Settings, Knowledge Base) |
| 8.2 | Primary buttons: #2a6ef2 radius 4 (main), #3b7cff radius 0 + big padding (Customers), #3f8f4f pill (Settings), #2e9d5b bold radius 14 (Reports CSV) |
| 8.3 | Spacing: 4/6 (main), 10/13/20 (Customers), 8/16 (Reports), 20 (Settings) |
| 8.4 | Titles: 20 px blue inline, 22 px Arial #222, 15 px bold `.section-title`, 24 px green, 26 px HTML |

## S9 — lock-in

Only one smoke test exists (`TicketDeskSmokeTest`). Each fix in S2–S7 should end as a TestFX test, e.g. assign with no
selection, delete the selected (filtered) ticket, and create a ticket → selected + count updated.

## FXML lint

| # | Issue | Detected by |
|---|---|---|
| F.1 | `fx:id="priorityCombo"` but the Kotlin field is `priorityBox`: never injected, the priority combo is empty, and "Clear" throws `UninitializedPropertyAccessException` | ✓ `fx_lint_fxml missingFxIdField` (line 81) + ✓ ui_errors |
| F.2 | Duplicate `fx:id="hintLabel"` in Settings (the second one wins injection) | ✓ `fx_lint_fxml duplicateFxId` |

## Tool gaps found while building this app

Observations about FXLoop itself, not planted issues:

1. **Gradle plugin, Kotlin projects:** `fxloopPrepare` writes `outputDir=build/classes/java/main`, leaves out `build/classes/kotlin/main` from the classpath, and leaves `sourceRoots` empty, so the app fails with class-not-found for `MainKt`. Verification here used a hand-patched `launch.json`. This blocks S0 / milestone D3.
2. `fx_set_property` on `Stage[0]` `width` → `UNSUPPORTED_TARGET: width is read-only`; S5's "resize stage via properties" has no tool path.
3. `fx_lint_visual lowContrast` misses status chips (labels with their own background inside table cells), the table header, and KPI captions.
4. `fx_lint_fxml missingFxIdField` reports every fx:id without a controller field (26 here, 1 real). The real one's suggestion was "rename fx:id to 'priorityCol'" instead of `priorityBox`.
5. `fx_explain_style property=-fx-background-color` on an inline-styled Button returns no items.
6. `fx_take_screenshot` of a window follows the root's overflow bounds: with a node at layoutX 5000 the screenshot was 5106 px wide.

# PRD 09 — Inspector v2: states, layout, sheets, evidence (Stage G)

**Status:** proposed (2026-11-02). Builds on PRD 08 F1–F3 as shipped.

**Objective:** close the gap between "a cascade viewer with live CSS edits" and "the place a JavaFX developer goes to
understand and fix *this node*". Four things are missing today: the inspector cannot show a hover or focus rule, it
explains CSS but not layout, it shows one node's rules but never the stylesheet as a whole, and it gives no visual
evidence that an experiment did what the developer thinks it did. Every feature here is dual-channel: the agent gets
the same data through a tool, the developer through the page.

**Audience:** developers styling and laying out their own app in the inspector (P2), and the agent doing the same work
through `fx_explain_style`, `fx_explain_layout` and the new tools below (P1).

**Entry gate:** PRD 08 shipped (cascade engine, `/inspect`, scratch editing, apply-to-file, library mapping).
Nothing here needs a protocol break; the FCP additions are new methods.

---

## 1. Where we are

| Question at the node | Today | Gap |
|---|---|---|
| Why is it this color? | Cascade with winner, overridden, blockers, `file:line`, suggest override | ✅ |
| Why does it look like this on hover / focus / pressed? | `fx_capture_states` renders a strip for the agent; the page has no way to hold a state, and moving the mouse to the browser ends the hover | **G1** No forced states |
| Why is it that size / there? | `fx_explain_layout` answers (bounds, min/pref/max, grow, margins, anchors, grid cell, hints); the page draws padding and insets only | **G2** No layout tab, no layout edits |
| Where is the node I mean? | Pick in app, tree browsing, styles filter | **G3** No tree search |
| Where does `-fx-base` come from, and what did `derive()` do? | One level of lookup resolution | **G4** No derivation chain |
| What does the app load, in which order, with which errors, and which rules are dead? | Per-node only; parse errors surface as a blocker on the node they hit | **G5** No stylesheet view, no CSS lint |
| Who else does this rule hit? | Count and highlight for *new* rules (`/api/selector`) | **G6** Not for existing rules; highlight is single-node |
| Did my scratch edit do what I think? | The app window, if visible; `scratchCss` in status | **G7** No before/after, no thumbnail, no state strip on the page |
| Oops, back one step | Clear all | **G8** No undo |
| Why does clicking do nothing? Why can't I style it? | Blocker `bound`; `fx_explain_property` for the agent | **G9** No binding tree or handlers on the page |

## 2. Target scenario

1. The developer runs `mvn fxloop:run`, picks the *Save* button, and opens the inspector. The header shows a thumbnail
   of the button as it is on screen.
2. They tick **:hover** in the state row. The button in the app renders hovered and stays that way; the Styles tab now
   lists the `.button:hover` rule from `modena.css` as the winner for `-fx-background-color`, overridden by nothing.
   They click the swatch: `-fx-hover-base` → `derive(-fx-base, 20%)` → `-fx-base` from `.root` in `app.css:12` →
   `#3c3f41`. They edit the value live, see the thumbnail pair *before / after · 2.1 % changed*, and **Apply to file**.
3. They switch to **Layout**. It reads *width 60 below preferred 84: `prefWidth=60` set in `contact-dialog.fxml:41`;
   parent `HBox` gives no grow*. They set `HBox.hgrow` to `ALWAYS` in the tab; the button grows in the app. **Apply to
   file** writes the attribute into the FXML; the FXML tier swaps the document and the view state survives.
4. They type `#saveBtn` in the tree search; later `Button` shows *12 matches* and ↓ walks them while the app outlines
   each one.
5. On **Sheets** they see `app.css` has a parse error at line 80 and four rules nothing in the app matches. They ask the
   agent to clean them up; the agent runs `fx_lint_css` and gets the same list.

## 3. G1 — Forced pseudo-states (I1)

**Page.** A state row under the node header: `:hover` `:focused` `:pressed` `:disabled` `:selected` `:showing`
`:armed`, plus a free-text entry for custom pseudo-classes the app defines. Ticking one sends it to the agent; the
styles pane re-renders with the new state; the app shows it.

**Agent.** New FCP method `FORCE_STATES {h, states: [..]}`. Implementation reuses what `Snapshots.captureStates` does
with `pseudoClassStateChanged`, but keeps the state until it is cleared. JavaFX controls flip their own pseudo-classes
on input (a mouse exit clears `hover`), so the agent attaches a listener to the node's `pseudoClassStates` set and
re-asserts forced ones that disappear. Forced states are cleared on: `FORCE_STATES {states: []}`, selecting another
node in the page, the page closing (SSE disconnect), app restart, and always before `fx_capture_states` runs its own
sequence. At most one node holds forced states at a time.

**Cascade.** No change to the engine: `Selector.stateMatches` reads the node's current pseudo-class set, which now
contains the forced ones. The response marks forced states (`pseudoClasses: [{name, forced: true}]`) so the page can
draw them and the agent can tell real from forced.

**Agent tool.** `fx_explain_style` gains `states: ["hover"]`: a *simulation* only (the engine is run with the given set
added, nothing is forced on the node), so the agent can ask "what would win on hover" without touching the app. The
live value check is skipped for simulated states and `simulation: "assumed"` says so.

**Screenshots** taken while a state is forced show it; the result carries `forcedStates` so that frames are never
mistaken for the resting look.

## 4. G2 — Layout tab (I2)

### 4.1 Read

A third tab next to Styles and Computed, fed by `/api/layout`, which already returns `explainLayout`. It shows:

- the box model as today, plus **margin** and **border** when set, and layout vs. bounds-in-parent when they differ
  (translate, scale, rotate);
- `min / pref / max` for width and height with where each value comes from: `USE_COMPUTED_SIZE`, set in FXML
  (`file:line` through the FXML registry), set in code, or CSS (`-fx-pref-width` links back to the Styles tab);
- the parent container's kind and the constraints that apply to this child (`HBox.hgrow`, `VBox.vgrow`,
  `GridPane` row/column/span/halignment, `AnchorPane` anchors, `BorderPane` position, `StackPane` alignment,
  `FlowPane`/`TilePane` orientation), each with its source;
- the hints `explainLayout` already computes ("width below preferred width", "unmanaged", "translate offset applied
  after layout", "not rendered") rendered as callouts at the top, each with a one-line explanation of what usually
  causes it and a link to the relevant container cheat-sheet section.

### 4.2 Edit (under the same `FXLOOP_INSPECTOR_EDIT` flag as CSS)

Every numeric and enum value in the tab is editable with the existing value editors (↑/↓, suggestions). Edits go to the
agent through `SET_PROPERTY`, which gains **constraint keys**: `HBox.hgrow`, `VBox.vgrow`, `GridPane.columnIndex`,
`GridPane.rowIndex`, `GridPane.columnSpan`, `GridPane.rowSpan`, `GridPane.halignment`, `GridPane.valignment`,
`AnchorPane.topAnchor` (and the other three), `BorderPane.alignment`, `StackPane.alignment`, `*.margin`. The agent
maps them to the static setters; `fx_set_property` accepts the same keys.

Layout edits are not CSS, so they are not in the scratch stylesheet. They are tracked as a separate **scratch
property set** `{path, property, before, after}` in the server, shown in the scratch bar as *2 layout edits*, and
reverted by **Clear** the same way. `fx_get_app_status` reports them as `scratchProps`.

**Apply to file.** For a node the FXML registry knows (document, `fx:id` or element index), the server writes the
attribute into the FXML element: set or replace `prefWidth="60"` → `prefWidth="-1"`, `HBox.hgrow="ALWAYS"` as a
namespaced attribute, `GridPane.columnIndex`. The patcher works on the text (attribute-level edit of the element the
registry located by line), never re-serializes the document, so formatting survives. The FXML tier then swaps the
document. For code-built nodes there is nothing to patch: the plan lists the edits as *set in code: hand to the
agent*, and **Ask agent to apply** sends them with the node path, exactly like CSS. Applying a layout edit while the
FXML document also has pending scratch CSS runs the CSS write first.

## 5. G3 — Tree search (I3)

A search field above the tree. Query grammar is `fx_find_nodes`'s: a CSS selector (`#saveBtn`, `.primary`,
`Button.primary`), `text:Save` for visible text, `type:TableView`. Results: *n matches*, ↑/↓ or Enter cycles through
them, the tree expands to each, the app outlines the current match, and the highlight overlay shows all matches at once
(§7) when there are 20 or fewer. The window selector scopes the search; `all windows` searches popups too.

## 6. G4 — Looked-up value chains (I4)

The cascade engine resolves a looked-up name one level today. Extend it to a chain with a cycle guard and depth 8:
for each declaration whose value contains a lookup or a `derive`/`ladder` call, `lookups: [{name, definedIn: {selector,
file, line, origin}, value, overriddenBy?}]` down to the literal, plus the resolved final color. The page shows the
chain on swatch hover, and a click on a step jumps to that rule. `fx_explain_style` returns the same array. Modena's
`derive()` percentages are applied with the public `Color.deriveColor` semantics; where the result and the live value
disagree the existing `simulation: mismatch` path reports it.

## 7. G5 and G6 — Sheets tab, `fx_lint_css`, rule scope (I5, I6)

### 7.1 Sheets

A window-level view (the window selector applies): every stylesheet in effective order with origin (user agent /
scene / which `Parent` / inline count), the file it was loaded from and the mapped source when one exists, rule and
declaration counts, and parse errors with `file:line` and the parser's message. Clicking a sheet lists its rules; each
rule shows the number of nodes it matches in this window right now, and clicking the selector outlines them all in
the app (multi-node highlight, §7.3).

### 7.2 Dead and broken rules

A **Check** button (not automatic: it is `rules × nodes`) computes, for every author sheet, the rules whose selectors
match no node in any open window. Matching for this check uses `Selector.applies` (structure only, pseudo-classes
ignored) so `.button:hover` is not reported dead just because nothing is hovered. Each dead rule links to its line.
Rules whose selectors reference a style class or id that no node in the app has at all are flagged separately
(*probably a typo*).

The same analysis is the new read-only tool **`fx_lint_css`**: `{sheets: [{file, origin, rules, errors: [...]}],
unused: [{file, line, selector}], unknownSelectors: [...]}`. It joins `fx_lint_visual` and `fx_lint_fxml` in the "find
problems" family, and the `fxloop-consistency` and `fxloop-style` skills gain a step that runs it.

### 7.3 Multi-node highlight

`HIGHLIGHT` takes `handles: [..]` in addition to `h`. The overlay draws up to 50 outlines in one popup per window
(labelled with the match index when the request asks for it). Used by tree search, rule scope and the Sheets tab.

## 8. G7 — Evidence on the page (I7)

- **Node thumbnail** in the header: a crop of the window screenshot at the node's bounds, refreshed on select and after
  every scratch change. Served by `GET /api/shot?h=..&w=..` on the viewer (token-protected like everything else),
  capped at 320 px wide, rate-limited by the existing screenshot cap.
- **Before / after** for scratch edits: the server snapshots the node before a `CSS_SCRATCH_SET` or a layout edit and
  after it has settled, runs the existing diff and shows the pair with `changedPct` in the scratch bar. *0 %* is the
  "your selector does not match" signal, as in the CSS reload tier.
- **States strip** button on the header: runs `CAPTURE_STATES` for the node and shows the strip inline. Forced states
  (§3) are cleared first and restored afterwards.

## 9. G8 — Undo for scratch (I8)

The scratch CSS items and the scratch property set form one history stack in the page. `Ctrl+Z` / `Ctrl+Shift+Z`
while the styles pane has focus, and *Undo* / *Redo* in the scratch bar. Each step re-sends the full scratch state (the
API is already whole-state), so there is no server change. **Apply to file** and **Clear** reset the stack.

## 10. G9 — Bindings and handlers (I9)

- In Computed and Styles, *bound in code* becomes a link that renders `explainProperty`'s dependency tree inline
  (the `EXPLAIN_PROPERTY` method exists).
- A **Handlers** section in the node header lists the `onXxx` event handler properties that are set (`onAction`,
  `onMouseClicked`, `onKeyPressed`, …) with the handler's class. Lambdas and method references are reported by their
  declaring class (`Foo$$Lambda` → *lambda in Foo*). Handlers added with `addEventHandler` live in internal state and
  are **not** listed; the section says so. This is an approximation and is documented as one.

## 11. Tool, protocol and config surface delta

| Item | Change |
|---|---|
| `fx_explain_style` | `states: [..]` simulation param; `lookups[]` per declaration; `pseudoClasses[].forced` |
| `fx_explain_layout` | `sources` per min/pref/max and constraint (FXML `file:line`, code, CSS) |
| `fx_set_property` | constraint keys (`HBox.hgrow`, `GridPane.columnIndex`, `AnchorPane.topAnchor`, `*.margin`, …) |
| `fx_lint_css` | **new**, read-only |
| `fx_take_screenshot` | `forcedStates` in the result when any are active |
| `fx_get_app_status` | `scratchProps` next to `scratchCss` |
| `fx_reload` | `clearScratch` also clears scratch properties |
| FCP | `FORCE_STATES`, `CSS_LINT`, `HIGHLIGHT {handles[]}`, `SET_PROPERTY` constraint keys, `CSS_CASCADE` lookups/forced |
| Viewer | `/api/states`, `/api/shot`, `/api/sheets`, `/api/lint`, `/api/find`, `/api/props` (scratch properties), `/api/fxml/apply` |
| Page | Layout tab, Sheets tab, state row, tree search, thumbnails, undo |
| Config | none new. Editing stays behind `FXLOOP_INSPECTOR_EDIT` / `fxloop:run` |

## 12. Acceptance

**Test bed.** The gauntlet *CSS cascade* tab gains: a `.button:hover` rule that beats the resting rule; a looked-up
color defined on `.root` and re-derived on a parent; two rules that match nothing; a selector with a misspelt class.
A new gauntlet *Layout* tab: an `HBox` with a child below pref width, a `GridPane` with span and alignment constraints,
an `AnchorPane` child, an unmanaged node and a translated node, all loaded from FXML with `fx:id`s.

- **A-G1 States.** Force `hover` on the gauntlet button → `/api/styles` shows the hover rule as winner, a screenshot
  shows the hovered look and carries `forcedStates`; clear → identical to the resting screenshot (diff ≈ 0). Moving the
  real mouse over and off the node leaves the forced state in place.
- **A-G2 Layout read.** For every node in the gauntlet layout tab, the tab's values equal `fx_explain_layout`'s and each
  FXML-set value links to the right `file:line`.
- **A-G3 Layout edit.** Set `HBox.hgrow=ALWAYS` on the squashed child → bounds change in the scene diff → apply to file →
  the FXML attribute is present, the document was swapped (tier `fxml`), and the scratch property set is empty.
  Checksum of every other file unchanged.
- **A-G4 Lookups.** The derived color's chain has three steps and ends in the live value.
- **A-G5 Lint.** `fx_lint_css` reports exactly the two dead rules, the misspelt selector and the parse error, each with
  the expected line; nothing else from the gauntlet sheets.
- **A-G6 Search.** `Button` in the tree search returns the same count as `fx_find_nodes`; the highlight overlay
  contains that many outlines.
- **A-G7 Evidence.** After a scratch edit the before/after pair exists and `changedPct > 0`; a selector that matches
  nothing yields `0`.
- **A-G8 Undo.** Three scratch edits, two undos, one redo → the scratch CSS equals the second edit's state.
- **Conformance.** A-F1 from PRD 08 is re-run with forced states on each node of the showcase app; agreement stays
  ≥ 99 %.
- **Manual.** One browser walkthrough of §2 on the showcase app, on a real display, Linux and Windows.

## 13. Milestones

1. **G1 — States, search, thumbnails** (I1, I3, I7 thumbnail + states strip). The hover gap is the most-asked question;
   search and thumbnails are small. ~3 days.
2. **G2 — Layout tab** read, then edit with FXML write-back (I2). ~4 days, the FXML patcher is the risk.
3. **G3 — Sheets, lint, lookups, multi-highlight** (I4, I5, I6). ~4 days.
4. **G4 — Undo, before/after diff, bindings and handlers** (I7 diff, I8, I9). ~2 days.

## 14. Risks

- **Forced states fighting the control.** Some skins toggle pseudo-classes in listeners on every pulse; the re-assert
  listener could loop. Mitigation: re-assert at most once per pulse and give up (reported as `forced: false`) after
  three consecutive reversals.
- **FXML attribute patching.** Elements without `fx:id` are located by the registry's line; an FXML file edited since
  the load (hand edits, the agent) can shift lines. The patcher re-verifies the element name and existing attributes
  before writing and refuses on mismatch, so a stale map never corrupts a file.
- **Dead-rule false positives.** Rules for views not currently open look dead. The report says *in the open windows*
  and the page offers to walk through the app's tabs (agent-driven) before judging.
- **Thumbnail cost.** A screenshot per select and per edit on a large window. Mitigation: crop on the agent side
  (`Node.snapshot` of the node only, not the window), the existing rate cap, and no refresh while the pane is hidden.

## 15. Non-goals

A general property editor for every node property (`fx_set_property` exists for the agent); editing FXML structure
(adding or moving nodes); an animation or timeline view; event tracing (a log of events as they fire); a replacement for
Scenic View's full property dump.

## 16. Open questions

1. Should forced states survive selecting another node, so a developer can compare two hovered buttons? (Default: no;
   one node at a time keeps the mental model simple and the clean-up safe.)
2. Should layout edits for code-built nodes attempt a source patch when the value is a literal in a single-line
   setter call (`button.setPrefWidth(60)`)? (Default: no in v1; the agent does it with full context.)
3. Is `fx_lint_css` worth running automatically in `fxloop-ui-review`? (Default: yes, as a low-severity section.)

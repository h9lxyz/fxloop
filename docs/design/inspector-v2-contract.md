# Inspector v2 — implementation contract (PRD 09)

Working contract between the parts of PRD 09 that are built in parallel. Every shape here is a promise: the producer
emits exactly this, the consumer relies on nothing else. Fields marked `?` may be absent.

## Ownership

| Area | Files (only these may be edited) |
|---|---|
| **A — agent: states, highlight, screenshots, handlers** | `fxloop-agent/.../ForcedStates.kt`, `Handlers.kt`, `Overlay.kt`, `Snapshots.kt` (+ new agent tests) |
| **B — agent: CSS engine (lookups, lint, sheets, simulated states)** | `fxloop-agent/.../css/Cascade.kt`, `css/CssInspect.kt`, `css/CssLint.kt`, `css/Sheets.kt` (+ new agent tests) |
| **C — agent: layout read + constraint keys + FXML edit plan** | `fxloop-agent/.../Introspect.kt` (explainLayout only), `Props.kt`, `FxmlEdit.kt`, `FxmlRegistry.kt` (+ new agent tests) |
| **D1 — server** | `fxloop-server/.../see/Viewer.kt`, `tools/*.kt`, `loop/Reloader.kt` (clearScratch only), `see/Visuals.kt`, server tests, `docs/tools.md`, skills text |
| **D2 — page** | `fxloop-server/src/main/resources/dev/fxloop/server/inspect/inspect.html` (+ `ViewerTest` page assertions if needed) |
| lead | `Fcp.kt`, `Dispatcher.kt`, gauntlet app, acceptance tests, this file |

`Fcp.kt` and `Dispatcher.kt` already contain every new method and route it to the stubs in A/B/C's files. Do not
change stub signatures.

## FCP methods (agent side)

### `forceStates` (A) — `ForcedStates.force(p)`
Params: target (`handle`/`path`/…) and `states: ["hover", "focused", …]` (names without colon; custom names allowed).
`states: []` or no target clears. At most one node holds forced states; forcing on another node clears the first.
Kept until cleared: a listener on `node.pseudoClassStates` re-asserts forced classes that disappear, at most once per
pulse; after three consecutive reversals of one class it gives up on that class (`forced: false`). `disabled` uses
`pseudoClassStateChanged` (never `setDisable`).
Result: `{target?, forced: ["hover"], gaveUp: ["armed"], pseudoClasses: [{name, forced}]}` (pseudoClasses = the
node's full current set).
Also: `ForcedStates.clear()`, `ForcedStates.forcedNames(node): Set<String>` (empty if none),
`ForcedStates.describe(): ObjectNode?` (`{target, forced}` or null), `ForcedStates.<T> suspended(block: () -> T): T`
(clears, runs block, re-applies — used by `captureStates`).
`status` includes `forcedStates` (= `describe()`) when active (wired in Dispatcher). Screenshot results
(`screenshot`, A) include `forcedStates: {target, forced}` when active.

### `highlight` (A) — extended
Params as today, plus `handles: [int]` (up to 50; more are ignored, `truncated: true`) and `labels: true` (draw the
1-based match index next to each outline). One popup per window draws all outlines. Result:
`{shown: n, truncated?, notShown: k}`. `clear: true` clears all.

### `nodeHandlers` (A) — `Handlers.describe(p)`
Result: `{target, handlers: [{property: "onAction", handler: "lambda in dev.x.Foo" | "dev.x.Foo$Bar", class}],
note: "handlers added with addEventHandler/addEventFilter are not listed (not public API)"}`. Only properties named
`on[A-Z]…` that are non-null.

### `cssCascade` (B) — changes
- `pseudoClasses`: now `[{name, forced}]` (forced from `ForcedStates.forcedNames`).
- each property whose effective declaration uses lookups or `derive`/`ladder`: `lookups: [{name, value, depth,
  definedOn, definedAt: {selector, file?, line?, origin, ruleId}, resolved: bool, overriddenBy?: {selector, file?,
  line?}}]` — a flattened depth-first chain (depth 0 = names in the declaration itself, depth 1 = names used by their
  definitions, …, max depth 8, cycle guard), plus `lookupValue: "#rrggbb" | "#rrggbbaa"` when the final color can be
  computed (Modena `derive` via `Color.deriveColor` semantics; `ladder` best effort). The old `{name, definedOn,
  definedAt, resolved}` keys stay valid.
- `explainStyle` param `states: ["hover"]`: simulation only — the cascade matches with those pseudo-classes added
  to the node's current set, nothing is set on the node, the live value check is skipped; every item then carries
  `simulation: "assumed"` and the result `simulatedStates: [..]`. Same shapes otherwise.

### `cssLint` (B) — `CssLint.lint(p)`
Params: `windowIndex?` (absent = all showing windows) plus the CSS roots (`projectDir`, `outputDirs`, …).
Considers every author stylesheet installed on a scene or Parent in those windows (not user-agent sheets, not
scratch). Matching uses `Selector.applies` only (pseudo-classes ignored).
Result: `{windows, nodes, sheets: [{file, url, origin, on, rules, declarations, errors: [{line?, message}]}],
unused: [{file, line?, selector}], unknownSelectors: [{file, line?, selector, unknown: [".bttn", "#svae"]}],
note: "unused = matches no node in the open windows"}`. A rule is unused when none of its selectors applies to any
node. A selector is unknown when it names a style class or id that no node in any showing window has; such rules are
reported only in `unknownSelectors`, not also in `unused`. `errors[].line` is parsed from the parser message when it
contains one.

### `cssSheets` (B) — `CssLint.sheets(p)`
Params: `windowIndex?` (default 0), `sheet?` (a url from the list).
Without `sheet`: `{window, sheets: [{id, url, file, origin, on, rules, declarations, errors: [{line?, message}],
external?, mapped?: {file}}]}` in effective order (user agent first; scene; parents outermost first; one entry per
distinct url+on). With `sheet`: the same object for that sheet plus `ruleList: [{index, selector, line?,
declarations, matches, handles: [int ≤ 50]}]`, matches counted over that window with `applies` (structure only).

### `setProperty` (C) — constraint keys
`name` may be a constraint key: `HBox.hgrow`, `HBox.margin`, `VBox.vgrow`, `VBox.margin`, `GridPane.columnIndex`,
`GridPane.rowIndex`, `GridPane.columnSpan`, `GridPane.rowSpan`, `GridPane.halignment`, `GridPane.valignment`,
`GridPane.hgrow`, `GridPane.vgrow`, `GridPane.margin`, `AnchorPane.topAnchor|rightAnchor|bottomAnchor|leftAnchor`,
`BorderPane.alignment`, `BorderPane.margin`, `StackPane.alignment`, `StackPane.margin`, `FlowPane.margin`,
`TilePane.alignment`, `TilePane.margin`. Mapped to the static setters, value coerced (enum, number, Insets), JSON
`null` or `""` clears the constraint. Result as today (`valueBefore`, `valueAfter`, `set`, `name`, `path`), values
as strings or null. Then `parent.requestLayout()`.

### `explainLayout` (C) — additions (existing keys unchanged)
- `parentKind`: `"HBox" | "VBox" | "GridPane" | "AnchorPane" | "BorderPane" | "StackPane" | "FlowPane" | "TilePane" | "other"`.
- `margin?`, `border?` (border widths `"t r b l"` for a Region with a border), `boundsDiffer: bool` (layoutBounds
  vs boundsInParent size or translate/scale/rotate), `transforms: {translateX, translateY, scaleX, scaleY, rotate}`.
- `sizes2: {minWidth, prefWidth, maxWidth, minHeight, prefHeight, maxHeight}`: each
  `{value: number (the property value, -1 = USE_COMPUTED_SIZE, -Infinity = USE_PREF_SIZE), computed: number
  (minWidth(-1) etc.), source}` where `source` is `{kind: "computed" | "fxml" | "css" | "code", file?, line?,
  cssProperty?}`. Only Regions/Controls have these properties; other nodes get only `computed`.
- `editable: [{key, value: string|null, kind: "enum"|"number"|"insets", choices?: [..], source}]` — the constraint
  keys valid for this parent kind plus `prefWidth`, `prefHeight`, `minWidth`, `minHeight`, `maxWidth`, `maxHeight`
  for a Region. `source` as above (`fxml` when the FXML element has that attribute).
- `fxml?: {file, absFile?, line, element, fxId?, document}` when the node was created by a registered FXML document
  (located by `fx:id`, else by element position under the document root).
- `hints` stay strings; plus `hintCodes: [{code: "below-pref" | "unmanaged" | "translated" | "not-rendered" |
  "zero-size", text, cause, section}]` (cause = one line on what usually causes it; section = container name the
  page can link to).

### `fxmlEditPlan` (C) — `FxmlEdit.plan(p)`
Params: `edits: [{handle?, path?, property, value}]` (property = a constraint key or `prefWidth` etc.; value string
or null = remove the attribute), plus CSS roots (`projectDir`, `outputDirs`, `resourceRoots`) to map the document
URL to its source file. Read-only. Result: `{files: [{file, absFile, sha256, text, changes: [{line, element,
attribute, from?, to?}]}], code: [{path, property, value}]}`. `code` = edits on nodes no registered FXML document
created. The patcher edits attribute text in place (never re-serializes), adds the namespace-free static attribute
(`HBox.hgrow="ALWAYS"`), verifies the element name/`fx:id` at the located line and throws `BAD_REQUEST` on mismatch.
`sha256` = `ScratchApply.sha256` of the file text read.

## Viewer HTTP (D1) — all token-guarded, rate-limited

| Endpoint | Method | Gate | Does |
|---|---|---|---|
| `/api/states?h=&path=&states=hover,focused` | POST | — | `forceStates`; empty `states` clears. Returns its result. |
| `/api/shot?h=&path=&w=` | GET | — | PNG of the node (`screenshot target=node`, maxWidth = min(w, 320)); counts against the screenshot rate cap; `image/png` or JSON error |
| `/api/strip?h=&path=` | GET | — | PNG state strip (`captureStates` + `ImageOps.strip`) |
| `/api/sheets?window=&sheet=` | GET | — | `cssSheets` |
| `/api/lint?window=` | GET | — | `cssLint` (window absent = all) |
| `/api/find?q=&window=` | GET | — | `findNodes`: `text:Foo` → text, `type:Button` → type, else query; window absent = all windows; limit 200. Returns `{total, items:[{h, path, type, fxId?, window?}]}` |
| `/api/highlight?handles=1,2,3&labels=1` | POST | — | multi highlight |
| `/api/handlers?h=` | GET | — | `nodeHandlers` |
| `/api/property?h=&name=` | GET | — | `explainProperty` |
| `/api/props` | GET | edit | `{items: [{h, path, property, before, after}]}` scratch property set |
| `/api/props` | POST JSON `{items:[{h, path, property, value}]}` | edit, lock | whole-state: applies new/changed via `setProperty` (remembering `before`), reverts removed ones to `before`. Returns `{items}` and publishes SSE `props`. Accepts `?evidence=1`: see below |
| `/api/props/clear` | POST | edit, lock | reverts all; SSE `props` |
| `/api/fxml/apply?dryRun=1` | POST | edit, lock | `fxmlEditPlan` for the scratch property set; dry run returns `{files:[{file, changes}], code:[..]}`; otherwise writes (sha checked, file must be an `.fxml` in a resource root), runs the reload (fxml tier), drops applied items from the set, returns `{applied, files, code, reload}`. If scratch CSS is active for the same document… CSS write first is not required (FXML only). |
| `/api/scratch/set?h=&path=&evidence=1` | POST | edit, lock | as today; with `evidence=1` and a target, also `evidence: {changedPct, verdict, seq}` |
| `/api/evidence?seq=&which=before|after` | GET | — | PNG of the last before/after pair (kept in memory, last 4) |

Evidence: node shot (as `/api/shot`, w 320) before the change, change, wait 200 ms, shot after, `ImageOps` diff
(threshold 0.06). `changedPct` is a number (0 = nothing changed: "your selector does not match").
When the last `from=inspect` SSE subscriber disconnects, the server sends `forceStates {states: []}`.
`fx_get_app_status` gains `scratchProps` (list as in `/api/props`) when non-empty; `fx_reload{clearScratch:true}`
also reverts scratch properties. New tool `fx_lint_css` (read-only, `windowIndex?`) returns `cssLint`.
`fx_explain_style` gains `states` (string array). `fx_set_property` description lists the constraint keys.
`fx_take_screenshot` passes `forcedStates` through and mentions it in the text.

## Page (D2)
Consumes only the endpoints above plus the existing ones. Features: state row (G1), tree search (G3), header
thumbnail (G7), Layout tab (G2 read + edit through `/api/props`, Apply to file via `/api/fxml/apply`), Sheets tab +
Check (lint) + rule scope highlight (G5/G6), lookup chain on swatch hover (G4), undo/redo over scratch CSS + props
(G8), before/after evidence in the scratch bar (G7), states strip (G7), binding tree + handlers (G9).

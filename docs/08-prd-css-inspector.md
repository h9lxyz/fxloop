# PRD 08 — CSS Inspector: "which rule wins, and why" (Stage F)
**Objective:** answer the most expensive JavaFX styling question — *"why doesn't my CSS apply, and what do I have to write to override it?"* — exactly, for both the agent and the developer. FXLoop reconstructs the CSS cascade per node from the public `javafx.css` API, maps every rule to `file:line`, says why each losing declaration loses, and proposes the smallest override that wins. The developer gets a browser-based inspector in the style of Chrome DevTools' Elements → Styles panel, next to the running app.

**Audience:** developers styling complex apps (Modena overrides, library themes, several stylesheets, custom controls) and the coding agent doing the same work through tools.

**Entry gate:** none beyond 1.0. Step 1 (§3) stands alone. Step 2 reuses the live viewer (PRD 04 §3.7); its in-app highlight shares the overlay with PRD 07 E6 pick mode and builds it if E6 is not there yet.

---

## 1. Problem

`fx_explain_style` reports a property's `StyleOrigin` and the candidate stylesheets along the parent chain, and stops there (`Introspect.explainStyle`: "rule-level source mapping … is out of scope"; [limitations](limitations.md)). Neither the agent nor the developer learns *which* rule set the value, *which* rules were overridden, or *what* would beat the winner. In practice the agent then guesses (adds `!important`, piles on selectors, or edits the wrong stylesheet) and the developer opens Scenic View, which as far as we know shows properties and origins but not the rule-level cascade with "overridden by" reasons.

JavaFX does not record the winning rule, but everything needed to recompute it is public API (verified with `javap` on OpenJFX 21.0.13):

| Need | Public API |
|---|---|
| Parse a stylesheet into rules, selectors, declarations | `CssParser.parse(URL)`, `Stylesheet.getRules()`, `Rule.getSelectors()`, `Rule.getDeclarations()` |
| Origin of a rule (user agent / author / inline) | `Rule.getOrigin()`, `Stylesheet.getOrigin()` |
| Does a selector match this node in its current state? | `Selector.stateMatches(Styleable, Set<PseudoClass>)`, `Selector.applies(Styleable)` |
| Specificity | `Selector.createMatch().getSpecificity()` |
| `!important` | `Declaration.isImportant()` |
| Inline styles as rules | `CssParser.parseInlineStyle(Styleable)` |
| Parse errors | `CssParser.errorsProperty()` |
| Inheritance, shorthands, current value and origin | `CssMetaData.isInherits()`, `getSubProperties()`, `StyleableProperty.getStyleOrigin()` |

Not exposed: line numbers and the original declaration text. §3.2 recovers both from the stylesheet source.

## 2. Design principles

1. **Reconstruct, then verify.** The engine's winner for each property is checked against the live `StyleableProperty` value and origin. Disagreement is reported as `simulation: "mismatch"` with both values, never hidden. The engine is an explanation, the live value is the truth (Principle 4, fail loud).
2. **Public API only** (Principle 3). Reading Modena's `.css` text from the `javafx.controls` jar is a resource read, not an internal API.
3. **Same answer for agent and developer.** The browser inspector renders the same JSON the tools return (dual-channel, Principle 8).
4. **Read-only by default.** Live CSS experiments (§5) are an explicit opt-in and never touch source files.

## 3. Step 1 — Cascade engine and agent tools

### 3.1 Cascade engine (agent)

For a node and its current pseudo-class state:

1. **Collect sheets in effective order:**
   - User agent: `Application.getUserAgentStylesheet()` (default Modena), `Scene`/`SubScene.getUserAgentStylesheet()`, `Region.getUserAgentStylesheet()` of the node's custom control.
   - Author: scene stylesheets, then `Parent.getStylesheets()` from the outermost ancestor inward. Per the JavaFX CSS reference, Parent stylesheets take precedence over Scene stylesheets.
   - Inline: `node.getStyle()`, and ancestors' inline styles for inherited properties and looked-up values.
2. **Match rules** with `stateMatches`. Every matching declaration becomes a candidate `{property, valueText, important, origin, specificity, sheet, ruleIndex, line}`.
3. **Order candidates per property** by `important`, then origin (user agent < code-set value < author < inline), then specificity, then source order. Values set from code (`StyleOrigin.USER`) enter the ordering as a pseudo-candidate "set in code".
4. **Inheritance:** for `isInherits()` properties with no matching declaration, walk up and report `inheritedFrom: <ancestor path>` with that ancestor's winning rule.
5. **Shorthands:** `-fx-font` vs `-fx-font-size`/`-fx-font-family`/…, `-fx-background-*`, `-fx-border-*` are grouped through `getSubProperties()`, so a shorthand that loses to a sub-property (or the other way round) is explained as such.
6. **Verify** each winner against the live value and origin (principle 1).

**Caching:** parsed stylesheets are cached by URL and invalidated by the CSS reload tier (`Reloads.reloadCss`) and on FXML swaps. The per-node cascade is computed on demand, on the FX thread, within the usual `callOnFx` timeout.

### 3.2 Source mapping

`Stylesheet.getRules()` is in source order. A small scanner over the stylesheet text (comments, strings, `@font-face` and `@import` aware) records the start line of each rule block and each declaration's raw text. The i-th scanned rule is paired with the i-th parsed rule, with the selector text as a cross-check. If the counts or selectors diverge, line numbers are omitted for that sheet and `sourceMap: "unavailable"` says why. Binary `.bss` stylesheets have no source: rules are shown without lines. Sheets in `outputDir` are mapped back to their source resource root (reverse of `Reloader.copyResources`), so `file` points at the file to edit, not the build copy.

### 3.3 Blockers ("why nothing I write applies")

Detected per property and reported as `blockers[]`, each with a one-line cause and a next step:

| Blocker | Detection | Next step |
|---|---|---|
| Property is bound in code | `Property.isBound()` (shared with `fx_explain_property`) | Remove the binding or style the source property |
| Inline style wins | winner origin `INLINE` | `!important` in the stylesheet, or remove `setStyle` (location via `fx_explain_property`) |
| More specific rule wins | winner specificity > yours | Suggested selector (§3.5) |
| No rule matched | your selector's rule exists in a sheet but `stateMatches` is false: report the first failing part (missing style class, id, pseudo-class, ancestor) | Fix the selector or add the class |
| Style belongs on a skin node | property not styleable on the node, but on a skin child (`.text`, `.arrow`, `.thumb`, `.column-header`) | Substructure selector, e.g. `.my-button .text` |
| Stylesheet not applied | sheet URL in no sheet list on the path, or parse errors from `errorsProperty()` | Fix the URL / the reported error |
| Looked-up color unresolved | value is a lookup (`-accent`) with no definition on the ancestor chain | Define it on `.root` or an ancestor |

### 3.4 `fx_explain_style` upgrade (backward compatible)

Existing fields stay. New fields:

```json
{"cssProperty":"-fx-background-color","value":"#e8e8e8","origin":"AUTHOR","simulation":"ok",
 "winner":{"selector":".ticket-dialog .button-bar .button","specificity":"0,3,0","file":"src/main/resources/app/theme.css","line":212,"text":"-fx-background-color: #e8e8e8;"},
 "overridden":[{"selector":".primary","specificity":"0,1,0","file":"src/main/resources/app/app.css","line":48,"text":"-fx-background-color: -accent;","reason":"lower specificity (0,1,0 < 0,3,0)"},
               {"selector":".button","origin":"USER_AGENT","file":"modena.css","line":563,"reason":"user agent stylesheet"}],
 "blockers":[]}
```

Token budget: without `property`, each styled property returns the winner plus `overriddenCount` only. With `property`, the full candidate list is returned, capped by `limit`. `rules:false` restores today's output exactly. The note about rule-level mapping goes away. [limitations](limitations.md) is updated to say what remains approximate (§9).

### 3.5 `fx_suggest_override` (new, read-only)

Input: target, `property`, optional `value`, optional `file`. Output: the smallest change that would win, verified by re-running the engine with a hypothetical rule appended:

```json
{"selector":".ticket-dialog .button-bar .button.primary","file":"src/main/resources/app/app.css","needsImportant":false,
 "snippet":".ticket-dialog .button-bar .button.primary { -fx-background-color: -accent; }",
 "why":"beats theme.css:212 (0,4,0 > 0,3,0)","blockers":[]}
```

Selector strategy, in order: reuse the winner's selector + one of the target's own classes; else `#fxId`-qualified; `!important` only when the winner is inline or itself `!important`. The default `file` is the author stylesheet closest to the node that the project owns (inside a resource root), never Modena or a dependency jar. Blockers that no selector can fix (bound property) return `selector: null` and the blocker.

## 4. Step 2 — Browser inspector

A page at `/inspect` on the existing viewer server (`FXLOOP_VIEWER`, loopback, token in query, PRD 04 §3.7). It is plain HTML, CSS and JS, shipped as a server resource, with no build step and no external requests.

**Layout** (DevTools Elements panel):

- **Toolbar:** *Pick in app*, *Show skin nodes*, window selector, connection status.
- **Tree (left):** the logical scene graph from the existing serializer. The skin-node toggle shows control internals, which the agent tools omit by default. Rows show `Type#fxId .classes`. Hovering a row highlights the node in the app; clicking selects it.
- **Styles (right):** rules in cascade order, winners first. Each rule shows selector, specificity badge, `file:line` and origin. Overridden declarations are struck through, with the reason on hover. An *inherited from* section and a *blockers* box with the suggested override (§3.5) follow, plus a *Computed* view (final values, with lookups resolved) and a box-model strip (size, padding, insets, from `fx_explain_layout`).
- **`file:line` links:** copy the path, and open it in the IDE via its URL handler if configured (`FXLOOP_EDITOR_URL`, e.g. `idea://open?file={file}&line={line}`).

**App ↔ browser:**

- **Highlight:** an agent-owned overlay popup that draws the node's bounds plus a label. It is invisible to the tools and screenshots, like the PRD 07 dev bar.
- **Pick:** pick mode (PRD 07 E6) selects the node in the browser. Picks arrive via the existing SSE stream as `pick` events.
- **Live updates:** after a CSS reload (agent or watch mode), the open selection re-queries automatically (`reload` SSE event), so the developer sees the new cascade right after a save.

**Endpoints (GET, token-guarded, rate-capped like the viewer):** `/api/tree?window=&skin=`, `/api/styles?path=`, `/api/layout?path=`, `/api/suggest?path=&property=`, `POST /api/highlight` (visual only: draws or clears the overlay, no app state change). The server proxies them to the agent over FCP (`CSS_CASCADE`, `HIGHLIGHT`). No FCP exposure to the browser.

## 5. Step 3 — Try it live (opt-in)

With `FXLOOP_INSPECTOR_EDIT=true`, the Styles pane lets the developer toggle a declaration, edit a value, or add a declaration to a new rule, the way DevTools does.

- **Mechanism:** edits go into one **scratch stylesheet**, installed as the last scene stylesheet through a `data:` URL (supported since JavaFX 17), or a temp file in `target/fxloop/` on older runtimes. Disabling a declaration is simulated by a scratch rule with the same selector and the next-lower winning candidate's value. The cascade then shows the scratch rule like any other, as `scratch:<n>`.
- **Ephemeral and visible:** nothing is written to source. The dev bar (PRD 07) shows `Scratch CSS active · 3 rules [Clear]`. `fx_get_app_status` reports `scratchCss`, and CSS-tier `visualDiff` results note it, so neither side mistakes a scratch change for a source change. Clearing removes the sheet. Scratch rules survive CSS reloads and are dropped on restart.
- **Handing over:** *Copy as CSS* copies the scratch rules. *Ask agent to apply* copies a ready-made prompt (`Apply these FXLoop scratch rules to the project's stylesheets: …`) and exposes the rules to the agent through `fx_get_app_status.scratchCss`. MCP is pull-only, so the developer pastes or says it; the agent then edits the real file, calls `fx_reload`, and clears the scratch sheet.
- **Security:** edit endpoints are `POST` with the token, only exist when the flag is set, and accept CSS text only. It is parsed with `CssParser` before installing, and rejected on parse errors. The viewer's read-only guarantee (PRD 04) holds when the flag is off.

## 6. Tool, protocol and config surface delta

| Item | Change |
|---|---|
| `fx_explain_style` | rule-level `winner`, `overridden`, `blockers`, `simulation`, `inheritedFrom`; param `rules` (default true) |
| `fx_suggest_override` | **new**, read-only |
| `fx_get_scene_graph` | param `skin` (include skin nodes), shared with the inspector tree |
| `fx_get_app_status` | gains `scratchCss` |
| FCP | methods `CSS_CASCADE`, `CSS_SUGGEST`, `HIGHLIGHT`, `CSS_SCRATCH_SET`, `CSS_SCRATCH_CLEAR` |
| Viewer | `/inspect`, `/api/*`, SSE `pick` and `reload` events |
| Config | `FXLOOP_INSPECTOR_EDIT`, `FXLOOP_EDITOR_URL` |

## 7. Acceptance

**Test bed:** a new *CSS cascade* tab in the gauntlet app with known conflicts, styled by `gauntlet.css` plus a second sheet on a parent:

- A specificity loss (`.primary` vs `.panel .bar .button`).
- An inline style beating a sheet.
- `!important` beating inline.
- A bound property (`textFill` bound in code).
- A rule that misses because of a missing style class.
- A skin-only property (`.text`).
- An inherited `-fx-font-size` from a parent inline style.
- A looked-up color defined on `.root` and overridden on a parent.
- A shorthand (`-fx-font`) vs a sub-property.
- A stylesheet with a deliberate parse error.

Scenarios:

- **A-F1 Conformance.** For every node in the three sample apps, the gauntlet and the showcase app, and every styleable property with a non-null origin, the simulated winner agrees with the live value and origin. Target: ≥ 99 % property-level agreement. Every mismatch is listed in the test report, and the known ones are documented in [limitations](limitations.md).
- **A-F2 Explanations.** Each gauntlet conflict returns the expected winner `file:line`, `overridden[].reason` and `blockers[]`.
- **A-F3 Suggest and verify.** For each fixable conflict, the agent writes `fx_suggest_override`'s snippet into the suggested file, calls `fx_reload`, and gets a `visualDiff` > 0. `fx_explain_style` then shows the new rule as the winner.
- **A-F4 Inspector.** Scripted HTTP checks: `/api/styles` returns the same JSON as `fx_explain_style`; highlight shows and clears; after a CSS reload, an SSE `reload` event arrives and `/api/styles` reflects the change. Plus one manual browser walkthrough of §4.
- **A-F5 Scratch.** Toggle a declaration → visible change, scratch rule shown in the cascade, `scratchCss` reported, no source file modified (checksum), Clear restores the original screenshot (diff ≈ 0).

## 8. Milestones

1. **F1 Engine + tools:** §3.1–3.5, gauntlet cascade tab, A-F1 to A-F3. Ships alone; the biggest win for the agent.
2. **F2 Inspector:** §4, highlight overlay (shared with PRD 07 E6), A-F4.
3. **F3 Try it live:** §5, A-F5.

Rough effort: F1 3–4 days, F2 3–4 days, F3 2–3 days.

## 9. Risks and known approximations

- **Cascade edge cases** (stylesheet ordering between Scene and nested Parents, `SubScene`, popups and context menus that take their sheets from the owner window, platform-specific Modena additions for touch or high contrast). Mitigation: the per-property self-check (principle 1) and the A-F1 conformance suite; mismatches are reported, not hidden.
- **Looked-up colors.** v1 shows the declared value and the resolved final value, plus where the lookup was defined when it's a direct `.root` or ancestor declaration. Full derivation chains (`derive(-fx-base, 20%)` ladders) are v2.
- **Performance** on huge scenes with big stylesheets: matching is per node, on demand, with parsed sheets cached. Budget: < 50 ms per node on the showcase app. The tree endpoint pages like `fx_get_scene_graph`.
- **Source mapping drift** from preprocessors (SCSS → CSS) or generated sheets: lines point at the generated `.css`; documented.

## 10. Non-goals

An in-app JavaFX inspector window (the app's own styling and FX-thread load would distort it, and it would show up in FXLoop's window lists and screenshots); saving inspector edits to source directly (the agent does this through files and `fx_reload`); a full property editor for non-CSS properties (`fx_set_property` exists); animations or timeline inspection; an IDE plugin.

## 11. Open questions

1. Should `fx_explain_style` default to `rules:true`, given the larger output? (Default: yes, without `property` only winners + counts are returned, which costs about the same as today.)
2. Should the inspector be its own server/port, or always live on the viewer? (Default: the viewer — one loopback endpoint, one token.)
3. Is the 99 % conformance target realistic before handling popups and context menus? (Default: measure in F1, and set the bar from the first run.)

# PRD 04 — FXLoop Visual Feedback Pillar (amendment to PRDs 00–03)
**Status:** elevates screenshots from a utility tool to a **first-class pillar**, driven by two product requirements:

- **R1 — The developer sees the app without running or watching it.** The agent must be able to put a current image of the application in front of the human in chat — including when the app runs headless, remote, or simply behind other windows.
- **R2 — CSS and visual issues must be catchable from pixels.** The scene graph as JSON is structurally complete but visually blind: it cannot show rendered colors, fonts, white-on-white text, overlap, clipping, truncation, spacing rhythm, shadows/gradients, or theme inconsistencies. Crucially, the MVP `effects` scene-diff **cannot detect pure-style changes at all** (its watched props are semantic, not style-resolved) — pixels are the *only* observable for most CSS edits.

This document specifies the new/changed tools, the delivery mechanism to the human, the context-budget policy, and lists the amendments applied to PRDs 00–03. The base docs have been updated in place; this PRD carries the deep spec the amendments point to.

---

## 1. R1 — How an image reaches the developer (mechanism, stated honestly)

Every image-producing tool follows the **dual-channel rule**:

1. **MCP image content block** in the tool result → the model always *sees* the pixels; MCP clients with image rendering (Claude Code in image-capable terminals and its IDE integrations, Claude Desktop) show the same image inline to the human in the transcript.
2. **Persisted PNG, always:** `target/fxloop/screens/<seq>-<label>.png` (zero-padded sequence + slugified label), path returned in `structuredContent`. This is the *guaranteed* channel: the agent cites the path in chat ("see `target/fxloop/screens/0007-after-css-fix.png`"), any IDE/file manager opens it, and it survives the session.
3. **Gallery (MVP):** server regenerates a static `target/fxloop/screens/index.html` (thumbnails, newest first, labels, timestamps) after each capture — one bookmarkable page for P2 to skim a whole session.
4. **Live viewer (Production, opt-in):** `FXLOOP_VIEWER=<port>` serves `127.0.0.1:<port>/?t=<token>`: latest frame auto-refreshing (~1 s poll or SSE) + the gallery. This is the "watch the app without the app" mode.

**Strategic consequence worth naming:** dual-channel screenshots + Monocle/xvfb (PRD 03 G3) make **JavaFX development inside devcontainers/Codespaces/remote boxes** viable — environments where no window exists for anyone. There, the screenshot *is* the application's face for both the model and the human. Add this to the Vision narrative (applied, see §6).

**Retention:** keep the newest 100 captures (configurable), prune oldest; `fx_restart_app` does not clear them (before/after across restarts is a feature).

## 2. Context-budget policy: *paths always, pixels on demand*

Images are the most expensive content in an LLM context. Binding policy:

- Every image tool/result returns **numbers + path always**; the image block is governed by `includeImage`:
  - `fx_take_screenshot`: default `true` (seeing is the point of the call).
  - `fx_compare_screenshots` and auto-diffs inside `fx_reload`: default `false` — summary numbers + `diffPath`; the agent pulls the picture only when the numbers warrant it.
  - `fx_get_screenshot` exists precisely to re-attach any persisted frame later (§3.3).
- Defaults: window captures `maxWidth=1200` (hard cap 1600); diff computation internally at width 800; PNG only.
- `settleMs` (default 150) on every capture: wait after the last action/reload before snapshotting, so animations/transitions settle and diffs don't flag motion blur.

## 3. Tool specifications

### 3.1 `fx_take_screenshot` v2 (upgrades the PoC tool; RO)
Params: `target=window|node`, `handle|path?`, `windowIndex?`, `maxWidth=1200`, `scale?`, `label?`, `annotate=none|interactive|all|<css-query> (none)`, `includeImage=true`, `settleMs=150`.
- **`scale` (0.25–3.0):** applied as a `SnapshotParameters` transform **at capture time** — JavaFX renders vectors, so `scale=2` on a node is true supersampling, not upscaling: small text becomes genuinely legible to the vision model. Recommended pattern: window overview at default, then `scale=2` node shots for fine inspection.
- **`annotate` (Set-of-Mark):** overlays numbered markers + bounding boxes on a copy of the frame. `interactive` = focus-traversable controls + `Labeled` (default filter); `all` = every visible node within budgets; a CSS query restricts to matches. Marker cap 40 (+`overflow:true`). `structuredContent.legend: [{n, path, fxId?, type, text?}]` maps every marker back to the tree. **This is the pixels↔tree bridge:** the model spots "marker 12 is misaligned / unreadable" and immediately has the node path for `fx_explain_style`/`fx_explain_layout`.
- Result adds: `{seq, file, label, w, h, scale, bytes, legend?}`.
- **Architecture:** the agent stays thin (Overview D-rule) — it ships the raw frame plus window origin/output scale; the **server** composites annotations with `Graphics2D` (boundsScreen → image coords, HiDPI-corrected) and writes/persists. FCP `screenshot` response gains `{windowOrigin:{x,y}, outputScale}` for that mapping.

### 3.2 `fx_compare_screenshots` (RO)
Params: `before` (seq|path), `after` (seq|path | `capture:true`), `threshold=0.06` (per-pixel normalized RGB distance), `includeImage=false`.
Result: `{changedPct, regions:[{x,y,w,h}]≤20 (clustered), diffPath, verdict:none|minor|major, pixelSize:{w,h}}` — composite diff image (changed pixels highlighted over dimmed grayscale base) persisted regardless. Size-mismatched inputs are center-cropped to the intersection with a `cropped:true` note.
Thresholding + `settleMs` exist to suppress caret-blink/animation false positives; remaining flicker is why `verdict:minor` (< 0.5 %) exists.

### 3.3 `fx_get_screenshot` (RO)
Params: `seq|path|latest:true`, `includeImage=true`. Re-emits any persisted frame (including diff composites) as an MCP image block — the "show the developer/model that frame again" tool, and the retrieval half of the pixels-on-demand policy.

### 3.4 `fx_reload` CSS-tier integration (changes PRD 02 §3 tier 1)
When tier `css` executes, the server auto-captures before/after of the focused window (internal width 800, no image blocks) and appends to the reload result:
`visualDiff: {changedPct, regions, diffPath, beforeSeq, afterSeq}`.
Two built-in verdict hints, because they encode the two classic CSS failure modes:
- `changedPct ≈ 0` → `"no visual change — the edited selector may not match any node; check fx_explain_style on the intended target"` (the selector-didn't-match bug, undetectable by `effects`).
- `changedPct` far above the touched region's share → `"change affected more than expected — selector may be broader than intended"` (regions list shows where).
Opt out with `visualDiff:false`.

### 3.5 `fx_lint_visual` (MVP basic → Production full; RO, all findings tagged `heuristic:true`)
Params: `scope` (window|path subtree), `checks?`. Computable complements to vision:
- **MVP:** `textTruncated` (Labeled/Text: measure string at the node's font via `Text` helper vs available width — best-effort), `siblingOverlap` (visible, pickable, intersecting `boundsInParent`; `StackPane` children excluded by design), `zeroSizeVisible`, `outOfScene` (visible node fully outside scene bounds).
- **Production adds:** `lowContrast` — WCAG ratio of `textFill` vs nearest ancestor `Region` solid background fill (gradients/images skipped, reported `skipped:unresolvedBackground`).
Each finding: `{check, path, detail, severity:info|warn, suggestion}`. Paginated.

### 3.6 `fx_capture_states` (Production; write-then-restore)
Params: `handle|path`, `states=[none,hover,focused,pressed,disabled]`, `scale=2`.
For each state: apply via `PseudoClass.getPseudoClass(...)` + `pseudoClassStateChanged` (`disabled` via `setDisable`), snapshot the node, then **restore the original state set** (guaranteed in `finally` on the FX thread). Compose one horizontal strip with captions → persist + return (dual-channel, `includeImage=true`).
Caveat, stated: skins that react only to *real* input events may render hover/pressed incompletely; result flags `method:pseudoClass`, and a `method:robotHover` alternative (robot moves the cursor for the hover frame) is offered for the hover state. This is the **CSS workbench**: one call shows a control's full state styling for review.

### 3.7 Live viewer (Production, opt-in — see §1.4)
Served by `fxloop-server` (it already owns the frames); loopback-only, token required in query, read-only endpoints (`/latest.png`, `/index`, `/sse`), off by default, no FCP exposure. Screenshot rate cap (PRD 03 G7) applies to viewer-triggered captures too; the viewer re-serves the latest persisted frame rather than forcing new captures.

## 4. Vision limitations & standing mitigations (honesty section)

| Limitation | Mitigation (spec'd above) |
|---|---|
| Small text illegible after downscale | `scale=2` supersampled node shots; annotate markers to pick the node first |
| Vision cannot read exact colors/hex reliably | Never eyeball values: pair the screenshot with `fx_explain_style` / `fx_get_property` for ground truth; pixels answer *"does it look right"*, the tree answers *"what is it exactly"* |
| Animations/caret blink poison diffs | `settleMs`, threshold, `verdict:minor` band |
| Headless (`xvfb`/Monocle sw pipeline) may render fonts/AA slightly differently than desktop | Compare like with like: diffs always within one environment; cross-env comparison flagged `crossEnv:true` warning |
| Pixel diffs ≠ regression assertions | Unchanged: visual-regression *assertions/baselines* stay deferred (PRD 03 §14). Diffs here are feedback, not CI gates |

## 5. Staging & acceptance additions

| Stage | Ships | Acceptance additions |
|---|---|---|
| **PoC** (retrofit if already built) | Dual-channel persistence + `label` + `settleMs` on `fx_take_screenshot` | Script step 2 extended: persisted file exists at returned path; H5 unchanged |
| **MVP** (new feature **F12**, spec = this doc §3.1–3.5 MVP parts) | annotate + legend, `scale`, `fx_compare_screenshots`, `fx_get_screenshot`, CSS-tier `visualDiff`, gallery index, `fx_lint_visual` (basic) | **A-V1:** sample app gains a deliberately misaligned button + a white-on-white label (behind a `--broken-css` flag); agent finds both via one annotated screenshot + `fx_lint_visual`, names the node paths. **A-V2:** edit `app.css` with a non-matching selector → `fx_reload.visualDiff.changedPct==0` + selector hint; fix selector → `changedPct>0` confined to expected region. **A-V3:** W1 extended — CSS color change verified by `visualDiff` *and* a `fx_get_screenshot` the human can open. Evals: +3 visual Q&A pairs in `evals/mvp.xml` |
| **Production** (folds into **G12**) | `fx_capture_states`, `lowContrast` lint, live viewer, HiDPI annotation-coordinate tests in the gauntlet, screenshot-pipeline budget already in G9 | Gauntlet rows: annotate coords correct at 150 %/200 % scaling; states-strip for a styled button renders 5 frames + restores state; viewer smoke (token required, latest frame served); contrast lint catches a seeded 1.4:1 label |

## 6. Amendments applied to the base PRDs (changelog)

| Doc | Change |
|---|---|
| 00 §1 | "Eyes" bullet now names pixels as first-class evidence + chat delivery; headless/remote dev named as enabled scenario |
| 00 §4 | New Principle 8: *Pixels and tree are complementary evidence* + dual-channel rule |
| 00 §8 | "See" row extended per stage (dual-channel / annotate+visual diff / states+viewer) |
| 00 header | Document set is now 0–4 |
| 01 §7 | `fx_take_screenshot` row: `label`, `settleMs`, persistence + path (dual-channel, → PRD 04) |
| 01 §10 | Acceptance step 2: persisted-file check |
| 02 §1 | Feature map: **F12 Visual feedback pillar** (spec → PRD 04) |
| 02 §10 | Tool delta: + `fx_compare_screenshots`, `fx_get_screenshot`, `fx_lint_visual`; `fx_take_screenshot` gains annotate/scale; `fx_reload` gains `visualDiff` |
| 02 §12/§13 | Workflows A-V1–A-V3 added; F12 spread across M7 (diff/compare) and M9 (annotate, lint, gallery) |
| 03 §1/§13 | Feature map: **G12 Visual pro** (states, contrast lint, viewer, HiDPI gauntlet rows); folded into M13/M15 |

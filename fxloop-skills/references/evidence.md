# Evidence rule

Every FXLoop skill follows this rule. A UI or UX improvement counts as done only when the developer can check it.

## The rule

1. **No claim without evidence.** Never write "fixed", "aligned", "now readable" or "looks better" unless one of
   these backs it up:
   - an `effects` diff from the action or `fx_reload` that shows the change (a node added or removed, or a property
     that changed), or
   - a **labeled, persisted screenshot** (`fx_take_screenshot label=...`) or diff composite
     (`fx_compare_screenshots`), cited by its **file path**.
2. **Paths always, pixels on demand.** Every frame is saved under `target/fxloop/screens/` (Maven) or
   `build/fxloop/screens/` (Gradle), and `index.html` there is a gallery. Cite paths in the reply so the developer can
   open them. Set `includeImage=false` when you don't need to look at the pixels yourself (big batches, size ladders).
3. **Exact values come from tools, never from eyeballing.** Colors, sizes, insets, fonts and contrast ratios come
   from `fx_explain_style`, `fx_explain_layout`, `fx_get_property` / `fx_get_node_details` or `fx_lint_visual`. A
   screenshot shows *that* something is off. The explain tools say *what* the values are.
4. **Before/after pairs.** Capture a labeled "before" frame before an edit and an "after" frame once it is applied,
   then compare them (`fx_compare_screenshots before=<seq> capture=true`) or use the reload's own `visualDiff`.
   Report `changedPct` and the region count, not adjectives.
5. **Say what you could not verify.** Some checks have no tool behind them: real screen-reader output, a GPU vs.
   software rendering difference, native dialogs, animation timing. Name those as "not verified" instead of
   implying they were checked.

## Techniques

- **Annotated shots are the bridge from pixels to the tree.** `annotate=interactive` (or `all`, or a CSS selector)
  draws numbered markers. The legend maps each number to a node path, which you pass straight to `fx_explain_*`,
  `fx_click_node` and the other tools.
- **Small text:** take a node shot with `target=node scale=2` (up to 3) instead of a full-window shot.
- **Point → node:** for something seen at a pixel position, call `fx_describe_at x y` with screen coordinates (from
  `boundsScreen`).
- **Labels:** use short, sortable labels such as `review-main`, `before-detail`, `after-detail`, `w1024-de`. They
  become the file names.
- **Window choice:** `fx_get_app_status` lists windows (`index`, `title`, `type STAGE|POPUP|DIALOG`). Pass
  `windowIndex` when more than one is showing.
- **Rate cap:** screenshots are limited to about 2 per second. Batch on purpose, and don't loop on retakes.
- **`visualDiff` from a CSS reload:** a result near 0 % means the selector probably matched nothing (a typo, a
  wrong id, an invalid pseudo-class). Above 30 % means the selector is broader than intended. Both come with a
  `hint`.

## Reporting template

```
<what changed, in one line>
Evidence: <before path> → <after path> (changedPct 2.4 %, 1 region) · effects: helpBtn.layoutX 212→236
Not verified: <anything without a tool behind it>
```

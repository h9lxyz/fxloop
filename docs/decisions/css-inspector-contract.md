# CSS inspector — internal contract (PRD 08)

Shared shapes between the agent (`fxloop-agent`), the server (`fxloop-server`) and the browser page
(`/inspect`). The PRD is the "what"; this file pins the wire format so the three parts agree.

## FCP methods (`Fcp.Method`)

| Constant | Name | Params | Result |
|---|---|---|---|
| `EXPLAIN_STYLE` | `explainStyle` | target (`handle`/`path`/`query`), `property?`, `rules` (default true), `limit`, `offset` | today's fields **plus** per item `winner`, `overriddenCount` (no `property`) or `overridden[]` (with `property`), `simulation`, `inheritedFrom?`, `blockers[]` |
| `CSS_CASCADE` | `cssCascade` | target | the full cascade for the inspector, see *Cascade JSON* |
| `CSS_SUGGEST` | `cssSuggest` | target, `property`, `value?`, `file?` | `{selector, file, needsImportant, snippet, why, blockers[]}` (PRD 08 §3.5) |
| `CSS_SELECTOR` | `cssSelector` | target, `selector` (≤ 500 chars) | `{selector, valid, matches?, matchesTarget?, error?, target}`: how many nodes of the target's scene the selector (list) applies to, ignoring pseudo-class state, and whether the target is one of them |
| `HIGHLIGHT` | `highlight` | target, or `{clear:true}` | `{shown:bool, bounds?:{x,y,w,h}}` — screen coordinates |
| `PICK_START` | `pickStart` | `windowIndex?`, `cancel?` | `{armed:bool}`; the next primary click on that window is consumed and emitted as `humanPick` |
| `SET_BAR_STATE` | `setBarState` | `{text?}` | `{bars:int}` — number of bar popups showing |
| `CSS_SCRATCH_SET` | `cssScratchSet` | `{css}` — style rules only, ≤ 64 KB; parsed with `CssParser` first, rejected (`BAD_REQUEST`) on parse errors, at-rules or no rules | *Scratch JSON* |
| `CSS_SCRATCH_CLEAR` | `cssScratchClear` | — | *Scratch JSON* (`active:false`) |
| `CSS_SCRATCH_PLAN` | `cssScratchPlan` | source roots, `cssMappings`, `targets?` `{"<n>": file}` | `{rules, items, keep?, files}`, see `POST /api/scratch/apply` |
| `GET_SCENE_GRAPH` | `getSceneGraph` | gains `skin:bool` — when true, children are `Parent.getChildrenUnmodifiable()` (skin internals) instead of `LogicalTree.children` | unchanged shape; skin-only nodes carry `"skin":true` and are addressed by handle `h` (their `path` may not resolve) |

## FCP events (`Fcp.Event`, agent → server)

| Constant | Name | Payload |
|---|---|---|
| `INSPECTOR_REQUESTED` | `inspectorRequested` | `{path?, h?}` — node to preselect (the last pick in that window, else its focus owner) |
| `SCRATCH_CHANGED` | `scratchChanged` | *Scratch JSON* — the dev bar's *Clear* removed the sheet; the server forwards it as SSE `scratch` |
| `HUMAN_PICK` | `humanPick` | `{target, ts, path, window:int}` (PRD 07 payload plus `path`/`window` for the inspector page) |

## Agent options

Superseded on merge with PRD 07: the dev bar is the Stage E bar, driven by the server through `setBarState`
(`FXLOOP_BAR=auto|on|pill|off`). There is no `bar=` agent argument. Pick is PRD 07 pick mode; `pickStart` arms it.

## Overlay rule

Every agent-owned popup (dev bar, highlight, pick outline) sets `window.properties["fxloop.overlay"] = true`.
`Windows.showing()` filters those out, so no tool, screenshot, path or window index ever sees them.

## Cascade JSON (`cssCascade` result, `/api/styles` body)

```json
{
  "target": {"h":12,"path":"Stage[0]/…","type":"Button","fxId":"save"},
  "styleClass": "button primary",
  "pseudoClasses": ["hover"],
  "inlineStyle": "-fx-padding: 4",
  "properties": [
    {"cssProperty":"-fx-background-color","value":"#e8e8e8","css":"#e8e8e8","origin":"AUTHOR","simulation":"ok",
     "winner": {DECL},
     "overridden": [{DECL, "reason":"lower specificity (0,1,0 < 0,3,0)"}],
     "inheritedFrom": {"path":"…","winner":{DECL}},
     "blockers": [{"kind":"inline-wins","cause":"…","next":"…"}]},
    {"cssProperty":"-fx-font-size","of":"-fx-font","value":13.0,"css":"13px","origin":"NONE","default":true,
     "note":"no stylesheet or code sets it: JavaFX uses the system default font (Font.getDefault(): 13px \"System\")", …}
  ],
  "rules": [
    {"ruleId":"s2:r14","selector":".panel .button","specificity":"0,2,0","origin":"AUTHOR",
     "file":"src/main/resources/app/app.css","absFile":"/abs/…/app.css","line":48,"sheet":"file:/…/app.css",
     "declarations":[{"property":"-fx-background-color","text":"-fx-background-color: red;","important":false,
                      "status":"winner|overridden","reason?":"…"}]}
  ],
  "sheets": [{"id":"s2","url":"file:/…","origin":"AUTHOR","on":"Scene|<node path>|Application",
              "file?":"…","sourceMap":"ok|unavailable|binary","reason?":"…","parseErrors":["…"]},
             {"id":"s3","url":"jar:file:/…/widgets-1.2.jar!/com/acme/widgets.css","origin":"AUTHOR","on":"Scene",
              "file":"widgets.css","external":true,"from":"widgets-1.2.jar","resource":"com/acme/widgets.css","mappable":true,
              "mapped?":{"file":"…","absFile":"/abs/…/widgets.css","match":"identical|differs|missing|unknown","reason?":"…"},
              "sourceMap":"ok","parseErrors":[]}],
  "editorUrl": "idea://open?file={file}&line={line}",
  "types": {"-fx-alignment": {"kind":"enum","choices":["top-left","…"],"initial":"top-left"},
            "-fx-background-color": {"kind":"paint","list":true}, "-fx-wrap-text": {"kind":"boolean","initial":"false"}},
  "scopes": [{"selector":"#form .label","matches":3}, {"selector":".label","matches":37}]
}
```

`properties` lists every styleable property of the node, sub-properties included. One that no rule, ancestor or code
sets carries `default:true` and a `note` saying where its value comes from (for the font: the system default,
`Font.getDefault()`; Modena sizes some controls in `em` but sets no font on `.root` or `.label`). `css` is the live value
as CSS text (`22px`, `"System"`, `bold`, `#ff0000`, `4 8 4 8`) when it has a short spelling; the inspector shows it and
prefills overrides with it. `scopes` are selectors a new rule for this node can use, narrowest first, with how many nodes
of the scene each applies to: the node's id, else its classes scoped by ancestor ids/classes (`#form .label`,
`.row > .label`), then its own id and classes alone. JavaFX CSS has no `:nth-child`, so the narrowest may still match
siblings (`matches` > 1).

`types` covers every styleable property of the node (sub-properties such as `-fx-font-size` included), for the value
editors. `kind` comes from the `CssMetaData` converter: `paint`, `color`, `size`, `insets`, `boolean`, `enum`, `font`,
`font-weight`, `font-style`, `duration`, `effect`, `cursor`, `url`, `string`, `shape`, `border-style`,
`background-position`, `background-size`, `background-repeat`, else `other`. `list` marks sequence/layered converters
(comma-separated values). `choices` are an enum's constants in CSS spelling (`CENTER_LEFT` → `center-left`); `initial`
is `getInitialValue(node)` as CSS text when it has a short spelling (colors `#rrggbb(aa)`, insets `t r b l`).

`DECL` = `{"ruleId","selector","specificity","origin","file?","absFile?","line?","text","important","sheet"}`.
`file`/`absFile` name the file to edit: the project source (not the build copy), else the developer's mapping of a sheet
outside the project (see *Stylesheet mappings*), else the sheet's own file (absolute) or, for a jar, its bare name.
`line` always comes from the text the app loaded.
The pseudo-candidate for a value set in code is `{"ruleId":"code","selector":"(set in code)","origin":"USER"}`;
the inline style is `{"ruleId":"inline","selector":"style=\"…\"","origin":"INLINE"}`.
`rules` is in cascade order, most precedent first (DevTools order). `specificity` is `"a,b,c"` (ids, classes +
pseudo-classes, types).

A sheet the project does not own (outside the resource roots and project dir, or in build output without a source:
a library jar, another module's build output, JavaFX's own, a `data:` sheet set from code) carries `external:true`,
`from` (the jar's file name, the runtime module, or the directory) and for jar/jrt sheets `resource` (the entry path).
`builtin:true` marks JavaFX's own (Modena, Caspian). Unmapped, it has `mappable` (false for `data:` URLs); mapped, it
has `mapped`, where `match` compares the mapped file with the text the app loaded: `identical`, `differs` (`reason`
says whether only the content changed, so lines may be off, or loaded rules are missing from the file), `missing`
(no such file), `unknown` (binary sheet).

## Stylesheet mappings

The inspector can map an external sheet to the local file it is built from (a library checked out next to the project),
so *Apply to file* may write there and `file:line` / *open in IDE* point at it. Mappings are `{url, file}` pairs kept by
the server in `<projectDir>/.fxloop/css-map.json` (that folder carries a `.gitignore` of `*`; the paths are absolute and
machine-specific). The server passes them to the agent with the source roots as `cssMappings:[{url, file}]` on
`explainStyle`, `cssCascade`, `cssSuggest` and `cssScratchPlan`. A sheet matches a mapping by URL without the reload
query, else (jar/jrt) by entry path, so a library version bump keeps it. Only `jar:`, `jrt:` and `file:` sheets can be
mapped, and only to an existing `.css` file outside the app's build output (stored as its real path).

A CSS reload of a mapped source (`fx_reload`, *Apply to file*) copies it over a build copy the app loads through a
`file:` URL (another module's `target/classes`) and reloads that; a jar cannot be updated, so the tier reports
`libraryNote` and the app keeps the old copy until the library is rebuilt and the app restarted.

## Scratch JSON (`cssScratchSet`/`cssScratchClear` result, `status.scratchCss`, `/api/scratch` body)

```json
{"active":true,"rules":2,"css":".go { -fx-text-fill: blue; }\n…",
 "items":[{"n":1,"selector":".go","declarations":[{"property":"-fx-text-fill","value":"blue","important":false}]}]}
```

The sheet is the last stylesheet of every app scene (a `data:text/css;base64,` URL on JavaFX 17+, else a temp file
next to `state.json`). In the cascade its rules carry `"file":"scratch:<n>"` and `"scratch":true` (no `line`/`absFile`);
origin stays `AUTHOR` because that is what JavaFX applies. `status.scratchCss` is present only while active.
CSS reloads keep it last and report `scratchRules`; a restart drops it.
Editing is on with `FXLOOP_INSPECTOR_EDIT`, or while the server is connected to an app whose handshake carries
`"inspectorEdit":true` (agent argument `inspectorEdit=true`, set by `fxloop:run` / `fxloopRun` unless `fxloop.inspectorEdit=false`).

## Viewer HTTP (all GET unless noted, `?t=<token>` required, loopback only)

| Path | Proxies | Notes |
|---|---|---|
| `/inspect` | — | the page (static resource `dev/fxloop/server/inspect/inspect.html`, inline CSS/JS) |
| `/api/windows` | `status` → `windows` | |
| `/api/tree?window=&skin=` | `getSceneGraph` (maxDepth 64, maxNodes 3000) | |
| `/api/styles?h=&path=` | `cssCascade` | `h` preferred, `path` fallback; adds `edit:true` with `FXLOOP_INSPECTOR_EDIT` |
| `/api/layout?h=&path=` | `explainLayout` | |
| `/api/suggest?h=&path=&property=` | `cssSuggest` | |
| `/api/selector?h=&path=&selector=` | `cssSelector` | *New rule* counts a typed selector's matches |
| `POST /api/highlight?h=&path=` / `?clear=1` | `highlight` | visual only |
| `POST /api/pick?window=` | `pickStart` | |
| `GET /api/scratch` | `status` → `scratchCss` | only with `FXLOOP_INSPECTOR_EDIT` (else 404); `{active:false,rules:0}` when none |
| `POST /api/scratch/set` | `cssScratchSet` | only with the flag; body is the whole scratch sheet as `text/css` (415 otherwise, 413 above 64 KB) |
| `POST /api/scratch/clear` | `cssScratchClear` | only with the flag |
| `POST /api/scratch/apply?dryRun=` | `cssScratchPlan`, then server write + CSS reload + `cssScratchClear` | only with the flag. Each scratch rule goes into the rule with the same selector (the last one already setting one of its properties, else the last one; existing values replaced in place, keeping `!important`; new declarations before the `}`), looked up in the project's sheets, then in mapped external sheets; else it is appended to the scene's first project sheet. An optional JSON body `{targets:{"<n>": file}}` picks the sheet for scratch rule n (1-based) among its `options` instead (merged into its rule with that selector, else appended); an unknown file is `BAD_REQUEST`. `dryRun=1` returns `{dryRun, rules, items:[{n, selector, file, kind: merge\|append, external?, options:[{file, kind, external?}], unmapped?:[{file, url, from?}]}], files:[{file, external?, live?, from?, changes:[{kind: update\|add\|newRule, selector, line, property?, from?, to?, css?}]}]}`: `options` are every project sheet and mapped external sheet, so a rule can always go into one of the app's own stylesheets as an override; `unmapped` names external sheets (not JavaFX's own) that define the selector but are not mapped yet; `live:false` marks a mapped sheet the app loads from a jar. Without `dryRun` the server checks each file (`.css`, inside a resource root and not build output, or a mapped source; unchanged sha256 since the plan: `FILE_CHANGED` otherwise), writes them, reloads them like a save and clears the scratch sheet, except for the rules that went into a `live:false` sheet: they stay in scratch CSS so the app keeps showing them (`applied:true`, `reload`, `scratch`: *Scratch JSON* afterwards, also sent as SSE `scratch`) |
| `GET /api/sheet-map/suggest?url=` | — | only with the flag. `{url, resource?, suggestions:[abs path]}`: existing files the sheet is likely built from, best first (next to an existing mapping's source root, `src/main/resources` of the module behind a `target/classes`-style build copy, of this project, its parent folder and their subprojects); at most 5 |
| `POST /api/sheet-map` | — | only with the flag; JSON `{url, file}` (absolute, project-relative or `~/…`; `null`/blank removes) → `{url, file}`; `BAD_REQUEST` for a missing or non-`.css` file, a build copy, or a URL that is not `jar:`/`jrt:`/`file:`. Pushed as SSE `sheetMap`; `423` while locked |
| `POST /api/lock/release` | — | ends the agent's input lock (`fx_lock_input`); `{locked:false}` |
| `/sse` | — | adds events `reload` (`{files:[…]}` after a CSS/FXML reload), `pick` (`{path,h,window}`), `scratch` (*Scratch JSON*) and `sheetMap` (`{url, file}` after a mapping change) next to `frame` |

Errors: HTTP 200 with `{"error":{"code","message"}}` when the agent call fails; 503 `{"error":{"code":"NO_APP"}}`
when no app is connected; 423 `{"error":{"code":"LOCKED"}}` from the `scratch/*` and `sheet-map` POSTs while the agent
holds the input lock. The SSE `status` event (the bar state) carries `lock: {remainingMs, reason?}` and `activity` ("clicking #save");
the page shows them and goes read-only while locked.

## Opening the browser

On `inspectorRequested` (dev bar button) or the `fx_open_inspector` tool, the server starts the viewer on an
ephemeral loopback port if `FXLOOP_VIEWER` is unset, then opens `http://127.0.0.1:<port>/inspect?t=<token>&h=&path=`
with `Desktop.browse`, falling back to `xdg-open` / `open` / `rundll32 url.dll,FileProtocolHandler`. The tool
returns the URL and whether the browser was opened (`FXLOOP_BROWSER=none` disables opening).

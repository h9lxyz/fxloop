# CSS inspector — internal contract (PRD 08)

Shared shapes between the agent (`fxloop-agent`), the server (`fxloop-server`) and the browser page
(`/inspect`). The PRD is the "what"; this file pins the wire format so the three parts agree.

## FCP methods (`Fcp.Method`)

| Constant | Name | Params | Result |
|---|---|---|---|
| `EXPLAIN_STYLE` | `explainStyle` | target (`handle`/`path`/`query`), `property?`, `rules` (default true), `limit`, `offset` | today's fields **plus** per item `winner`, `overriddenCount` (no `property`) or `overridden[]` (with `property`), `simulation`, `inheritedFrom?`, `blockers[]` |
| `CSS_CASCADE` | `cssCascade` | target | the full cascade for the inspector, see *Cascade JSON* |
| `CSS_SUGGEST` | `cssSuggest` | target, `property`, `value?`, `file?` | `{selector, file, needsImportant, snippet, why, blockers[]}` (PRD 08 §3.5) |
| `HIGHLIGHT` | `highlight` | target, or `{clear:true}` | `{shown:bool, bounds?:{x,y,w,h}}` — screen coordinates |
| `PICK_START` | `pickStart` | `windowIndex?`, `cancel?` | `{armed:bool}`; the next primary click on that window is consumed and emitted as `humanPick` |
| `SET_BAR_STATE` | `setBarState` | `{text?}` | `{bars:int}` — number of bar popups showing |
| `CSS_SCRATCH_SET` | `cssScratchSet` | `{css}` — style rules only, ≤ 64 KB; parsed with `CssParser` first, rejected (`BAD_REQUEST`) on parse errors, at-rules or no rules | *Scratch JSON* |
| `CSS_SCRATCH_CLEAR` | `cssScratchClear` | — | *Scratch JSON* (`active:false`) |
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
    {"cssProperty":"-fx-background-color","value":"#e8e8e8","origin":"AUTHOR","simulation":"ok",
     "winner": {DECL},
     "overridden": [{DECL, "reason":"lower specificity (0,1,0 < 0,3,0)"}],
     "inheritedFrom": {"path":"…","winner":{DECL}},
     "blockers": [{"kind":"inline-wins","cause":"…","next":"…"}]}
  ],
  "rules": [
    {"ruleId":"s2:r14","selector":".panel .button","specificity":"0,2,0","origin":"AUTHOR",
     "file":"src/main/resources/app/app.css","absFile":"/abs/…/app.css","line":48,"sheet":"file:/…/app.css",
     "declarations":[{"property":"-fx-background-color","text":"-fx-background-color: red;","important":false,
                      "status":"winner|overridden","reason?":"…"}]}
  ],
  "sheets": [{"id":"s2","url":"file:/…","origin":"AUTHOR","on":"Scene|<node path>|Application",
              "file?":"…","sourceMap":"ok|unavailable|binary","reason?":"…","parseErrors":["…"]}],
  "editorUrl": "idea://open?file={file}&line={line}",
  "types": {"-fx-alignment": {"kind":"enum","choices":["top-left","…"],"initial":"top-left"},
            "-fx-background-color": {"kind":"paint","list":true}, "-fx-wrap-text": {"kind":"boolean","initial":"false"}}
}
```

`types` covers every styleable property of the node (sub-properties such as `-fx-font-size` included), for the value
editors. `kind` comes from the `CssMetaData` converter: `paint`, `color`, `size`, `insets`, `boolean`, `enum`, `font`,
`font-weight`, `font-style`, `duration`, `effect`, `cursor`, `url`, `string`, `shape`, `border-style`,
`background-position`, `background-size`, `background-repeat`, else `other`. `list` marks sequence/layered converters
(comma-separated values). `choices` are an enum's constants in CSS spelling (`CENTER_LEFT` → `center-left`); `initial`
is `getInitialValue(node)` as CSS text when it has a short spelling (colors `#rrggbb(aa)`, insets `t r b l`).

`DECL` = `{"ruleId","selector","specificity","origin","file?","absFile?","line?","text","important","sheet"}`.
The pseudo-candidate for a value set in code is `{"ruleId":"code","selector":"(set in code)","origin":"USER"}`;
the inline style is `{"ruleId":"inline","selector":"style=\"…\"","origin":"INLINE"}`.
`rules` is in cascade order, most precedent first (DevTools order). `specificity` is `"a,b,c"` (ids, classes +
pseudo-classes, types).

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
| `POST /api/highlight?h=&path=` / `?clear=1` | `highlight` | visual only |
| `POST /api/pick?window=` | `pickStart` | |
| `GET /api/scratch` | `status` → `scratchCss` | only with `FXLOOP_INSPECTOR_EDIT` (else 404); `{active:false,rules:0}` when none |
| `POST /api/scratch/set` | `cssScratchSet` | only with the flag; body is the whole scratch sheet as `text/css` (415 otherwise, 413 above 64 KB) |
| `POST /api/scratch/clear` | `cssScratchClear` | only with the flag |
| `/sse` | — | adds events `reload` (`{files:[…]}` after a CSS/FXML reload), `pick` (`{path,h,window}`) and `scratch` (*Scratch JSON*) next to `frame` |

Errors: HTTP 200 with `{"error":{"code","message"}}` when the agent call fails; 503 `{"error":{"code":"NO_APP"}}`
when no app is connected.

## Opening the browser

On `inspectorRequested` (dev bar button) or the `fx_open_inspector` tool, the server starts the viewer on an
ephemeral loopback port if `FXLOOP_VIEWER` is unset, then opens `http://127.0.0.1:<port>/inspect?t=<token>&h=&path=`
with `Desktop.browse`, falling back to `xdg-open` / `open` / `rundll32 url.dll,FileProtocolHandler`. The tool
returns the URL and whether the browser was opened (`FXLOOP_BROWSER=none` disables opening).

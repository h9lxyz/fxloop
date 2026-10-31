# fxloop-gauntlet-app

Pure-Java JavaFX 21 robustness/perf target (no FXML). Run: `./mvnw -f sample-apps/fxloop-gauntlet-app/pom.xml javafx:run` (add `-Dfxloop.gauntlet.args=--big` or `-Djavafx.args=--big`-style program arg `--big` to pre-build 5000 nodes).
Stage "FXLoop Gauntlet", 900x700, `TabPane #tabs`.

| Tab | Ids |
|---|---|
| Dialogs | alertBtn, dialogBtn, dialogResult |
| Menus | menuBar, menuNew, menuExit, menuResult, ctxTarget, ctxCopy, ctxPaste, tooltipBtn |
| Stages | openStageBtn (second stage: secondStageBtn), popupBtn (popupLabel), fullscreenBtn |
| Scroll | scroller, deepBtn, deepResult, innerTabs (A, B), innerBtn |
| Visual | lowContrastLabel (#d0d0d0 on #fff, ~1.5:1), stateBtn (gauntlet.css), truncLabel, zeroRegion, offsceneBtn (layoutX=5000, unmanaged) |
| Web | web |
| Big | bigTable (100000 rows), bigCol, buildBigBtn, bigPane |
| RTL | rtlToggle, rtlLabel |

Tab nodes have ids `tabDialogs`, `tabMenus`, ... Tests run headless on Monocle: `./mvnw -q -f sample-apps/fxloop-gauntlet-app/pom.xml test`.

Live dev loop (PRD 07 §3): each tab builder is registered with `FxLoopDev.view(key, this::builder)` with keys `dialogs`, `menus`, `stages`, `scroll`, `visual`, `web`, `big`, `rtl`, so a hotswap of a builder body can be remounted in place (`tier: remount`). Without fxloop attached the call is a plain passthrough (`supplier.get()`).

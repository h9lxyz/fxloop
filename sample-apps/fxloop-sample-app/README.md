# fxloop-sample-app

Contacts demo app; the test target for FXLoop tooling. Ids and texts are load-bearing.

Package `dev.fxloop.sample`: `Launcher` (classpath entry) -> `ContactApp` -> `main-view.fxml` + `MainController`.
Stage title `FXLoop Sample — Contacts`, scene 640x480.

## Node ids
`nameField`, `categoryBox` (Work/Family/Friends), `addBtn` (disabled while name blank), `contactTable`
(`nameCol`, `categoryCol`), `deleteBtn`, `boomBtn` (throws `IllegalStateException("boom")`), `helpBtn`,
`hintLabel`, `statusLabel` (bound: `"N contacts"`).

## Flags (program args or system property `fxloop.sample.args`, space separated)
- `--seed`: preload Ada Lovelace/Work, Alan Turing/Work, Grace Hopper/Friends ("3 contacts").
- `--broken-css`: also load `broken.css` (misaligned `helpBtn` overlapping its sibling, white-on-white `hintLabel`).

## Run
    ../../mvnw javafx:run
    ../../mvnw javafx:run -Djavafx.args="--seed --broken-css"

Tests (headless via Monocle): `./mvnw -q -f sample-apps/fxloop-sample-app/pom.xml test` from the repo root.

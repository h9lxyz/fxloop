# fxloop-showcase-app — Ticket Desk

A deliberately flawed **Kotlin + Gradle (Kotlin DSL)** JavaFX 21 helpdesk app used to demo the FXLoop skills
(PRD 06, S0–S9). It looks like an ordinary in-house tool, and its sources carry no hints about the bugs.
The answer key is [ISSUES.md](ISSUES.md). Don't give it to an agent during a demo or eval.

The project is **not connected to FXLoop yet** (no `dev.fxloop` plugin), so `/fxloop:connect` (S0) is the first step
of every demo. The entry point is a top-level `fun main` (`dev.fxloop.showcase.MainKt`).

    ./gradlew test     # one headless smoke test (TestFX + Monocle)
    ./gradlew run      # plain run, no FXLoop

## Screens

| Screen | How to reach it | Built with |
|---|---|---|
| Main window: header, sidebar, ticket table, detail form, footer | start | `main-view.fxml` + `MainController.kt` |
| New ticket dialog | header `+ New ticket` (`#newTicketBtn`) | Kotlin code (`NewTicketDialog.kt`) |
| Settings window | header gear (`#settingsBtn`) | `settings.fxml` + `settings.css` |
| Customers | sidebar `#navCustomers` | Kotlin code, inline styles |
| Reports (pie chart, KPIs) | sidebar `#navReports` | Kotlin code |
| Knowledge Base (WebView) | sidebar `#navKnowledge` | `knowledge-base.html` (needs `FXLOOP_WEBVIEW=true` for `fx_eval_js`) |

## Flags

Program arguments, or `-Dfxloop.showcase.args="..."`:

| Flag | Effect |
|---|---|
| `--de` | German resource bundle (long strings) |
| `--rtl` | `NodeOrientation.RIGHT_TO_LEFT` on the root |
| `--empty` | no tickets or customers (empty states) |

## Demo prompts

| Skill | Ask |
|---|---|
| S0 connect | "connect FXLoop to this project and show me the app" |
| S1 ui-review | "review the main window — what looks off?" |
| S2 fix-layout | "the detail panel doesn't resize and the buttons are squashed" |
| S3 style | "the New ticket button has no hover state", "add dark mode", "make it look modern" |
| S4 accessibility | "is this app keyboard friendly? run a contrast check" |
| S5 responsive | "does this work on a 1024×640 laptop? and in German?" (`--de`, `--rtl`) |
| S6 flow-polish | "creating and assigning a ticket feels clunky", "the app freezes when I refresh" |
| S7 forms-dialogs | "clean up the New ticket dialog and the Settings window" |
| S8 consistency | "make Customers, Reports and Settings look like the main window" |
| S9 lock-in | "make sure the assign/delete fixes don't regress" |

Kotlin note: the HotSwap tier compiles Java only. For code fixes run with `FXLOOP_EXTERNAL_COMPILE=true` and
`./gradlew -t classes`. CSS and FXML edits reload live without it.

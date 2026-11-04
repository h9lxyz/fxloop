# fxloop-showcase-app — Ticket Desk

A deliberately flawed **Kotlin + Maven** JavaFX 21 helpdesk app used to demo the FXLoop skills
(PRD 06, S0–S9). It looks like an ordinary in-house tool, and its sources carry no hints about the bugs.
The answer key is [ISSUES.md](ISSUES.md). Don't give it to an agent during a demo or eval.

It is a standalone Maven project (own wrapper, not a reactor module, no `fxloop-parent`) and is **not connected to
FXLoop yet** (no `fxloop-maven-plugin`, no MCP config), so `fxloop-connect` (S0) is the first step of every demo. The
entry point is a top-level `fun main` (`dev.fxloop.showcase.MainKt`).

    ./mvnw verify          # one headless smoke test (TestFX + Monocle)
    ./mvnw javafx:run      # plain run, no FXLoop

The server jar from the README quickstart is all the FXLoop server needs to launch it: without the build plugin it
prepares the project itself (`prepare: server` in `fx_get_app_status`). `./mvnw install -DskipTests` at the repo root,
or `java -jar ~/.fxloop/fxloop-server.jar --seed-local-repo`, is only needed once you add `fxloop-maven-plugin` to the
pom for `./mvnw fxloop:run`.

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

## Running a demo

Once per machine: install the server jar and the skills as the [README quickstart](../../README.md#quickstart) says
(from a clone, the skills are `cp -r fxloop-skills/skills/fxloop-* ~/.claude/skills/` for Claude Code).

Per demo, work on a copy. Connecting edits `pom.xml` and adds an MCP config, and the copy leaves the answer key behind:

    rm -rf ~/demo/ticket-desk && mkdir -p ~/demo && cp -r sample-apps/fxloop-showcase-app ~/demo/ticket-desk && rm ~/demo/ticket-desk/ISSUES.md

Open the agent in `~/demo/ticket-desk`, ask the S0 prompt below, reload the MCP servers when it asks, say "continue",
then work down the table. Score the run against [ISSUES.md](ISSUES.md) here.

To explore first and bring the agent in later, connect once (above), then run the app yourself:

    ./mvnw fxloop:run                          # add -Dfxloop.appArgs=--de for German

Click around, **Pick** something that looks off in the dev bar, then ask the agent, e.g. "the picked button is
squashed, fix it". It connects to the running window instead of starting another one.

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

Kotlin note: `fx_reload` compiles `.kt` edits with `./mvnw compile` and then hot-swaps them (a few seconds). For a
faster loop, let the IDE auto-build and set `external-compile=true` in `fxloop.properties`. CSS and FXML edits
reload live either way.

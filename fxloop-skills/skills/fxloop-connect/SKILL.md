---
name: fxloop-connect
description: Connect FXLoop to a JavaFX project (Java or Kotlin, Maven or Gradle) so the agent can see, drive and live-reload the running app. Registers the FXLoop MCP server with the agent, adds the FXLoop build plugin and a production guard, launches the app and shows a first screenshot. Use when the developer says "connect FXLoop", "set up fxloop", "show me my app", "can you see my JavaFX app", or when any other fxloop skill finds no working fxloop setup. Idempotent; safe to re-run.
metadata:
  fxloop-version: 1.0.0-SNAPSHOT
---

# Connect FXLoop

Goal: from "never used FXLoop" to "here is a screenshot of your app" in one pass, with **no manual edits** by the
developer. Re-running changes nothing that's already in place. Leave the edits unstaged and show the diff at the end.

Version: use `fxloop-version` from this file's frontmatter for every `dev.fxloop` coordinate. Snippets:
[references/snippets.md](references/snippets.md). MCP configs per agent: [references/mcp-clients.md](references/mcp-clients.md).

## 0. MCP server

If the `fx_*` tools (e.g. `fx_get_app_status`) are already available to you, skip this step.

1. Copy `scripts/FxloopBootstrap.java` (next to this file) to `~/.fxloop/FxloopBootstrap.java`. Create the
   directory if needed and overwrite an older copy.
2. Run `java ~/.fxloop/FxloopBootstrap.java --install` in the workspace. It prints where the server jar comes from.
   Exit code 2 means no jar is available; report its message and stop.
3. Register the server for the agent you're running in, as shown in the MCP clients reference: one file, merged into
   any existing config.
4. Carry on with steps 1–4. The tools only appear after the agent reloads its MCP config. Step 5 handles that.

## 1. Detect (read only)

Collect these facts before editing anything:

- **Build tool:** `pom.xml` → Maven; `build.gradle.kts` / `build.gradle` → Gradle (note the DSL). A wrapper
  (`mvnw`, `gradlew`) in the project or a parent.
- **App module:** in a multi-module build, the module whose sources contain the `Application` subclass (grep
  `extends Application` / `: Application()`). Call it `<app>`, and its path relative to the workspace root `<rel>`.
- **Language:** `src/main/kotlin`, the `kotlin-maven-plugin`, or `kotlin("jvm")` / `org.jetbrains.kotlin.jvm`.
- **JavaFX setup:** `org.openjfx:javafx-maven-plugin` / `org.openjfx.javafxplugin`, the `javafx-*` dependencies,
  `module-info.java`.
- **Main class config:** javafx plugin `mainClass`, `application { mainClass }`, exec/jar-manifest `mainClass`.
  Ambiguous when: no config **and** (several `Application` subclasses, or a Launcher class plus an Application, or a
  Kotlin top-level `main` → `<pkg>.MainKt`). Only then pin `mainClass` in the fxloop plugin config.
- **Already connected?** The MCP entry, the fxloop build plugin, the guard, the `## FXLoop` notes section and the
  `.gitignore` entries are present → skip those steps.
- **Demo-data flags:** grep `main`/`start` for program args (`"--seed"`, `"--demo"`, `getParameters().getRaw()`,
  system properties like `-D...args`). Note them; don't invent any.
- **Annotation processors** (Lombok, MapStruct, kapt, KSP): fine, but reloads go through the build tool (slower).
  Mention it.

## 2. Add the build plugin

- **Maven:** add `dev.fxloop:fxloop-maven-plugin:<version>` under `<build><plugins>`, with no `<executions>` (the goal
  is invoked explicitly by the server, so the normal build is unaffected). Add `<configuration><mainClass>` only if
  step 1 found ambiguity.
- **Gradle:** add `id("dev.fxloop") version "<version>"` to `plugins {}`. For a `-SNAPSHOT` version also make sure
  `settings.gradle(.kts)` has `pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }`. Add an
  `fxloop { mainClass = "..." }` block only for ambiguity.
- Multi-module: edit only `<app>`'s build file.

## 3. Guard production

Add the guard from the snippets file: the Maven enforcer rule (dev.fxloop artifacts may be `provided`/`test`, never
packaged) or the Gradle `fxloopGuard` task wired before `jar`. Tell the developer in one line why: the agent jar must
never ship, and this fails the package build if it ever leaks onto the runtime classpath. FXLoop itself needs no
dependency in the app (the agent is injected with `-javaagent` at dev launch).

## 4. Project config (only when the launch needs it)

Write `fxloop.properties` at the **workspace root** (where the agent session runs) **only** for:

| Situation | Line |
|---|---|
| App module isn't the workspace root | `project-dir=<rel>` |
| No display (CI, SSH, WSL without WSLg) and the first launch failed for that reason | `headless=xvfb` (Linux) or `headless=monocle` |

Keys are the server flags without `--` (see the configuration doc). The MCP server reads this file **at startup**, so
after creating or changing it, the `fxloop` server must be restarted once (the reload column of the MCP clients
reference). Everything else goes into the report as an optional line, not into the file: demo-data
flags (`app-args=--seed`), `webview=true` for apps with a WebView, `external-compile=true`. Kotlin needs no entry:
`.kt` edits compile through the build tool by default.

## 5. Prepare and launch

0. If the `fx_*` tools still aren't available (step 0 just wrote the config, or `fxloop.properties` changed), stop
   here. Tell the developer the one reload action for their agent, and that saying "continue" afterwards finishes
   the setup. Re-running this skill then skips everything already in place.
1. `fx_get_app_status`. The first call builds (prepare goal), launches with the agent and connects. A
   `--seed`-style flag in `app-args` applies here.
2. `fx_take_screenshot label=connect-first-look` and show it. Cite the PNG path.
3. If something fails, use the table below. Fix it, then `fx_start_app` (or `fx_restart_app` if it's running).

| Error / log line | Fix |
|---|---|
| `BUILD_FAILED` + compile errors | The project doesn't compile as-is. Report it; don't "fix" unrelated code |
| `BUILD_FAILED`: plugin `dev.fxloop:...` not found | The FXLoop artifacts aren't available for this version: release versions come from Maven Central / the Plugin Portal; a SNAPSHOT needs FXLoop installed locally (`./mvnw install` in the FXLoop repo) |
| `cannot determine the main class` / candidates listed | Pin the right candidate as `mainClass` (step 2) |
| `JavaFX runtime components are missing` | The app is launched on the classpath with a main class that *extends* `Application`. Pin a Launcher (a class or Kotlin top-level `main` that calls `Application.launch`), or use `launchMode=modulepath` |
| `Module javafx.controls not found` / `--add-modules` | JavaFX jars are missing from the runtime classpath; add the `javafx-controls`/`-fxml` dependencies (with classifier for Maven if the project uses one) or the openjfx plugin |
| `STARTUP_TIMEOUT` | `start()` blocks (I/O, dialogs before show) or the main class never launches JavaFX. Read the log tail in the message |
| `APP_EXITED` + exception | Read the 50 log lines in the error. It's often a resource path or a missing `--seed` precondition |
| No display / `Unable to open DISPLAY` | `headless=xvfb` in `fxloop.properties` (step 4) |

## 6. Leave a trail

- Append a short `## FXLoop` section to the project's agent instructions file, using the template in the snippets
  file. Use `AGENTS.md` or `CLAUDE.md`, whichever exists (both: the one your agent reads); if neither exists, create
  the one your agent reads (`CLAUDE.md` for Claude Code, otherwise `AGENTS.md`). Skip it if the section exists. The
  section covers: how the app is launched, the main class, demo flags, where
  screenshots land, how Kotlin reloads work, and the guard.
- `.gitignore`: if `target/` (Maven) or `build/` (Gradle) is already ignored, leave the file alone. If not, add that
  build directory (create `.gitignore` if the repo has none). FXLoop writes screenshots and `launch.json` there.

## 7. Report

Finish with:

1. The screenshot path and one sentence about what you see.
2. What was added where (file → change), as `git diff --stat`, plus the full diff of the build file.
3. How to undo it: revert those hunks (including the MCP config entry); outside the project only `~/.fxloop/`
   (the launcher and the jar cache) was added.
4. Optional `fxloop.properties` lines that would help (demo flags, `webview=true`) with a one-line reason each.
5. Language/loop notes that apply: Kotlin (`.kt` → build-tool compile + HotSwap, `external-compile=true` with IDE
   auto-build for speed), annotation processors (slower reloads), headless.
6. Three things to try next, e.g. "review the main window", "the dialog looks cramped, fix the layout", "add a dark
   mode".

Edits stay unstaged. Don't commit unless asked.

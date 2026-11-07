---
name: fxloop-connect
description: Connect FXLoop to a JavaFX project (Java or Kotlin, Maven or Gradle) so the agent can see, drive and live-reload the running app. Registers the FXLoop MCP server with the agent, launches the app and shows a first screenshot, without changing the build files. On request it adds the FXLoop build plugin and a production guard, so the developer can start the app themselves with the dev bar. Use when the developer says "connect FXLoop", "set up fxloop", "show me my app", "can you see my JavaFX app", "connect to my running app", "I want to run the app myself", "set up fxloop:run", "add the FXLoop build plugin", or when any other fxloop skill finds no working fxloop setup. Idempotent; safe to re-run.
metadata:
  fxloop-version: 1.0.0-SNAPSHOT
---

# Connect FXLoop

Goal: from "never used FXLoop" to "here is a screenshot of your app" in one pass, with **no manual edits** by the
developer and **no change to the build files**: the server compiles the project and works out the launch itself.
Re-running changes nothing that's already in place. Leave the edits unstaged (don't commit unless asked) and show the
diff at the end.

The build plugin is only for starting the app yourself. Add it (step 6) only when the developer asks for that ("I want
to run the app myself", "set up fxloop:run"), after steps 0 to 3 if FXLoop isn't connected yet.

Version: `fxloop-version` in this file's frontmatter picks the server jar (step 0). Every `dev.fxloop` coordinate you
write into a build file uses the version that jar reports instead (first line of `--version`): it is what
`--seed-local-repo` writes, and a skill installed from the repository says `-SNAPSHOT` here even with a released jar.
Snippets: [references/snippets.md](references/snippets.md). MCP configs per agent:
[references/mcp-clients.md](references/mcp-clients.md).

## 0. MCP server

If the `fx_*` tools (e.g. `fx_get_app_status`) are already available to you, skip this step. It copies nothing into
the home directory except the server jar, `~/.fxloop/fxloop-server.jar`, plus the server's caches there.

1. **Jar.** If `~/.fxloop/fxloop-server.jar` exists, run `java -jar <jar> --version`. It must report this file's
   `fxloop-version` (for a `-SNAPSHOT` version, any version is fine). Otherwise download the matching release:
   `https://github.com/h9lxyz/fxloop/releases/download/<version>/fxloop-server-all.jar`, or
   `https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-server-all.jar` for a SNAPSHOT. Show the developer the URL and the SHA-256 of the file (`SHA256SUMS` is a release asset).

   ```sh
   curl -fsSL --create-dirs -o "$HOME/.fxloop/fxloop-server.jar" <url>
   ```

   If the download fails (proxy, firewall), stop and tell the developer to put the jar at that path by any means; the
   skill continues from there.
2. **JDK check.** Before writing anything, run `java -jar <jar> doctor --json` in the workspace and relay its lines.
   Stop with its fix when the JDK is below 17 or a JRE; other warnings are handled in steps 1 to 3.
3. **Register**, but not yet. The order is: detect (step 1) -> `fxloop.properties` (step 2) -> MCP config -> **one**
   reload (step 3), so everything the server reads at startup exists before the reload. Write the MCP entry for the
   agent you're running in, as shown in the MCP clients reference: `java -jar <absolute jar path>`. The path is per
   machine; say so in the report. Merge into any existing config.

Windows: use PowerShell, `curl.exe` for `curl`, and `mvnw.cmd` / `gradlew.bat` for wrapper commands. Write the jar as
`"$HOME/.fxloop/fxloop-server.jar"` in every command, including the report: that form works in bash, zsh and PowerShell.

## 1. Detect (read only)

Collect these facts before writing anything:

- **Build tool:** `pom.xml` → Maven; `build.gradle.kts` / `build.gradle` → Gradle (note the DSL). A wrapper
  (`mvnw`, `gradlew`) in the project or a parent.
- **App module:** in a multi-module build, the module whose sources contain the `Application` subclass (grep
  `extends Application` / `: Application()`). Call it `<app>`, and its path relative to the workspace root `<rel>`.
- **Language:** `src/main/kotlin`, the `kotlin-maven-plugin`, or `kotlin("jvm")` / `org.jetbrains.kotlin.jvm`.
- **Main class:** javafx plugin `mainClass`, `application { mainClass }`, exec/jar-manifest `mainClass`. Ambiguous
  when: no config **and** (several `Application` subclasses, or a Launcher class plus an Application, or a Kotlin
  top-level `main` → `<pkg>.MainKt`), or when the doctor lists candidates. Only then pin one (step 2).
- **Already connected?** The MCP entry, the `## FXLoop` notes section and the `.gitignore` entry are present → skip
  those steps. A build plugin or guard the project already has stays as it is.
- **Demo-data flags:** grep `main`/`start` for program args (`"--seed"`, `"--demo"`, `getParameters().getRaw()`,
  system properties like `-D...args`). Note them; don't invent any.
- **Annotation processors** (Lombok, MapStruct, kapt, KSP): fine, but reloads go through the build tool (slower).
  Mention it.

## 2. Project config (only when the launch needs it)

Write `fxloop.properties` at the **workspace root** (where the agent session runs) **only** for:

| Situation | Line |
|---|---|
| App module isn't the workspace root | `project-dir=<rel>` |
| Step 1 found several main-class candidates | `main-class=<fqcn>` (ask which one when it isn't clear) |
| No display (CI, SSH, WSL without WSLg) and the first launch failed for that reason | `headless=xvfb` (Linux) or `headless=monocle` |

Keys are the server flags without `--` (see the configuration doc). The MCP server reads this file **at startup**, so
write it before the MCP config, so the one reload in step 3 covers it. Everything else goes into the report as an
optional line, not into the file: demo-data flags (`app-args=--seed`), `webview=true` for apps with a WebView,
`external-compile=true`. Kotlin needs no entry: `.kt` edits compile through the build tool by default.

## 3. Prepare and launch

0. If the `fx_*` tools still aren't available (step 0 just wrote the config), stop here. Everything the server reads
   at startup is already written, including `fxloop.properties`. Tell the developer the one reload action for their
   agent, and that saying "continue" afterwards finishes the setup. Re-running this skill then skips everything already in place.
1. `fx_get_app_status`. The first call compiles, works out the launch (`prepare: server`; `plugin` when the build has
   the FXLoop plugin), launches with the agent and connects. If the developer already runs the app themselves
   (`mvn fxloop:run`, `gradle fxloopRun`, or an IDE launch with `FxLoopDev.install()`), it connects to that window
   instead (`mode: embedded`). Don't stop it; restart isn't available there, so ask them to re-run when a change needs
   one. A `--seed`-style flag in `app-args` applies here.
2. `fx_take_screenshot label=connect-first-look` and show it. Cite the PNG path.
3. If something fails, use the table below. Fix it, then `fx_start_app` (or `fx_restart_app` if it's running).

| Error / log line | Fix |
|---|---|
| `BUILD_FAILED` + compile errors | The project doesn't compile as-is. Report it; don't "fix" unrelated code |
| `cannot determine the main class` / candidates listed | Pin one as `main-class=` in `fxloop.properties` (step 2). The server reads it at startup, so ask for one more reload, then `fx_start_app` |
| `JavaFX runtime components are missing` | The app is launched on the classpath with a main class that *extends* `Application`. Pin a Launcher (a class or Kotlin top-level `main` that calls `Application.launch`) as `main-class=`, or set `launch-mode=modulepath`, both in `fxloop.properties` and followed by a reload |
| `Module javafx.controls not found` / `--add-modules` | JavaFX jars are missing from the runtime classpath; add the `javafx-controls`/`-fxml` dependencies (with classifier for Maven if the project uses one) or the openjfx plugin |
| `STARTUP_TIMEOUT` | `start()` blocks (I/O, dialogs before show) or the main class never launches JavaFX. Read the log tail in the message |
| `APP_EXITED` + exception | Read the 50 log lines in the error. It's often a resource path or a missing `--seed` precondition |
| No display / `Unable to open DISPLAY` | `headless=xvfb` in `fxloop.properties` (step 2) |
| `BUILD_FAILED`: plugin `dev.fxloop:...` not found | The build declares the FXLoop plugin at a version that isn't seeded: run `java -jar "$HOME/.fxloop/fxloop-server.jar" --seed-local-repo`, set the plugin version to what `--version` reports, add `mavenLocal()` to `pluginManagement` for Gradle, and retry |

## 4. Leave a trail

- Append a short `## FXLoop` section to the project's agent instructions file, using the template in the snippets
  file. Use `AGENTS.md` or `CLAUDE.md`, whichever exists (both: the one your agent reads); if neither exists, create
  the one your agent reads (`CLAUDE.md` for Claude Code, otherwise `AGENTS.md`). Skip it if the section exists. It
  covers how the app is launched, the main class, demo flags, screenshots and Kotlin reloads.
- `.gitignore`: if `target/` (Maven) or `build/` (Gradle) is already ignored, leave the file alone. If not, add that
  build directory (create `.gitignore` if the repo has none). FXLoop writes screenshots and `launch.json` there.

## 5. Report

1. The screenshot path and one sentence about what you see.
2. What was added where (file → change), as `git diff --stat`. Say that the build files are unchanged.
3. How to undo it: remove the `fxloop` MCP entry, `fxloop.properties` if you wrote it and the `## FXLoop` section, and
   delete `~/.fxloop/` (the server jar and its caches). Nothing else was added outside the project. The MCP entry
   holds a path to the jar on this machine; teammates register their own.
4. Optional `fxloop.properties` lines that would help (demo flags, `webview=true`) with a one-line reason each.
5. Language/loop notes that apply: Kotlin (`.kt` → build-tool compile + HotSwap, `external-compile=true` with IDE
   auto-build for speed), annotation processors (slower reloads), headless.
6. One line: to start the app yourself with the dev bar and bring the agent in later, ask "I want to run the app
   myself". That adds the FXLoop build plugin (step 6).
7. Three things to try next, e.g. "review the main window", "the dialog looks cramped, fix the layout", "add a dark
   mode".

## 6. Run it yourself: build plugin and guard (only on request)

`mvn fxloop:run` / `gradle fxloopRun` start the app with FXLoop and the dev bar, without an agent session; you connect
to that window later. They need the build plugin, which isn't on Maven Central or the Plugin Portal yet.

1. Run `java -jar <jar> --seed-local-repo`. It writes FXLoop's plugin, agent and server into `~/.m2/repository/dev/fxloop/`.
2. **Maven:** add `dev.fxloop:fxloop-maven-plugin:<version>` under `<build><plugins>`, with no `<executions>`, so the
   normal build is unaffected. **Gradle:** add `id("dev.fxloop") version "<version>"` to `plugins {}`, and `mavenLocal()`
   to `pluginManagement` and the dependency repositories. Multi-module: only `<app>`'s build file. If
   `fxloop.properties` pins `main-class=`, pin it in the plugin config too (snippets file): the run goals don't read it.
3. Add the guard (snippets file): the Maven enforcer rule (dev.fxloop artifacts may be `provided`/`test`, never
   packaged) or the Gradle `fxloopGuard` task wired before `jar`. Say in one line why: now that FXLoop is in the build,
   it fails packaging if a FXLoop artifact ever lands on the runtime classpath.
4. Check that the plugin resolves: `./mvnw -q compile fxloop:prepare` or `./gradlew -q classes fxloopPrepare` in
   `<app>`. Add the "Run it yourself" and "Guard" lines of the template to the `## FXLoop` section.
5. Report the build-file diff and the command (`./mvnw fxloop:run -Dfxloop.appArgs=<flags>` or `./gradlew fxloopRun`;
   then Pick a node or open the CSS inspector, and ask). Undo: revert the hunks, delete `~/.m2/repository/dev/fxloop/`.
   Teammates and CI need the seed line too until FXLoop is on Central; without it, a Gradle build fails outright.

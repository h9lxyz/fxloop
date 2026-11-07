# Install FXLoop

FXLoop gives a coding agent eyes, ears and hands on a running JavaFX app: an MCP server that builds and launches the
app, takes screenshots, clicks through it, explains layout and CSS, and live-reloads edits. This page is the complete
setup, from an empty machine to a first screenshot. It is written so that a coding agent can do it unattended in the
developer's project, and it reads just as well by hand.

**If you are the agent:** work in the developer's current project directory. Do the steps in order; each says what
to check and what to do when it fails. Stop and ask only where a step says so. Nothing here changes application
code or commits anything, and nothing is written outside the project except `~/.fxloop/` (the server jar and its
caches), your own MCP client config, your agent's skills directory (step 6) and, only if you do step 7,
`~/.m2/repository/dev/fxloop/`. Say in one line what
you are doing at each step, and finish with the report in step 9.

**Requirements:** a JDK 17 or newer on `PATH` (a JDK, not a JRE), a JavaFX app built with Maven or Gradle, and an
agent that speaks MCP (Claude Code, Cursor, VS Code with Copilot agent mode, Codex CLI, Gemini CLI, or any other
stdio MCP client). Every command below works as written in bash, zsh and PowerShell. On Windows, use PowerShell and type
`curl.exe` for `curl`, which can be an alias there.

## 1. Check the machine

```sh
java -version     # 17 or newer
javac -version    # must work: a JDK, not a JRE
```

Below 17, or no `javac`: stop and tell the developer to install a JDK 17 or newer (for example
[Temurin](https://adoptium.net)) and put it first on `PATH` (or set `JAVA_HOME`). Nothing below works without it.

Then note, read-only:

- **Build tool:** `pom.xml` means Maven; `build.gradle.kts` or `build.gradle` means Gradle. A wrapper (`mvnw`,
  `gradlew`) in the project or a parent directory is used automatically. On Windows the wrapper commands are
  `mvnw.cmd` and `gradlew.bat`; quote paths that contain spaces.
- **App module:** in a multi-module build, the module whose sources contain the `Application` subclass (grep for
  `extends Application` or `: Application()`). Its path relative to the project root is needed in step 4.
- **Display:** on Linux, `echo "$DISPLAY$WAYLAND_DISPLAY"` printing nothing means there is no display (SSH, a
  container, CI). The app then needs a headless mode in step 4. Windows and macOS desktops always have one.

## 2. Get the server jar

One jar in `~/.fxloop/`. Nothing else is downloaded, and it needs no install step beyond the copy.

If `~/.fxloop/fxloop-server.jar` already exists, run the `--version` line below first; when it prints a version and
`OK`, keep it and skip the download.

```sh
curl -fsSL --create-dirs -o "$HOME/.fxloop/fxloop-server.jar" https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-server-all.jar
java -jar "$HOME/.fxloop/fxloop-server.jar" --version
```

`releases/latest` is the newest tagged release; a specific one is `releases/download/<version>/fxloop-server-all.jar`.
`--version` prints the version, the commit it was built from, and
whether the JDK that runs it is usable. The jar's other commands are `doctor` (step 3) and `--seed-local-repo`
(step 7); `--help` lists them; anything else starts the MCP server on stdin and stdout.

**If the download fails** (a proxy, a firewall, antivirus quarantine), either of these works:

- [Build the jar from source](https://github.com/h9lxyz/fxloop/blob/main/CONTRIBUTING.md#build-the-server-jar) (needs
  `git` and three to five minutes) and copy it to `~/.fxloop/fxloop-server.jar`.

- Or the developer gets `fxloop-server-all.jar` any other way and saves it as `~/.fxloop/fxloop-server.jar`
  (`C:\Users\<you>\.fxloop\fxloop-server.jar` on Windows). Nothing is executed from the download location.

Behind a proxy, `curl` honours `https_proxy=http://proxy:port`. With no access to GitHub at all, get the repository as a source zip or from an internal mirror (this file is
at its root), build with the Maven proxy or mirror in `~/.m2/settings.xml`, or have a colleague send
`fxloop-server-all.jar` and `fxloop-skills.zip`; nothing else is downloaded.

## 3. Run the doctor

In the project directory:

```sh
java -jar "$HOME/.fxloop/fxloop-server.jar" doctor
```

It never writes anything. It checks, in order, the JDK, the build tool and wrapper, the JavaFX dependencies and
launch mode, the main class, the FXLoop build plugin, the display, and the MCP client configs in the workspace, with a
one-line fix for every ❌ and ⚠️. Relay the lines to the developer and act on them:

| Line | What to do |
|---|---|
| ❌ JDK | Fix it first (step 1); nothing else works |
| ⚠️ Main class: *not compiled yet* | Fine; the first launch compiles the project |
| ❌ Main class: *ambiguous*, candidates listed | Pin one in step 4 (`main-class=`) |
| ✅ FXLoop build plugin: *no FXLoop build plugin* | Fine; the server prepares the project itself. Step 7 adds the plugin only for `fxloop:run` |
| ⚠️ Display | Add the headless line it suggests in step 4 |
| ⚠️ Agent (MCP client) | Step 5. The doctor prints the config snippet for the client it finds (`--client=cursor`, `--client=all` for others). A `claude mcp add` registration counts; `claude mcp get fxloop` confirms it too |

`--json` prints the same checks as JSON. In a terminal whose locale is not UTF-8 the markers print as `OK`,
`WARN` and `FAIL` instead of the symbols.

## 4. Project settings, only when the launch needs them

Write `fxloop.properties` at the project root (where the agent session runs) **only** for these cases. Keys are
the server's flags without `--`; the full list is in
[docs/configuration.md](https://github.com/h9lxyz/fxloop/blob/main/docs/configuration.md).

| Situation | Line |
|---|---|
| The app module is not the project root (multi-module build) | `project-dir=<relative path to the module>` |
| No display (Linux without `DISPLAY`, SSH, a container) | `headless=xvfb` (needs `xvfb-run` on `PATH`), else `headless=monocle` (needs the `org.testfx:openjfx-monocle` test dependency) |
| The doctor listed several main-class candidates | `main-class=<fully.qualified.Name>` (the doctor prints the exact line) |
| The app needs demo data or flags to show anything | `app-args=--seed` (whatever its `main` reads; don't invent flags) |
| The app has a WebView and you want `fx_eval_js` | `webview=true` |

Write it now, before the MCP config: the server reads this file at startup, so writing it first keeps step 8 to one
reload. Run the doctor again afterwards: its Display line should now say `headless=xvfb configured` (or the main
class it was pinned to), which proves the file is read from the right directory.

## 5. Register the server with your agent

Pick the client you are running in. The command is always `java -jar <absolute path to ~/.fxloop/fxloop-server.jar>`;
the path is per machine, so a committed config only works for colleagues who installed the jar at the same place
(the Claude Code, Cursor and VS Code forms below use a home variable for that). Fastest: paste what
`java -jar "$HOME/.fxloop/fxloop-server.jar" doctor --client=<claude-code|cursor|vscode|codex|gemini>` prints, which has
your real paths filled in. The server runs in the client's working directory; add `FXLOOP_PROJECT_DIR` only when the
client does not start it in the project. Merge into an existing file and never drop other servers; if an `fxloop`
entry already exists with the same command, leave it alone.

| Client | File (project root unless noted) | Reload after writing |
|---|---|---|
| Claude Code | `.mcp.json`, or the `claude mcp add` line | Restart the session (or `/mcp` → fxloop → Reconnect). Approve the project server when asked |
| Cursor | `.cursor/mcp.json` | Settings → MCP → enable/refresh `fxloop`, or reload the window |
| VS Code (Copilot agent mode) | `.vscode/mcp.json` | Start the server from the `mcp.json` code lens, or reload the window |
| Codex CLI | `.codex/config.toml` (trusted projects), else `~/.codex/config.toml` | Restart `codex` |
| Gemini CLI | `.gemini/settings.json` | Restart `gemini` |
| Anything else | The client's stdio MCP server config | Its own reload |

**Claude Code.** One command, run inside the project. `-s project` writes `.mcp.json` next to the build file, which
is what an agent should use (Claude Code treats `.mcp.json` as sensitive and may refuse to write it directly; the
command writes the same file). Without `-s` the entry is private to this directory in `~/.claude.json`; `-s user`
registers it for every project.

```sh
claude mcp add -s project fxloop -- java -jar '${HOME}/.fxloop/fxloop-server.jar'
```

On Windows, write `${USERPROFILE}` instead of `${HOME}`. The resulting `.mcp.json` (Claude Code expands the variable; a
copy is `.mcp.json.example` in the repository):

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["-jar", "${HOME}/.fxloop/fxloop-server.jar"]
    }
  }
}
```

**Cursor**, `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["-jar", "${userHome}/.fxloop/fxloop-server.jar"],
      "env": { "FXLOOP_PROJECT_DIR": "${workspaceFolder}" }
    }
  }
}
```

**VS Code**, `.vscode/mcp.json`:

```json
{
  "servers": {
    "fxloop": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "${userHome}/.fxloop/fxloop-server.jar"],
      "env": { "FXLOOP_PROJECT_DIR": "${workspaceFolder}" }
    }
  }
}
```

**Codex CLI**, `.codex/config.toml` (no variables: absolute paths, where `/home/me` stands for your home directory,
`/Users/<you>` on macOS; the project file only applies when the project is trusted, otherwise write
`~/.codex/config.toml` and say that the entry is pinned to this project):

```toml
[mcp_servers.fxloop]
command = "java"
args = ["-jar", "/home/me/.fxloop/fxloop-server.jar"]
env = { FXLOOP_PROJECT_DIR = "/home/me/work/my-app" }
```

**Gemini CLI**, `.gemini/settings.json` (absolute paths, same placeholders as for Codex):

```json
{
  "mcpServers": {
    "fxloop": {
      "command": "java",
      "args": ["-jar", "/home/me/.fxloop/fxloop-server.jar"],
      "env": { "FXLOOP_PROJECT_DIR": "/home/me/work/my-app" }
    }
  }
}
```

**Any other client:** a stdio server, command `java`, arguments `-jar <absolute path to the jar>`, optional
environment `FXLOOP_PROJECT_DIR=<app project>` when the client does not start the server in it.

The Claude Code setup is tested in the FXLoop repository; the other formats follow each client's documentation. If a
client ignores the file, check its current docs for the config location rather than guessing.

## 6. Install the skills (recommended)

Ten `SKILL.md` workflows that turn the tools into UI and UX work on the app: connect, review a screen, fix layout,
style and theming, accessibility, responsive and localized layouts, flow polish, forms and dialogs, consistency, and
locking fixes in as TestFX tests. They trigger from plain requests ("the buttons are squashed", "add dark mode").
They are plain folders in the open skills format; the MCP tools work without them.

```sh
npx skills add h9lxyz/fxloop -g -y
```

This needs Node.js 22.20 or newer and `git`. It installs all ten for every project, into each agent it finds on the
machine; drop `-g` for this project only. Without Node.js, unzip
https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-skills.zip into your agent's skills directory
(`~/.claude/skills/` for Claude Code), or copy the folders `fxloop-skills/skills/fxloop-*` from a clone of the
repository. If your agent has no skills directory, skip this step.

## 7. Add the build plugin and the guard (optional)

Not needed for the agent to see and drive the app: without the plugin the server prepares the project itself and
`fx_get_app_status` reports `prepare: server`. The plugin adds `mvn fxloop:run` / `gradle fxloopRun`, which runs the
app yourself with the FXLoop dev bar (pick a node, open the CSS inspector) and lets the agent connect to that window
later. The guard makes sure FXLoop can never end up in a packaged build; FXLoop itself needs no dependency in the app
(the agent is injected with `-javaagent` at dev launch).

**Agent:** do this step only when the developer asked to run the app themselves. Otherwise skip it and mention it in
the report (step 9).

Until FXLoop is on Maven Central and the Gradle Plugin Portal, seed the local Maven repository once from the jar you
installed. It writes FXLoop's build plugins, agent and server, plus the three small artifacts they depend on, into
`~/.m2/repository/dev/fxloop/` and nothing else; the run goals below resolve them from there (Gradle through
`mavenLocal()`). Re-run it after updating the jar:

```sh
java -jar "$HOME/.fxloop/fxloop-server.jar" --seed-local-repo
```

**In every snippet below, replace `1.0.0-SNAPSHOT` with the version `--version` printed in step 2.** That is the version
the seed wrote, and any other one fails to resolve.

**Maven**, under `<build><plugins>` of the app module's `pom.xml`. No `<executions>`: the server invokes the goal
explicitly, so the normal build is unaffected. Add `<configuration><mainClass>` only when the doctor found the main
class ambiguous.

```xml
<plugin>
  <groupId>dev.fxloop</groupId>
  <artifactId>fxloop-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <!-- only when the main class is ambiguous:
  <configuration><mainClass>com.acme.MainKt</mainClass></configuration> -->
</plugin>
```

The guard (if the project already declares `maven-enforcer-plugin`, add this `<execution>` to it and keep their
version):

```xml
<plugin>
  <!-- FXLoop guard: dev.fxloop artifacts may be provided/test only, never packaged -->
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.6.2</version>
  <executions>
    <execution>
      <id>fxloop-never-ships</id>
      <phase>package</phase>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <searchTransitive>true</searchTransitive>
            <excludes>
              <exclude>dev.fxloop:*:*:*:compile</exclude>
              <exclude>dev.fxloop:*:*:*:runtime</exclude>
            </excludes>
            <message>FXLoop must not ship: use provided or test scope for dev.fxloop artifacts.</message>
          </bannedDependencies>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**Gradle (Kotlin DSL)**, in the app module's `build.gradle.kts`:

```kotlin
plugins {
    // ...existing plugins
    id("dev.fxloop") version "1.0.0-SNAPSHOT"
}

// only when the main class is ambiguous:
// fxloop { mainClass = "com.acme.MainKt" }
```

The plugin, the agent and the server come from the local repository, so `settings.gradle.kts` needs `mavenLocal()` in
both repository blocks (merge with existing blocks; a project that declares `repositories {}`
in the build file instead adds `mavenLocal()` there):

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal() // fxloopRun resolves dev.fxloop:fxloop-agent and fxloop-server:all from the project repositories
        mavenCentral()
    }
}
```

The guard, also in `build.gradle.kts`:

```kotlin
// FXLoop guard: fail packaging if any dev.fxloop artifact reaches the runtime classpath (it must never ship)
val fxloopGuard = tasks.register("fxloopGuard") {
    group = "verification"
    description = "Fails if a dev.fxloop artifact is on the runtime classpath"
    val leaked = configurations.runtimeClasspath.map { c ->
        c.incoming.resolutionResult.allComponents.map { it.id.displayName }.filter { it.startsWith("dev.fxloop:") }
    }
    doLast { check(leaked.get().isEmpty()) { "FXLoop must not ship; use compileOnly/testImplementation: ${leaked.get()}" } }
}
tasks.named("jar") { dependsOn(fxloopGuard) }
```

**Gradle (Groovy DSL)**:

```groovy
plugins {
    id 'dev.fxloop' version '1.0.0-SNAPSHOT'
}

def fxloopGuard = tasks.register('fxloopGuard') {
    group = 'verification'
    description = 'Fails if a dev.fxloop artifact is on the runtime classpath'
    def leaked = configurations.runtimeClasspath.map { c ->
        c.incoming.resolutionResult.allComponents.collect { it.id.displayName }.findAll { it.startsWith('dev.fxloop:') }
    }
    doLast { if (!leaked.get().isEmpty()) throw new GradleException("FXLoop must not ship: ${leaked.get()}") }
}
tasks.named('jar') { dependsOn fxloopGuard }
```

Finally, make sure `target/` (Maven) or `build/` (Gradle) is in `.gitignore`: FXLoop writes screenshots and
`launch.json` there.

`./mvnw fxloop:run` and `./gradlew fxloopRun` need a display: the `headless=` line of step 4 only applies to launches
by the server. On a machine without one, wrap them (`xvfb-run -a ./mvnw fxloop:run`); the window is invisible, but
the agent connects to it all the same.

## 8. Reload, then take the first screenshot

The `fx_*` tools appear only after the client reloads its MCP servers (the last column of the table in step 5); no
agent loads a new server mid-session. **Agent:** if you do not have the `fx_*` tools yet, stop here. Tell the
developer the one reload action for their client and that saying "continue" afterwards finishes the setup; this
guide is safe to re-run, every step above skips what is already in place.

With the tools available:

1. Call `fx_get_app_status`. The first call compiles the project, launches the app with the FXLoop agent and
   connects; expect thirty seconds to three minutes the first time (the slow end is a cold Maven or Gradle cache
   downloading the app's dependencies; later calls answer in well under a second). The result says `state: running`,
   which window is open, and `prepare: plugin` or `prepare: server` (how the launch was worked out). If the developer
   already runs the app with `mvn fxloop:run` or `gradle fxloopRun`, the server connects to that window instead
   (`mode: embedded`, no `prepare` field since nothing was built). If the first call fails, fix the cause from the table below and call `fx_start_app`: after a
   failed launch `fx_get_app_status` only reports `state: exited` and does not build again.
2. Call `fx_take_screenshot` with `label: first-look` and show the developer the PNG path it returns (screenshots
   land in `target/fxloop/screens/` or `build/fxloop/screens/`).
3. From here on, describe what you want: `fx_take_screenshot`, `fx_find_nodes`, `fx_click_node`, `fx_explain_layout`,
   `fx_explain_style`, `fx_lint_visual`, `fx_reload` after an edit. The server's tool list is the reference.

| Error or log line | Fix |
|---|---|
| `BUILD_FAILED` with compile errors | The project does not compile as-is. Report it; don't "fix" unrelated code |
| `BUILD_FAILED` with `Could not GET`, `Could not resolve`, `429 Too Many Requests` or `Connection reset` on a repository URL | A cold dependency cache and a slow, rate-limited or proxied repository, not FXLoop or the code. Wait twenty seconds and call `fx_start_app` again, or run the project's own build (`./mvnw compile`, `./gradlew classes`) until it passes |
| `BUILD_FAILED`: plugin `dev.fxloop:...` not found | The build plugin is in the build file but not in a repository: run the `--seed-local-repo` line from step 7, set the plugin version to what `--version` prints, add `mavenLocal()` to both repository blocks for Gradle (step 7), retry |
| `fxloopRun`: `Could not find dev.fxloop:fxloop-agent` or `fxloop-server` | `mavenLocal()` is missing from the dependency repositories (`fxloopPrepare` still works with it in `pluginManagement` only): add it to `dependencyResolutionManagement` in `settings.gradle.kts` or to `repositories {}` in the build file, or re-run the seed line if the jar was updated |
| `CONFIG`: cannot determine the main class, candidates listed | Pin one with `main-class=` in `fxloop.properties` (step 4), then reload |
| `JavaFX runtime components are missing` | The app is launched on the classpath with a main class that *extends* `Application`. Pin a Launcher (a class or a Kotlin top-level `main` that calls `Application.launch`), or force the module path: `launch-mode=modulepath` in `fxloop.properties` (Maven projects), `<configuration><launchMode>modulepath</launchMode></configuration>` on `fxloop-maven-plugin`, or `fxloop { launchMode = "modulepath" }` in Gradle |
| `Module javafx.controls not found` / `--add-modules` | JavaFX jars are missing from the runtime classpath; add the `javafx-controls`/`-fxml` dependencies or the OpenJFX plugin |
| `STARTUP_TIMEOUT` | `start()` blocks (I/O, dialogs before show) or the main class never launches JavaFX. Read the log tail in the message |
| `APP_EXITED` with an exception | Read the log lines in the error: usually a resource path or a missing demo flag (`app-args=`) |
| No display / `Unable to open DISPLAY` | `headless=xvfb` or `headless=monocle` in `fxloop.properties` (step 4), then reload. For `fxloop:run` / `fxloopRun` wrap the command in `xvfb-run -a` instead |
| Anything else | `java -jar "$HOME/.fxloop/fxloop-server.jar" doctor` in the project, and [docs/troubleshooting.md](https://github.com/h9lxyz/fxloop/blob/main/docs/troubleshooting.md) |

## 9. Report

End with:

1. The screenshot path and one sentence about what is on it.
2. What was added where (file → change), plus the full diff of the build file if step 7 was done. Leave edits
   unstaged; don't commit.
3. How to undo it: remove the `fxloop` MCP entry, revert the build-file hunks, delete `fxloop.properties` if it was
   written, delete `~/.fxloop/`, and run `npx skills remove h9lxyz/fxloop -g` for the skills. Nothing else was added outside the
   project.
4. How to run the app yourself with the dev bar and bring the agent in later (`./mvnw fxloop:run` or
   `./gradlew fxloopRun`; under `xvfb-run -a` without a display), which needs step 7 first if it was skipped, and
   three things to try next, for example
   "review the main window", "the dialog looks cramped, fix the layout", "add a dark mode".

## Uninstall

Remove the MCP entry (`claude mcp remove fxloop` for Claude Code, run in the directory where it was added, or the
entry in the client's file), the plugin and guard from the build file if they were added, `fxloop.properties`, and
`~/.fxloop/`. Remove the skills with `npx skills remove h9lxyz/fxloop -g` (or delete the `fxloop-*` folders from your
agent's skills directory). The seeded artifacts, if any, are `~/.m2/repository/dev/fxloop/`. Nothing else is installed.

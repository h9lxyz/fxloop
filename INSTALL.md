# Install FXLoop

FXLoop is an MCP server that lets a coding agent build, launch, screenshot, click through and live-reload a JavaFX app.
This page takes you from an empty machine to a first screenshot. A coding agent can follow it unattended in the
developer's project; it reads just as well by hand.

**If you are the agent:** work in the developer's project directory and do steps 1 to 6 in order. Stop and ask only
where a step says so. Nothing here changes application code or commits anything. Outside the project, only these are
written: `~/.fxloop/` (the server jar and its caches), your MCP client config, your skills directory (if you install
the skills) and `~/.m2/repository/org/lab59/fxloop/` (only with the build plugin). Say in one line what you do at each step.

**Requirements:** a JDK 21 or newer (on `PATH`, or installed by a version manager such as mise, SDKMAN or asdf), a
JavaFX app built with Maven or Gradle, and an agent that speaks MCP (Claude Code, Cursor, VS Code with Copilot agent
mode, Codex CLI, Gemini CLI, or any other stdio MCP client). The commands work as written in bash, zsh and PowerShell;
on Windows type `curl.exe` for `curl`.

## 1. Find a JDK 21+

```sh
java -version
```

If it prints 21 or newer, use `java` below. Otherwise, or if `java` is not found, look for a JDK 21+ and use the
absolute path of its `java` **wherever this guide says `java`**, including the MCP config in step 4. Places to look:
`$JAVA_HOME/bin/java`, `mise which java`, `~/.sdkman/candidates/java/current/bin/java`, `asdf which java`,
`~/.jdks/*/bin/java` (IntelliJ), `/usr/lib/jvm/*/bin/java`, `$(/usr/libexec/java_home -v 21+)/bin/java` (macOS),
`C:\Program Files\*\jdk-2*\bin\java.exe` (Windows; in PowerShell run a quoted path as `& "C:\...\java.exe"`). Check a
candidate with `<path> -version`. Don't change `PATH` and don't install anything.

None found: stop and ask the developer to install a JDK 21 or newer, for example [Temurin](https://adoptium.net).

## 2. Get the server jar

One jar in `~/.fxloop/`; nothing else is downloaded. If `~/.fxloop/fxloop-server.jar` already exists and its
`--version` line below prints `OK`, keep it and skip the download.

```sh
curl -fsSL --create-dirs -o "$HOME/.fxloop/fxloop-server.jar" https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-server-all.jar
java -jar "$HOME/.fxloop/fxloop-server.jar" --version
```

`--version` prints the version, the commit, and whether the JDK running it is usable. `OK` means this `java` works.
`FAIL` (for example a JRE without `javac`) or `UnsupportedClassVersionError` means go back to step 1 and pick another.

If the download fails (proxy, firewall, antivirus), `curl` honours `https_proxy=http://proxy:port`. Otherwise the
developer saves `fxloop-server-all.jar` from any source as `~/.fxloop/fxloop-server.jar`, or
[builds it from source](https://github.com/h9lxyz/fxloop/blob/main/CONTRIBUTING.md#build-the-server-jar).

## 3. Run the doctor

In the project directory:

```sh
java -jar "$HOME/.fxloop/fxloop-server.jar" doctor
```

It writes nothing. It checks the JDK, build tool and wrapper, JavaFX, main class, FXLoop build plugin, display and
MCP client configs, with a one-line fix for every ❌ and ⚠️ (`FAIL` and `WARN` in a terminal that is not UTF-8). Relay
the lines to the developer and act on them:

| Line | What to do |
|---|---|
| ❌ JDK | Back to step 1; nothing else works |
| ⚠️ Main class: *not compiled yet* | Fine; the first launch compiles the project |
| A fix that names `fxloop.properties` (main class ambiguous, app in a submodule, no display) | Write that line into `fxloop.properties` ([Project settings](#project-settings-only-if-the-doctor-asks)) before step 4, then run the doctor again |
| ✅ FXLoop build plugin: *no FXLoop build plugin* | Fine; the server prepares the project itself |
| ⚠️ Agent (MCP client) | Step 4 |

## 4. Register the server with your agent

Run the doctor for the client you are running in and paste what it prints into the file it names. It has the real
paths and the right `java` filled in:

```sh
java -jar "$HOME/.fxloop/fxloop-server.jar" doctor --client=<claude-code|cursor|vscode|codex|gemini>
```

Merge into an existing file and never drop other servers; an `fxloop` entry that is already there stays. The server
runs in the client's working directory; `FXLOOP_PROJECT_DIR` is only needed where the client starts it elsewhere.

| Client | File (project root unless noted) | Reload after writing |
|---|---|---|
| Claude Code | `.mcp.json` | Restart the session (or `/mcp` → fxloop → Reconnect). Approve the project server when asked |
| Cursor | `.cursor/mcp.json` | Settings → MCP → enable/refresh `fxloop`, or reload the window |
| VS Code (Copilot agent mode) | `.vscode/mcp.json` | Start the server from the `mcp.json` code lens, or reload the window |
| Codex CLI | `.codex/config.toml` (trusted projects), else `~/.codex/config.toml` | Restart `codex` |
| Gemini CLI | `.gemini/settings.json` | Restart `gemini` |
| Anything else | Its stdio MCP server config | Its own reload |

Whatever the client, the entry is a stdio server whose command is the `java` from step 1 (`java` or its absolute
path) with the arguments `-jar <jar path>`; the examples below assume `java` is on `PATH`. For Claude Code, this
command writes `.mcp.json` (Claude Code may refuse to write that file directly); on Windows write `${USERPROFILE}` for
`${HOME}`:

```sh
claude mcp add -s project fxloop -- java -jar '${HOME}/.fxloop/fxloop-server.jar'
```

<details>
<summary>The entry for each client</summary>

Claude Code, `.mcp.json`:

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

Cursor, `.cursor/mcp.json`:

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

VS Code, `.vscode/mcp.json` (top-level key `servers`, plus `type`):

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

Codex CLI and Gemini CLI expand no variables, so they take absolute paths (`/home/me` stands for the home directory).
Codex, `.codex/config.toml`:

```toml
[mcp_servers.fxloop]
command = "java"
args = ["-jar", "/home/me/.fxloop/fxloop-server.jar"]
env = { FXLOOP_PROJECT_DIR = "/home/me/work/my-app" }
```

Gemini, `.gemini/settings.json`:

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

</details>

## 5. Reload, then take the first screenshot

The `fx_*` tools appear only after the client reloads its MCP servers (last column above); no agent picks up a new
server mid-session. **Agent:** if you do not have the `fx_*` tools yet, stop here. Tell the developer the one reload
action for their client and that saying "continue" afterwards finishes the setup. This guide is safe to re-run: every
step skips what is already in place.

With the tools available:

1. Call `fx_get_app_status`. The first call compiles the project, launches the app with the FXLoop agent and
   connects: thirty seconds to three minutes the first time (a cold Maven or Gradle cache), well under a second later.
   It reports `state: running` and the open window. If the developer already runs the app with `fxloop:run` or
   `fxloopRun`, it connects to that window instead (`mode: embedded`). If the call fails, fix the cause
   ([If something fails](#if-something-fails)) and call `fx_start_app`: after a failed launch `fx_get_app_status` only
   reports `state: exited` and does not build again.
2. Call `fx_take_screenshot` with `label: first-look` and show the developer the PNG path it returns.
3. From here on, describe what you want; the server's tool list is the reference.

## 6. Report

1. The screenshot path and one sentence about what is on it.
2. What was added where (file → change), with the full build-file diff if you added the build plugin. Leave edits
   unstaged; don't commit. If the MCP entry uses an absolute `java` path, say that it is specific to this machine.
3. How to undo it: see [Uninstall](#uninstall).
4. That the developer can run the app themselves with the dev bar and bring the agent in later
   ([Add the build plugin](#add-the-build-plugin-optional)), and three things to try next, for example "review the
   main window", "the dialog looks cramped, fix the layout", "add a dark mode".

## Project settings (only if the doctor asks)

`fxloop.properties` at the project root (where the agent session runs). Write it before registering the server: the
server reads it at startup, so one reload covers both. Keys are the server's flags without `--`, see
[docs/configuration.md](https://github.com/h9lxyz/fxloop/blob/main/docs/configuration.md).

| Situation | Line |
|---|---|
| The app module is not the project root (multi-module build) | `project-dir=<relative path to the module>` |
| No display (Linux without `DISPLAY`, SSH, a container) | `headless=xvfb` (needs `xvfb-run`), else `headless=monocle` (needs the `org.testfx:openjfx-monocle` test dependency) |
| Several main-class candidates | `main-class=<fully.qualified.Name>` (the doctor prints the line) |
| The app needs demo data or flags to show anything | `app-args=--seed` (whatever its `main` reads; don't invent flags) |
| The app has a WebView and you want `fx_eval_js` | `webview=true` |

## Install the skills

Ten workflows that turn the tools into UI work from plain requests ("the buttons are squashed", "add dark mode"):
review, layout, styling, accessibility, responsive layouts, flows, forms, consistency, TestFX tests, and connecting.
The MCP tools work without them.

```sh
npx skills add h9lxyz/fxloop -g -y
```

This needs Node.js 22.20+ and `git`; drop `-g` to install for this project only. Without Node.js, unzip
https://github.com/h9lxyz/fxloop/releases/latest/download/fxloop-skills.zip into your agent's skills directory
(`~/.claude/skills/` for Claude Code). No skills directory: skip this.

## Add the build plugin (optional)

The agent does not need it. It adds `./mvnw fxloop:run` / `./gradlew fxloopRun`, which start the app with the FXLoop
dev bar (pick a node, open the CSS inspector) so the agent can connect to that window later. **Agent:** do this only
when the developer asked to run the app themselves.

Seed the local Maven repository once from the jar (until FXLoop is on Maven Central and the Gradle Plugin Portal), and
again after updating the jar. It writes only `~/.m2/repository/org/lab59/fxloop/`:

```sh
java -jar "$HOME/.fxloop/fxloop-server.jar" --seed-local-repo
```

**In every snippet below, replace `1.0.0-SNAPSHOT` with the version `--version` printed in step 2**; any other version
fails to resolve.

**Maven**, under `<build><plugins>` of the app module's `pom.xml` (no `<executions>`, so the normal build is
unchanged):

```xml
<plugin>
  <groupId>org.lab59.fxloop</groupId>
  <artifactId>fxloop-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <!-- only when the main class is ambiguous:
  <configuration><mainClass>com.acme.MainKt</mainClass></configuration> -->
</plugin>
```

And the guard, which fails packaging if FXLoop ever reaches the runtime classpath (if the project already declares
`maven-enforcer-plugin`, add this `<execution>` to it and keep its version):

```xml
<plugin>
  <!-- FXLoop guard: org.lab59.fxloop artifacts may be provided/test only, never packaged -->
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
              <exclude>org.lab59.fxloop:*:*:*:compile</exclude>
              <exclude>org.lab59.fxloop:*:*:*:runtime</exclude>
            </excludes>
            <message>FXLoop must not ship: use provided or test scope for org.lab59.fxloop artifacts.</message>
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
    id("org.lab59.fxloop") version "1.0.0-SNAPSHOT"
}

// only when the main class is ambiguous:
// fxloop { mainClass = "com.acme.MainKt" }
```

`settings.gradle.kts` needs `mavenLocal()` in both repository blocks (merge with existing blocks; a project that
declares `repositories {}` in the build file adds it there):

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal() // fxloopRun resolves org.lab59.fxloop:fxloop-agent and fxloop-server:all from the project repositories
        mavenCentral()
    }
}
```

The guard, also in `build.gradle.kts`:

```kotlin
// FXLoop guard: fail packaging if any org.lab59.fxloop artifact reaches the runtime classpath (it must never ship)
val fxloopGuard = tasks.register("fxloopGuard") {
    group = "verification"
    description = "Fails if a org.lab59.fxloop artifact is on the runtime classpath"
    val leaked = configurations.runtimeClasspath.map { c ->
        c.incoming.resolutionResult.allComponents.map { it.id.displayName }.filter { it.startsWith("org.lab59.fxloop:") }
    }
    doLast { check(leaked.get().isEmpty()) { "FXLoop must not ship; use compileOnly/testImplementation: ${leaked.get()}" } }
}
tasks.named("jar") { dependsOn(fxloopGuard) }
```

<details>
<summary>Gradle (Groovy DSL): the plugin and the guard</summary>

```groovy
plugins {
    id 'org.lab59.fxloop' version '1.0.0-SNAPSHOT'
}

def fxloopGuard = tasks.register('fxloopGuard') {
    group = 'verification'
    description = 'Fails if a org.lab59.fxloop artifact is on the runtime classpath'
    def leaked = configurations.runtimeClasspath.map { c ->
        c.incoming.resolutionResult.allComponents.collect { it.id.displayName }.findAll { it.startsWith('org.lab59.fxloop:') }
    }
    doLast { if (!leaked.get().isEmpty()) throw new GradleException("FXLoop must not ship: ${leaked.get()}") }
}
tasks.named('jar') { dependsOn fxloopGuard }
```

</details>

Make sure `target/` (Maven) or `build/` (Gradle) is in `.gitignore`: FXLoop writes screenshots and `launch.json`
there. `fxloop:run` and `fxloopRun` need a display (`headless=` only applies to launches by the server); without one,
wrap them: `xvfb-run -a ./mvnw fxloop:run`.

## If something fails

| Error or log line | Fix |
|---|---|
| `BUILD_FAILED` with compile errors | The project does not compile as-is. Report it; don't "fix" unrelated code |
| `BUILD_FAILED` with `Could not GET`, `Could not resolve`, `429 Too Many Requests` or `Connection reset` on a repository URL | A cold dependency cache and a slow or rate-limited repository, not FXLoop. Wait twenty seconds and call `fx_start_app` again, or run the project's own build (`./mvnw compile`, `./gradlew classes`) until it passes |
| `BUILD_FAILED`: plugin `org.lab59.fxloop:...` not found | Run the `--seed-local-repo` line, set the plugin version to what `--version` prints, add `mavenLocal()` to both repository blocks for Gradle ([build plugin](#add-the-build-plugin-optional)), retry |
| `fxloopRun`: `Could not find org.lab59.fxloop:fxloop-agent` or `fxloop-server` | `mavenLocal()` is missing from the dependency repositories: add it to `dependencyResolutionManagement` in `settings.gradle.kts` (or `repositories {}` in the build file), or re-run the seed line after a jar update |
| `CONFIG`: cannot determine the main class, candidates listed | `main-class=` in `fxloop.properties`, then reload |
| `JavaFX runtime components are missing` | The main class *extends* `Application` and runs on the classpath. Pin a Launcher (a class or Kotlin top-level `main` that calls `Application.launch`) with `main-class=`, or force the module path: `launch-mode=modulepath` in `fxloop.properties` (Maven), `<configuration><launchMode>modulepath</launchMode></configuration>` on `fxloop-maven-plugin`, or `fxloop { launchMode = "modulepath" }` in Gradle |
| `Module javafx.controls not found` / `--add-modules` | JavaFX jars are missing from the runtime classpath; add the `javafx-controls`/`-fxml` dependencies or the OpenJFX plugin |
| `STARTUP_TIMEOUT` | `start()` blocks (I/O, dialogs before show) or the main class never launches JavaFX. Read the log tail in the message |
| `APP_EXITED` with an exception | Read the log lines in the error: usually a resource path or a missing demo flag (`app-args=`) |
| No display / `Unable to open DISPLAY` | `headless=xvfb` or `headless=monocle` in `fxloop.properties`, then reload. For `fxloop:run` / `fxloopRun` use `xvfb-run -a` |
| `java` not found, or the MCP server does not start | Use the absolute `java` path from step 1 as the MCP `command` too |
| Anything else | The doctor (step 3), then [docs/troubleshooting.md](https://github.com/h9lxyz/fxloop/blob/main/docs/troubleshooting.md) |

## Uninstall

Remove the `fxloop` MCP entry (`claude mcp remove fxloop` in the project for Claude Code), `fxloop.properties`, the
plugin and guard if added, and `~/.fxloop/`. Remove the skills with `npx skills remove h9lxyz/fxloop -g`, and the
seeded artifacts, if any, in `~/.m2/repository/org/lab59/fxloop/`. Nothing else is installed.

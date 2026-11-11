# PRD 11 — Onboarding: two paths to a first screenshot (Stage O)

**Status:** proposed (2026-11-02). Supersedes the install parts of PRD 06 §3 and the bootstrap in
[decisions/skills-packaging.md](../decisions/skills-packaging.md).

**Objective:** a developer who has never seen FXLoop, on Windows, macOS or Linux, gets from the README to "the agent
just showed me a screenshot of my app" in under five minutes, with no clone, no build of FXLoop, nothing in `~/.m2`,
and nothing in their home directory except one jar. Two paths, clearly separated:

- **Path A — MCP server only.** One jar, one MCP config entry, no change to the app's build file. The server detects
  the build tool, compiles through the project's wrapper, finds the main class itself, launches, connects.
- **Path B — the skills.** Path A plus the skill folders. `fxloop-connect` does the install, adds the build plugin
  (for `fxloop:run` and the dev bar), the production guard and the notes, and shows the first screenshot.

**Audience:** new developers (P2) and the agent running `fxloop-connect` (P1). Also contributors: the README's
"build from source" moves to a contributor section and stops being the first thing a user sees.

**Entry gate:** PRD 10 (one tagged GitHub release with the version-free asset names). Everything else here is
independent of publishing to Maven Central; Central makes path B's plugin step cheaper, not path A.

---

## 1. Where we are (fresh Windows machine, 2026-11-02)

| Step in the README | What happens |
|---|---|
| `git clone https://github.com/fxloop/fxloop.git` | 404; the repository is `h9lxyz/fxloop` |
| `./mvnw install -DskipTests` | bash syntax; `mvnw.cmd` on Windows; builds seven modules and runs nothing useful for a user; 5–10 minutes |
| `(cd fxloop-gradle-plugin && ./gradlew publishToMavenLocal)` | bash subshell; not runnable in PowerShell |
| `cp -r fxloop-skills/skills/fxloop-* ~/.claude/skills/` | bash; `~` and the glob do not work in PowerShell |
| `fxloop-connect` step 0 | copies `FxloopBootstrap.java` into `~/.fxloop/` ("why is there a Java source file in my home directory?"); `--install` looks in `~/.m2` first because a SNAPSHOT can only be there, then for jbang, then runs the project's own wrapper to fetch an artifact that no remote has: a slow failure with a Maven stack trace |
| MCP config `java ${HOME}/.fxloop/FxloopBootstrap.java` | `HOME` is unset on Windows; `java` on `PATH` must be a JDK (the single-file launcher fails on a JRE with an unrelated error) |
| First `fx_get_app_status` | the server runs `dev.fxloop:fxloop-maven-plugin:<v>:prepare`, which is not in any repository on a fresh machine; the fallback demands `FXLOOP_MAIN_CLASS`. So "MCP only" needs the plugin or a manual main class |
| Gradle project | the plugin needs `mavenLocal()` in `pluginManagement` for a SNAPSHOT |
| Two reloads | once for the MCP config, once more if `fxloop.properties` was written after it |

The bootstrap's design made sense while the jar had to be built locally: a JDK-only launcher so the MCP config never
contained a machine path, with jar resolution in one place. With a downloadable jar it is the single most confusing
part of the setup, and it carries the resolution logic that fails on new machines.

## 2. Principles

1. **The server jar is the product.** Everything path A needs is in it or detected by it: the agent jar (already
   bundled), main-class detection, module-path decisions, a Gradle fallback, a doctor. Nothing else is downloaded.
2. **No source files in the home directory.** `~/.fxloop/` holds the jar and the server's caches, nothing else.
   `FxloopBootstrap.java` is removed.
3. **Boring configs.** The MCP entry is `java -jar <path to the jar>`. The path is absolute and per machine; the
   README and the skill say so. Client-specific variables are used only where the client is known to expand them.
4. **Every command in two shells.** bash/zsh and PowerShell, side by side, everywhere a command appears.
5. **Detect, then ask.** The server resolves the main class the way the Maven plugin does today; only an ambiguous
   result asks, and it asks with the candidates listed.
6. **One reload.** `fxloop-connect` writes everything the server reads at startup before asking for the one reload.
7. **Works offline after the first download**, and behind a proxy with a manual jar path.

## 3. Install (both paths)

The README's quickstart becomes:

```sh
# bash / zsh
mkdir -p ~/.fxloop && curl -fsSL -o ~/.fxloop/fxloop-server.jar \
  https://github.com/<owner>/fxloop/releases/latest/download/fxloop-server-all.jar
```

```powershell
# PowerShell
New-Item -Force -ItemType Directory "$HOME\.fxloop" | Out-Null
Invoke-WebRequest -OutFile "$HOME\.fxloop\fxloop-server.jar" `
  https://github.com/<owner>/fxloop/releases/latest/download/fxloop-server-all.jar
```

`java -jar ~/.fxloop/fxloop-server.jar --version` confirms the download and prints the JDK check (§6).

Requirements, stated once above the commands: a JDK 17 or newer on `PATH` (a JDK, not a JRE), a JavaFX app built with
Maven or Gradle, an agent that speaks MCP.

## 4. Path A — MCP server only

### 4.1 Register

```sh
claude mcp add fxloop -- java -jar ~/.fxloop/fxloop-server.jar         # bash / zsh
claude mcp add fxloop -- java -jar "$HOME\.fxloop\fxloop-server.jar"    # PowerShell
```

For other clients, the `.mcp.json`-style snippets from the MCP clients reference, with the absolute jar path. The
server runs in the client's working directory; `FXLOOP_PROJECT_DIR` only when the app is not the workspace root
(`fxloop.properties` works too). No `FXLOOP_MAIN_CLASS` in the quickstart.

### 4.2 Make the build plugin optional (server-side prepare)

Today Maven `prepare` needs `fxloop-maven-plugin` in a repository, and Gradle has no fallback at all. The server
gains a **plugin-free prepare** for both:

- **Maven.** The existing fallback (`compile` + `dependency:build-classpath`) is completed with what `PrepareMojo`
  does: main-class resolution in the same order (`fxloop.properties` → `javafx-maven-plugin` → `exec` → jar manifest →
  class scan of `target/classes`), read from the effective POM (`help:effective-pom` once, cached by POM mtime);
  launch mode from `module-info.java` and the `org.openjfx` artifacts on the classpath; `release` from the compiler
  plugin; annotation-processor detection. `MainClassResolver` and `JavaFxModules` move from the Maven plugin into a
  small shared module (`fxloop-launch`, depending only on ASM and `fxloop-protocol`) that the server, the Maven plugin
  and the Gradle plugin all use, so detection cannot drift between them.
- **Gradle.** The server bundles the Gradle plugin jar and applies it through a generated init script
  (`--init-script <fxloop dir>/fxloop.init.gradle.kts`, `initscript { dependencies { classpath(files(<jar>)) } }`,
  `allprojects { apply<FxloopPlugin>() }`) when the project does not apply `dev.fxloop` itself. `fxloopPrepare` then
  runs exactly as it does with the plugin applied. Nothing is written into the user's build.
- The result is the same `launch.json`; `prepare: "server"` in `fx_get_app_status` says which route ran. The build
  plugin, when present, still wins (it sees the real build model and supports `fxloop:run`).

With this, path A's first `fx_get_app_status` builds, launches and connects on a project with no FXLoop anywhere in
its build files.

### 4.3 What path A does not have

`mvn fxloop:run` / `gradle fxloopRun` (the dev bar still shows when the server launches the app, and pick and the
inspector work; what is missing is running the app yourself). The upgrade to that is the plugin snippet in the Maven
or Gradle plugin README, or path B.

### 4.4 Pre-Central bridge for the plugins

Until the artifacts are on Central and the Plugin Portal, `fxloop:run` cannot resolve the plugin or the agent. The
server carries the Maven plugin jar and POM and its own agent jar, and `java -jar fxloop-server.jar --seed-local-repo`
writes them into `~/.m2/repository/dev/fxloop/…` with a `maven-metadata-local.xml`, so `mvn fxloop:run` works with
the plugin in the POM, and Gradle with `mavenLocal()` in `pluginManagement`. `fxloop-connect` runs it when the plugin
fails to resolve. The command and the bundled copies are removed in the first release after Central publishing works
(PRD 10 §7). This replaces "clone and build FXLoop" as the only pre-Central option.

## 5. Path B — the skills

### 5.1 Install the skills

```sh
curl -fsSL https://github.com/<owner>/fxloop/releases/latest/download/fxloop-skills.zip -o /tmp/fxloop-skills.zip \
  && unzip -o /tmp/fxloop-skills.zip -d ~/.claude/skills/
```

```powershell
Invoke-WebRequest -OutFile "$env:TEMP\fxloop-skills.zip" https://github.com/<owner>/fxloop/releases/latest/download/fxloop-skills.zip
Expand-Archive -Force "$env:TEMP\fxloop-skills.zip" "$HOME\.claude\skills\"
```

Other agents: the same zip into their skills directory. The zip is produced by the release (PRD 10 §3) from
`fxloop-skills/skills/`, so nothing changes about how the skills are authored or tested.

**Claude Code convenience (optional):** a plugin marketplace manifest in the repository (`.claude-plugin/`) that
points at the same skill folders and carries the MCP entry, so `claude plugin marketplace add <owner>/fxloop` and
`/plugin install fxloop` do §3, §4.1 and §5.1 in one step with no reload. This does not reverse the packaging decision:
the plain folders stay the source and the only thing other agents see; the manifest is generated and tested like the
reference copies (`SkillsLayoutTest`).

### 5.2 `fxloop-connect` v2

Step 0 is rewritten; steps 1–7 stay.

| | Today | v2 |
|---|---|---|
| Launcher | copy `FxloopBootstrap.java` to `~/.fxloop/`, run `--install` | none |
| Server jar | `~/.m2` → `~/.fxloop` → jbang → wrapper fetch | `~/.fxloop/fxloop-server.jar` exists and `--version` reports the skill's `fxloop-version` (any version for a SNAPSHOT skill); else download the release matching the version (`releases/download/<version>/fxloop-server-all.jar`, `latest` for a SNAPSHOT skill) with `curl`/`Invoke-WebRequest`, and show the URL and the checksum |
| MCP config | `java <home>/.fxloop/FxloopBootstrap.java` with client variables | `java -jar <absolute jar path>`; the report says the file is per machine. Claude Code may use `${HOME}`/`${USERPROFILE}` as today |
| Order | config, then `fxloop.properties` if needed, sometimes two reloads | detect (step 1) → `fxloop.properties` (step 4) → MCP config → **one** reload |
| JDK check | none | `java -jar … doctor` (§6) before writing anything; stops with the fix when the JDK is below 17 or a JRE |
| Plugin resolution | "run `./mvnw install` in the FXLoop repo" | `--seed-local-repo` (§4.4) until Central; Central afterwards |
| Windows | `${USERPROFILE}` note | wrapper names (`mvnw.cmd`, `gradlew.bat`), quoting of paths with spaces, PowerShell variants of every command in the report |

The skill's report ends, as today, with the screenshot, the diff, how to undo (now: the MCP entry, the build-file
hunks, and `~/.fxloop/`), and the `fxloop:run` line.

## 6. `doctor`: detect, then ask

A server subcommand, `java -jar fxloop-server.jar doctor [--json]`, run in the project directory. It never writes.
It reports, in order, with ✅/⚠️/❌ and a one-line fix each:

1. JDK: version, JDK vs JRE, `JAVA_HOME` vs the `java` on `PATH` when they differ, JetBrains Runtime detected.
2. Build tool: Maven or Gradle, wrapper found (and where), or `mvn`/`gradle` on `PATH`.
3. JavaFX: the `javafx-*` dependencies and their version, `module-info.java`, the chosen launch mode.
4. Main class: the resolved class and which rule found it; or the candidates when ambiguous, with the
   `fxloop.properties` line (`main-class=…`) that pins one.
5. FXLoop build plugin: present (version, matches the server?) or absent (path A: fine; says what `fxloop:run` needs).
6. Display: a real display, or the headless mode that would work here (`xvfb` on Linux without `DISPLAY`,
   `monocle` otherwise), with the properties line.
7. Agent: which MCP client configs exist in the workspace and whether they contain `fxloop`; prints the snippet for
   the client it does not find (all of them with `--client=all`).

`fxloop-connect` runs it first and relays the lines; a human runs it when "it doesn't work". `--json` is for the skill
and for tests. This is where "detects or asks for the main class" lives: the server detects, `doctor` shows the result,
the skill or the developer pins it only when asked to.

## 7. README quickstart (the result)

Top of the README, after the pitch: **Requirements** (three lines), **Install** (the two shell blocks from §3), then
two short sections:

- **A. Just the MCP server:** the register line (§4.1) and "ask your agent to call `fx_get_app_status`".
- **B. With the skills:** the skills install (§5.1), the Claude Code one-liner when the marketplace manifest exists,
  and "ask: connect FXLoop to this project and show me the app".

"Run the app yourself" stays as the third block and says it needs the build plugin (a link to the snippet). Build
from source, jbang and the Gradle `publishToMavenLocal` move to a **Contributing** section at the end. The clone URL
is fixed everywhere (README, `mkdocs.yml`, `docs/index.md`, the Gradle plugin metadata) as PRD 10 §5 requires.

## 8. Acceptance

**Matrix.** Fresh VMs or containers with only a JDK and git: Windows 11 (PowerShell), macOS (zsh), Ubuntu (bash).
Projects: `fxloop-sample-app` (Maven, Java), `fxloop-sample-app-kotlin` (Gradle, Kotlin), each copied **without** any
`dev.fxloop` plugin in its build file for path A.

- **A-O1 Path A.** README §3 + §4.1 verbatim, then `fx_get_app_status` from Claude Code: the app is launched and a
  screenshot taken, on both projects and all three OSes. Measured wall time from first command to screenshot under
  five minutes on a normal connection. Assertions: no `dev.fxloop` entries in `~/.m2`; no files outside `~/.fxloop/`
  and the project; the server log shows `prepare: server`.
- **A-O2 Path B.** README §3 + §5.1, then "connect FXLoop to this project and show me the app": one reload, then a
  screenshot; the build file diff contains the plugin and the guard and nothing else; the report's undo section is
  accurate; re-running the skill changes nothing.
- **A-O3 Doctor.** On a project with two `Application` subclasses and no main-class config, `doctor` lists both and the
  pin line; after pinning, it reports the class and the rule. On a JRE it fails at step 1 with the JDK fix. Headless
  Linux reports `xvfb`.
- **A-O4 Gradle fallback.** `fx_get_app_status` on the Gradle sample with no plugin applied produces the same
  `launch.json` as `fxloopPrepare` with the plugin applied (field-by-field equality).
- **A-O5 Detection parity.** `MainClassResolver` moved to the shared module: the Maven plugin's existing tests pass
  unchanged, and the server's fallback resolves the same class as the plugin on every sample app.
- **A-O6 Seed.** `--seed-local-repo` followed by `mvn fxloop:run` on the Maven sample starts the app with the dev bar
  on a machine that never built FXLoop.
- **A-O7 Proxy.** With GitHub unreachable, the README's manual alternative (get the jar any other way and put it at
  `~/.fxloop/fxloop-server.jar`) works, and `doctor` says what is missing when it is not there.
- **Scripted.** `OnboardingTest` in the acceptance module: a local HTTP server serves the built jar at the release URL
  path, the install lines run against it, then the MCP harness calls `fx_get_app_status` on a plugin-less copy of the
  sample app. Runs on the Linux acceptance lane; the OS matrix above is a manual checklist for each release candidate.

## 9. Surface delta

| Item | Change |
|---|---|
| Server CLI | `--version` (with JDK check), `doctor [--json] [--client=…]`, `--seed-local-repo` (temporary) |
| Server | plugin-free prepare for Maven (main class, launch mode, release, processors) and Gradle (init script); bundles the Gradle plugin jar and, until Central, the Maven plugin jar + POM |
| New module | `fxloop-launch`: `MainClassResolver`, `JavaFxModules`, launch-mode rules, shared by server and both plugins |
| `fx_get_app_status` | `prepare: "plugin" \| "server"`, `doctor` summary on the first call when something is ⚠️ |
| `fxloop-connect` | step 0 rewritten (§5.2); `scripts/FxloopBootstrap.java` deleted; PowerShell variants in the report |
| MCP clients reference | jar-path configs; `FXLOOP_SERVER_JAR` removed |
| Release | consumes PRD 10's version-free asset names |
| README | §7 |
| Optional | `.claude-plugin/` marketplace manifest generated from the skill folders |

## 10. Milestones

1. **Jar-only install** (§3, §4.1, §5.1, §5.2 without doctor, README §7): needs one tagged release. Removes the
   bootstrap and the clone. ~1–2 days.
2. **Plugin-free prepare** (§4.2, the shared module, A-O4/A-O5). Makes path A true. ~3 days.
3. **Doctor** (§6) and the seed command (§4.4). ~2 days.
4. **Acceptance matrix** (§8) on three OSes against `1.0.0`; then the marketplace manifest if wanted. ~1 day plus
   calendar time.

## 11. Risks

- **Moving detection out of the Maven plugin** touches working code. The plugin's tests stay; the shared module is
  extracted without behaviour change first (A-O5), and the server fallback is built on it second.
- **Gradle init scripts** interact with the project's own `settings.gradle` plugin management and with configuration
  cache. The fallback is used only when the plugin is absent, and the acceptance covers Kotlin DSL + configuration
  cache on the Kotlin sample.
- **Corporate Windows**: SmartScreen or antivirus may quarantine a downloaded jar. The README's manual path (copy the
  jar from anywhere, put it at the path) is the escape hatch; nothing is executed from the download location.
- **`~/.m2` seeding** writes into a directory Maven owns. Only the `dev/fxloop` subtree is touched, with
  local-repository metadata, and the command is temporary by design.

## 12. Non-goals

Native installers (MSI, pkg, deb), package managers (Homebrew, Scoop, winget) before 1.0, auto-update of the jar, a
GUI setup wizard, supporting agents without MCP.

## 13. Open questions

1. Keep the jbang alias? It needs Central or a URL `script-ref`. (Default: keep the catalog, point it at the release
   jar URL after the first release, and stop mentioning it in the quickstart.)
2. Should path B's Claude Code marketplace manifest be in scope for `1.0.0`, or after? (Default: after; the two shell
   blocks and one reload are acceptable, and the manifest adds a second install path to test.)
3. `doctor` on the first `fx_get_app_status` call: always, or only when the launch fails? (Default: only the ⚠️ lines,
   always; the full report on failure.)

# Configuration

The server reads `FXLOOP_*` environment variables (set them in the MCP client's `env`). Any variable can also be given as a CLI flag `--key=value`: strip `FXLOOP_`, lower-case, `_` becomes `-` (`FXLOOP_PROJECT_DIR` is `--project-dir=...`). Flags win over environment. Booleans accept `true`/`1`/`yes`.

**Project file:** an optional `fxloop.properties` with the flag names as keys (lowest precedence, below env and flags), read at server start from the server's working directory, else from the configured project dir. A relative `project-dir` is resolved against the file's directory. This is how the `fxloop-connect` skill configures a project without MCP edits; reconnect the MCP server after changing it.

```properties
# the module with the Application, relative to this file
project-dir=app
app-args=--seed
headless=xvfb
```

| Variable / flag | Default | Meaning |
|---|---|---|
| `FXLOOP_PROJECT_DIR` / `--project-dir` | server working dir | Root of the app project (Maven/Gradle). Screenshots, `launch.json` and state live under its `target/fxloop` (Maven) or `build/fxloop` (Gradle). Tool file paths are confined to it. |
| `FXLOOP_MAIN_CLASS` / `--main-class` | auto-detected | Main class (optionally `module/fqcn`). Needed only when detection finds several candidates. |
| `FXLOOP_JVM_ARGS` / `--jvm-args` | none | Extra JVM args for the app; whitespace-split, double quotes group. Appended after the plugin's `jvmArgs`. |
| `FXLOOP_APP_ARGS` / `--app-args` | none | Extra program args (e.g. `--seed`). Same splitting. |
| `FXLOOP_BUILD_CMD` / `--build-cmd` | auto (wrapper preferred) | Full build command override, whitespace-split (e.g. `./mvnw`, `gradle`). Legacy alias: `FXLOOP_MVN_CMD`. |
| `FXLOOP_BUILD_TOOL` / `--build-tool` | auto-detected | `maven` or `gradle` (anything other than `gradle` means Maven). |
| `FXLOOP_PREPARE` / `--prepare` | on | `none` skips the build and reads an existing `launch.json`. |
| `FXLOOP_PREPARE_ROUTE` / `--prepare-route` | `auto` | `plugin`, `server` or `auto` (plugin when it resolves, else server-side prepare). |
| `FXLOOP_LAUNCH_MODE` / `--launch-mode` | `auto` | Maven projects: `auto` (module path when a `module-info.java` exists, else classpath), `classpath` or `modulepath`; passed to the plugin as `-Dfxloop.launchMode` or used by the server-side prepare. Gradle: `fxloop { launchMode = ... }` in the build. |
| `FXLOOP_STARTUP_TIMEOUT_MS` / `--startup-timeout-ms` | `30000` | Max wait for the agent handshake after launch (`STARTUP_TIMEOUT`). |
| `FXLOOP_AGENT_JAR` / `--agent-jar` | bundled in server jar, extracted to `<fxloop dir>/fxloop-agent.jar` | Use a different agent jar. |
| `FXLOOP_JAVA_HOME` | server's `java.home` | JDK used to launch the app (not a `--flag`; read straight from the environment). |
| `FXLOOP_HEADLESS` / `--headless` | unset (real display) | `monocle` or `xvfb`; see [Monocle](ci/monocle.md). |
| `FXLOOP_MONOCLE_JAR` / `--monocle-jar` | looked up in `~/.m2` | Explicit `openjfx-monocle` jar for `monocle` mode. |
| `FXLOOP_APP_LOGS` / `--app-logs` | `false` | Capture app `java.util.logging` records (incl. SLF4J via JUL bridge) into `fx_get_logs source=app`. |
| `FXLOOP_WEBVIEW` / `--webview` | `false` | Enables `fx_eval_js` (otherwise `DISABLED`). |
| `FXLOOP_VIEWER` / `--viewer` | off | Port for the live screenshot viewer (token-protected HTTP). |
| `FXLOOP_EDITOR_URL` / `--editor-url` | unset | Template for "open in editor" links in the inspector, e.g. `idea://open?file={file}&line={line}`; returned as `editorUrl` by `/api/styles`. |
| `FXLOOP_INSPECTOR_EDIT` / `--inspector-edit` | `false` | "Try it live" in the CSS inspector: click a value to edit it (suggestions per property type, ↑/↓ on numbers, a color picker for colors, live preview), untick a declaration, or add a declaration or rule. Edits go into one scratch stylesheet in the running app; *Apply to file…* previews and then writes them into the project stylesheets they belong to (and reloads), or into a stylesheet you pick per rule, while *Copy as CSS* / *Ask agent to apply* hand them over instead (the agent applies them, then calls `fx_reload{clearScratch:true}`). A rule from a library's stylesheet (a jar, another module) can be saved into the library's source once you *Map to source…* it, or as an override into one of your stylesheets; mappings are kept in `<project dir>/.fxloop/css-map.json` (git-ignored). Off: the inspector stays read-only and the scratch and mapping endpoints do not exist. `fxloop:run` / `fxloopRun` turn it on unless `fxloop.inspectorEdit=false`: for their inspector helper, and, through the app's handshake, for any agent session that connects to the run. |
| `FXLOOP_BROWSER` / `--browser` | `auto` | `none` stops the server from opening a browser for `fx_open_inspector` and dev-bar picks (the URL is still returned). |
| `FXLOOP_WATCH` / `--watch` | `css-only` | Initial file-watch mode: `css-only`, `all` (also FXML, Java and Kotlin), `off`. Change at runtime with `fx_set_watch_mode`. |
| `FXLOOP_EXTERNAL_COMPILE` / `--external-compile` | `false` | The IDE/build tool compiles; the server HotSwaps changed classes from the output dir instead of running its own javac. |
| `FXLOOP_JBR` / `--jbr` | auto | JetBrains Runtime enhanced redefinition: adds `-XX:+AllowEnhancedClassRedefinition` to the launch so structural hot-swaps (new methods, lambdas, fields) are attempted before restarting; marks hotswap results `jbrEnhanced`. Unset, it is on when the launch JDK (`FXLOOP_JAVA_HOME`) is JBR; `false` turns it off, `true` forces it (the flag fails on other JDKs). See [limitations](limitations.md). |
| `FXLOOP_SCREENS_KEEP` / `--screens-keep` | `100` | Persisted screenshots retained (ring). |
| `FXLOOP_SCREENSHOT_RATE` / `--screenshot-rate` | `2.0` | Max screenshots per second (rate cap). |
| `FXLOOP_REPLAY` / `--replay` | `true` | Replay the action journal after a restart; `false` disables by default. |
| `FXLOOP_RESTAGE` / `--restage` | `false` | Experimental zero-config remount: when no `FxLoopDev.view` mounts exist, re-runs `Application.start` on the primary stage after clearing its scene (tier `restage`). `start()` may start services or timers twice. |
| `FXLOOP_RECORD_HUMAN` / `--record-human` | `true` | Journal the developer's own clicks, key presses and text commits (`source: human`) in launched and embedded modes. Memory only; `PasswordField` content is never recorded. `false` disables capture. |
| `FXLOOP_HUMAN_IDLE_MS` / `--human-idle-ms` | `10000` | Human input within this window defers restarts (`restartDeferred: humanActive`). |
| `FXLOOP_CONTROL` / `--control` | `auto` | `auto` starts in `agent` and switches to `shared` on the first human input, never back. `agent`: the agent may drive with robot input. `shared`: actions default to synthetic events. |
| `FXLOOP_BAR` / `--bar` | `auto` | Dev bar on the app window: `on`, `pill` (corner pill) or `off`. `auto` is `on` with a real display and `off` headless. Its **Open CSS inspector** button opens the browser CSS inspector (PRD 08). While the agent holds the input lock (`fx_lock_input`) it reads "you're watching" and shows **Take over**. |
| `FXLOOP_PICK_KEY` / `--pick-key` | `Ctrl+Shift+F12` | Hotkey that toggles pick mode on the focused window. |
| `FXLOOP_RESTART_KEY` / `--restart-key` | `Ctrl+Shift+F5` | Hotkey for the bar's **Restart** button: restarts with replay. |
| `FXLOOP_TEST` / `--test` | `false` | Adds `-Dfxloop.test=true` to the app and exposes test-only tool `fx_test_block_fx_thread`. Not for normal use. |
| `FXLOOP_LOG` | slf4j default | Server log level (stderr), e.g. `debug`. stdout is reserved for MCP frames. |

JVM system properties on the **server** (`-D` before `-jar`): `fxloop.attach=false` disables `fx_attach`. On the **app**: see [security](security.md).

## Running the app yourself

`mvn fxloop:run` (Maven) or `gradle fxloopRun` (Gradle) runs the app in the foreground with the agent and the dev bar,
without any agent session. Explore it, **Pick** nodes, open **Open CSS inspector** (served by an inspector helper, the server in
`--sidecar=true` mode, until an agent session connects; it steps aside while one is connected), then ask your agent: the server's first call finds the running
app through `target|build/fxloop/handshake.json` (written with its own owner-only token) and connects instead of
launching. Control starts out `shared`, so the agent uses synthetic events and never takes your mouse. Reloads work,
and edits made after the app started are picked up even if you made them before the agent connected. Restart is not
available (the build tool owns the JVM); re-run the goal. The same auto-connect applies to IDE launches that call
`FxLoopDev.install()`. See the [Maven plugin](https://github.com/h9lxyz/fxloop/tree/main/fxloop-maven-plugin) and
Gradle plugin READMEs for the options (`fxloop.bar`, `fxloop.inspectorEdit`, app args).

## Build tool detection

1. `FXLOOP_BUILD_TOOL` if set.
2. `pom.xml` in the project dir means Maven.
3. `build.gradle(.kts)` or `settings.gradle(.kts)` means Gradle.
4. Otherwise Maven.

The command is `FXLOOP_BUILD_CMD` if set; otherwise the **wrapper** (`mvnw`/`mvnw.cmd`, `gradlew`/`gradlew.bat`) found in the project dir or any parent directory; otherwise `mvn`/`gradle` from `PATH`.

What runs on start: Maven `compile org.lab59.fxloop:fxloop-maven-plugin:<server version>:prepare` (adds `-Dfxloop.mainClass` from `FXLOOP_MAIN_CLASS`); Gradle `classes fxloopPrepare`. Both write `launch.json`, which the server reads to launch the app. `fx_get_app_status` reports which route ran as `prepare: "plugin" | "server"`.

## Without the build plugin (server-side prepare)

**Maven.** When `fxloop-maven-plugin` cannot be resolved, the server runs `compile`, `help:effective-pom` (cached until a POM changes) and `dependency:build-classpath` in one invocation and builds the same descriptor the plugin would: main class in the plugin's order (`main-class` → `javafx-maven-plugin` → `exec-maven-plugin` → jar manifest → class scan of `target/classes`), launch mode from `module-info.java` and the `org.openjfx` jars, `release`, JVM/app args from `javafx-maven-plugin`, annotation processors.

**Gradle.** When no build file applies `org.lab59.fxloop`, the server applies its bundled copy of the Gradle plugin through a generated init script (`build/fxloop/fxloop.init.gradle.kts`, jars cached in `~/.fxloop/cache/<version>/gradle/`) and runs `classes fxloopPrepare`. Nothing is written into the build files; the configuration cache stays valid.

`prepare-route=auto|plugin|server` (`FXLOOP_PREPARE_ROUTE`) forces a route; the default `auto` uses the plugin when it resolves.

## Live dev loop (fxloop-dev API)

Code-built views re-render after a HotSwap only when they are wrapped in `FxLoopDev.view`. Wrapping is a plain passthrough when FXLoop is absent, so it is safe to leave in dev builds:

```java
tabs.getTabs().add(new Tab("Edit", FxLoopDev.view("edit-form", this::buildEditForm)));
FxLoopDev.onReload(() -> table.refresh());
```

* `FxLoopDev.view(key, supplier)` re-runs the supplier after each successful HotSwap and swaps the new node into the old node's place.
* `FxLoopDev.onReload(runnable)` runs after each successful reload, on the FX thread. Exceptions are reported, not thrown into the app.
* **Use method references, not lambdas**, for the supplier (`this::buildEditForm`). A lambda body is a synthetic method of its class, so adding one is a structural change that stock HotSwap rejects. Editing the body of `buildEditForm` is a plain method-body swap. The same applies to `onReload` lambdas: a new one needs a restart, so change existing ones instead.

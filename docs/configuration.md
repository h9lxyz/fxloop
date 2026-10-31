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
| `FXLOOP_MAIN_CLASS` / `--main-class` | auto-detected by the build plugin | Main class (optionally `module/fqcn`). **Required** if the Maven plugin is unavailable (see below). |
| `FXLOOP_JVM_ARGS` / `--jvm-args` | none | Extra JVM args for the app; whitespace-split, double quotes group. Appended after the plugin's `jvmArgs`. |
| `FXLOOP_APP_ARGS` / `--app-args` | none | Extra program args (e.g. `--seed`). Same splitting. |
| `FXLOOP_BUILD_CMD` / `--build-cmd` | auto (wrapper preferred) | Full build command override, whitespace-split (e.g. `./mvnw`, `gradle`). Legacy alias: `FXLOOP_MVN_CMD`. |
| `FXLOOP_BUILD_TOOL` / `--build-tool` | auto-detected | `maven` or `gradle` (anything other than `gradle` means Maven). |
| `FXLOOP_PREPARE` / `--prepare` | on | `none` skips the build and reads an existing `launch.json`. |
| `FXLOOP_STARTUP_TIMEOUT_MS` / `--startup-timeout-ms` | `30000` | Max wait for the agent handshake after launch (`STARTUP_TIMEOUT`). |
| `FXLOOP_AGENT_JAR` / `--agent-jar` | bundled in server jar, extracted to `<fxloop dir>/fxloop-agent.jar` | Use a different agent jar. |
| `FXLOOP_JAVA_HOME` | server's `java.home` | JDK used to launch the app (not a `--flag`; read straight from the environment). |
| `FXLOOP_HEADLESS` / `--headless` | unset (real display) | `monocle` or `xvfb`; see [Monocle](ci/monocle.md). |
| `FXLOOP_MONOCLE_JAR` / `--monocle-jar` | looked up in `~/.m2` | Explicit `openjfx-monocle` jar for `monocle` mode. |
| `FXLOOP_APP_LOGS` / `--app-logs` | `false` | Capture app `java.util.logging` records (incl. SLF4J via JUL bridge) into `fx_get_logs source=app`. |
| `FXLOOP_WEBVIEW` / `--webview` | `false` | Enables `fx_eval_js` (otherwise `DISABLED`). |
| `FXLOOP_VIEWER` / `--viewer` | off | Port for the live screenshot viewer (token-protected HTTP). |
| `FXLOOP_EDITOR_URL` / `--editor-url` | unset | Template for "open in editor" links in the inspector, e.g. `idea://open?file={file}&line={line}`; returned as `editorUrl` by `/api/styles`. |
| `FXLOOP_INSPECTOR_EDIT` / `--inspector-edit` | `false` | "Try it live" in the CSS inspector: click a value to edit it (suggestions per property type, ↑/↓ on numbers, a color picker for colors, live preview), untick a declaration, or add a declaration or rule. Edits go into one scratch stylesheet in the running app, never into source; *Copy as CSS* / *Ask agent to apply* hand them over, and the agent applies them, then calls `fx_reload{clearScratch:true}`. Off: the inspector stays read-only and the scratch endpoints do not exist. `fxloop:run` / `fxloopRun` turn it on unless `fxloop.inspectorEdit=false`: for their inspector helper, and, through the app's handshake, for any agent session that connects to the run. |
| `FXLOOP_BROWSER` / `--browser` | `auto` | `none` stops the server from opening a browser for `fx_open_inspector` and dev-bar picks (the URL is still returned). |
| `FXLOOP_WATCH` / `--watch` | `css-only` | Initial file-watch mode: `css-only`, `all` (also FXML, Java and Kotlin), `off`. Change at runtime with `fx_set_watch_mode`. |
| `FXLOOP_EXTERNAL_COMPILE` / `--external-compile` | `false` | The IDE/build tool compiles; the server HotSwaps changed classes from the output dir instead of running its own javac. |
| `FXLOOP_JBR` / `--jbr` | `false` | Opt into JetBrains Runtime enhanced redefinition: adds `-XX:+AllowEnhancedClassRedefinition` to the launch so structural hot-swaps are attempted before restarting; marks hotswap results `jbrEnhanced`. Requires launching on JBR (`FXLOOP_JAVA_HOME`). See [limitations](limitations.md). |
| `FXLOOP_SCREENS_KEEP` / `--screens-keep` | `100` | Persisted screenshots retained (ring). |
| `FXLOOP_SCREENSHOT_RATE` / `--screenshot-rate` | `2.0` | Max screenshots per second (rate cap). |
| `FXLOOP_REPLAY` / `--replay` | `true` | Replay the action journal after a restart; `false` disables by default. |
| `FXLOOP_RESTAGE` / `--restage` | `false` | Experimental zero-config remount: when no `FxLoopDev.view` mounts exist, re-runs `Application.start` on the primary stage after clearing its scene (tier `restage`). `start()` may start services or timers twice. |
| `FXLOOP_RECORD_HUMAN` / `--record-human` | `true` | Journal the developer's own clicks, key presses and text commits (`source: human`) in launched and embedded modes. Memory only; `PasswordField` content is never recorded. `false` disables capture. |
| `FXLOOP_HUMAN_IDLE_MS` / `--human-idle-ms` | `10000` | Human input within this window defers restarts (`restartDeferred: humanActive`). |
| `FXLOOP_CONTROL` / `--control` | `auto` | `auto` starts in `agent` and switches to `shared` on the first human input, never back. `agent`: the agent may drive with robot input. `shared`: actions default to synthetic events. |
| `FXLOOP_BAR` / `--bar` | `auto` | Dev bar on the app window: `on`, `pill` (corner pill) or `off`. `auto` is `on` with a real display and `off` headless. Its **Inspect CSS** button opens the browser CSS inspector (PRD 08). |
| `FXLOOP_PICK_KEY` / `--pick-key` | `Ctrl+Shift+F12` | Hotkey that toggles pick mode on the focused window. |
| `FXLOOP_RESTART_KEY` / `--restart-key` | `Ctrl+Shift+F5` | Hotkey for the bar's **Restart** button: restarts with replay. |
| `FXLOOP_TEST` / `--test` | `false` | Adds `-Dfxloop.test=true` to the app and exposes test-only tool `fx_test_block_fx_thread`. Not for normal use. |
| `FXLOOP_LOG` | slf4j default | Server log level (stderr), e.g. `debug`. stdout is reserved for MCP frames. |

JVM system properties on the **server** (`-D` before `-jar`): `fxloop.attach=false` disables `fx_attach`. On the **app**: see [security](security.md).

## Running the app yourself

`mvn fxloop:run` (Maven) or `gradle fxloopRun` (Gradle) runs the app in the foreground with the agent and the dev bar,
without any agent session. Explore it, **Pick** nodes, open **Inspect CSS** (served by an inspector helper, the server in
`--sidecar=true` mode, until an agent session connects; it steps aside while one is connected), then ask your agent: the server's first call finds the running
app through `target|build/fxloop/handshake.json` (written with its own owner-only token) and connects instead of
launching. Control starts out `shared`, so the agent uses synthetic events and never takes your mouse. Reloads work,
and edits made after the app started are picked up even if you made them before the agent connected. Restart is not
available (the build tool owns the JVM); re-run the goal. The same auto-connect applies to IDE launches that call
`FxLoopDev.install()`. See the [Maven plugin](https://github.com/fxloop/fxloop/tree/main/fxloop-maven-plugin) and
Gradle plugin READMEs for the options (`fxloop.bar`, `fxloop.inspectorEdit`, app args).

## Build tool detection

1. `FXLOOP_BUILD_TOOL` if set.
2. `pom.xml` in the project dir means Maven.
3. `build.gradle(.kts)` or `settings.gradle(.kts)` means Gradle.
4. Otherwise Maven.

The command is `FXLOOP_BUILD_CMD` if set; otherwise the **wrapper** (`mvnw`/`mvnw.cmd`, `gradlew`/`gradlew.bat`) found in the project dir or any parent directory; otherwise `mvn`/`gradle` from `PATH`.

What runs on start: Maven `compile dev.fxloop:fxloop-maven-plugin:<server version>:prepare` (adds `-Dfxloop.mainClass` from `FXLOOP_MAIN_CLASS`); Gradle `classes fxloopPrepare` (needs the `dev.fxloop` plugin applied, see the [Gradle plugin README](../fxloop-gradle-plugin/README.md)). Both write `launch.json`, which the server reads to launch the app.

## Maven fallback without the plugin

If the Maven run fails because `fxloop-maven-plugin` cannot be resolved, the server falls back to `compile` + `dependency:build-classpath` (runtime scope) and builds a classpath-mode descriptor with `target/classes` and `src/main/java`/`src/main/resources` as roots. This path **requires `FXLOOP_MAIN_CLASS`** (error `CONFIG` otherwise) and does not support module-path launches. Gradle has no fallback.

## Live dev loop (fxloop-dev API)

Code-built views re-render after a HotSwap only when they are wrapped in `FxLoopDev.view`. Wrapping is a plain passthrough when FXLoop is absent, so it is safe to leave in dev builds:

```java
tabs.getTabs().add(new Tab("Edit", FxLoopDev.view("edit-form", this::buildEditForm)));
FxLoopDev.onReload(() -> table.refresh());
```

* `FxLoopDev.view(key, supplier)` re-runs the supplier after each successful HotSwap and swaps the new node into the old node's place.
* `FxLoopDev.onReload(runnable)` runs after each successful reload, on the FX thread. Exceptions are reported, not thrown into the app.
* **Use method references, not lambdas**, for the supplier (`this::buildEditForm`). A lambda body is a synthetic method of its class, so adding one is a structural change that stock HotSwap rejects. Editing the body of `buildEditForm` is a plain method-body swap. The same applies to `onReload` lambdas: a new one needs a restart, so change existing ones instead.

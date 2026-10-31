# Configuration

The server reads `FXLOOP_*` environment variables (set them in the MCP client's `env`). Any variable can also be given as a CLI flag `--key=value`: strip `FXLOOP_`, lower-case, `_` becomes `-` (`FXLOOP_PROJECT_DIR` is `--project-dir=...`). Flags win over environment. Booleans accept `true`/`1`/`yes`.

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
| `FXLOOP_WATCH` / `--watch` | `css-only` | Initial file-watch mode: `css-only`, `all` (also Java/FXML), `off`. Change at runtime with `fx_set_watch_mode`. |
| `FXLOOP_EXTERNAL_COMPILE` / `--external-compile` | `false` | The IDE/build tool compiles; the server HotSwaps changed classes from the output dir instead of running its own javac. |
| `FXLOOP_JBR` / `--jbr` | `false` | Opt into JetBrains Runtime enhanced redefinition: adds `-XX:+AllowEnhancedClassRedefinition` to the launch so structural hot-swaps are attempted before restarting; marks hotswap results `jbrEnhanced`. Requires launching on JBR (`FXLOOP_JAVA_HOME`). See [limitations](limitations.md). |
| `FXLOOP_SCREENS_KEEP` / `--screens-keep` | `100` | Persisted screenshots retained (ring). |
| `FXLOOP_SCREENSHOT_RATE` / `--screenshot-rate` | `2.0` | Max screenshots per second (rate cap). |
| `FXLOOP_REPLAY` / `--replay` | `true` | Replay the action journal after a restart; `false` disables by default. |
| `FXLOOP_TEST` / `--test` | `false` | Adds `-Dfxloop.test=true` to the app and exposes test-only tool `fx_test_block_fx_thread`. Not for normal use. |
| `FXLOOP_LOG` | slf4j default | Server log level (stderr), e.g. `debug`. stdout is reserved for MCP frames. |

JVM system properties on the **server** (`-D` before `-jar`): `fxloop.attach=false` disables `fx_attach`. On the **app**: see [security](security.md).

## Build tool detection

1. `FXLOOP_BUILD_TOOL` if set.
2. `pom.xml` in the project dir means Maven.
3. `build.gradle(.kts)` or `settings.gradle(.kts)` means Gradle.
4. Otherwise Maven.

The command is `FXLOOP_BUILD_CMD` if set; otherwise the **wrapper** (`mvnw`/`mvnw.cmd`, `gradlew`/`gradlew.bat`) found in the project dir or any parent directory; otherwise `mvn`/`gradle` from `PATH`.

What runs on start: Maven `compile dev.fxloop:fxloop-maven-plugin:<server version>:prepare` (adds `-Dfxloop.mainClass` from `FXLOOP_MAIN_CLASS`); Gradle `classes fxloopPrepare` (needs the `dev.fxloop` plugin applied, see the [Gradle plugin README](../fxloop-gradle-plugin/README.md)). Both write `launch.json`, which the server reads to launch the app.

## Maven fallback without the plugin

If the Maven run fails because `fxloop-maven-plugin` cannot be resolved, the server falls back to `compile` + `dependency:build-classpath` (runtime scope) and builds a classpath-mode descriptor with `target/classes` and `src/main/java`/`src/main/resources` as roots. This path **requires `FXLOOP_MAIN_CLASS`** (error `CONFIG` otherwise) and does not support module-path launches. Gradle has no fallback.

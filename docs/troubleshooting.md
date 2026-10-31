# Troubleshooting

Errors come back as tool results with `isError`, an error `code`, a message and a one-line hint. Server logs go to stderr (`FXLOOP_LOG=debug` for more); app output is available via `fx_get_logs`.

| Code | Meaning | Fix |
|---|---|---|
| `APP_NOT_RUNNING` | A tool needing the UI ran while the app is exited/stopped | `fx_start_app` or `fx_restart_app`; `fx_get_logs` shows why it stopped |
| `STARTUP_TIMEOUT` | No agent handshake within `FXLOOP_STARTUP_TIMEOUT_MS` (30 s) | Check the main class actually starts JavaFX (`Application.launch`), look at the log tail in the message, raise the timeout for slow startups, make sure no `-Dfxloop.enabled=false` is in `FXLOOP_JVM_ARGS` |
| `APP_EXITED` | JVM exited during startup | Read the 50 log lines in the message: usually missing JavaFX modules, wrong main class or an exception in `start()` |
| `BUILD_FAILED` | Maven/Gradle failed or could not be executed | Fix the compile error shown (the last 40 lines are included); install a build tool, add a wrapper or set `FXLOOP_BUILD_CMD`. Once the app runs, compile errors from `fx_reload` come back as data instead |
| `FX_TIMEOUT` | The FX thread did not answer in time (blocked/deadlocked) | The error carries the FX-thread stack: fix the blocking call (I/O, `Thread.sleep`, `invokeAndWait` on the FX thread), then `fx_restart_app` |
| `NODE_NOT_FOUND` | Path/id no longer matches a node | Re-locate with `fx_find_nodes` / `fx_get_scene_graph`; paths change after the UI changes |
| `AMBIGUOUS_PATH` | Selector matched several nodes | Choose one of `data.candidates` and pass it as `path`, or use `#fx:id` / index `Type[i]` |
| `PROPERTY_BOUND` | Cannot set a bound property | `fx_explain_property` to see the binding; set the source property instead |
| `REDEFINE_REJECTED` | JVM refused the HotSwap (fields/methods added, hierarchy change) | `fx_reload` with `allowRestart:true` (default) or `fx_restart_app {replay:true}`; JBR opt-in in [limitations](limitations.md) |
| `HEADLESS_UNSUPPORTED` | `FXLOOP_HEADLESS=monocle` but no matching Monocle jar | Add `org.testfx:openjfx-monocle:<version>` (see [table](ci/monocle.md)) as a test dependency, set `FXLOOP_MONOCLE_JAR`, or use `FXLOOP_HEADLESS=xvfb` |
| `CONFIG` | Missing/invalid configuration (e.g. `launch.json` unreadable, agent jar missing) | `FXLOOP_MAIN_CLASS` when the Maven plugin is unavailable; run `mvn compile dev.fxloop:fxloop-maven-plugin:1.0.0-SNAPSHOT:prepare` (or `gradlew fxloopPrepare`); `FXLOOP_AGENT_JAR` |

## Other codes you may see

| Code | Meaning |
|---|---|
| `ALREADY_RUNNING` | `fx_start_app` while running; use `fx_restart_app` |
| `NOT_SUPERVISED` | Restart/stop on an attached or embedded app; do it where the app was started |
| `LAUNCH_FAILED` | The `java` process could not be spawned; check `FXLOOP_JAVA_HOME` and the agent jar |
| `ATTACH_FAILED` / `CONNECT_FAILED` | `fx_attach` / `fx_connect_existing` could not reach the JVM or handshake; see the hint (attach disabled, other user, jlinked runtime, `FxLoopDev.install()` missing) |
| `DISABLED` | Feature switched off: `fx_attach` (`-Dfxloop.attach=false`), `fx_eval_js` (needs `FXLOOP_WEBVIEW=true`) |
| `BAD_REQUEST` | Bad parameters, or a path outside the project dir |
| `WINDOW_NOT_FOUND`, `PROPERTY_NOT_FOUND`, `COERCION_FAILED`, `UNSUPPORTED_TARGET`, `FXML_NOT_REGISTERED` | Wrong target or value type; use `fx_get_app_status`, `fx_get_node_details`, `fx_get_property` to discover valid ones |
| `UNAUTHORIZED`, `VERSION_MISMATCH` | FCP token/protocol mismatch: stale agent jar (`FXLOOP_AGENT_JAR`) or wrong handshake |

## Common situations

* **Nothing appears on Linux CI/SSH:** no display. Use `FXLOOP_HEADLESS=monocle` or `xvfb`, see [CI](ci/README.md).
* **Server prints to stdout and the client breaks:** never wrap the server in scripts that echo to stdout; logs belong on stderr.
* **Screenshots missing:** they are under `target/fxloop/screens` (`build/fxloop/screens` for Gradle); `FXLOOP_SCREENS_KEEP` limits retention.

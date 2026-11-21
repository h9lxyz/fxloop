# Attach to an app you started

Start your JavaFX app the way you always do, then run one command to give it the FXLoop dev bar and CSS inspector.
Nothing in your build files changes, and a coding-agent session can join the same window later.

```sh
java -jar ~/.fxloop/fxloop-server.jar attach
```

Run it while the app is running, however you started it (IDE Run button, `mvn javafx:run`, `gradle run` or plain
`java`). FXLoop finds the app, loads its agent into it, works out the project from the app's class path without running
the build, and prints where the inspector is. Ctrl+C stops FXLoop; your app keeps running.

## What you get

- The **dev bar** on the app window, with **Pick** and **Open CSS inspector**.
- The **CSS inspector** with `file:line` of each rule in your source stylesheets, "try it live" and *Apply to file…*.
- **Stylesheet saves applied live**, in about a quarter of a second. `--watch=all` also hot-swaps Java method bodies on
  save.
- An **agent session** can join the same window: ask your agent, and its first call connects to the running app. Its
  `fx_reload` hot-swaps method bodies. While it is connected, `attach` steps aside, and comes back when it leaves.

## Limits

- **You restart the app.** FXLoop did not start it, so it cannot restart or stop it. When you restart it, `attach`
  waits and attaches to the new process on its own (unless `--once`).
- **FXML views that were already loaded are not swapped live.** They pick up the change after your next restart.
- **Inspect-only without a project.** When the app runs only from jars, or outside a Maven or Gradle project, you can
  pick, inspect and try CSS live, but nothing is saved to files.
- **Standard layout.** Sources are taken from `src/main/java`, `src/main/kotlin` and `src/main/resources`, not from the
  build model.
- **JDK warning.** On JDK 21 and later the app's console shows a warning that an agent was loaded into a running JVM,
  and that a future release will disallow this by default. Start the app with `-XX:+EnableDynamicAgentLoading` to
  silence it.
- Tested on Linux with JDK 25. Windows and macOS are not verified yet.

The agent stays in the app until it exits, listening on localhost only with a per-run token in an owner-only file
([security](security.md)). Attaching needs the same OS user as the app.

## Options

| Option | Use |
|---|---|
| `<pid>` | Attach to this process instead of searching |
| `--list` | List the running JavaFX apps with their pid and project, then exit |
| `--once` | Stop when the app exits instead of waiting for its restart |
| `--project-dir=<dir>` | The project to use, when the guess from the class path is wrong. Must contain a build file and the app's class folders |
| `--watch=css-only\|all\|off` | What saves apply live: stylesheets (default), also Java method bodies, or nothing |

With several JavaFX apps running, `attach` takes the one that belongs to the project in the current directory, or asks
which one (without a console it lists them and you pass the pid).
Other `--key=value` options and `fxloop.properties` work as for the server ([configuration](configuration.md)).

## Attach or the build plugin

The [build plugin](https://github.com/h9lxyz/fxloop/blob/main/INSTALL.md#add-the-build-plugin-optional)
(`mvn fxloop:run`, `gradle fxloopRun`) gives the same dev bar and inspector, but starts the app with the agent from the
beginning. Choose it when you want FXML views reloaded live from the first window on, no JDK warning in the console, or
the same FXLoop version pinned in the build for the whole team.

## If it fails

| Message | Do this |
|---|---|
| `No running JavaFX app found.` | Start the app first and wait until its window is open, then run `attach` again |
| `… does not allow loading an agent while it runs …` | Add `-XX:+EnableDynamicAgentLoading` to the app's JVM options, restart it, run `attach` again |
| `… already runs …`, `… already has …` or `… already serves …` | The app already has FXLoop (`fxloop:run`, an agent session, or another `attach`); use that one |
| `Cannot load the FXLoop agent into …` | Run `attach` as the same OS user as the app, on a full JDK (not a jlinked runtime without `java.instrument`), without `-XX:+DisableAttachMechanism` |

`FXLOOP_LOG=info` before the command prints more detail. More in [troubleshooting](troubleshooting.md).

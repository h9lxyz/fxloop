# fxloop-maven-plugin

Provides `fxloop:run`, which runs your app with the FXLoop agent and dev bar so you can explore it and bring in an
agent whenever you want, and `fxloop:prepare`, which writes `target/fxloop/launch.json` (a `LaunchDescriptor` from `fxloop-protocol`)
so the FXLoop server can launch the app without any manual configuration. The goal has no default phase; invoke it
explicitly after `compile`:

```sh
mvn -q compile dev.fxloop:fxloop-maven-plugin:1.0.0-SNAPSHOT:prepare
```

With `<pluginGroup>dev.fxloop</pluginGroup>` in `settings.xml`, `mvn compile fxloop:prepare` works too.

Optional explicit configuration:

```xml
<plugin>
  <groupId>dev.fxloop</groupId>
  <artifactId>fxloop-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <configuration>
    <mainClass>com.example.Launcher</mainClass>
    <jvmArgs><jvmArg>-Xmx512m</jvmArg></jvmArgs>
  </configuration>
</plugin>
```

## Run the app yourself: `fxloop:run`

```sh
mvn fxloop:run                                  # with <pluginGroup>dev.fxloop</pluginGroup>, or the plugin in the pom
mvn fxloop:run -Dfxloop.appArgs=--de,--rtl      # extra program args for this run
```

It compiles, writes `launch.json` like `prepare`, and runs the app in the foreground with the FXLoop agent. The dev bar
shows from the start (`FXLoop · no agent connected`): click around, use **Pick** to mark a node, open **Inspect CSS**
(the browser CSS inspector, served by a small FXLoop helper started next to the app; its URL is printed), and ask your
agent whenever you like. While an agent session is connected the helper steps aside and the session serves the
inspector; when the session ends the helper takes over again. The FXLoop MCP server finds the running app by itself on its first call (`fx_get_app_status`) and
connects to it instead of launching another copy; your pick is then available as `fx_get_picked`. Close the app or
press Ctrl+C to stop.

Compared with letting the agent launch the app, everything works except restart: CSS, FXML and Java reloads (also for
edits you made before the agent connected), inspection, input, screenshots and the CSS inspector. Restarting means
re-running the goal.

| Parameter | Property | Default | Description |
|---|---|---|---|
| `bar` | `fxloop.bar` | `on` | Dev bar before an agent connects: `on`, `pill` or `off`. |
| `inspector` | `fxloop.inspector` | `true` | Start the inspector helper (Inspect CSS without an agent session). |
| `inspectorEdit` | `fxloop.inspectorEdit` | `true` | "Try it live" in the CSS inspector: edit values, untick declarations and add rules in a scratch stylesheet, never in source. Applies to the helper and to agent sessions that connect to this run. `false` keeps it read-only. |
| `agentJar` | `fxloop.agentJar` | `dev.fxloop:fxloop-agent` at the plugin's version | Agent jar override. |
| `serverJar` | `fxloop.serverJar` | `dev.fxloop:fxloop-server:jar:all` at the plugin's version | Server jar for the inspector helper. |
| `javaHome` | `fxloop.javaHome` | the JDK running Maven | JDK that runs the app. |

`fxloop:run` also takes every `prepare` parameter below.

## Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `outputFile` | `fxloop.outputFile` | `${project.build.directory}/fxloop/launch.json` | Where the descriptor is written. |
| `mainClass` | `fxloop.mainClass` | auto-detected | Main class, optionally `module/fqcn`. |
| `launchMode` | `fxloop.launchMode` | `auto` | `auto` (module path if a `module-info.java` exists, else classpath), `classpath`, `modulepath`. |
| `jvmArgs` | `fxloop.jvmArgs` | none | Extra JVM args; `javafx-maven-plugin` `<options>` are appended. |
| `appArgs` | `fxloop.appArgs` | none | Extra program args; `javafx-maven-plugin` `<commandlineArgs>` is appended. |

## Main class resolution

1. `fxloop.mainClass`
2. `org.openjfx:javafx-maven-plugin` `<mainClass>` (`module/fqcn` is split into `mainModule` + `mainClass`)
3. `org.codehaus.mojo:exec-maven-plugin` `<mainClass>`
4. `maven-jar-plugin` `<archive><manifest><mainClass>`
5. Class scan of `target/classes`: a non-`Application` class with `main(String[])` that references `Application`
   (Launcher pattern), else the single `Application` subclass, else the single class with `main`.
   If still ambiguous the build fails and lists the candidates.

## Launch modes

- `classpath`: all runtime jars in `classpath`.
- `modulepath`, modular app: `mainModule` set; output dir and all jars in `modulePath`.
- `modulepath`, non-modular app: `org.openjfx` jars in `modulePath`, `addModules` derived from artifact ids
  (`javafx-controls` becomes `javafx.controls`), everything else stays on the classpath.

`release` comes from the compiler plugin `<release>`, then `maven.compiler.release`, `maven.compiler.target`, then the
running JVM. `annotationProcessors` is true when `<annotationProcessorPaths>` is configured or a compile-classpath
jar ships `META-INF/services/javax.annotation.processing.Processor`.

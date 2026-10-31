# fxloop-maven-plugin

Provides `fxloop:prepare`, which writes `target/fxloop/launch.json` (a `LaunchDescriptor` from `fxloop-protocol`)
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

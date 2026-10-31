# fxloop-gradle-plugin

Gradle plugin `dev.fxloop` (Kotlin). Registers task `fxloopPrepare` (group `fxloop`, depends on `classes`) that writes
`build/fxloop/launch.json` using the same schema as the Maven `fxloop:prepare` (`LaunchDescriptor`, `buildTool: "gradle"`).

Standalone Gradle build (not a Maven reactor module). Requires `dev.fxloop:fxloop-protocol:1.0.0-SNAPSHOT` in `mavenLocal()`
(`./mvnw install`).

    ./gradlew -p fxloop-gradle-plugin build publishToMavenLocal

```kotlin
plugins { java; id("dev.fxloop") version "1.0.0-SNAPSHOT" }

fxloop {
    mainClass = "com.acme.Launcher"      // optional
    jvmArgs = listOf("-Xmx512m")
    appArgs = listOf("--seed")
    launchMode = "auto"                  // auto | classpath | modulepath
}
```

Main class resolution: `fxloop.mainClass` -> `javafx { mainClass }` (org.openjfx.javafxplugin, if present) ->
`application.mainClass` -> jar manifest `Main-Class` -> ASM scan of the compiled classes (Launcher pattern preferred);
ambiguity fails and lists candidates. `auto` launches on the module path only when `module-info.java` exists.
Publishing to the Plugin Portal is configured (`com.gradle.plugin-publish`) but not executed. Compiles with `--release 17`
on any JDK >= 17 (no toolchain required).

## Run the app yourself: `fxloopRun`

```sh
./gradlew fxloopRun                       # app with the FXLoop agent and the dev bar
./gradlew fxloopRun --args="--de --rtl"   # extra program args for this run
./gradlew fxloopRun -Pfxloop.bar=pill     # dev bar as a corner pill (on|pill|off)
./gradlew fxloopRun -Pfxloop.inspector=false   # no inspector helper
```

Runs the app in the foreground from `launch.json` with the agent (`dev.fxloop:fxloop-agent` at the plugin's version,
resolved from the project's repositories: a SNAPSHOT needs `mavenLocal()`). The dev bar shows before any agent
connects, with **Pick** and **Inspect CSS**: an inspector helper (`dev.fxloop:fxloop-server:all`, also from the project's
repositories) serves the browser CSS inspector until an agent session connects, steps aside while it is there, and
returns afterwards; its output and URL are in `build/fxloop/inspector.log`. The FXLoop server finds the running app on
its first call and connects instead of launching another copy. Everything but restart works (re-run the task). Uses
the project's Java toolchain when one is configured.

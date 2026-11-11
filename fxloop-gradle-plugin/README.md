# fxloop-gradle-plugin

Gradle plugin `dev.fxloop` (Kotlin). Registers task `fxloopPrepare` (group `fxloop`, depends on `classes`) that writes
`build/fxloop/launch.json` using the same schema as the Maven `fxloop:prepare` (`LaunchDescriptor`, `buildTool: "gradle"`).

Setup in your own project (plugin and production guard): [INSTALL.md](../INSTALL.md#add-the-build-plugin-optional).

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

Groovy DSL, with the production guard that fails the `jar` task when an FXLoop artifact is on the runtime classpath:

```groovy
plugins {
    id 'dev.fxloop' version '1.0.0-SNAPSHOT'
}

def fxloopGuard = tasks.register('fxloopGuard') {
    group = 'verification'
    description = 'Fails if a dev.fxloop artifact is on the runtime classpath'
    def leaked = configurations.runtimeClasspath.map { c ->
        c.incoming.resolutionResult.allComponents.collect { it.id.displayName }.findAll { it.startsWith('dev.fxloop:') }
    }
    doLast { if (!leaked.get().isEmpty()) throw new GradleException("FXLoop must not ship: ${leaked.get()}") }
}
tasks.named('jar') { dependsOn fxloopGuard }
```

Main class resolution: `fxloop.mainClass` -> `javafx { mainClass }` (org.openjfx.javafxplugin, if present) ->
`application.mainClass` -> jar manifest `Main-Class` -> ASM scan of the compiled classes (Launcher pattern preferred);
ambiguity fails and lists candidates. `auto` launches on the module path only when `module-info.java` exists.

Kotlin (and other JVM languages) work without configuration: the scan covers every `main.output.classesDirs` entry,
`sourceRoots` holds all of the source set's source dirs (`src/main/kotlin` too), and `outputDir` is
`build/classes/java/main` unless only another language produced classes (a pure Kotlin app gets
`build/classes/kotlin/main`, which is what `FXLOOP_EXTERNAL_COMPILE` watches). Other classes dirs lead the `classpath`.
Publishing to the Plugin Portal is configured (`com.gradle.plugin-publish`) but not executed. Compiles with `--release 21`
on any JDK >= 21 (no toolchain required).

## Run the app yourself: `fxloopRun`

```sh
./gradlew fxloopRun                       # app with the FXLoop agent and the dev bar
./gradlew fxloopRun --args="--de --rtl"   # extra program args for this run
./gradlew fxloopRun -Pfxloop.bar=pill     # dev bar as a corner pill (on|pill|off)
./gradlew fxloopRun -Pfxloop.inspector=false   # no inspector helper
./gradlew fxloopRun -Pfxloop.inspectorEdit=false   # read-only CSS inspector (no "Try it live")
```

Runs the app in the foreground from `launch.json` with the agent (`dev.fxloop:fxloop-agent` at the plugin's version,
resolved from the project's repositories: a SNAPSHOT needs `mavenLocal()` in the dependency repositories, not only in
`pluginManagement`, after `java -jar ~/.fxloop/fxloop-server.jar --seed-local-repo`; without a display run the task
under `xvfb-run -a`). The dev bar shows before any agent
connects, with **Pick** and **Open CSS inspector**: an inspector helper (`dev.fxloop:fxloop-server:all`, also from the project's
repositories) serves the browser CSS inspector until an agent session connects, steps aside while it is there, and
returns afterwards; its output and URL are in `build/fxloop/inspector.log`. The inspector has "Try it live" on, also
while an agent session is connected: edit CSS values and add rules in a scratch stylesheet, then hand them to your agent
to apply. The FXLoop server finds the running app on
its first call and connects instead of launching another copy. Everything but restart works (re-run the task). Uses
the project's Java toolchain when one is configured.

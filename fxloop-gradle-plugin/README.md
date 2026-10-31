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

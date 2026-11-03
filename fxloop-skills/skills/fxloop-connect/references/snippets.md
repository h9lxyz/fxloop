# Connect snippets

Replace `<version>` with `fxloop-version` from the skill's frontmatter.

## Maven: build plugin

```xml
<plugin>
  <groupId>dev.fxloop</groupId>
  <artifactId>fxloop-maven-plugin</artifactId>
  <version><version></version>
  <!-- only when the main class is ambiguous:
  <configuration><mainClass>com.acme.MainKt</mainClass></configuration> -->
</plugin>
```

For a `-SNAPSHOT` version, run `java -jar ~/.fxloop/fxloop-server.jar --seed-local-repo` once first (PowerShell:
`java -jar "$HOME\.fxloop\fxloop-server.jar" --seed-local-repo`); it writes the plugin into `~/.m2` until the
artifacts are on Maven Central.

No executions: the server runs `compile dev.fxloop:fxloop-maven-plugin:<version>:prepare` itself, which writes
`target/fxloop/launch.json`. Other parameters: `launchMode` (`auto|classpath|modulepath`), `jvmArgs`, `appArgs`.

## Maven: production guard

Verified: a clean build passes, a `compile`/`runtime`-scope `dev.fxloop` dependency fails `package`, `provided`
passes.

```xml
<plugin>
  <!-- FXLoop guard: dev.fxloop artifacts may be provided/test only, never packaged -->
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.6.2</version>
  <executions>
    <execution>
      <id>fxloop-never-ships</id>
      <phase>package</phase>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <searchTransitive>true</searchTransitive>
            <excludes>
              <exclude>dev.fxloop:*:*:*:compile</exclude>
              <exclude>dev.fxloop:*:*:*:runtime</exclude>
            </excludes>
            <message>FXLoop must not ship: use provided or test scope for dev.fxloop artifacts.</message>
          </bannedDependencies>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

If the project already declares `maven-enforcer-plugin`, add this `<execution>` to it instead of a second plugin
entry, and keep their version.

## Gradle (Kotlin DSL): build plugin

```kotlin
plugins {
    // ...existing plugins
    id("dev.fxloop") version "<version>"
}

// only when the main class is ambiguous:
// fxloop { mainClass = "com.acme.MainKt" }
```

For a `-SNAPSHOT` version, run `java -jar ~/.fxloop/fxloop-server.jar --seed-local-repo` once (it writes the plugin,
the agent and the server into `~/.m2`), and add `mavenLocal()` to both repository blocks of `settings.gradle.kts`
(merge with existing blocks; a project that declares `repositories {}` in the build file adds `mavenLocal()` there):

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal() // fxloopRun resolves dev.fxloop:fxloop-agent and fxloop-server:all from the project repositories
        mavenCentral()
    }
}
```

## Gradle (Kotlin DSL): production guard

Verified, including with the configuration cache.

```kotlin
// FXLoop guard: fail packaging if any dev.fxloop artifact reaches the runtime classpath (it must never ship)
val fxloopGuard = tasks.register("fxloopGuard") {
    group = "verification"
    description = "Fails if a dev.fxloop artifact is on the runtime classpath"
    val leaked = configurations.runtimeClasspath.map { c ->
        c.incoming.resolutionResult.allComponents.map { it.id.displayName }.filter { it.startsWith("dev.fxloop:") }
    }
    doLast { check(leaked.get().isEmpty()) { "FXLoop must not ship; use compileOnly/testImplementation: ${leaked.get()}" } }
}
tasks.named("jar") { dependsOn(fxloopGuard) }
```

## Gradle (Groovy DSL)

```groovy
plugins {
    id 'dev.fxloop' version '<version>'
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

## fxloop.properties (workspace root)

```properties
# FXLoop server config (keys = server flags without --). Reload the fxloop MCP server after editing.
project-dir=app
app-args=--seed
```

## Agent instructions section (`AGENTS.md` / `CLAUDE.md`)

```markdown
## FXLoop

The app runs under FXLoop (MCP server `fxloop`); the agent can screenshot, click, inspect and live-reload it.

- Launch: `fx_get_app_status` builds (`<prepare command>`) and starts `<main class>`<, with `<app-args>`>.
- Run it yourself with the dev bar: `<./mvnw fxloop:run | ./gradlew fxloopRun>`; the agent connects to that window when asked.
- Demo data: `<flags and what they do>` (set in `fxloop.properties` → `app-args`).
- Screenshots: `<target|build>/fxloop/screens/` (gallery: `index.html` there).
- Reloads: CSS and FXML live; Java hot-swaps<; Kotlin `.kt` compiles through <Maven|Gradle> then hot-swaps>.
  Structural changes restart the app and replay the recorded actions.
- Guard: `<maven-enforcer fxloop-never-ships | fxloopGuard task>` fails packaging if FXLoop leaks into the runtime
  classpath.
```

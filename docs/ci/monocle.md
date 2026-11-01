# Monocle headless lane

[OpenJFX Monocle](https://github.com/TestFX/Monocle) is a headless Glass implementation: no display server needed. FXLoop's server selects it with `FXLOOP_HEADLESS=monocle`.

## Compatibility table

Monocle must match the JavaFX feature line of the app. This is exactly what the server knows (`Headless.kt`):

| JavaFX line | `org.testfx:openjfx-monocle` |
|---|---|
| 17 | `17.0.10` |
| 21 | `21.0.2` |
| 25, 27 | `21.0.2` (no newer Monocle exists; CI runs FXLoop's headless suites with it on both) |
| anything else | no known build: use `FXLOOP_HEADLESS=xvfb` (honest gap) |

The line is read from the `javafx-graphics-<N>...jar` on the app's class/module path.

## How `FXLOOP_HEADLESS` works

| Value | Behaviour |
|---|---|
| unset | Real display |
| `monocle` | Adds `-Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw -Dprism.text=t2k -Dtestfx.headless=true -Djava.awt.headless=true` to the app JVM and puts the Monocle jar on the classpath. Jar source order: `FXLOOP_MONOCLE_JAR`; an `openjfx-monocle` already on the app classpath; else `~/.m2/repository/org/testfx/openjfx-monocle/<version>/` from the table. |
| `xvfb` | Wraps the launch in `xvfb-run -a -s "-screen 0 1600x1200x24"` (Linux; install the `xvfb` package). |

Failure modes return `HEADLESS_UNSUPPORTED` with a hint: unknown JavaFX line, or the Monocle jar missing from `~/.m2`. Fix by adding the dependency to your project (it lands in `~/.m2` on resolution):

```xml
<dependency>
  <groupId>org.testfx</groupId>
  <artifactId>openjfx-monocle</artifactId>
  <version>21.0.2</version>   <!-- from the table -->
  <scope>test</scope>
</dependency>
```

Differences from a real desktop (software rendering, no native dialogs) are listed in [limitations](../limitations.md).

# FXLoop in CI

Three lanes, copy-paste templates for **your** JavaFX project (our own pipeline is in `.github/workflows/`):

| Lane | Template | Needs | Use for |
|---|---|---|---|
| Xvfb (Linux) | [github-actions-xvfb.yml](github-actions-xvfb.yml) | `xvfb` package | Any JavaFX line; real Prism pipeline; default choice |
| Monocle | [github-actions-monocle.yml](github-actions-monocle.yml) | `openjfx-monocle` matching your JavaFX line | Fast, no X server, all OSes; see [monocle.md](monocle.md) |
| Desktop robot | [github-actions-desktop.yml](github-actions-desktop.yml) | macOS/Windows runner session | A few native-behaviour tests |

With the FXLoop MCP server, select the lane with `FXLOOP_HEADLESS=monocle|xvfb` (or leave it unset on a real display). Details in [monocle.md](monocle.md) and [configuration](../configuration.md).

## Caching

* Maven: `actions/setup-java@v4` with `cache: maven` (keys on `pom.xml` files). Gradle: `cache: gradle` or `gradle/actions/setup-gradle@v4`.
* Monocle jars are resolved from `~/.m2`, so the Maven cache covers them.
* Do not cache `target/fxloop` (it contains per-run tokens and screenshots); upload `target/fxloop/screens` as an artifact on failure instead.

## Flaky-test quarantine

Tag unstable UI tests and run them in a separate surefire execution that cannot fail the build:

```java
@Tag("flaky")
@Test void dragAndDropReorders() { ... }
```

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <excludedGroups>flaky</excludedGroups>
  </configuration>
  <executions>
    <execution>
      <id>flaky</id>
      <phase>integration-test</phase>
      <goals><goal>test</goal></goals>
      <configuration>
        <excludedGroups combine.self="override"></excludedGroups>
        <groups>flaky</groups>
        <testFailureIgnore>true</testFailureIgnore>
        <rerunFailingTestsCount>2</rerunFailingTestsCount>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The main execution stays green and strict; the `flaky` execution reports but never fails. Review its report weekly and either fix or delete.

## Tips

* Pin a screen size (`xvfb-run -s "-screen 0 1600x1200x24"`) so layouts and screenshots are reproducible.
* Don't compare screenshots across lanes or OSes; the software pipeline renders text differently ([limitations](../limitations.md)).
* Keep FXLoop out of your shipped artifact ([security](../security.md)).

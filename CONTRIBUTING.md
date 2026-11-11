# Contributing to FXLoop

## Build and test

```sh
git clone https://github.com/h9lxyz/fxloop.git && cd fxloop
./mvnw install                  # the FXLoop modules; UI tests run headless, no display needed
./mvnw install -Psamples        # plus the sample apps and their tests (what CI builds)
./mvnw install -Pacceptance     # plus the end-to-end MCP suite
```

On Windows, use `mvnw.cmd` and `gradlew.bat`.

## Build the server jar

The server bundles the Gradle plugin, which Gradle builds, so a jar that also works for Gradle apps takes three steps.
This needs three to five minutes; for Maven apps the first line is enough.

```sh
./mvnw -B install -DskipTests
./fxloop-gradle-plugin/gradlew -p fxloop-gradle-plugin publishToMavenLocal
./mvnw -B -q install -DskipTests -pl fxloop-server
```

The jar is `fxloop-server/target/fxloop-server-1.0.0-SNAPSHOT-all.jar`. Copy it to `~/.fxloop/fxloop-server.jar`, or
register that path with your agent instead.

Releases are cut by pushing a `x.y.z` tag. When you cut one, also bump the version in the README's build-plugin
quickstart, both in the download URL and in the `<plugin>` block; `ReadmeQuickstartTest` keeps the two equal.

Publishing a tag to Maven Central and the Gradle Plugin Portal is a separate, manual step: run the **Publish** workflow
with the tag as its version. It uploads a signed deployment under the `org.lab59` namespace, which you then publish at
[central.sonatype.com](https://central.sonatype.com/publishing/deployments). It needs the repository secrets
`CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET`.

## Generated files

`docs/tools.md` is generated from the tool registry
(`java -jar fxloop-server/target/fxloop-server-*-all.jar --tool-reference`); don't edit it by hand.

## Repository layout

| Module | What it is |
|---|---|
| `fxloop-server` | The stdio MCP server (shaded `-all.jar`): builds, launches, supervises, reloads |
| `fxloop-agent` | The in-JVM agent: scene graph, input, reload, FXML registry |
| `fxloop-protocol` | The protocol between server and agent, and the `launch.json` schema |
| `fxloop-launch` | Main-class and launch-mode detection shared by the server and both build plugins |
| `fxloop-dev` | Optional dev dependency for IDE-launched apps ([decision](docs/design/decisions/fxml-load-registry.md)) |
| `fxloop-maven-plugin` | `fxloop:run` and `fxloop:prepare` ([README](fxloop-maven-plugin/README.md)) |
| `fxloop-gradle-plugin` | Gradle plugin `org.lab59.fxloop`, a standalone Gradle build ([README](fxloop-gradle-plugin/README.md)) |
| `fxloop-skills` | The agent skills, shared references and evals ([README](fxloop-skills/README.md)) |
| `sample-apps/*` | Test apps, including `fxloop-showcase-app` with planted UI bugs |
| `fxloop-acceptance` | End-to-end suite that drives the server over MCP |

The tooling is written in Kotlin and targets Java 21 bytecode.

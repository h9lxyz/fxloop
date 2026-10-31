# Security

## Threat model

FXLoop is a **developer tool** that lets an MCP client (an LLM agent) inspect and drive a local app. It is built for one trusted user on one machine.

| Threat | Mitigation |
|---|---|
| Another local user/process connects to the agent and drives the app | FCP listens on **loopback only** (ephemeral port); every connection must present a 128-bit random token (`SecureRandom`), compared in **constant time**. |
| Token leaks via process list | The token is passed to the agent through an **owner-only token file** (`tokenFile=` agent arg), never on the command line. Tokens are redacted from logs. |
| Handshake file read by others | `handshake.json` and `token` are created `0600` (POSIX; on Windows the user-profile ACL applies). |
| Oversized/malicious frames | Hard **8 MB** frame cap in the FCP codec. |
| Screenshot flooding | Rate cap, default 2/s (`FXLOOP_SCREENSHOT_RATE`). |
| Agent shipped to production | See "Production guards" below. |
| Prompt-injected agent reads/writes arbitrary files | Tool file paths (screenshot/export outputs, references) are **confined to the project directory** (`BAD_REQUEST: path escapes the project directory`). |
| Silent hijack of running JVMs | `fx_attach` is explicit, audit-logged (`dev.fxloop.audit` logger: `AUDIT attach requested/attached pid=...`) and can be disabled with `-Dfxloop.attach=false` on the server. |
| Arbitrary script execution in the app | `fx_eval_js` is **off** unless `FXLOOP_WEBVIEW=true`. |
| Data exfiltration | **No telemetry.** The server makes no network calls of its own (build tools may fetch dependencies as usual). The optional live viewer (and the CSS inspector API on it) binds `127.0.0.1` and requires a token; it is read-only except two visual-only POSTs (`/api/highlight`, `/api/pick`) and is rate-limited. |

Out of scope: a malicious process running as the same OS user (it can read your files anyway), and untrusted MCP clients: anything connected to the MCP server can drive the app, so only configure it for agents you trust.

## Production guards

| Guard | Effect |
|---|---|
| `-Dfxloop.enabled=false` | Agent and embedded mode do nothing, regardless of arguments. |
| No `handshake=` agent argument | `-javaagent:fxloop-agent.jar` is a no-op (zero footprint): nothing starts, nothing listens. |
| `-Dfxloop.dev=true` | Required for `fxloop-dev` to auto-install via `ServiceLoader`; absent in production means no auto-install. |
| `-Dfxloop.attach=false` | (server) disables attach mode. |

## Scope guidance

`fxloop-dev` (and the plugins) must **never be on the runtime classpath of a shipped artifact**. Use `provided`/`compileOnly` or a dev-only profile:

```xml
<profile>
  <id>fxloop-dev</id>
  <dependencies>
    <dependency>
      <groupId>dev.fxloop</groupId><artifactId>fxloop-dev</artifactId>
      <version>1.0.0-SNAPSHOT</version><scope>provided</scope>
    </dependency>
  </dependencies>
</profile>
```

Gradle: `compileOnly("dev.fxloop:fxloop-dev:1.0.0-SNAPSHOT")`. The server-launched mode needs no dependency in your app at all: the agent is injected with `-javaagent`.

### Fail the production build if FXLoop leaks

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <executions>
    <execution>
      <id>no-fxloop-in-prod</id>
      <phase>package</phase>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <searchTransitive>true</searchTransitive>
            <excludes><exclude>dev.fxloop:*</exclude></excludes>
            <message>FXLoop must not ship in production; use a dev profile or 'provided' scope.</message>
          </bannedDependencies>
        </rules>
        <fail>true</fail>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Put this in a profile that is active for production packaging. Maven's `bannedDependencies` evaluates the resolved dependency tree; to allow `provided` usage in dev, keep the dev profile (with the `fxloop-dev` dependency) inactive when this rule runs.

### jlink / jpackage

A jlinked runtime without `java.instrument` cannot load the agent (`fx_attach` reports this). That is fine for production: ship without FXLoop, develop with a full JDK.

## Supply chain

* Release artifacts are signed with GPG via `./mvnw -Prelease deploy` (maven-gpg-plugin, sources and javadoc attached).
* The `release` profile generates a CycloneDX SBOM (`cyclonedx-maven-plugin`).
* `.github/dependabot.yml` keeps Maven, Gradle and GitHub Actions dependencies current.
* The agent shades and relocates Jackson, ByteBuddy and the Kotlin stdlib under `fxloop.internal` so it cannot clash with or be shadowed by your app's dependencies.

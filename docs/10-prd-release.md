# PRD 10 — Releases: one action, every artifact (Stage R)

**Status:** proposed (2026-11-02).

**Objective:** a release is one action that produces everything a user needs, in a form they can fetch without
cloning or building: a GitHub release with the server jar and the skills, the Maven artifacts on Central, the Gradle
plugin on the Plugin Portal, and release notes. `main` stays on a `-SNAPSHOT`, and a rolling **edge** build gives the
onboarding path (PRD 11) a downloadable jar before the first numbered release exists.

**Audience:** the maintainer cutting releases, and the onboarding path, which depends on predictable asset URLs.

**Entry gate:** none. This PRD is the entry gate for PRD 06 §3.1 ("published artifacts") and PRD 11.

---

## 1. Where we are

`release.yml` runs on a `v*` tag push: `./mvnw -Prelease deploy` (Central, signed, sources, javadoc, SBOM), then the
Gradle plugin's `publishPlugins`, then a GitHub release with `fxloop-server-*-all.jar` and the BOMs. It has never run:
there is no tag in the repository. Reading it against what a release actually needs:

| | Problem |
|---|---|
| **R1** | **The version is not connected to the tag.** The POMs say `1.0.0-SNAPSHOT`. Tagging `v1.0.0` would deploy a SNAPSHOT (Central rejects it) and attach `fxloop-server-1.0.0-SNAPSHOT-all.jar` to a release called 1.0.0. |
| **R2** | **The version lives in many files.** Root and module POMs, the Gradle plugin build and README, the sample Gradle builds, `jbang-catalog.json`, the Maven plugin README, `security.md`, the README badge and status line, and the `fxloop-connect` frontmatter (`SkillsLayoutTest` ties it to the server). A bump by hand misses one. |
| **R3** | **All or nothing.** If Central or the Plugin Portal step fails (a missing secret, the `dev.fxloop` namespace not verified, the portal's first-publish approval pending), the job stops and no GitHub release is created, although the jar is the artifact most users need. |
| **R4** | **No tests before publishing.** `deploy` runs unit tests, but not the `samples` or `acceptance` profiles that CI runs, and nothing checks the tagged commit was green. |
| **R5** | **Assets are incomplete and have no stable names.** No skills bundle, no checksums, no version-free asset name, so an installer cannot use `releases/latest/download/<name>`. |
| **R6** | **Pre-releases are not distinguished.** A `1.0.0-rc.1` would be marked *latest*. |
| **R7** | **The repository's home is ambiguous.** README, `mkdocs.yml`, `docs/index.md` and the Gradle plugin metadata point at `github.com/fxloop/fxloop`, which does not exist; the remote is `h9lxyz/fxloop`. Every release URL depends on this. |
| **R8** | **Nothing to try before a release.** There is no way to get a current jar without building. |

## 2. Recommendation

### 2.1 Trigger: `workflow_dispatch`, not a tag push

The workflow is started by hand with inputs `version` (e.g. `1.0.0-rc.1`, `1.0.0`) and `next` (the following
SNAPSHOT, defaulted from `version`). It writes the version everywhere (§2.3), commits `release: v<version>`, creates
the tag, builds and tests, publishes, creates the GitHub release, then writes `next`, commits `back to <next>` and
pushes both commits and the tag.

Why not a tag push: the version must be committed before the build (R1, R2), so a tag-triggered workflow needs a
manual bump commit first, which is exactly the error-prone part. The alternative of deriving the version from the tag
at build time (`versions:set`, never committed) keeps `main` clean of bot commits but leaves the tagged commit saying
SNAPSHOT, breaks `SkillsLayoutTest` unless the skill file is rewritten in the build too, and makes "which commit is
1.0.0" a question. Dispatch with committed versions is boring and reproducible, which is what a release should be.

A `v*` tag pushed by hand still runs the workflow in **re-release mode**: no version writes, build and test the tagged
commit, and (re)create the GitHub release and the publish stages that are not done yet. This is the recovery path when
a stage failed after the tag existed.

### 2.2 Stages, each gated on its own

| Stage | Runs when | Produces |
|---|---|---|
| **verify** | always | `./mvnw -B -Psamples install` on Linux JDK 21 plus the acceptance suite under Xvfb, and the Gradle plugin's `build`. The same commands as `ci.yml`, in one job, so a release can never ship what CI would reject. The full OS matrix is CI's job on `main`; the release job checks that the commit has a successful CI run (`gh run list --commit`) and fails if it has none. |
| **github-release** | verify passed | The release with the assets in §2.4. Pre-release when the version contains a hyphen (`rc`, `beta`). Always runs, so the jar is there even when publishing fails. |
| **maven-central** | verify passed **and** the Central secrets are set | `./mvnw -B -Prelease deploy -DskipTests`. The step reads the secrets into `env` and skips itself with a visible notice when they are empty (GitHub does not allow secrets in `if:` directly). |
| **gradle-plugin-portal** | verify passed **and** the portal secrets are set | `publishPlugins`. Same gating. |
| **docs** | github-release done | `mkdocs gh-deploy` of the tagged docs to GitHub Pages (a separate, tiny workflow that also runs on `main`). |
| **post** | always, last | Writes `next`, commits, pushes. Opens a tracking issue when any publish stage was skipped or failed, listing what to re-run. |

Stages that skip or fail never prevent the ones after them except as listed. The job summary shows one line per
stage: done / skipped (why) / failed.

### 2.3 One version, one script, one test

`release/set-version.sh <version>` (bash; the workflow runs on Linux) rewrites every location in R2. A new
`VersionConsistencyTest` in the server module reads the root POM version and asserts that every other location
agrees, so a forgotten file fails `./mvnw install`, not the release. The list of locations lives in one place: the
script, which the test calls with `--list`. The Gradle plugin and the sample Gradle builds keep literal versions (no
cross-build file reading); the script handles them.

Not chosen: Maven CI-friendly versions (`${revision}` + flatten plugin) would move the truth into `.mvn/maven.config`,
but Gradle, jbang and the docs still need rewriting, so it removes one file from the list and adds a plugin.

### 2.4 Assets and names

| Asset | Name | Why |
|---|---|---|
| Server (shaded, bundles the agent) | `fxloop-server-<v>-all.jar` **and** a copy `fxloop-server-all.jar` | The versioned name for humans; the version-free one for `releases/latest/download/fxloop-server-all.jar` in installers (PRD 11) |
| Skills | `fxloop-skills-<v>.zip` and `fxloop-skills.zip` | `fxloop-skills/skills/` zipped, so installing the skills does not need a clone |
| Checksums | `SHA256SUMS` | For installers and humans |
| SBOM | `fxloop-<v>-bom.json` (aggregate) | Already generated by the release profile |
| Provenance | GitHub artifact attestation (`actions/attest-build-provenance`) on the jar | One step; makes the jar's origin verifiable |

Release notes: `generate_release_notes: true` plus a fixed header with the tested matrix (JDKs, JavaFX versions, OSes
from CI) and the install lines from the README.

### 2.5 Edge builds (R8)

A workflow on every push to `main` that builds the server jar and the skills zip and attaches them to a rolling
pre-release tagged `edge` (assets replaced, tag moved to the commit, marked pre-release, never *latest*). The
onboarding installer uses `releases/download/edge/fxloop-server-all.jar` until a numbered release exists, and "try
the latest" users use it afterwards. Edge jars report their version as `<next>-SNAPSHOT+<short sha>` (from the
manifest) so a bug report says which commit it was.

A moving tag is unusual but common for this purpose (many CLIs do it); CI artifacts are not an alternative because
downloading them needs a GitHub login, which an installer cannot assume.

### 2.6 Version policy

- First numbered release: **`1.0.0-rc.1`**. The tracking document says the 1.0 feature set is done and the open items
  are publishing and the macOS/Windows lanes, which is what a release candidate is for. `1.0.0` follows once the
  onboarding acceptance (PRD 11 §8) passes on all three OSes from the published artifacts.
- Semantic versions; a hyphen marks a pre-release (GitHub pre-release flag, Central accepts them, the Plugin Portal
  accepts them).
- The skills pin an exact version (`fxloop-version` in `fxloop-connect`); the installer takes *latest*. The server
  warns at startup when the skill version and its own differ by more than a patch.
- Patch releases come from `release/<major.minor>.x` branches, dispatched from that ref; the workflow does not care
  which branch it runs on.

### 2.7 Prerequisites (outside the workflow)

| Item | Status | Needed by |
|---|---|---|
| Decide the canonical repository (`h9lxyz/fxloop` or create the `fxloop` org) and fix every URL (R7) | open | everything |
| Central Portal account, **namespace `dev.fxloop` verified** (DNS TXT on `fxloop.dev`, or use an `io.github.h9lxyz` namespace) | open | maven-central |
| `MAVEN_CENTRAL_USERNAME` / `_PASSWORD` (portal user token), `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` secrets | open | maven-central |
| Gradle Plugin Portal account, `GRADLE_PUBLISH_KEY` / `_SECRET`; first publish of `dev.fxloop` needs portal approval | open | gradle-plugin-portal |
| GitHub Pages enabled for the repository | open | docs |
| Workflow permission to push to `main` (branch protection: allow the Actions bot, or a fine-grained PAT as `RELEASE_TOKEN`) | open | post |

None of these blocks the GitHub release stage, which is the point of §2.2.

## 3. Workflow sketch

```yaml
name: Release
on:
  workflow_dispatch:
    inputs:
      version: { description: "Version to release, e.g. 1.0.0-rc.1", required: true }
      next:    { description: "Next development version; default bumps patch", required: false }
      dry_run: { type: boolean, default: false, description: "Everything except push, publish and release" }
  push:
    tags: ["v*"]            # re-release mode

jobs:
  prepare:   # set version (dispatch only), commit, tag; outputs version, prerelease, mode
  verify:    # needs prepare; samples + acceptance + gradle plugin build; checks CI status of the commit
  github:    # needs verify; builds assets, SHA256SUMS, attestation; softprops/action-gh-release with prerelease flag
  central:   # needs verify; if: env.HAVE_CENTRAL == 'true'; ./mvnw -B -Prelease deploy -DskipTests
  portal:    # needs verify; if: env.HAVE_PORTAL == 'true'; gradlew publishPlugins
  post:      # needs [github, central, portal]; if: always(); set next version, push; open issue on skipped/failed
```

`dry_run` runs every stage with `publish`, `push` and `release` replaced by *would do*, and uploads the assets as a CI
artifact so the output can be inspected. The first real use of the workflow is a dry run.

## 4. Acceptance

- **A-R1 Dry run.** `dry_run=true` with `version=1.0.0-rc.1` passes end to end on `main`; the artifact contains the
  renamed jar, the skills zip, `SHA256SUMS`, and the job summary shows every stage.
- **A-R2 Consistency.** `set-version.sh 9.9.9` followed by `./mvnw -q -pl fxloop-server test -Dtest=VersionConsistencyTest`
  passes; reverting one file by hand makes it fail with that file's name.
- **A-R3 Edge.** A push to `main` updates the `edge` pre-release; `curl -L releases/download/edge/fxloop-server-all.jar`
  from a machine with no clone runs `java -jar … --tool-reference` and prints the reference.
- **A-R4 rc.1.** The real `1.0.0-rc.1` release exists with every asset; `java -jar fxloop-server-1.0.0-rc.1-all.jar`
  launches the sample app through `fx_get_app_status` on a clean machine with no `~/.m2` entries for `dev.fxloop`.
- **A-R5 Publish.** Once the prerequisites exist: `mvn fxloop:run` on the sample app resolves the plugin from Central
  with an empty local repository; `id("dev.fxloop") version "1.0.0-rc.1"` resolves from the Plugin Portal.
- **A-R6 Recovery.** With the portal secret removed, the workflow still produces the GitHub release, marks the portal
  stage skipped, and opens the tracking issue; pushing the same tag again (re-release mode) with the secret restored
  publishes the plugin and changes nothing else.

## 5. Milestones

1. **Edge + assets** (§2.4, §2.5): `edge.yml`, asset naming, checksums. Unblocks PRD 11 immediately. ~1 day.
2. **Version script + test** (§2.3). ~half a day.
3. **Release workflow** (§2.1–2.2, §3) with dry run; cut `1.0.0-rc.1` to GitHub only. ~1–2 days.
4. **Publishing** (§2.7): namespace, accounts, secrets; re-release `rc.1` to Central and the Portal. Calendar time,
   not work.

## 6. Risks

- **Bot commits on `main`** need branch protection to allow them. If that is not wanted, the `post` stage opens a PR
  for the `next` bump instead of pushing (one more click per release).
- **Moving `edge` tag** confuses `git describe` on checkouts that fetched it; document `git fetch --no-tags` for
  contributors who care, or name it `edge` only as a release and not as a git tag (`action-gh-release` needs a tag; keep
  the tag).
- **Central validation** rejects artifacts with missing javadoc for Kotlin modules in some setups; the release profile
  already attaches javadoc jars with `doclint none`; the dry run through `-Prelease verify` (no deploy) catches the rest.
- **Gradle plugin build** resolves `fxloop-protocol` from `mavenLocal()`; the release job's `verify` stage installs it
  first, as CI does. Once on Central the plugin build can drop `mavenLocal()`.

## 7. Non-goals

Native installers or `jpackage` images; a Homebrew, Scoop or winget package (revisit after 1.0 when the install line
in PRD 11 has been used for a while); automatic releases on a schedule; signing with Sigstore beyond GitHub's
attestation.

## 8. Open questions

1. Repository home: keep `h9lxyz/fxloop` and fix the links, or create the `fxloop` organisation now, before any URL is
   in a published artifact? (Recommendation: decide before milestone 1, since the edge URL goes into PRD 11's installer.)
2. Namespace: `dev.fxloop` requires owning `fxloop.dev`; is the domain registered? If not, `io.github.h9lxyz` is
   verifiable in minutes, at the cost of renaming the group id everywhere.
3. Should the `post` stage push or open a PR? (Default: push, with the Actions bot allowed on `main`.)

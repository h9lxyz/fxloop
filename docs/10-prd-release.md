# PRD 10 — Releases: push a tag, get a GitHub release (Stage R)

**Status:** implemented (2026-11-04); not yet exercised by a real tag.

**Objective:** pushing a tag `<major>.<minor>.<patch>` produces a GitHub release with the server jar and the skills. The version
comes from the tag at build time; the code stays on `1.0.0-SNAPSHOT` and no commit is made by the workflow. That is
the whole release process. Maven Central and the Gradle Plugin Portal are a later, separate step.

**Audience:** the maintainer, and the onboarding path (PRD 11), which downloads the jar from the release.

---

## 1. Where we are

`release.yml` runs on `v*` tags and has never run (no tag exists). It would build the committed `1.0.0-SNAPSHOT`,
try to deploy it to Central (which rejects SNAPSHOTs and has no credentials yet), and stop before the GitHub release.

## 2. How it works

```
git tag 0.0.1 && git push origin 0.0.1
```

The workflow, on `push: tags: ["[0-9]+.[0-9]+.[0-9]+"]` (only that shape; any other tag is ignored):

1. `VERSION=$GITHUB_REF_NAME`.
2. `./mvnw -B -q versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false` — sets the version across the reactor
   in the checkout only; nothing is committed. The server's `Versions.SERVER` reads the jar manifest, so the built
   server reports `$VERSION` with no code change.
3. `./mvnw -B -DskipTests install` — CI already tested this commit on push; the release does not test again.
4. `./fxloop-gradle-plugin/gradlew -p fxloop-gradle-plugin -PfxloopVersion=$VERSION publishToMavenLocal`, then
   `./mvnw -B -q -DskipTests install -pl fxloop-server` — the plugin build reads `fxloopVersion` when given, else its
   committed default, and the server is rebuilt so its jar bundles that plugin (PRD 11 §4.2).
5. Zip `fxloop-skills/skills/` into `fxloop-skills.zip`, with `fxloop-version: $VERSION` substituted in
   `fxloop-connect/SKILL.md` so the skill pins the release it ships with.
6. `softprops/action-gh-release` with the changelog from §4 as the body and the assets below.

## 3. Assets

| Asset | Why |
|---|---|
| `fxloop-server-all.jar` | the server, bundling the agent |
| `fxloop-skills.zip` | the skill folders |
| `SHA256SUMS` | checksums of the above |

The names carry no version, and each file is published once. `releases/latest/download/fxloop-server-all.jar` is then a
stable URL for the install guide and the connect skill, and `releases/download/<version>/fxloop-server-all.jar` pins a
release; the jar's `--version` says which one a downloaded file is.

## 4. Changelog

The release notes are generated from the commits between the previous tag and this one. GitHub's own
`generate_release_notes` is not used: it lists merged pull requests only, and most of this repository's history is
direct commits on `main` (97 of the last 100), which it would drop.

The commits already follow the conventional prefixes (`feat:`, `fix:`, `docs:`, `build:`, `ci:`, `test:`,
`refactor:`), so a few lines of shell in the workflow group them:

```sh
PREV=$(git describe --tags --abbrev=0 "$GITHUB_REF_NAME^" 2>/dev/null)   # empty on the first release
RANGE=${PREV:+$PREV..}$GITHUB_REF_NAME
section() { git log --no-merges --pretty='- %s (%h)' "$RANGE" | grep -E "^- $1(\(.*\))?!?: " | sed -E "s/^- $1(\(.*\))?!?: /- /"; }
{
  echo "## Features";  section feat
  echo "## Fixes";     section fix
  echo "## Docs";      section docs
  echo "## Other";     git log --no-merges --pretty='- %s (%h)' "$RANGE" | grep -vE '^- (feat|fix|docs)(\(.*\))?!?: '
  echo; echo "**Full changelog:** https://github.com/<owner>/fxloop/compare/$PREV...$GITHUB_REF_NAME"
} > notes.md
```

Empty sections are dropped; a scope in the prefix (`feat(inspector): …`) is kept in the line. A `!` after the
prefix or a `BREAKING CHANGE:` footer puts the line under a **Breaking** heading at the top. No `CHANGELOG.md` is
committed: the release page is the changelog, and `git log` between tags is the source of truth. If the grouping
ever needs more than this (links to issues, contributors), `git-cliff` reads the same prefixes and replaces the
script; not before.

## 5. What this needs in the repository

- `release.yml` rewritten to §2 (the Central and Plugin Portal steps removed).
- `fxloop-gradle-plugin/build.gradle.kts`: `version = (findProperty("fxloopVersion") ?: "1.0.0-SNAPSHOT").toString()`
  and the same for its `fxloop-protocol` and `fxloop-launch` dependencies.
- Every URL that says `github.com/fxloop/fxloop` changed to the real repository, since the release URL goes into the
  README and the skill.

Nothing else changes. `SkillsLayoutTest` keeps tying the committed skill version to the committed server version
(both SNAPSHOT); the substitution in step 5 happens in the zipped copy only.

## 6. Known limitation

A released server invokes `dev.fxloop:fxloop-maven-plugin:<v>:prepare`, which exists in no repository until Central
publishing. Until then the server's plugin-free prepare (PRD 11 §4.2) or a locally installed plugin is what makes a
release jar launch an app.

## 7. Later: Maven Central and the Gradle Plugin Portal

When the `dev.fxloop` namespace is verified and the secrets exist, a second workflow (or a `workflow_dispatch` input
on this one) runs `./mvnw -B -Prelease deploy` and `publishPlugins` with the same on-the-fly version. It is deliberately
not part of the tag workflow, so a publishing problem can never block the GitHub release.

## 8. Acceptance

- **A-R1.** Push `1.0.0`: the release exists, has the two assets and the checksums;
  `java -jar fxloop-server-all.jar --version` prints `1.0.0`; `git status` on `main` is unchanged.
- **A-R2.** `curl -L .../releases/latest/download/fxloop-server-all.jar` on a machine with no clone downloads the jar
  and `--tool-reference` prints the reference.
- **A-R3.** The skills zip's `fxloop-connect/SKILL.md` says `fxloop-version: 1.0.0`; the repository's copy still
  says `1.0.0-SNAPSHOT`.
- **A-R4.** The release body lists every non-merge commit since the previous tag under Features, Fixes, Docs or
  Other, with the compare link; on the first release the range is the whole history.

## 9. Non-goals

Pre-releases (`rc`, `beta`) and `v`-prefixed tags, bot commits, version bumps in the code, a rolling edge build, native installers, package managers, signing beyond
what Central will later require.

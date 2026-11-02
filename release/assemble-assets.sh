#!/usr/bin/env bash
# Assembles the downloadable assets (PRD 10 §2.4) into <out dir> (default: dist/):
#   fxloop-server-<version>-all.jar and fxloop-server-all.jar   the stdio MCP server, agent bundled
#   fxloop-skills-<version>.zip     and fxloop-skills.zip       fxloop-skills/skills/fxloop-* at the zip root
#   SHA256SUMS
# The version-free names are what the README and INSTALL.md download (`releases/download/edge/...` and later
# `releases/latest/download/...`). Run after `./mvnw install -DskipTests`; for a jar that can prepare Gradle projects
# without the plugin (PRD 11 §4.2), also after `gradlew -p fxloop-gradle-plugin publishToMavenLocal` and a server rebuild
# (the order in .github/workflows/edge.yml). Fails when the jar lacks the bundled Gradle plugin.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-dist}
mkdir -p "$out"
out=$(cd "$out" && pwd)

jar=$(find "$root/fxloop-server/target" -maxdepth 1 -name 'fxloop-server-*-all.jar' | head -n 1)
if [ -z "$jar" ]; then
  echo "assemble-assets: no fxloop-server/target/fxloop-server-*-all.jar; run ./mvnw install -DskipTests first" >&2
  exit 1
fi
version=$(basename "$jar" | sed -E 's/^fxloop-server-(.*)-all\.jar$/\1/')
if ! unzip -l "$jar" dev/fxloop/server/gradle/fxloop-gradle-plugin.jar >/dev/null 2>&1; then
  echo "assemble-assets: $jar lacks the bundled Gradle plugin; run ./fxloop-gradle-plugin/gradlew -p fxloop-gradle-plugin publishToMavenLocal and ./mvnw install -DskipTests -pl fxloop-server" >&2
  exit 1
fi

rm -f "$out"/fxloop-server-*.jar "$out"/fxloop-skills*.zip "$out/SHA256SUMS"
cp "$jar" "$out/fxloop-server-$version-all.jar"
cp "$jar" "$out/fxloop-server-all.jar"
(cd "$root/fxloop-skills/skills" && zip -qr "$out/fxloop-skills-$version.zip" fxloop-*)
cp "$out/fxloop-skills-$version.zip" "$out/fxloop-skills.zip"
(cd "$out" && sha256sum fxloop-server-*.jar fxloop-skills*.zip > SHA256SUMS)

echo "assemble-assets: version $version in $out"
ls -l "$out"

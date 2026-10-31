#!/usr/bin/env sh
# Copies the shared references/ into every skill that links them, so each skills/fxloop-*/ folder is self-contained
# and can be installed on its own. Run after editing references/; SkillsLayoutTest fails while a copy is stale.
set -eu
cd "$(dirname "$0")"
for skill in skills/*/; do
  for shared in references/*.md; do
    name=$(basename "$shared")
    if grep -q "](references/$name)" "$skill/SKILL.md"; then
      mkdir -p "$skill/references"
      cp "$shared" "$skill/references/$name"
    elif [ -f "$skill/references/$name" ]; then
      rm "$skill/references/$name"
    fi
  done
done

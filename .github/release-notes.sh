#!/usr/bin/env bash
# Prints the release notes for tag $1: the commits since the previous tag, grouped by conventional prefix (PRD 10 §4).
set -euo pipefail

tag=$1
prev=$(git describe --tags --abbrev=0 "$tag^" 2>/dev/null || true) # empty on the first release
range=${prev:+$prev..}$tag

breaking='' features='' fixes='' docs='' other=''
while read -r hash subject; do
  # A scope stays in the line: `feat(inspector): x` becomes `inspector: x`.
  if [[ $subject =~ ^([a-z]+)(\(([^\)]*)\))?(!)?:\ (.*)$ ]]; then
    type=${BASH_REMATCH[1]} scope=${BASH_REMATCH[3]} bang=${BASH_REMATCH[4]} text=${BASH_REMATCH[5]}
  else
    type='' scope='' bang='' text=$subject
  fi
  line="- ${scope:+$scope: }$text ($hash)"$'\n'
  if [[ -n $bang ]] || git log -1 --format=%b "$hash" | grep -q '^BREAKING CHANGE:'; then
    breaking+=$line
  else
    case $type in
      feat) features+=$line ;;
      fix) fixes+=$line ;;
      docs) docs+=$line ;;
      *) other+="- $subject ($hash)"$'\n' ;;
    esac
  fi
done < <(git log --no-merges --format='%h %s' "$range")

section() { if [[ -n $2 ]]; then printf '## %s\n\n%s\n' "$1" "$2"; fi; }
section Breaking "$breaking"
section Features "$features"
section Fixes "$fixes"
section Docs "$docs"
section Other "$other"
if [[ -n $prev ]]; then
  echo "**Full changelog:** https://github.com/h9lxyz/fxloop/compare/$prev...$tag"
fi

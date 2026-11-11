# FXLoop skills (contributor view)

The ten agent skills FXLoop ships for improving the UI and UX of a JavaFX app. They are plain `SKILL.md` folders in the
open skills format. Users install and use them as described in [docs/skills.md](../docs/skills.md); this page is for
people changing them.

## Layout

| Path | What |
|---|---|
| `skills/fxloop-*/` | The installable skills. `fxloop-connect` also carries the MCP config reference and the build-file snippets |
| `references/` | Shared reference docs: evidence rule, JavaFX CSS, layout containers, FX thread, accessibility, FXML, TestFX, Kotlin notes |
| `sync-references.sh` | Copies each shared reference into the skills that link it. Run it after editing `references/` |
| `evals/` | One eval file per skill (skill-creator format plus a `setup` field naming the sample app and flags) and `triggers.json` (should/shouldn't trigger phrasings) |

## Keeping references in sync

Each skill folder must be self-contained, so shared references are copied into every skill that links them. Edit
`references/`, then run `fxloop-skills/sync-references.sh`. `SkillsLayoutTest` (server module) fails when a bundled copy
is stale, a skill links outside its own folder, or the versions in `fxloop-connect` drift from the server.

## Running the skill evals

Each `evals/<skill>.json` lists tasks to give an agent that has the skill installed; the `setup` field names the sample
app and flags to launch (`--seed`, `--broken-css`, gauntlet tabs). `triggers.json` holds phrasings that should and
should not trigger each skill. The demo bed is `sample-apps/fxloop-showcase-app`, whose answer key is its `ISSUES.md`;
don't give that file to the agent.

---
name: responsive
description: Test and fix how a running JavaFX app copes with small and large windows, long translated strings, RTL and HiDPI, using FXLoop to resize the stage through a size ladder and lint every frame. Use when the developer asks "does it work on a small screen / 1366x768 laptop", "what happens when I resize", "long German strings break it", "test right-to-left", "HiDPI looks wrong" for a JavaFX/Kotlin desktop app.
---

# Responsive

References: [layout-containers.md](../../references/layout-containers.md), [evidence.md](../../references/evidence.md).

## Procedure

1. **Baseline:** `fx_get_property name=window.width` / `window.height` (on any node of the window, e.g.
   `query=".root"`) → remember them for the restore. Also read `window.minWidth`/`minHeight`, and take
   `fx_take_screenshot label=resp-base`.
2. **Size ladder.** The default is `1600×1000`, `1366×768`, `1280×720`, `1024×640`, `800×600`, plus the developer's
   own target size. For each size:
   - `fx_set_property name=window.width value=<w>` and `name=window.height value=<h>` (on any node in the window).
     Read back `window.width`: if the stage enforces a minimum it won't go lower, and that is itself a finding (good
     or bad).
   - `fx_take_screenshot label=w<w>x<h> includeImage=false`, then `fx_lint_visual` (truncation, overlap,
     outOfScene).
   - Look at the images only for sizes whose lint changed or that the developer cares about (`fx_get_screenshot
     seq=...`).
   Then restore the baseline size.
3. **Long strings:** if the app has locales (a `ResourceBundle`, `--lang`/`--de`-style flags, `Locale` handling),
   restart with the longest locale. Change `app-args` in `fxloop.properties` and reconnect the MCP server, or ask
   the developer. Then run the ladder subset `1366×768` and `1024×640`. Without locales, stress test: `fx_set_text` /
   `fx_set_property name=text` on key labels and buttons with a 40 % longer string (keep a copy of the original and
   restore it, or `fx_restart_app`).
4. **RTL:** if the app supports it (a flag or a setting), restart with RTL and lint at one size. Otherwise
   `fx_set_property query=".root" name=nodeOrientation value=RIGHT_TO_LEFT` live, screenshot and lint, then set it
   back to `LEFT_TO_RIGHT`. Look for absolute positions that land off-screen (`outOfScene`) and icons that should
   mirror.
5. **HiDPI:** FXLoop can't change the OS scale. Check for symptoms instead: fixed pixel sizes on text containers,
   bitmap icons without `@2x` variants, `-fx-font-size` in px on many rules rather than one `.root` size. Report
   it as a code finding, "not verified at 150 %/200 %".
6. **Fix:** stage `minWidth`/`minHeight` at the smallest working size, grow constraints instead of fixed sizes,
   `minWidth = USE_PREF_SIZE` on labels that must not ellipsize, wrapping (`wrapText`) for long labels, a
   `ScrollPane` for tall forms, `CONSTRAINED_RESIZE_POLICY` on tables, real layout instead of absolute positions.
   Then re-run the ladder.

## Output

A size matrix: one row per size, one column per check, cells hold finding counts with the frame path. Then the
fixes and the matrix after them:

```
| Size | truncated | overlap | offscreen | frame |
| 1366×768 | 0 | 0 | 0 | .../12-w1366x768.png |
| 1024×640 | 7 → 0 | 1 → 0 | 0 | .../15-w1024x640.png |
min size now 900×600 (was none) · German at 1024×640: 47 → 3 truncations (remaining: <nodes, why acceptable>)
```

Always restore the window size and orientation you changed. When you restarted with other `app-args`, put
`fxloop.properties` back and tell the developer to reconnect the server.

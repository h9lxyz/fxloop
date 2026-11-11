# Decision: FXML load registry (F4)

**Status:** accepted; Plan A and Plan B both shipped. **PRD:** 02 §4.

## Problem

To live-reload an edited `.fxml`, the agent must know which loaded scene-graph root and controller came from which FXML file. JavaFX keeps no such registry.

## Plan A: ByteBuddy retransform of `FXMLLoader` (default)

`FxmlHook` (in `fxloop-agent`) installs a ByteBuddy `AgentBuilder` retransformation on `javafx.fxml.FXMLLoader` with **exit advice on the instance `load()`** (zero-arg, non-static). All static `load(...)` variants delegate to an instance, so one hook covers them. The advice records `location`, returned root and controller into `FxmlRegistry`.

| Property | How |
|---|---|
| Retransform-safe | `disableClassFormatChanges()` + retransformation strategy: advice adds **no fields or methods**, so it works on already-loaded classes |
| Module path | `assureReadEdgeTo(inst, FxmlRegistry)`: the `javafx.fxml` module is made to read the agent's registry class |
| Safe in the app | Advice is `suppress = Throwable`, references only public types, and parameters are nullable so no Kotlin null-check intrinsics are inlined into `FXMLLoader` |
| Failure mode | If installation fails the agent logs a warning and continues; Plan B still works |

**Disable:** agent argument `fxmlHook=false` (default `true`). Embedded mode installs the hook only when an `Instrumentation` is available.

## Plan B: `FxLoopDev.track` / `FxLoopDev.load` (public API)

`fxloop-dev` exposes `FxLoopDev.track(loader)` and `FxLoopDev.load(location)`, which register the same mapping explicitly. It is the escape hatch when instrumentation is unavailable (embedded apps without `-javaagent`, jlinked runtimes, custom loader subclasses that override `load`), and `FxLoopDev.install()` also starts the in-process agent for IDE-launched apps (`fx_connect_existing`).

## Outcome

* Documents loaded through `FXMLLoader` are swapped live by `fx_reload` (tier `fxml`) with no code in the app.
* Anything not registered returns `FXML_NOT_REGISTERED` / `unregistered`, and `fx_reload` falls back to restart with replay.
* Both plans write to the same registry, so they can be mixed.

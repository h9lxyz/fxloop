# Limitations

## Native dialogs

`FileChooser`, `DirectoryChooser` and OS print dialogs are native windows outside the JavaFX scene graph; the agent can neither see nor click them, and a modal native dialog blocks the FX thread. Make them replaceable and mock them in dev:

```java
public final class Dialogs {
    /** Test/dev seam: swap with a lambda that returns a fixed file. */
    public static volatile Function<Window, File> openFile = w -> {
        FileChooser fc = new FileChooser();
        return fc.showOpenDialog(w);
    };
    private Dialogs() {}
}

// in the controller
File f = Dialogs.openFile.apply(stage);

// in the dev launcher / fixture (e.g. behind --seed)
Dialogs.openFile = w -> new File("src/test/resources/sample.csv");
```

JavaFX-implemented dialogs (`Alert`, `Dialog`, `ChoiceDialog`) appear as windows of kind `DIALOG` and are fully drivable.

## Hot reload (HotSwap)

| Edit | Result |
|---|---|
| Method body change | Swapped live (tier `hotswap`) |
| Code-built view wrapped in `FxLoopDev.view` | Re-rendered in place after the swap (tier `remount`) |
| CSS change | Re-applied live, with `visualDiff` |
| FXML change (document loaded via `FXMLLoader`/`FxLoopDev`) | Swapped live; unregistered FXML triggers restart |
| Add/remove methods or fields, change signatures | `REDEFINE_REJECTED` then restart (+ replay) |
| Class hierarchy / interfaces / modifiers change, new classes needing existing-class edits | Restart |
| Annotation processors, build file (`pom.xml`, `build.gradle*`) changes | Restart (build-tool fallback compile) |
| Compile error | Returned as data (`compileErrors`); nothing swapped, no restart |

Plain JVM HotSwap only changes method bodies, so adding a lambda (a new synthetic method), a controller method or a field restarts the app. Launch on JetBrains Runtime (`FXLOOP_JAVA_HOME` pointing at a JBR) and fxloop detects it and adds `-XX:+AllowEnhancedClassRedefinition`, so those structural changes are hot-swapped instead; `FXLOOP_JBR=false` opts out, `FXLOOP_JBR=true` forces the flag. Enhanced redefinition is a JBR feature, not guaranteed by FXLoop.

A restart skips the build-tool prepare (`prepare: "skipped"`) when every edit since the last reload is already in the output directory (compiled and swapped, or copied, by a reload or by watch mode), so only the JVM is relaunched. This applies to reload restarts, `fx_restart_app` and the dev bar's Restart. A changed build file, a deleted file, or edits no reload has applied yet (including everything under `FXLOOP_EXTERNAL_COMPILE`) run the full prepare.

With watch mode `all`, an edit the watcher already hot-swapped is not compiled again by `fx_reload` (and the reverse); the result lists it under `alreadyApplied`.

Existing instances keep their state; code in constructors or static initialisers does not re-run after a swap. Restart with replay (`FXLOOP_REPLAY`) re-applies recorded actions to get back to the same screen.

### Live dev loop

* **Code-built views:** only views wrapped in `FxLoopDev.view` re-render after a HotSwap. UI built in `start()`, constructors or `initialize()` changes only when that code runs again. See [fxloop-dev](configuration.md#live-dev-loop-fxloop-dev-api).
* **View state is carried over, model state is not:** text, caret and selection, list/table/tree selections, scroll positions, selected tab, expanded panes, toggles and focus are restored onto the new nodes. Controller, model and service fields are not: the FXML controller is re-created and a remounted factory runs again from scratch. `fx_reload{carryState:false}` opts out.
* **Restoring state can fire listeners:** setting a selection or tab can run app listeners, which may load data or call services again.
* **Remount can leak external resources:** a view holding a `WebView`, media player or similar may leave the old instance running. Release it in an `FxLoopDev.onReload` callback.
* **Human and agent input are told apart by timing:** a human click during an agent action may be missed or counted as a conflict (`conflicts`, `skippedHuman`).
* **Shared control is synthetic:** in `shared` control, actions use synthetic events, which skip hover, real picking and some native behaviour. Re-check critical flows in `agent` mode before trusting a result.
* **The dev bar is an overlay window:** on Wayland, macOS fullscreen spaces and tiling window managers it can fall back to a corner pill or be off. The same state is always in `fx_get_app_status`.
* **Restage is experimental:** `FXLOOP_RESTAGE` re-runs `start()`, which may start services or connections twice.

## Input and inspection

* **Typing:** `fx_type_keys` via the Robot depends on the keyboard layout and focus. Use `fx_set_text` (programmatic) for text; it fires the same property listeners but not key events.
* **Pseudo-state capture:** `fx_capture_states` temporarily forces `:hover`, `:focused`, `:pressed`, ... on a node and restores them in a `finally`; effects that depend on real input or animations may differ from true interaction.
* **`fx_explain_style`:** reports the rule-level winner and the overridden declarations per property, each with `file:line` (or `(set in code)` / inline), from a cascade reconstructed through the public `javafx.css` API. Each property is verified against the live value and origin (`simulation: ok|mismatch`); a `mismatch` is reported, not hidden. Approximations (see [PRD 08 §9](08-prd-css-inspector.md#9-risks-and-known-approximations)):
  * Popups and context menus take their stylesheets from the owner window; SubScene user-agent sheets are not modeled exactly.
  * Platform-specific Modena additions (touch, high contrast) are not modeled.
  * Looked-up colors (`derive()`, `.root` lookups) are resolved one level only; no derive() chains.
  * `.bss` binary sheets have no line numbers.
  * Preprocessed CSS (SCSS, generated sheets) points at the generated `.css`.
  * The value check covers simple types only (paints, numbers, insets, booleans, enums, strings).
* **`fx_explain_property`:** shows the binding dependency tree of a property; for bidirectional bindings the peer property is unknown.
* **Logs:** app logs are captured through `java.util.logging`; SLF4J is captured only via a JUL bridge (`slf4j-jdk14`/`jul-to-slf4j` setups); other frameworks appear only if they write to the process stdout/stderr (`fx_get_logs source=process`, optionally `stream=stdout|stderr`).

## Modes

* **Attach** (`fx_attach`): no reload tiers and no restart/stop (the JVM is not ours); input, inspection and screenshots work. JDK 21+ prints a dynamic-agent warning unless the target runs with `-XX:+EnableDynamicAgentLoading`.
* **Embedded** (`FxLoopDev.install()` + `fx_connect_existing`): same as attach regarding restart.
* **Developer-run** (`fxloop:run`, `fxloopRun`): no restart or stop (the build tool owns the JVM); reloads, input,
  inspection and screenshots work. Connected automatically on the server's first call.

## Dev bar and overlays

The dev bar, the highlight outline and the pick outline are agent-owned popups. They are marked `fxloop.overlay` and excluded from window lists, screenshots, paths and the scene graph, so tools never see them. Their real-display behavior (focus, stacking, multi-monitor, HiDPI, Windows and macOS) is verified only by the Linux headless tests; treat it as unverified until checked on a desktop.

## Headless

Monocle/Xvfb render with the software pipeline (`prism.order=sw`): fonts, antialiasing and some effects differ pixel-wise from a real GPU desktop, so do not compare screenshots across lanes. Monocle has no native chooser dialogs and no real window decorations; JavaFX lines without a known Monocle build must use Xvfb ([details](ci/monocle.md)).

## Size

The agent jar is about 6 MB (ByteBuddy + Jackson + Kotlin stdlib, relocated). It is only loaded in the app JVM you run under FXLoop.

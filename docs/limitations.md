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
| CSS change | Re-applied live, with `visualDiff` |
| FXML change (document loaded via `FXMLLoader`/`FxLoopDev`) | Swapped live; unregistered FXML triggers restart |
| Add/remove methods or fields, change signatures | `REDEFINE_REJECTED` then restart (+ replay) |
| Class hierarchy / interfaces / modifiers change, new classes needing existing-class edits | Restart |
| Annotation processors, build file (`pom.xml`, `build.gradle*`) changes | Restart (build-tool fallback compile) |
| Compile error | Returned as data (`compileErrors`); nothing swapped, no restart |

Plain JVM HotSwap only changes method bodies. With JetBrains Runtime you can opt in to enhanced redefinition: launch on JBR (`FXLOOP_JAVA_HOME`) and set `FXLOOP_JBR=true` (fxloop then adds `-XX:+AllowEnhancedClassRedefinition`). Enhanced redefinition is a JBR feature, not guaranteed by FXLoop.

Existing instances keep their state; code in constructors or static initialisers does not re-run after a swap. Restart with replay (`FXLOOP_REPLAY`) re-applies recorded actions to get back to the same screen.

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

## Dev bar and overlays

The dev bar, the highlight outline and the pick outline are agent-owned popups. They are marked `fxloop.overlay` and excluded from window lists, screenshots, paths and the scene graph, so tools never see them. Their real-display behavior (focus, stacking, multi-monitor, HiDPI, Windows and macOS) is verified only by the Linux headless tests; treat it as unverified until checked on a desktop.

## Headless

Monocle/Xvfb render with the software pipeline (`prism.order=sw`): fonts, antialiasing and some effects differ pixel-wise from a real GPU desktop, so do not compare screenshots across lanes. Monocle has no native chooser dialogs and no real window decorations; JavaFX lines without a known Monocle build must use Xvfb ([details](ci/monocle.md)).

## Size

The agent jar is about 6 MB (ByteBuddy + Jackson + Kotlin stdlib, relocated). It is only loaded in the app JVM you run under FXLoop.

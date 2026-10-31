# Kotlin notes

UI/UX fixes are the same in Kotlin: CSS, FXML, screenshots and the explain tools don't care about the language. The
differences are where the fix goes and how the loop runs.

## Where the fix goes

| Java | Kotlin |
|---|---|
| `@FXML private TextField nameField;` | `@FXML private lateinit var nameField: TextField` (the annotation lands on the field) |
| `@FXML void save(ActionEvent e)` | `@FXML fun save(e: ActionEvent)` or `@FXML fun save()` |
| `field.setText(x)` / `getText()` | `field.text = x` / `field.text` (synthetic properties) |
| `btn.disableProperty().bind(...)` | the same; `btn.disableProperty().bind(list.selectionModel.selectedItemProperty().isNull)` |
| `HBox.setHgrow(n, Priority.ALWAYS)` | the same (static call) |
| `Platform.runLater(() -> ...)` | `Platform.runLater { ... }`, or coroutines with `Dispatchers.JavaFx` (fx-threading.md) |
| `public class Launcher { main }` | top-level `fun main(args: Array<String>)` in `Main.kt` → main class **`MainKt`** (`<package>.MainKt`) |

- An injection failure shows up as `UninitializedPropertyAccessException: lateinit property x has not been
  initialized`. Usually it's an fx:id/field name mismatch; run `fx_lint_fxml`.
- `Application` subclasses need a public no-arg constructor (a plain `class App : Application()` is fine).
- Prefer `ObservableList` from `FXCollections.observableArrayList<T>()` over Kotlin lists for anything a control
  shows.

## How the loop runs

- CSS and FXML edits reload live, exactly as in Java.
- `.kt` edits: `fx_reload` compiles through the build tool (Gradle `classes` / Maven `compile`, a few seconds) and
  then hot-swaps the changed classes. A structural change (new property, method or class shape) restarts with
  journal replay.
- Fastest loop: let the IDE auto-build, or run `./gradlew -t classes`, and set `external-compile=true` in
  `fxloop.properties` (then reconnect the fxloop MCP server). FXLoop then just hot-swaps whatever the IDE wrote to
  the classes directory.
- `fx_export_test` writes Java. Keep it if the build compiles `src/test/java` (the Gradle `java` plugin does; Maven
  needs the compiler plugin's `testCompile`), or port it to `src/test/kotlin`.

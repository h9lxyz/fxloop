# TestFX: locking UI behavior in

## Skeleton (JUnit 5)

```java
@ExtendWith(ApplicationExtension.class)
class AssignTicketTest {
    @Start
    void start(Stage stage) throws Exception {
        new TicketDeskApp().start(stage);   // the Application subclass; pass seed flags via a system property if needed
    }

    @Test
    void assignRequiresASelection(FxRobot robot) {
        Button assign = robot.lookup("#assignBtn").queryButton();
        assertThat(assign.isDisabled()).isTrue();
        robot.clickOn("#ticketTable .table-row-cell");          // select the first row
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(assign.isDisabled()).isFalse();
    }
}
```

Kotlin variant:

```kotlin
@ExtendWith(ApplicationExtension::class)
class AssignTicketTest {
    @Start fun start(stage: Stage) = TicketDeskApp().start(stage)

    @Test fun assignRequiresASelection(robot: FxRobot) {
        val assign = robot.lookup("#assignBtn").queryButton()
        assertThat(assign.isDisabled).isTrue()
        robot.clickOn("#ticketTable .table-row-cell")
        WaitForAsyncUtils.waitForFxEvents()
        assertThat(assign.isDisabled).isFalse()
    }
}
```

`@BeforeAll` in Kotlin needs a `companion object` with `@JvmStatic`.

## Idioms

- Look nodes up by **fx:id** (`#id`) or style class. Never by generated paths or indices, which break when the layout
  changes.
- Use `robot.clickOn(query)`, `robot.write("text")` (real key events) or
  `robot.interact(() -> field.setText("x"))` (fast, layout-independent), and `robot.type(KeyCode.ENTER)`.
- **Wait, don't sleep:** use `WaitForAsyncUtils.waitForFxEvents()` after actions, and
  `WaitForAsyncUtils.waitFor(5, SECONDS, () -> table.getItems().size() == 14)` for background work.
- Assert on **state** (items, text, disabled, selection), not pixels. Visual regression assertions are out of scope.
- Dialogs: look up the button text in the new window (`robot.lookup(".dialog-pane .button").match(hasText("OK"))`), or
  use `robot.listTargetWindows()`.
- Native `FileChooser`: inject a test seam first (see the FileChooser recipe in FXLoop's limitations doc).

## Headless (Monocle)

Dependencies (test scope): `org.testfx:testfx-junit5:4.0.18`, `org.testfx:openjfx-monocle:21.0.2` (match the
JavaFX line: 17 → `17.0.10`, 21 → `21.0.2`), `org.hamcrest:hamcrest:2.2`, plus AssertJ or Hamcrest for assertions.

System properties for the test JVM:

```
testfx.robot=glass  testfx.headless=true  glass.platform=Monocle  monocle.platform=Headless
prism.order=sw  prism.text=t2k  java.awt.headless=true
```

Maven: put them under `maven-surefire-plugin` → `<systemPropertyVariables>`. Gradle: in
`tasks.test { systemProperty("testfx.headless", "true") ... }`. Run with `./mvnw test -Dtest=AssignTicketTest` or
`./gradlew test --tests AssignTicketTest`.

## Flake patterns

| Symptom | Cause | Fix |
|---|---|---|
| Passes alone, fails in the suite | State leaks between tests (static model, system properties) | Reset in `@BeforeEach`/`@Start`; clear properties in `@AfterAll` |
| Intermittent "node not found" | Lookup before the scene updated | `waitForFxEvents()`, or `waitFor` on a condition |
| Wrong text typed | Keyboard layout / IME | `interact(() -> setText(...))` instead of `write` |
| Click lands elsewhere | Node off-screen in a ScrollPane, or on an unselected tab | Scroll or select first, or use `interact` to fire the action |
| Slow test after a background `Task` | Fixed sleeps | `waitFor` on the observable result |

## From FXLoop

`fx_export_test name=X` writes `src/test/java/<pkg>/XGeneratedTest.java` plus `FxPath.java` (a path-lookup helper)
from the action journal. It's a **draft**: replace `FxPath.lookup(...)` with `#fx:id` lookups, resolve every
`TODO(fxloop)`, give the test a behavior name, and delete the helper once nothing uses it. In Kotlin projects whose
build doesn't compile `src/test/java`, port the draft to `src/test/kotlin` with the Kotlin variant above.

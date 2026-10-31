---
name: fxloop-lock-in
description: Turn a UI fix or flow in a JavaFX app into a green TestFX regression test with FXLoop. Exports the recorded action journal as a JUnit 5 + TestFX draft, hardens it (fx:id lookups, real assertions from effects, waits), ports it to Kotlin when needed, and runs it headless on Monocle. Use when the developer says "make sure this doesn't regress", "write a test for this flow", "lock this in", "add a UI test for the fix" in a JavaFX/Kotlin desktop project.
---

# Lock in

References: [testfx.md](references/testfx.md) (idioms, Monocle setup, flakes, Kotlin variant),
[kotlin.md](references/kotlin.md).

## Procedure

1. **Record a clean run of the flow.** `fx_restart_app` with no replay, which gives a fresh state but keeps the
   journal. Then drive only the steps that matter (`fx_click_node`, `fx_set_text`, `fx_type_keys`). Note the
   journal seq range with `fx_get_journal limit=20`: the first and last seq of the flow.
2. **Export a draft:** `fx_export_test name=<Behavior> fromSeq=<a> toSeq=<b>`. That writes
   `src/test/java/<pkg>/<Behavior>GeneratedTest.java` and the `FxPath.java` helper (the result reports
   `steps`/`todos`).
3. **Harden it** (always; the export is a draft):
   - Rename the class and method to the behavior (`AssignTicketTest.assignRequiresSelection`) and drop `Generated`.
   - Replace `FxPath.lookup(robot, "Stage[0]/...")` with `robot.lookup("#fxId")`. If a node has no fx:id, add one to
     the FXML/code; that's a small, legitimate production change.
   - Resolve every `TODO(fxloop)`. Turn the effects into **assertions about the fix**: the state that was wrong
     before (disabled, selected item, count, text, no exception). One test per behavior.
   - Add `WaitForAsyncUtils.waitForFxEvents()` after actions, and `waitFor(...)` for background work. No sleeps.
   - Use the app's seed or demo flags the same way the app reads them (e.g. a system property in `@BeforeAll`, cleared
     in `@AfterAll`).
   - Delete `FxPath.java` once nothing references it.
   - **Kotlin project:** if the build doesn't compile `src/test/java` (check for a Java test source set and the
     compiler config), port the test to `src/test/kotlin` (testfx.md Kotlin variant) and delete the Java draft.
4. **Make the tests runnable headless.** Add the TestFX, Monocle and JUnit dependencies and the Monocle system
   properties if they're missing (testfx.md). Match `openjfx-monocle` to the JavaFX line.
5. **Run it:** `./mvnw -q test -Dtest=<Class>` or `./gradlew test --tests <Class>`, in the background if it's slow.
   Fix failures in the test first. If it reveals a real bug, report it instead of weakening the assertion.
6. **Prove it guards the fix (mutation check):** temporarily revert the fix (or flip the asserted condition in the
   production code), run the test, see it **fail**, then restore the fix and see it pass. Report both results. Skip
   this only if reverting is impractical, and say so.

## Output

```
Test: src/test/.../AssignTicketTest.kt (2 tests) — green headless (Monocle), 4.1 s
Guards: assignBtn disabled without selection (fails with the old binding: verified), assign with no selection → no NPE
Build changes: <deps/properties added, if any>
```

Don't commit unless asked. Remove leftover generated drafts.

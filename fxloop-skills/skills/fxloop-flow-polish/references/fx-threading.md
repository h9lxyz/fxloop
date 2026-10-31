# The JavaFX Application Thread

## Rules

1. **Touch the live scene graph only on the FX thread.** That covers creating or showing stages, `Alert`s and
   dialogs, changing node properties, and modifying an `ObservableList` that backs a visible control. Off-thread
   writes either throw `IllegalStateException: Not on FX application thread; currentThread = ...` or corrupt state
   silently (stale cells, missing repaints, the Alert that never appears).
2. **Never block the FX thread.** No I/O, network, JDBC, `Thread.sleep`, `Future.get()`, `CountDownLatch.await` or
   long loops in event handlers, `initialize()`, listeners or `start()`. The UI freezes for exactly as long.
3. **Do the work in the background, deliver on the FX thread:**

```java
Task<List<Ticket>> load = new Task<>() {
    @Override protected List<Ticket> call() throws Exception { return api.fetchTickets(); }   // background
};
load.setOnSucceeded(e -> table.getItems().setAll(load.getValue()));                           // FX thread
load.setOnFailed(e -> showError(load.getException()));                                        // FX thread
refreshBtn.disableProperty().bind(load.runningProperty());                                    // feedback
new Thread(load, "load-tickets").start();   // or an ExecutorService; Service<T> for restartable work
```

   For one-off hand-offs, use `Platform.runLater(() -> label.setText(msg))`. Check with
   `Platform.isFxApplicationThread()`.
4. **Show progress for anything over ~100 ms.** Disable the trigger, show a `ProgressIndicator` or a status text, and
   re-enable when done (bind to `task.runningProperty()`).

## Kotlin

Use `kotlinx-coroutines-javafx` and `Dispatchers.JavaFx`:

```kotlin
private val scope = MainScope()   // Dispatchers.Main = JavaFx with kotlinx-coroutines-javafx on the classpath
fun onRefresh() = scope.launch {
    refreshBtn.isDisable = true
    try {
        val tickets = withContext(Dispatchers.IO) { api.fetchTickets() }   // background
        table.items.setAll(tickets)                                         // back on the FX thread
    } finally { refreshBtn.isDisable = false }
}
```

Without coroutines, use the same `Task` code as in Java. Don't use `runBlocking` on the FX thread.

## What FXLoop shows you

| Signal | Meaning | Next step |
|---|---|---|
| A tool call returns `FX_TIMEOUT` with `fxThreadStack` | The FX thread is busy or blocked right now | Read the top project frames of the stack: that's the blocking call. Fix it, then `fx_restart_app` |
| `fx_click_node` takes seconds to return | The handler runs on the FX thread for that long | Move the work into a `Task` / coroutine and show progress |
| `fx_get_logs source=process` shows `Not on FX application thread` | An off-thread UI write | Wrap the write in `Platform.runLater`, or restructure into `Task.setOnSucceeded` |
| `fx_get_ui_errors` shows exceptions with `dedupCount` > 1 | An exception from a repeatedly fired handler (listener, cell factory) | Fix the root cause. Consider re-raising rather than swallowing |
| An action's `effects` are empty but the UI should change | The update ran off-thread or was swallowed, or a binding isn't wired | `fx_get_logs`, `fx_explain_property` |

## Hot reload limits

HotSwap replaces method bodies only. Adding a field, method or lambda capture can need a restart: `fx_reload` handles
it (restart and journal replay) and says so in `restartReasons`. Constructor and `initialize()` changes don't re-run
on live objects; they apply after the restart, or on the next FXML swap for controllers. Kotlin `.kt` edits are
compiled by the build tool (Gradle `classes` / Maven `compile`) and then hot-swapped, so they are slower than Java's
in-process `javac`. For the fastest Kotlin loop, let the IDE or `./gradlew -t classes` compile and set
`external-compile=true` in `fxloop.properties`.

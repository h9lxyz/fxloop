---
name: fxloop-flow-polish
description: Walk a user task end to end in the running JavaFX app with FXLoop (clicks, typing, effects), find the friction (extra steps, missing feedback, late validation, unclear disabled states, missing empty/loading/error states, UI freezes and FX-thread misuse), fix it and replay the flow as proof. Use when the developer says "this flow feels clunky", "users get lost here", "the app freezes when I click X", "nothing happens when I press save", "creating a ticket takes too many steps" in a JavaFX/Kotlin desktop app.
---

# Flow polish

References: [fx-threading.md](references/fx-threading.md), [evidence.md](references/evidence.md),
[testfx.md](references/testfx.md) (hand over to `fxloop-lock-in` at the end).

## Procedure

1. **Define the task** in one sentence with a success state, e.g. "create a ticket for Acme, assign it to me; done
   when it's selected in the table and the footer count goes up". Ask if it's ambiguous.
2. **Drive it like a user.** If the developer is in the app, say you are about to click through it and call
   `fx_lock_input locked=true reason="<the task>"` so stray input cannot interfere (they see it on the dev bar and can
   take over); release it with `locked=false` when the run is done. If a result says the developer took over, stop
   and ask. Use `fx_click_node` (robot mode) and `fx_set_text` for fields (`fx_type_keys` for
   Enter, Escape and Tab). After **every** step, read `effects` and note:
   - **Steps and wall time:** a click that takes more than ~200 ms to return means work on the FX thread.
   - **Feedback:** did anything visible change (status text, selection, a toast, a disabled state)? Empty `effects`
     after a meaningful action means no feedback, or a silent failure.
   - **Errors:** check `fx_get_ui_errors sinceSeq=<last>` after risky steps, and `fx_get_logs source=process
     lines=50` for `Not on FX application thread` or swallowed stack traces.
   - **Validation timing:** errors only on submit, one modal Alert per field, rules checked in a strange order.
   - **Enablement:** for buttons that are disabled or enabled at the wrong time, run `fx_explain_property
     name=disable` and read what it's bound to (wrong source = a copy-paste binding).
   - **Edge states:** no selection, an active filter or search, an empty list, a duplicate, very long input. Try the
     cheap ones. Use an empty-data flag if the app has one (e.g. `--empty`) via `app-args`.
   - **Freezes:** an `FX_TIMEOUT` error carries `fxThreadStack`. The top project frame is the blocking call.
   Take `fx_take_screenshot label=flow-<n>-<step>` at decision points (forms, dialogs, results).
3. **Friction list**, ranked by user impact (data loss > wrong result > blocked > confusing > slow > extra step):

   | # | Step | Friction | Evidence | Proposed change |

   Get agreement on what to change when the changes are product decisions (confirmation dialogs, removing steps).
   Bugs (wrong ticket deleted, NPE, freeze) you fix directly.
4. **Fix** with the patterns that apply:
   - A blocking handler → `Task`/`Service` (Kotlin: coroutines with `Dispatchers.JavaFx`), with a disabled trigger and
     progress while it runs.
   - Off-thread UI → `Platform.runLater` / `setOnSucceeded`.
   - Wrong binding → bind to the real condition (`selectedItemProperty().isNull`).
   - Destructive action → confirm the action by name ("Delete ticket #1042?") and act on the **selected item**, not
     an index into a filtered list.
   - Feedback → a status message, select and scroll to the new item, update counts.
   - Validation → inline, at field exit or on input, with the submit button disabled until the form is valid.
   - Empty/loading/error states → a table `placeholder`, a progress indicator, error text with a retry.
   Apply with `fx_reload`. Read `compileErrors` and `restartReasons`. After a restart, the journal replays: check
   `replay.replayed`/`failedAt`.
5. **Replay the flow** from a fresh state: `fx_restart_app` (no replay), then drive the same steps again. Show the
   new step count, timings, the effects that now give feedback, and an empty `fx_get_ui_errors`.

## Output

```
Task: <sentence>   Before: 9 steps, 2.6 s freeze on Refresh, 1 NPE   After: 6 steps, no freeze, 0 errors
| # | Friction | Fix (file:line) | Evidence after |
Frames: <paths>
Next: lock the flow in as a test? (lock-in skill)
```

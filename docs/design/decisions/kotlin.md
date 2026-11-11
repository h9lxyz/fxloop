# Decision: Kotlin for the tooling

**Status:** accepted. Amends **D1** (Java server) in the PRDs: the implementation language is now **JVM/Kotlin**; the rationale of D1 (single JVM toolchain, direct access to `jdk.attach`, `javax.tools`, JavaFX and `Instrumentation`, one shaded jar) is unchanged.

The language was switched to Kotlin mid-build at the maintainer's request. Consequences:

| Area | Decision |
|---|---|
| Tooling modules | `fxloop-protocol`, `fxloop-agent`, `fxloop-server`, `fxloop-maven-plugin`, `fxloop-gradle-plugin`, `fxloop-acceptance` are Kotlin 2.4 with `jvmTarget` 21 (the bytecode baseline: runs on JDK 21+) |
| Test-target apps | `sample-apps/*` stay **Java**: they stand in for typical user apps, and the HotSwap tier compiles *Java* edits with `javax.tools` |
| Agent classpath hygiene | The agent relocates the **Kotlin stdlib** to `fxloop.internal.kotlin` (as with Jackson and ByteBuddy under `fxloop.internal`) so it never clashes with an app's own Kotlin version |
| Agent code in `FXMLLoader` | Inlined advice is written to avoid Kotlin intrinsics (nullable parameters) so it only references public types |
| Cost | Agent jar is about 6 MB |

Kotlin app projects are not supported by the in-process javac HotSwap tier; use `FXLOOP_EXTERNAL_COMPILE=true` (your build compiles, FXLoop swaps the resulting classes).

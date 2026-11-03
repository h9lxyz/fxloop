# fxloop-sample-app-kotlin

Kotlin twin of `fxloop-sample-app` (PRD 06 §6): Gradle Kotlin DSL, package `dev.fxloop.sample`, **same node ids, texts
and flags** (`--seed`, `--broken-css`). Entry point is a top-level `fun main` in `Main.kt`, so the main class is
`dev.fxloop.sample.MainKt`; controller fields are `@FXML private lateinit var`. Node ids: `nameField`, `categoryBox`
(Work/Family/Friends), `addBtn` (disabled while the name is blank), `contactTable`, `deleteBtn`, `statusLabel`. It is the test bed for the Kotlin notes
in the FXLoop skills and for Kotlin reloads (`.kt` edits go through the build tool, then HotSwap).

    ./gradlew -p ../../fxloop-gradle-plugin publishToMavenLocal   # once; plugin resolved from mavenLocal
    ./gradlew test fxloopPrepare                               # Monocle tests; writes build/fxloop/launch.json
    ./gradlew run --args="--seed --broken-css"

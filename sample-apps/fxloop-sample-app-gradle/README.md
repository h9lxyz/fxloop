# fxloop-sample-app-gradle

Gradle twin of `fxloop-sample-app` (identical sources, package `org.lab59.fxloop.sample`, same node ids), used to exercise the
`org.lab59.fxloop` Gradle plugin. Uses `org.openjfx.javafxplugin` 0.1.0 (JavaFX 21.0.13) and the TestFX/Monocle headless tests.

    ./gradlew -p ../../fxloop-gradle-plugin publishToMavenLocal   # once; plugin resolved from mavenLocal
    ./gradlew test fxloopPrepare                               # writes build/fxloop/launch.json
    ./gradlew run

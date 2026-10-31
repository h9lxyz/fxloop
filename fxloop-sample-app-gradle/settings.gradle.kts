pluginManagement {
    repositories {
        mavenLocal() // dev.fxloop plugin (./gradlew -p ../fxloop-gradle-plugin publishToMavenLocal)
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "fxloop-sample-app-gradle"

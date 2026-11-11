pluginManagement {
    repositories {
        mavenLocal() // org.lab59.fxloop plugin (./gradlew -p ../../fxloop-gradle-plugin publishToMavenLocal)
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "fxloop-sample-app-kotlin"

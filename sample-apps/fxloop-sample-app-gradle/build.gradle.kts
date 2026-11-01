plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("dev.fxloop") version "1.0.0-SNAPSHOT"
}

group = "dev.fxloop"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

javafx {
    version = "21.0.13"
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    mainClass = "dev.fxloop.sample.Launcher"
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.testfx:testfx-junit5:4.0.18")
    testImplementation("org.hamcrest:hamcrest:2.2") // TestFX API exposes Matcher
    testImplementation("org.testfx:openjfx-monocle:21.0.2")
}

tasks.test {
    useJUnitPlatform()
    // Headless: Monocle, never the real desktop display.
    systemProperty("testfx.robot", "glass")
    systemProperty("testfx.headless", "true")
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("prism.order", "sw")
    systemProperty("prism.text", "t2k")
    systemProperty("java.awt.headless", "true")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

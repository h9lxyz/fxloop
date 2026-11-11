plugins {
    kotlin("jvm") version "2.4.21"
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "org.lab59.fxloop"
// The release passes -PfxloopVersion=<tag> (PRD 10); the committed default is the development version.
val fxloopVersion = (findProperty("fxloopVersion") ?: "1.0.0-SNAPSHOT").toString()
version = fxloopVersion

repositories {
    // fxloop-launch (and fxloop-protocol, which it uses) are installed by the Maven build (`./mvnw install`); not yet on Central.
    mavenLocal()
    mavenCentral()
}

// JDK 21 bytecode level. A JDK 21 toolchain is not required: any JDK >= 21 compiles with --release 21.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The plugin pins the agent jar to its own version: generated resource org/lab59/fxloop/gradle/fxloop.properties
val versionResource = tasks.register("fxloopVersionResource") {
    val out = layout.buildDirectory.dir("generated/fxloop-resources")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val f = out.get().file("org/lab59/fxloop/gradle/fxloop.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$v\n")
    }
}
sourceSets.main { resources.srcDir(versionResource) }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // Gradle 9 embeds Kotlin 2.x; keep the plugin's language level at the floor so it loads on all supported Gradles
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    implementation("org.lab59.fxloop:fxloop-launch:$fxloopVersion")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    website.set("https://github.com/h9lxyz/fxloop")
    vcsUrl.set("https://github.com/h9lxyz/fxloop.git")
    plugins {
        create("fxloop") {
            id = "org.lab59.fxloop"
            displayName = "FXLoop"
            description = "Emits build/fxloop/launch.json so FXLoop can launch, instrument and hot-reload a JavaFX application."
            implementationClass = "org.lab59.fxloop.gradle.FxloopPlugin"
            tags.set(listOf("javafx", "fxloop", "hot-reload", "testing"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // functional tests bring their own Gradle user home settings; keep output readable
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

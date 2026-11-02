plugins {
    kotlin("jvm") version "2.4.21"
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "dev.fxloop"
version = "1.0.0-SNAPSHOT"

repositories {
    // fxloop-protocol is installed by the Maven build (`./mvnw install`); not yet on Central.
    mavenLocal()
    mavenCentral()
}

// JDK 17 bytecode level. A JDK 17 toolchain is not required: any JDK >= 17 compiles with --release 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The plugin pins the agent jar to its own version: generated resource dev/fxloop/gradle/fxloop.properties
val versionResource = tasks.register("fxloopVersionResource") {
    val out = layout.buildDirectory.dir("generated/fxloop-resources")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val f = out.get().file("dev/fxloop/gradle/fxloop.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$v\n")
    }
}
sourceSets.main { resources.srcDir(versionResource) }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Gradle 9 embeds Kotlin 2.x; keep the plugin's language level at the floor so it loads on all supported Gradles
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    implementation("dev.fxloop:fxloop-protocol:1.0.0-SNAPSHOT")
    implementation("org.ow2.asm:asm:9.11.1")

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
            id = "dev.fxloop"
            displayName = "FXLoop"
            description = "Emits build/fxloop/launch.json so FXLoop can launch, instrument and hot-reload a JavaFX application."
            implementationClass = "dev.fxloop.gradle.FxloopPlugin"
            tags.set(listOf("javafx", "fxloop", "hot-reload", "testing"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // functional tests bring their own Gradle user home settings; keep output readable
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

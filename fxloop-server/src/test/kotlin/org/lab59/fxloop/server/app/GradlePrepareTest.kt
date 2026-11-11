package org.lab59.fxloop.server.app

import org.lab59.fxloop.server.mcp.ToolException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class GradlePrepareTest {
    private val jars = listOf(Path.of("/c/fxloop-gradle-plugin.jar"), Path.of("/c/with\$dollar.jar"))

    @Test
    fun initScriptPutsJarsOnTheInitscriptClasspathAndAppliesThePlugin() {
        val s = GradlePrepare.initScript(jars)
        assertThat(s).contains("initscript {", "classpath(files(", "/c/fxloop-gradle-plugin.jar\"")
            .contains("org.lab59.fxloop.gradle.FxloopPlugin::class.java", "withPlugin(\"application\")", "withPlugin(\"org.openjfx.javafxplugin\")")
            .contains("hasPlugin(\"org.lab59.fxloop\")")
            .contains("with\\\$dollar.jar") // dollars escaped for Kotlin DSL
    }

    @Test
    fun pathsAreEscapedForKotlinDsl() { // a string: Windows has no path with a quote in it
        assertThat(GradlePrepare.kts("C:\\c\\with \"quote\$.jar")).isEqualTo("C:/c/with \\\"quote\\\$.jar")
    }

    @Test
    fun initScriptIsRewrittenOnlyWhenItChanged(@TempDir dir: Path) {
        val f = dir.resolve("build/fxloop/${GradlePrepare.INIT_SCRIPT}")
        GradlePrepare.writeInitScript(f, jars)
        val stamp = Files.getLastModifiedTime(f)
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(stamp.toMillis() - 10_000))
        val before = Files.getLastModifiedTime(f)
        GradlePrepare.writeInitScript(f, jars)
        assertThat(Files.getLastModifiedTime(f)).isEqualTo(before)
    }

    @Test
    fun extractWritesEveryBundledJarOnceAndRefreshesChangedOnes(@TempDir dir: Path) {
        val open = { v: String -> { n: String -> ByteArrayInputStream("$v:$n".toByteArray()) } }
        val out = GradlePrepare.extract(dir, open("1"))
        assertThat(out.map { it.fileName.toString() }).containsExactlyElementsOf(GradlePrepare.JARS.map { "$it.jar" })
        assertThat(Files.readString(out[0])).isEqualTo("1:fxloop-gradle-plugin.jar")
        GradlePrepare.extract(dir, open("2"))
        assertThat(Files.readString(dir.resolve("fxloop-gradle-plugin.jar"))).isEqualTo("2:fxloop-gradle-plugin.jar")
    }

    @Test
    fun runtimeJarHasThePluginDependenciesAndIsReproducible() {
        val jar = GradlePrepare.runtimeJar()
        val names = ZipInputStream(ByteArrayInputStream(jar)).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertThat(names).contains(
            "org/lab59/fxloop/protocol/Json.class", "org/lab59/fxloop/launch/LaunchDescriptor.class",
            "org/lab59/fxloop/launch/MainClassResolver.class", "org/lab59/fxloop/launch/launch.schema.json",
            "org/objectweb/asm/ClassReader.class", "kotlin/Unit.class",
            "com/fasterxml/jackson/databind/ObjectMapper.class", "com/fasterxml/jackson/core/JsonParser.class",
            "com/fasterxml/jackson/annotation/JsonProperty.class",
        )
        assertThat(names).noneMatch { it.startsWith("org/lab59/fxloop/server/") || it.startsWith("reactor/") }
        assertThat(GradlePrepare.runtimeJar()).isEqualTo(jar)
    }

    @Test
    fun missingBundleIsAClearToolException(@TempDir dir: Path) {
        assertThatThrownBy { GradlePrepare.extract(dir) { null } }
            .isInstanceOf(ToolException::class.java)
            .hasMessageContaining("lacks the bundled Gradle plugin")
            .satisfies({ e -> assertThat((e as ToolException).hint).contains("id(\"org.lab59.fxloop\")") })
    }

    @Test
    fun pluginDetectionGrepsBuildFilesAndRecognisesMissingTask(@TempDir dir: Path) {
        assertThat(GradlePrepare.pluginApplied(dir)).isFalse()
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { application }")
        assertThat(GradlePrepare.pluginApplied(dir)).isFalse()
        Files.writeString(dir.resolve("build.gradle.kts"), "group = \"org.lab59.fxloop\"\nplugins { application }")
        assertThat(GradlePrepare.pluginApplied(dir)).isFalse()
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id(\"org.lab59.fxloop\") version \"1\" }")
        assertThat(GradlePrepare.pluginApplied(dir)).isTrue()
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id 'org.lab59.fxloop' version '1' }")
        assertThat(GradlePrepare.pluginApplied(dir)).isTrue()
        assertThat(GradlePrepare.taskMissing("* What went wrong:\nTask 'fxloopPrepare' not found in root project 'x'.")).isTrue()
        assertThat(GradlePrepare.taskMissing("compilation failed")).isFalse()
    }
}

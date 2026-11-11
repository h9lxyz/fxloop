package dev.fxloop.server.app

import dev.fxloop.server.mcp.ToolException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MavenPrepareTest {
    private fun pom(dir: Path, body: String, extraBuild: String = "") = EffectivePom.parse(
        """<project><properties><maven.compiler.release>17</maven.compiler.release></properties>
           <build><sourceDirectory>$dir/src/main/java</sourceDirectory><outputDirectory>$dir/target/classes</outputDirectory>
           <resources><resource><directory>$dir/src/main/resources</directory></resource></resources>
           <plugins>$body</plugins>$extraBuild</build></project>""",
    )

    /** [path] as the descriptor carries it: absolute, with the OS's own separators. */
    private fun abs(path: String) = Path.of(path).toAbsolutePath().toString()

    private fun layout(dir: Path): Path {
        Files.createDirectories(dir.resolve("src/main/java"))
        Files.createDirectories(dir.resolve("src/main/resources"))
        return Files.createDirectories(dir.resolve("target/classes"))
    }

    private val fxPlugin = """<plugin><groupId>org.openjfx</groupId><artifactId>javafx-maven-plugin</artifactId>
        <configuration><mainClass>p.Main</mainClass><options><option>-Dx=1</option></options><commandlineArgs>a b</commandlineArgs></configuration></plugin>"""

    @Test
    fun classpathModeCarriesTheMavenPluginsFields(@TempDir dir: Path) {
        layout(dir)
        val d = MavenPrepare.describe(dir, pom(dir, fxPlugin), listOf("/r/org/openjfx/javafx-base/21/javafx-base-21-linux.jar", "/r/x.jar"), null)
        assertThat(d.buildTool).isEqualTo("maven")
        assertThat(d.mainClass).isEqualTo("p.Main")
        assertThat(d.mainModule).isNull()
        assertThat(d.classpath).containsExactly(abs("/r/org/openjfx/javafx-base/21/javafx-base-21-linux.jar"), abs("/r/x.jar"))
        assertThat(d.modulePath).isEmpty()
        assertThat(d.jvmArgs).containsExactly("-Dx=1")
        assertThat(d.appArgs).containsExactly("a", "b")
        assertThat(d.release).isEqualTo("17")
        assertThat(d.outputDir).isEqualTo(abs("$dir/target/classes"))
        assertThat(d.sourceRoots).containsExactly(abs("$dir/src/main/java"))
        assertThat(d.resourceRoots).containsExactly(abs("$dir/src/main/resources"))
        assertThat(d.annotationProcessors).isFalse()
    }

    @Test
    fun explicitMainClassWinsOverPluginConfig(@TempDir dir: Path) {
        layout(dir)
        assertThat(MavenPrepare.describe(dir, pom(dir, fxPlugin), emptyList(), "q.Other").mainClass).isEqualTo("q.Other")
    }

    @Test
    fun moduleInfoSwitchesToModulePathAndReadsTheModuleName(@TempDir dir: Path) {
        layout(dir)
        Files.writeString(dir.resolve("src/main/java/module-info.java"), "// c\nmodule my.app { requires javafx.controls; }")
        val d = MavenPrepare.describe(dir, pom(dir, fxPlugin), listOf("/r/org/openjfx/javafx-base/21/javafx-base-21.jar"), null)
        assertThat(d.mainModule).isEqualTo("my.app")
        assertThat(d.classpath).isEmpty()
        assertThat(d.modulePath).containsExactly(abs("$dir/target/classes"), abs("/r/org/openjfx/javafx-base/21/javafx-base-21.jar"))
    }

    @Test
    fun forcedModulePathWithoutModuleInfoSplitsJavaFxOut(@TempDir dir: Path) {
        layout(dir)
        val d = MavenPrepare.describe(dir, pom(dir, fxPlugin), listOf("/r/org/openjfx/javafx-controls/21/javafx-controls-21-linux.jar", "/r/org/openjfx/javafx-controls/21/javafx-controls-21.jar", "/r/lib.jar"), null, launchMode = "modulepath")
        assertThat(d.modulePath).hasSize(2)
        assertThat(d.classpath).containsExactly(abs("/r/lib.jar"))
        assertThat(d.addModules).containsExactly("javafx.controls")
    }

    @Test
    fun ambiguousMainClassListsCandidatesAndPinLines(@TempDir dir: Path) {
        val classes = layout(dir)
        for (n in listOf("A", "B")) { // two Application subclasses, compiled for real
            val pkg = Files.createDirectories(classes.resolve("p"))
            Files.write(pkg.resolve("$n.class"), appSubclass("p/$n"))
        }
        val e = org.junit.jupiter.api.Assertions.assertThrows(ToolException::class.java) {
            MavenPrepare.describe(dir, pom(dir, ""), emptyList(), null)
        }
        assertThat(e.message).contains("p.A (extends Application)", "p.B (extends Application)")
        assertThat(e.hint).contains("main-class=p.A", "main-class=p.B")
    }

    @Test
    fun noMainClassIsAnError(@TempDir dir: Path) {
        layout(dir)
        assertThatThrownBy { MavenPrepare.describe(dir, pom(dir, ""), emptyList(), null) }
            .isInstanceOf(ToolException::class.java).hasMessageContaining("no main class found")
    }

    @Test
    fun releaseFallsBackToTargetThenRuntime(@TempDir dir: Path) {
        layout(dir)
        val onlyTarget = EffectivePom.parse("<project><properties><maven.compiler.target>11</maven.compiler.target></properties></project>")
        assertThat(MavenPrepare.release(onlyTarget, null)).isEqualTo("11")
        val unresolved = EffectivePom.parse("<project><properties><maven.compiler.release>\${x}</maven.compiler.release></properties></project>")
        assertThat(MavenPrepare.release(unresolved, null)).isEqualTo(Runtime.version().feature().toString())
        val compiler = EffectivePom.parse("<project><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration><release>9</release></configuration></plugin></plugins></build></project>")
            .plugin("org.apache.maven.plugins", "maven-compiler-plugin")
        assertThat(MavenPrepare.release(onlyTarget, compiler)).isEqualTo("9")
    }

    @Test
    fun processorsFromPathsServiceFileOrProvidedDependency(@TempDir dir: Path) {
        val compiler = "<plugin><artifactId>maven-compiler-plugin</artifactId><configuration><annotationProcessorPaths><path/></annotationProcessorPaths></configuration></plugin>"
        val withPaths = pom(dir, compiler)
        assertThat(MavenPrepare.detectProcessors(withPaths, withPaths.plugin("org.apache.maven.plugins", "maven-compiler-plugin"), emptyList(), null)).isTrue()

        val jar = dir.resolve("proc.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { z ->
            z.putNextEntry(ZipEntry("META-INF/services/javax.annotation.processing.Processor")); z.write("x.P".toByteArray()); z.closeEntry()
        }
        assertThat(MavenPrepare.detectProcessors(pom(dir, ""), null, listOf(jar.toString()), null)).isTrue()
        assertThat(MavenPrepare.detectProcessors(pom(dir, ""), null, emptyList(), dir)).isFalse()

        // a provided dependency is located in the local repository layout
        val repo = Files.createDirectories(dir.resolve("repo/org/projectlombok/lombok/1.0"))
        Files.copy(jar, repo.resolve("lombok-1.0.jar"))
        val lombok = EffectivePom.parse("<project><dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.0</version><scope>provided</scope></dependency></dependencies></project>")
        assertThat(MavenPrepare.detectProcessors(lombok, null, emptyList(), dir.resolve("repo"))).isTrue()
    }

    @Test
    fun javaFxArtifactIdFromLocalRepositoryPath() {
        assertThat(MavenPrepare.javaFxArtifactId("/h/.m2/repository/org/openjfx/javafx-fxml/21/javafx-fxml-21-linux.jar")).isEqualTo("javafx-fxml")
        assertThat(MavenPrepare.javaFxArtifactId("C:\\Users\\a\\.m2\\repository\\org\\openjfx\\javafx-base\\21\\javafx-base-21-win.jar")).isEqualTo("javafx-base")
        assertThat(MavenPrepare.javaFxArtifactId("/h/.m2/repository/org/other/x/1/x-1.jar")).isNull()
    }

    /** Minimal class `p.N extends javafx.application.Application` (ASM is on the test classpath via fxloop-launch). */
    private fun appSubclass(internal: String): ByteArray {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, internal, null, "javafx/application/Application", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun pluginResolutionFailureIsRecognised() {
        val unresolved = "[ERROR] Plugin dev.fxloop:fxloop-maven-plugin:1.0 or one of its dependencies could not be resolved:\n[ERROR] Could not find artifact dev.fxloop:fxloop-maven-plugin:jar:1.0"
        val bt = BuildTool(dev.fxloop.server.Config(projectDir = Path.of(".")))
        assertThat(bt.pluginUnresolvable(unresolved)).isTrue()
        assertThat(bt.pluginUnresolvable("[ERROR] Failed to execute goal dev.fxloop:fxloop-maven-plugin:1.0:prepare: Cannot determine the main class")).isFalse()
        assertThat(bt.pluginUnresolvable("[ERROR] COMPILATION ERROR")).isFalse()
    }

    @Test
    fun buildGetsTheServerJdkOnlyWhenJavaHomeIsUnset() {
        val jdk = Path.of("/jdks/21").toString()
        val bin = Path.of(jdk, "bin").toString()
        val sep = java.io.File.pathSeparator

        val bare = mutableMapOf("Path" to "/usr/bin")
        BuildTool.withJdk(bare, jdk)
        assertThat(bare).containsEntry("JAVA_HOME", jdk).containsEntry("Path", "$bin$sep/usr/bin").doesNotContainKey("PATH")

        val noPath = mutableMapOf<String, String>()
        BuildTool.withJdk(noPath, jdk)
        assertThat(noPath).containsEntry("JAVA_HOME", jdk).containsEntry("PATH", bin)

        val set = mutableMapOf("JAVA_HOME" to "/opt/jdk17", "PATH" to "/usr/bin")
        BuildTool.withJdk(set, jdk)
        assertThat(set).isEqualTo(mapOf("JAVA_HOME" to "/opt/jdk17", "PATH" to "/usr/bin"))
    }
}

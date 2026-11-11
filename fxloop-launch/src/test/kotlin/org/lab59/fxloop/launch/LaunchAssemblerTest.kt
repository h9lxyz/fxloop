package org.lab59.fxloop.launch

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LaunchAssemblerTest {

    @TempDir
    lateinit var dir: Path

    private val out get() = Files.createDirectories(dir.resolve("classes"))
    private val src get() = Files.createDirectories(dir.resolve("src"))
    private val fxJar = "/r/org/openjfx/javafx-controls/21/javafx-controls-21-linux.jar"
    private val fx = JavaFxArtifacts(setOf(fxJar), listOf("javafx-controls", "javafx-controls", "javafx-base"))

    private fun assemble(
        main: String? = "p.Main",
        mode: String? = "auto",
        sources: List<String> = listOf(src.toString()),
        runtime: List<String> = listOf(fxJar, "/r/lib.jar"),
        distinct: Boolean = false,
        exclude: Set<String> = emptySet(),
        release: String? = "17",
        javafx: () -> JavaFxArtifacts = { fx },
        processors: () -> Boolean = { false },
        runtimeSupplier: () -> List<String> = { runtime },
    ) = LaunchAssembler.assemble(
        buildTool = "maven",
        mainClassSteps = listOf(MainClassStep("explicit", main)),
        outputDir = out,
        launchMode = mode,
        sourceRoots = sources,
        resourceRoots = listOf(dir.resolve("missing").toString()),
        distinctRoots = distinct,
        runtimeClasspath = runtimeSupplier,
        javafx = javafx,
        excludeFromModulePath = exclude,
        jvmArgs = listOf("-Dx=1"),
        appArgs = listOf("a"),
        workingDir = dir.toString(),
        release = release,
        annotationProcessors = processors,
    )

    private fun assembled(a: LaunchAssembly) = (a as LaunchAssembly.Assembled)

    @Test
    fun classpathModePutsTheRuntimeOnTheClasspath() {
        val a = assembled(assemble())
        assertThat(a.mode).isEqualTo("classpath")
        assertThat(a.modular).isFalse()
        assertThat(a.mainClassSource).isEqualTo("explicit")
        val d = a.descriptor
        assertThat(d.buildTool).isEqualTo("maven")
        assertThat(d.mainClass).isEqualTo("p.Main")
        assertThat(d.mainModule).isNull()
        assertThat(d.classpath).containsExactly(fxJar, "/r/lib.jar")
        assertThat(d.modulePath).isEmpty()
        assertThat(d.addModules).isEmpty()
        assertThat(d.jvmArgs).containsExactly("-Dx=1")
        assertThat(d.appArgs).containsExactly("a")
        assertThat(d.workingDir).isEqualTo(dir.toString())
        assertThat(d.outputDir).isEqualTo(out.toString())
        assertThat(d.sourceRoots).containsExactly(src.toAbsolutePath().toString())
        assertThat(d.resourceRoots).isEmpty()
        assertThat(d.release).isEqualTo("17")
        assertThat(d.annotationProcessors).isFalse()
    }

    @Test
    fun classpathModeDropsAnExplicitModule() {
        assertThat(assembled(assemble(main = "my.mod/p.Main", mode = "classpath")).descriptor.mainModule).isNull()
    }

    @Test
    fun moduleInfoSelectsTheModulePathAndItsModuleName() {
        Files.writeString(src.resolve("module-info.java"), "/* module x { */ module my.app { requires javafx.controls; }")
        val a = assembled(assemble(exclude = setOf("/r/lib.jar"), javafx = { error("not consulted for a modular app") }))
        assertThat(a.mode).isEqualTo("modulepath")
        assertThat(a.modular).isTrue()
        assertThat(a.descriptor.mainModule).isEqualTo("my.app")
        assertThat(a.descriptor.modulePath).containsExactly(out.toString(), fxJar)
        assertThat(a.descriptor.classpath).isEmpty()
    }

    @Test
    fun explicitModuleWinsOverModuleInfo() {
        Files.writeString(src.resolve("module-info.java"), "module my.app {}")
        assertThat(assembled(assemble(main = "other.mod/p.Main")).descriptor.mainModule).isEqualTo("other.mod")
    }

    @Test
    fun forcedModulePathWithoutModuleInfoSplitsJavaFxOut() {
        val d = assembled(assemble(mode = "modulepath")).descriptor
        assertThat(d.mainModule).isNull()
        assertThat(d.modulePath).containsExactly(fxJar)
        assertThat(d.classpath).containsExactly("/r/lib.jar")
        assertThat(d.addModules).containsExactly("javafx.controls", "javafx.base")
    }

    @Test
    fun missingMainClassIsATypedFailureAndSkipsTheRest() {
        val a = assemble(main = null, runtimeSupplier = { error("not evaluated") }, processors = { error("not evaluated") })
        assertThat(a).isInstanceOf(LaunchAssembly.MainClassUnresolved::class.java)
        val e = (a as LaunchAssembly.MainClassUnresolved).error
        assertThat(e.noneFound).isTrue()
        assertThat(e.outputDirs).containsExactly(out)
    }

    @Test
    fun invalidLaunchModeIsATypedFailure() {
        val a = assemble(mode = "jar", runtimeSupplier = { error("not evaluated") })
        assertThat(a).isInstanceOf(LaunchAssembly.InvalidLaunchMode::class.java)
        assertThat((a as LaunchAssembly.InvalidLaunchMode).error.message)
            .isEqualTo("Invalid fxloop.launchMode 'jar' (expected auto|classpath|modulepath)")
    }

    @Test
    fun rootsAreFilteredAndOptionallyDistinct() {
        val roots = listOf(src.toString(), dir.resolve("nope").toString(), src.toString())
        val abs = src.toAbsolutePath().toString()
        assertThat(assembled(assemble(sources = roots)).descriptor.sourceRoots).containsExactly(abs, abs)
        assertThat(assembled(assemble(sources = roots, distinct = true)).descriptor.sourceRoots).containsExactly(abs)
    }

    @Test
    fun releaseDefaultsToTheRunningJdk() {
        assertThat(assembled(assemble(release = null)).descriptor.release).isEqualTo(Runtime.version().feature().toString())
    }

    @Test
    fun mavenReleasePrefersPluginThenPropertiesAndIgnoresUnresolved() {
        val props = mapOf("maven.compiler.target" to " 11 ")
        assertThat(LaunchAssembler.mavenRelease("9", props::get)).isEqualTo("9")
        assertThat(LaunchAssembler.mavenRelease("\${r}", props::get)).isEqualTo("11")
        assertThat(LaunchAssembler.mavenRelease(null, mapOf("maven.compiler.release" to "\${x}")::get))
            .isEqualTo(Runtime.version().feature().toString())
    }

    @Test
    fun withoutOutputDirMakesEntriesAbsolute() {
        assertThat(LaunchAssembler.withoutOutputDir(listOf(out.toString(), "rel.jar"), out))
            .containsExactly(Path.of("rel.jar").toAbsolutePath().toString())
    }

    @Test
    fun splitArgsOnWhitespace() {
        assertThat(LaunchAssembler.splitArgs(" a  b\tc ")).containsExactly("a", "b", "c")
        assertThat(LaunchAssembler.splitArgs(null)).isEmpty()
    }

    @Test
    fun processorsFromConfigurationJarOrDirectory() {
        assertThat(LaunchAssembler.detectProcessors(true) { error("not scanned when configured") }).isTrue()
        val jar = dir.resolve("proc.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { z ->
            z.putNextEntry(ZipEntry(LaunchAssembler.PROCESSOR_SERVICE)); z.write("x.P".toByteArray()); z.closeEntry()
        }
        val notAJar = Files.writeString(dir.resolve("broken.jar"), "nope")
        assertThat(LaunchAssembler.detectProcessors(false) { listOf(notAJar.toString(), jar.toString()) }).isTrue()
        val classes = dir.resolve("procdir")
        Files.createDirectories(classes.resolve(LaunchAssembler.PROCESSOR_SERVICE).parent)
        Files.writeString(classes.resolve(LaunchAssembler.PROCESSOR_SERVICE), "x.P")
        assertThat(LaunchAssembler.detectProcessors(false) { listOf(classes.toString()) }).isTrue()
        assertThat(LaunchAssembler.detectProcessors(false) { listOf(notAJar.toString(), "/no/such.jar") }).isFalse()
        assertThat(assembled(assemble(processors = { true })).descriptor.annotationProcessors).isTrue()
    }
}

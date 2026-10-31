package dev.fxloop.gradle

import dev.fxloop.protocol.LaunchDescriptor
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/** Writes `build/fxloop/launch.json`. All inputs are plain values or providers; no Project access at execution. */
@UntrackedTask(because = "Cheap; the descriptor must always reflect the current classpath and compiled classes")
abstract class FxloopPrepareTask : DefaultTask() {
    @get:Input @get:Optional abstract val explicitMainClass: Property<String>
    @get:Input @get:Optional abstract val javafxMainClass: Property<String>
    @get:Input @get:Optional abstract val applicationMainClass: Property<String>
    @get:Input @get:Optional abstract val manifestMainClass: Property<String>
    @get:Input abstract val launchMode: Property<String>
    @get:Input abstract val jvmArgs: ListProperty<String>
    @get:Input abstract val appArgs: ListProperty<String>
    @get:Input abstract val applicationJvmArgs: ListProperty<String>
    @get:Input abstract val sourceRoots: ListProperty<String>
    @get:Input abstract val resourceRoots: ListProperty<String>
    @get:Input abstract val javafxJars: ListProperty<String>
    @get:Input abstract val javafxArtifactIds: ListProperty<String>
    @get:Input @get:Optional abstract val release: Property<String>
    @get:Input abstract val annotationProcessors: Property<Boolean>
    @get:Input abstract val workingDir: Property<String>

    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val runtimeClasspath: ConfigurableFileCollection
    @get:Internal abstract val classesDir: DirectoryProperty
    @get:Internal abstract val classesDirs: ConfigurableFileCollection
    @get:Internal abstract val resourcesDir: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val javaClasses = classesDir.get().asFile.toPath().toAbsolutePath()
        val classDirs = (listOf(javaClasses) + classesDirs.files.map { it.toPath().toAbsolutePath() }).distinct()
        // outputDir is where HotSwap looks for changed classes: the Java dir unless only another language (Kotlin)
        // produced classes; the other class dirs go on the classpath/module path in front of the dependencies.
        val outputDir = classDirs.firstOrNull(::hasClasses) ?: javaClasses
        val extraClassDirs = classDirs.filter { it != outputDir && Files.isDirectory(it) }
        val resources: Path? = resourcesDir.orNull?.asFile?.toPath()?.toAbsolutePath()

        val main = MainClassResolver.resolve(
            MainClassResolver.Inputs(
                explicitMainClass.orNull, javafxMainClass.orNull, applicationMainClass.orNull,
                manifestMainClass.orNull, classDirs,
            ),
        )

        val sourceRoots = existing(sourceRoots.get())
        val resourceRoots = existing(resourceRoots.get())
        val moduleInfo = sourceRoots.map { Path.of(it, "module-info.java") }.firstOrNull { Files.isRegularFile(it) }
        val mode = resolveMode(moduleInfo != null)

        // Runtime classpath minus the compiled-classes dir (carried separately as outputDir);
        // the resources dir stays so resources load from the classpath.
        val runtime = (extraClassDirs + runtimeClasspath.files.map { it.toPath().toAbsolutePath() })
            .filter { it != outputDir }.distinct().toMutableList()
        if (resources != null && Files.isDirectory(resources) && resources !in runtime) runtime.add(0, resources)
        val runtimeStr = runtime.map(Path::toString)

        val classpath = mutableListOf<String>()
        val modulePath = mutableListOf<String>()
        val addModules = mutableListOf<String>()
        var mainModule = main.mainModule

        if (mode == "modulepath") {
            if (moduleInfo != null) {
                if (mainModule == null) mainModule = parseModuleName(Files.readString(moduleInfo))
                modulePath += outputDir.toString()
                // a plain resources directory has no module descriptor; it would become an automatic module
                modulePath += runtime.filter { it != resources }.map(Path::toString)
            } else {
                mainModule = null
                val fx = javafxJars.get().toSet()
                runtimeStr.forEach { (if (it in fx) modulePath else classpath) += it }
                addModules += JavaFxModules.moduleNames(javafxArtifactIds.get())
            }
        } else {
            mainModule = null
            classpath += runtimeStr
        }

        LaunchDescriptor.builder()
            .buildTool("gradle")
            .mainClass(main.mainClass)
            .mainModule(mainModule)
            .classpath(classpath)
            .modulePath(modulePath)
            .addModules(addModules)
            .jvmArgs(jvmArgs.get() + applicationJvmArgs.get())
            .appArgs(appArgs.get())
            .workingDir(workingDir.get())
            .sourceRoots(sourceRoots)
            .resourceRoots(resourceRoots)
            .outputDir(outputDir.toString())
            .release(release.getOrElse(Runtime.version().feature().toString()))
            .annotationProcessors(annotationProcessors.get())
            .build()
            .writeTo(outputFile.get().asFile.toPath())

        logger.lifecycle(
            "FXLoop: mainClass={} ({}), mode={}, classpath={}, modulePath={}, sourceRoots={}, resourceRoots={} -> {}",
            main.mainClass, main.source, mode, classpath.size, modulePath.size,
            sourceRoots.size, resourceRoots.size, outputFile.get().asFile,
        )
    }

    private fun resolveMode(modular: Boolean): String =
        when (val m = launchMode.getOrElse("auto").trim().lowercase()) {
            "auto" -> if (modular) "modulepath" else "classpath"
            "classpath", "modulepath" -> m
            else -> throw GradleException("Invalid fxloop.launchMode '${launchMode.get()}' (expected auto|classpath|modulepath)")
        }

    private fun hasClasses(dir: Path): Boolean =
        Files.isDirectory(dir) && Files.walk(dir).use { s -> s.anyMatch { it.toString().endsWith(".class") } }

    private fun existing(dirs: List<String>): List<String> =
        dirs.map { Path.of(it) }.filter { Files.isDirectory(it) }.map { it.toAbsolutePath().toString() }

    internal companion object {
        private val MODULE_NAME = Regex("(?:^|\\s)module\\s+([\\w.]+)\\s*\\{", RegexOption.MULTILINE)

        fun parseModuleName(source: String): String? {
            val stripped = source.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("//[^\\n]*"), " ")
            return MODULE_NAME.find(stripped)?.groupValues?.get(1)
        }
    }
}

package org.lab59.fxloop.gradle

import org.lab59.fxloop.launch.JavaFxArtifacts
import org.lab59.fxloop.launch.LaunchAssembler
import org.lab59.fxloop.launch.LaunchAssembly
import org.lab59.fxloop.launch.LaunchMode
import org.lab59.fxloop.launch.MainClassException
import org.lab59.fxloop.launch.MainClassStep
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
        val allClasses = (listOf(javaClasses) + classesDirs.files.map { it.toPath().toAbsolutePath() }).distinct()
        // outputDir is where FXLoop compiles to and watches for external builds: the Java classes dir, unless only
        // another language (Kotlin) produced classes; the remaining classes dirs go on the classpath.
        val outputDir = if (hasClasses(javaClasses)) javaClasses else allClasses.firstOrNull(::hasClasses) ?: javaClasses
        val extraClasses = allClasses.filter { it != outputDir && hasClasses(it) }
        val resources: Path? = resourcesDir.orNull?.asFile?.toPath()?.toAbsolutePath()

        val assembly = LaunchAssembler.assemble(
            buildTool = "gradle",
            mainClassSteps = listOf(
                MainClassStep("fxloop.mainClass", explicitMainClass.orNull),
                MainClassStep("javafx plugin", javafxMainClass.orNull),
                MainClassStep("application.mainClass", applicationMainClass.orNull),
                MainClassStep("jar manifest Main-Class", manifestMainClass.orNull),
            ),
            outputDir = outputDir,
            scanDirs = listOf(outputDir) + extraClasses,
            launchMode = launchMode.getOrElse("auto"),
            sourceRoots = sourceRoots.get(),
            resourceRoots = resourceRoots.get(),
            // Runtime classpath minus every compiled-classes dir (outputDir is carried separately), led by the other
            // languages' classes dirs; the resources dir stays so resources load from the classpath.
            runtimeClasspath = {
                val runtime = runtimeClasspath.files.map { it.toPath().toAbsolutePath() }.filter { it !in allClasses }.toMutableList()
                if (resources != null && Files.isDirectory(resources) && resources !in runtime) runtime.add(0, resources)
                runtime.addAll(0, extraClasses)
                runtime.map(Path::toString)
            },
            javafx = { JavaFxArtifacts(javafxJars.get().toSet(), javafxArtifactIds.get()) },
            // a plain resources directory has no module descriptor; it would become an automatic module
            excludeFromModulePath = setOfNotNull(resources?.toString()),
            jvmArgs = jvmArgs.get() + applicationJvmArgs.get(),
            appArgs = appArgs.get(),
            workingDir = workingDir.get(),
            release = release.orNull,
            annotationProcessors = { annotationProcessors.get() },
        )
        val a = when (assembly) {
            is LaunchAssembly.Assembled -> assembly
            is LaunchAssembly.MainClassUnresolved -> throw GradleException(mainClassMessage(assembly.error), assembly.error)
            is LaunchAssembly.InvalidLaunchMode ->
                throw GradleException(assembly.error.message ?: "Invalid fxloop.launchMode", assembly.error)
        }
        if (a.mode == LaunchMode.MODULEPATH && a.modular && extraClasses.isNotEmpty()) {
            logger.warn("FXLoop: modular app with classes outside {} ({}); they would need --patch-module, " +
                "set fxloop { launchMode = \"classpath\" } if the launch fails", outputDir, extraClasses)
        }
        val d = a.descriptor
        d.writeTo(outputFile.get().asFile.toPath())

        logger.lifecycle(
            "FXLoop: mainClass={} ({}), mode={}, classpath={}, modulePath={}, sourceRoots={}, resourceRoots={} -> {}",
            d.mainClass, a.mainClassSource, a.mode, d.classpath.size, d.modulePath.size,
            d.sourceRoots.size, d.resourceRoots.size, outputFile.get().asFile,
        )
    }

    private fun hasClasses(dir: Path): Boolean =
        Files.isDirectory(dir) && Files.walk(dir).use { s -> s.anyMatch { it.toString().endsWith(".class") } }

    internal companion object {
        fun mainClassMessage(e: MainClassException): String =
            if (e.noneFound) {
                "FXLoop: no main class found in ${e.outputDirs.joinToString()}. Compile first, or set fxloop { mainClass = \"<fqcn>\" }."
            } else {
                "FXLoop: cannot determine the main class unambiguously. Candidates:" + e.candidateLines +
                    "\nSet fxloop { mainClass = \"<fqcn>\" } (or application { mainClass = ... })."
            }
    }
}

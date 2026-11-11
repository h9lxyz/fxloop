package org.lab59.fxloop.launch

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** JavaFX artifacts on the runtime path: their files (as runtime entries) and artifact ids, in the caller's order. */
class JavaFxArtifacts(val jars: Set<String>, val artifactIds: List<String>)

/** Outcome of [LaunchAssembler.assemble]: the descriptor, or why it could not be built. Callers map failures to their own exception type. */
sealed interface LaunchAssembly {
    /** [mode] is `classpath` or `modulepath`; [modular] is true when a `module-info.java` was found. */
    class Assembled(val descriptor: LaunchDescriptor, val mode: String, val modular: Boolean, val mainClassSource: String) : LaunchAssembly

    /** No main class, or several candidates ([MainClassException.noneFound] tells which). */
    class MainClassUnresolved(val error: MainClassException) : LaunchAssembly

    /** The requested launch mode is not `auto`, `classpath` or `modulepath`; [error] carries the message to surface. */
    class InvalidLaunchMode(val error: IllegalArgumentException) : LaunchAssembly
}

/**
 * Builds the [LaunchDescriptor] from build-tool-neutral inputs, shared by `fxloop:prepare`, `fxloopPrepare` and the
 * server's plugin-free Maven prepare. Callers gather the inputs from their build model; everything that decides the
 * launch (main class, launch mode, classpath/module-path split, `--add-modules`) happens here.
 *
 * Inputs that can be costly or fail (runtime path, JavaFX artifacts, processor detection) are functions, evaluated only
 * once the main class and launch mode are settled and only where needed, so failures surface in the same order as before.
 */
object LaunchAssembler {
    const val PROCESSOR_SERVICE = "META-INF/services/javax.annotation.processing.Processor"
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param mainClassSteps explicit main-class sources in priority order; otherwise [scanDirs] are scanned
     * @param sourceRoots candidate source roots; those that are not directories are dropped (likewise [resourceRoots])
     * @param distinctRoots also drop duplicate roots (the server's Maven prepare collects roots from several places)
     * @param runtimeClasspath runtime entries without [outputDir], in launch order
     * @param javafx JavaFX artifacts; consulted only for a forced module path without `module-info.java`
     * @param excludeFromModulePath entries kept off the module path of a modular app (e.g. a plain resources dir)
     * @param release compiler release level; `null` means the running JDK's feature version
     */
    fun assemble(
        buildTool: String,
        mainClassSteps: List<MainClassStep>,
        outputDir: Path,
        scanDirs: List<Path> = listOf(outputDir),
        launchMode: String?,
        sourceRoots: List<String>,
        resourceRoots: List<String>,
        distinctRoots: Boolean = false,
        runtimeClasspath: () -> List<String>,
        javafx: () -> JavaFxArtifacts,
        excludeFromModulePath: Set<String> = emptySet(),
        jvmArgs: List<String>,
        appArgs: List<String>,
        workingDir: String,
        release: String?,
        annotationProcessors: () -> Boolean,
    ): LaunchAssembly {
        val main = try {
            MainClassResolver.resolve(mainClassSteps, scanDirs)
        } catch (e: MainClassException) {
            return LaunchAssembly.MainClassUnresolved(e)
        }

        val sources = existing(sourceRoots, distinctRoots)
        val resources = existing(resourceRoots, distinctRoots)
        val moduleInfo = sources.map { Path.of(it, "module-info.java") }.firstOrNull { Files.isRegularFile(it) }
        val mode = try {
            LaunchMode.resolve(launchMode, moduleInfo != null)
        } catch (e: IllegalArgumentException) {
            return LaunchAssembly.InvalidLaunchMode(e)
        }

        val runtime = runtimeClasspath()
        val classpath = mutableListOf<String>()
        val modulePath = mutableListOf<String>()
        val addModules = mutableListOf<String>()
        var mainModule = main.mainModule
        if (mode == LaunchMode.MODULEPATH) {
            if (moduleInfo != null) {
                if (mainModule == null) mainModule = LaunchMode.parseModuleName(Files.readString(moduleInfo))
                modulePath += outputDir.toString()
                modulePath += runtime.filter { it !in excludeFromModulePath }
            } else {
                mainModule = null
                val fx = javafx()
                runtime.forEach { (if (it in fx.jars) modulePath else classpath) += it }
                addModules += JavaFxModules.moduleNames(fx.artifactIds)
            }
        } else {
            mainModule = null
            classpath += runtime
        }

        val descriptor = LaunchDescriptor.builder()
            .buildTool(buildTool)
            .mainClass(main.mainClass)
            .mainModule(mainModule)
            .classpath(classpath)
            .modulePath(modulePath)
            .addModules(addModules)
            .jvmArgs(jvmArgs)
            .appArgs(appArgs)
            .workingDir(workingDir)
            .sourceRoots(sources)
            .resourceRoots(resources)
            .outputDir(outputDir.toString())
            .release(release ?: Runtime.version().feature().toString())
            .annotationProcessors(annotationProcessors())
            .build()
        return LaunchAssembly.Assembled(descriptor, mode, moduleInfo != null, main.source)
    }

    /** [entries] as absolute paths, without [outputDir] (which the descriptor carries separately). */
    fun withoutOutputDir(entries: List<String>, outputDir: Path): List<String> =
        entries.map { Path.of(it).toAbsolutePath() }.filter { it != outputDir }.map { it.toString() }

    /**
     * Maven's release level: the compiler plugin's `<release>`, else `maven.compiler.release` / `maven.compiler.target`
     * looked up through [property], else the running JDK. Unresolved `${...}` values are ignored.
     */
    fun mavenRelease(pluginRelease: String?, property: (String) -> String?): String {
        if (isValue(pluginRelease)) return pluginRelease!!
        for (key in listOf("maven.compiler.release", "maven.compiler.target")) {
            val v = property(key)
            if (isValue(v)) return v!!.trim()
        }
        return Runtime.version().feature().toString()
    }

    /** Whitespace-separated arguments (e.g. javafx-maven-plugin `commandlineArgs`); blank or `null` gives none. */
    fun splitArgs(text: String?): List<String> = text.orEmpty().split(WHITESPACE).filter { it.isNotEmpty() }

    /** True when [configured] (explicit processor paths) or a jar or directory on [path] declares an annotation processor. */
    fun detectProcessors(configured: Boolean, path: () -> List<String>): Boolean {
        if (configured) return true
        for (entry in path()) {
            val f = File(entry)
            try {
                if (f.isFile) ZipFile(f).use { if (it.getEntry(PROCESSOR_SERVICE) != null) return true }
                else if (f.isDirectory && File(f, PROCESSOR_SERVICE).isFile) return true
            } catch (_: IOException) {
                // not a readable jar: skip
            }
        }
        return false
    }

    private fun isValue(v: String?): Boolean = v != null && v.isNotBlank() && !v.contains("\${")

    private fun existing(dirs: List<String>, distinct: Boolean): List<String> =
        dirs.map { Path.of(it) }.filter { Files.isDirectory(it) }.map { it.toAbsolutePath().toString() }
            .let { if (distinct) it.distinct() else it }
}

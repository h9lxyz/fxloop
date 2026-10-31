package dev.fxloop.maven

import dev.fxloop.protocol.LaunchDescriptor
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.apache.maven.model.Plugin
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject

/** Writes `target/fxloop/launch.json` describing how to launch the project's JavaFX application. */
@Mojo(name = "prepare", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
open class PrepareMojo : AbstractMojo() {

    @field:Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    /** Target file for the launch descriptor. */
    @field:Parameter(property = "fxloop.outputFile", defaultValue = "\${project.build.directory}/fxloop/launch.json")
    lateinit var outputFile: File

    /** Main class (optionally `module/fqcn`); overrides all auto-detection. */
    @field:Parameter(property = "fxloop.mainClass")
    var mainClass: String? = null

    /** `auto`, `classpath` or `modulepath`. */
    @field:Parameter(property = "fxloop.launchMode", defaultValue = "auto")
    var launchMode: String? = null

    /** Extra JVM arguments. */
    @field:Parameter(property = "fxloop.jvmArgs")
    var jvmArgs: List<String>? = null

    /** Extra program arguments. */
    @field:Parameter(property = "fxloop.appArgs")
    var appArgs: List<String>? = null

    @Throws(MojoExecutionException::class, MojoFailureException::class)
    override fun execute() {
        try {
            doExecute()
        } catch (e: IOException) {
            throw MojoExecutionException("Failed to write launch descriptor: ${e.message}", e)
        }
    }

    private fun doExecute() {
        val outputDir = Path.of(project.build.outputDirectory).toAbsolutePath()
        val javafxPlugin = PluginConfigs.find(project, "org.openjfx", "javafx-maven-plugin")
        val execPlugin = PluginConfigs.find(project, "org.codehaus.mojo", "exec-maven-plugin")
        val jarPlugin = PluginConfigs.find(project, "org.apache.maven.plugins", "maven-jar-plugin")
        val compilerPlugin = PluginConfigs.find(project, "org.apache.maven.plugins", "maven-compiler-plugin")

        val main = MainClassResolver.resolve(
            MainClassResolver.Inputs(
                mainClass,
                PluginConfigs.text(javafxPlugin, "mainClass"),
                PluginConfigs.text(execPlugin, "mainClass"),
                PluginConfigs.text(jarPlugin, "archive", "manifest", "mainClass"),
                outputDir,
            ),
        )

        val sourceRoots = existing(project.compileSourceRoots)
        val resourceRoots = project.resources.flatMap { existing(listOf(it.directory)) }

        val moduleInfoSource = findModuleInfo(project.compileSourceRoots)
        val mode = resolveMode(moduleInfoSource != null)

        val runtime = runtimeElements()
            .map { Path.of(it).toAbsolutePath() }
            .filter { it != outputDir }
            .map { it.toString() }

        val classpath = mutableListOf<String>()
        val modulePath = mutableListOf<String>()
        val addModules = mutableListOf<String>()
        var mainModule = main.mainModule

        if (mode == "modulepath") {
            if (moduleInfoSource != null) {
                if (mainModule == null) mainModule = parseModuleName(Files.readString(Path.of(moduleInfoSource)))
                modulePath.add(outputDir.toString())
                modulePath.addAll(runtime)
            } else {
                mainModule = null
                val fx = project.artifacts.filter { JavaFxModules.GROUP_ID == it.groupId && it.file != null }
                val fxPaths = fx.map { it.file.toPath().toAbsolutePath().toString() }
                for (p in runtime) (if (p in fxPaths) modulePath else classpath).add(p)
                addModules.addAll(JavaFxModules.moduleNames(fx.map { it.artifactId }))
            }
        } else {
            mainModule = null
            classpath.addAll(runtime)
        }

        val jvm = jvmArgs.orEmpty() + PluginConfigs.childTexts(javafxPlugin, "options")
        val app = appArgs.orEmpty() +
            PluginConfigs.text(javafxPlugin, "commandlineArgs").orEmpty().split(WHITESPACE).filter { it.isNotEmpty() }

        val d = LaunchDescriptor.builder()
            .buildTool("maven")
            .mainClass(main.mainClass)
            .mainModule(mainModule)
            .classpath(classpath)
            .modulePath(modulePath)
            .addModules(addModules)
            .jvmArgs(jvm)
            .appArgs(app)
            .workingDir(project.basedir.toPath().toAbsolutePath().toString())
            .sourceRoots(sourceRoots)
            .resourceRoots(resourceRoots)
            .outputDir(outputDir.toString())
            .release(resolveRelease(compilerPlugin))
            .annotationProcessors(detectProcessors(compilerPlugin))
            .build()
        d.writeTo(outputFile.toPath())

        log.info(
            "FXLoop: mainClass=${main.mainClass} (${main.source}), mode=$mode, classpath=${classpath.size}, " +
                "modulePath=${modulePath.size}, sourceRoots=${sourceRoots.size}, " +
                "resourceRoots=${resourceRoots.size} -> $outputFile",
        )
    }

    private fun runtimeElements(): List<String> =
        try {
            project.runtimeClasspathElements
        } catch (e: Exception) {
            throw MojoExecutionException("Cannot resolve runtime classpath", e)
        }

    private fun resolveMode(modular: Boolean): String =
        when (val m = (launchMode ?: "auto").trim().lowercase()) {
            "auto" -> if (modular) "modulepath" else "classpath"
            "classpath", "modulepath" -> m
            else -> throw MojoFailureException("Invalid fxloop.launchMode '$launchMode' (expected auto|classpath|modulepath)")
        }

    private fun resolveRelease(compilerPlugin: Plugin?): String {
        val r = PluginConfigs.text(compilerPlugin, "release")
        if (isValue(r)) return r!!
        for (key in listOf("maven.compiler.release", "maven.compiler.target")) {
            val v = project.properties.getProperty(key)
            if (isValue(v)) return v.trim()
        }
        return Runtime.version().feature().toString()
    }

    private fun detectProcessors(compilerPlugin: Plugin?): Boolean {
        if (PluginConfigs.has(compilerPlugin, "annotationProcessorPaths")) return true
        val elements = try {
            project.compileClasspathElements
        } catch (e: Exception) {
            return false
        }
        val svc = "META-INF/services/javax.annotation.processing.Processor"
        for (el in elements) {
            val f = File(el)
            if (f.isFile) {
                try {
                    ZipFile(f).use { if (it.getEntry(svc) != null) return true }
                } catch (ignored: IOException) {
                    // not a readable jar: skip
                }
            } else if (f.isDirectory && File(f, svc).isFile) {
                return true
            }
        }
        return false
    }

    companion object {
        private val MODULE_NAME = Regex("""(?:^|\s)module\s+([\w.]+)\s*\{""", RegexOption.MULTILINE)
        private val WHITESPACE = Regex("\\s+")

        @JvmStatic
        internal fun parseModuleName(moduleInfoSource: String): String? {
            val stripped = moduleInfoSource
                .replace(Regex("(?s)/\\*.*?\\*/"), " ")
                .replace(Regex("//[^\\n]*"), " ")
            return MODULE_NAME.find(stripped)?.groupValues?.get(1)
        }

        private fun findModuleInfo(roots: List<String>): String? =
            roots.map { Path.of(it, "module-info.java") }.firstOrNull { Files.isRegularFile(it) }?.toString()

        private fun existing(dirs: List<String?>): List<String> =
            dirs.filterNotNull().filter { Files.isDirectory(Path.of(it)) }.map { Path.of(it).toAbsolutePath().toString() }

        private fun isValue(v: String?): Boolean = v != null && v.isNotBlank() && !v.contains("\${")
    }
}

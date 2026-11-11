package org.lab59.fxloop.maven

import org.lab59.fxloop.launch.JavaFxArtifacts
import org.lab59.fxloop.launch.JavaFxModules
import org.lab59.fxloop.launch.LaunchAssembler
import org.lab59.fxloop.launch.LaunchAssembly
import org.lab59.fxloop.launch.MainClassException
import org.lab59.fxloop.launch.MainClassStep
import java.io.File
import java.io.IOException
import java.nio.file.Path
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

        val assembly = LaunchAssembler.assemble(
            buildTool = "maven",
            mainClassSteps = listOf(
                MainClassStep("fxloop.mainClass", mainClass),
                MainClassStep("javafx-maven-plugin", PluginConfigs.text(javafxPlugin, "mainClass")),
                MainClassStep("exec-maven-plugin", PluginConfigs.text(execPlugin, "mainClass")),
                MainClassStep("maven-jar-plugin manifest", PluginConfigs.text(jarPlugin, "archive", "manifest", "mainClass")),
            ),
            outputDir = outputDir,
            launchMode = launchMode,
            sourceRoots = project.compileSourceRoots,
            resourceRoots = project.resources.mapNotNull { it.directory },
            runtimeClasspath = { LaunchAssembler.withoutOutputDir(runtimeElements(), outputDir) },
            javafx = {
                val fx = project.artifacts.filter { JavaFxModules.GROUP_ID == it.groupId && it.file != null }
                JavaFxArtifacts(fx.map { it.file.toPath().toAbsolutePath().toString() }.toSet(), fx.map { it.artifactId })
            },
            jvmArgs = jvmArgs.orEmpty() + PluginConfigs.childTexts(javafxPlugin, "options"),
            appArgs = appArgs.orEmpty() + LaunchAssembler.splitArgs(PluginConfigs.text(javafxPlugin, "commandlineArgs")),
            workingDir = project.basedir.toPath().toAbsolutePath().toString(),
            release = LaunchAssembler.mavenRelease(PluginConfigs.text(compilerPlugin, "release")) { project.properties.getProperty(it) },
            annotationProcessors = { detectProcessors(compilerPlugin) },
        )
        val a = when (assembly) {
            is LaunchAssembly.Assembled -> assembly
            is LaunchAssembly.MainClassUnresolved -> throw MojoFailureException(mainClassMessage(assembly.error), assembly.error)
            is LaunchAssembly.InvalidLaunchMode -> throw MojoFailureException(assembly.error.message, assembly.error)
        }
        val d = a.descriptor
        d.writeTo(outputFile.toPath())

        log.info(
            "FXLoop: mainClass=${d.mainClass} (${a.mainClassSource}), mode=${a.mode}, classpath=${d.classpath.size}, " +
                "modulePath=${d.modulePath.size}, sourceRoots=${d.sourceRoots.size}, " +
                "resourceRoots=${d.resourceRoots.size} -> $outputFile",
        )
    }

    private fun runtimeElements(): List<String> =
        try {
            project.runtimeClasspathElements
        } catch (e: Exception) {
            throw MojoExecutionException("Cannot resolve runtime classpath", e)
        }

    private fun detectProcessors(compilerPlugin: Plugin?): Boolean =
        LaunchAssembler.detectProcessors(PluginConfigs.has(compilerPlugin, "annotationProcessorPaths")) {
            try {
                project.compileClasspathElements
            } catch (e: Exception) {
                emptyList()
            }
        }

    companion object {
        internal fun mainClassMessage(e: MainClassException): String =
            if (e.noneFound) {
                "No main class found in ${e.outputDirs.joinToString()}. Compile first, or set -Dfxloop.mainClass=<fqcn>."
            } else {
                "Cannot determine the main class unambiguously. Candidates:" + e.candidateLines +
                    "\nSet -Dfxloop.mainClass=<fqcn> (or <mainClass> in the fxloop plugin configuration)."
            }
    }
}

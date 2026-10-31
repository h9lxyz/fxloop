package dev.fxloop.gradle

import dev.fxloop.protocol.LaunchDescriptor
import java.nio.file.Files
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/**
 * `gradle fxloopRun`: runs the app from `launch.json` in the foreground with the FXLoop agent and the dev bar. Nothing
 * is supervised: the developer explores the app, and any agent session can connect later (the server finds the
 * handshake this task writes). Closing the app or cancelling the build (Ctrl+C) ends the task.
 */
@UntrackedTask(because = "Runs the application")
abstract class FxloopRunTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val launchFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val agentJar: ConfigurableFileCollection
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>

    /** Dev bar before an agent connects: `on`, `pill` or `off` (`-Pfxloop.bar=`). */
    @get:Input abstract val bar: Property<String>

    /** Extra program arguments for this run, e.g. `--args="--de --rtl"`. */
    @get:Input @get:Optional
    @get:Option(option = "args", description = "Extra program arguments for this run, whitespace-separated")
    abstract val extraArgs: Property<String>

    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val launch = launchFile.get().asFile.toPath().toAbsolutePath()
        val d = LaunchDescriptor.readFrom(launch)
        val handshake = launch.resolveSibling("handshake.json")
        Files.deleteIfExists(handshake)
        val agent = agentJar.files.singleOrNull { it.name.endsWith(".jar") }
            ?: throw GradleException("cannot resolve the FXLoop agent jar (a SNAPSHOT needs mavenLocal() in the project's repositories)")
        val extra = extraArgs.orNull.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cmd = d.runCommand(javaLauncher.get().executablePath.asFile.absolutePath,
            "${agent.absolutePath}=${d.runAgentArgs(handshake, bar.get())}", extra)
        logger.lifecycle("FXLoop: running ${d.mainClass} with the dev bar. To bring in your agent, ask it to " +
            "\"connect to my running app\" (handshake: $handshake). Ctrl+C stops the app.")
        try {
            val result = execOperations.exec { spec ->
                spec.commandLine(cmd)
                spec.workingDir(d.workingDir)
                spec.isIgnoreExitValue = true
            }
            if (result.exitValue != 0) throw GradleException("the app exited with code ${result.exitValue}")
        } finally {
            Files.deleteIfExists(handshake) // a dead app must not be found by the next agent session
        }
    }
}

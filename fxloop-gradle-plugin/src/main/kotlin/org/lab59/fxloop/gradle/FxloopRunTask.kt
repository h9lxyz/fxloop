package org.lab59.fxloop.gradle

import org.lab59.fxloop.protocol.LaunchDescriptor
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
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

    /** Inspector helper (`-Pfxloop.inspector=false` turns it off): Inspect CSS works before an agent connects. */
    @get:Input abstract val inspector: Property<Boolean>
    @get:Internal abstract val serverJar: ConfigurableFileCollection
    @get:Internal abstract val projectDir: DirectoryProperty

    /** "Try it live" in the CSS inspector, also for agent sessions that connect (`-Pfxloop.inspectorEdit=false` keeps it read-only). */
    @get:Input abstract val inspectorEdit: Property<Boolean>

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
            // owner = this (daemon) JVM: a killed daemon must not leave the app behind
            "${agent.absolutePath}=${d.runAgentArgs(handshake, bar.get(), inspectorEdit.get(), ownerPid = ProcessHandle.current().pid())}", extra)
        logger.lifecycle("FXLoop: running ${d.mainClass} with the dev bar. To bring in your agent, ask it to " +
            "\"connect to my running app\" (handshake: $handshake). Ctrl+C stops the app.")
        val helper = if (inspector.get()) startHelper(d, java = cmd.first(), log = launch.resolveSibling("inspector.log")) else null
        try {
            val result = execOperations.exec { spec ->
                spec.commandLine(cmd)
                spec.workingDir(d.workingDir)
                spec.isIgnoreExitValue = true
            }
            if (result.exitValue != 0) throw GradleException("the app exited with code ${result.exitValue}")
        } finally {
            helper?.takeIf { it.isAlive }?.destroy()
            Files.deleteIfExists(handshake) // a dead app must not be found by the next agent session
        }
    }

    /**
     * Starts the inspector helper; it waits for the app's handshake and exits with the app. Its output goes to [log]
     * (a daemon's child would print nowhere visible); the inspector URL is relayed once it appears.
     */
    private fun startHelper(d: LaunchDescriptor, java: String, log: Path): Process? {
        val jar = runCatching { serverJar.files.singleOrNull { it.name.endsWith(".jar") } }.getOrNull()
        if (jar == null) {
            logger.warn("FXLoop: cannot resolve org.lab59.fxloop:fxloop-server (all) for the inspector helper; Inspect CSS " +
                "needs an agent session until it is available (a SNAPSHOT needs mavenLocal())")
            return null
        }
        Files.deleteIfExists(log)
        val p = ProcessBuilder(d.inspectorHelperCommand(java, jar.toPath(), projectDir.get().asFile.toPath(), bar.get(), inspectorEdit.get()))
            .directory(projectDir.get().asFile).redirectErrorStream(true).redirectOutput(log.toFile())
            .apply { environment()["FXLOOP_LOG"] = "warn" }.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (p.isAlive && System.currentTimeMillis() < deadline) {
            val line = runCatching { Files.readAllLines(log) }.getOrDefault(emptyList()).firstOrNull { "CSS inspector:" in it }
            if (line != null) {
                logger.lifecycle(line.removePrefix("[fxloop] "))
                break
            }
            Thread.sleep(200)
        }
        return p
    }
}

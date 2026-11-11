package org.lab59.fxloop.maven

import org.lab59.fxloop.launch.LaunchDescriptor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Execute
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException

/**
 * `mvn fxloop:run`: compiles, writes `launch.json` like [PrepareMojo], then runs the app in the foreground with the
 * FXLoop agent and the dev bar. Nothing is supervised: the developer explores the app, and any agent session can
 * connect later (the server finds the handshake this run writes). Ctrl+C or closing the app ends the goal.
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
class RunMojo : PrepareMojo() {

    @field:Component
    lateinit var repositorySystem: RepositorySystem

    @field:Parameter(defaultValue = "\${repositorySystemSession}", readonly = true, required = true)
    lateinit var repositorySession: RepositorySystemSession

    @field:Parameter(defaultValue = "\${project.remotePluginRepositories}", readonly = true, required = true)
    lateinit var pluginRepositories: List<RemoteRepository>

    @field:Parameter(defaultValue = "\${plugin}", readonly = true, required = true)
    lateinit var plugin: PluginDescriptor

    /** Dev bar before an agent connects: `on`, `pill` or `off`. */
    @field:Parameter(property = "fxloop.bar", defaultValue = "on")
    var bar: String = "on"

    /** Agent jar override; default: `org.lab59.fxloop:fxloop-agent` at this plugin's version. */
    @field:Parameter(property = "fxloop.agentJar")
    var agentJar: File? = null

    /** Start the inspector helper so the dev bar's Inspect CSS works before an agent session connects. */
    @field:Parameter(property = "fxloop.inspector", defaultValue = "true")
    var inspector: Boolean = true

    /**
     * "Try it live" in the CSS inspector: edit values and add rules in a scratch stylesheet, never in source. Applies to
     * the helper and to agent sessions that connect to this run.
     */
    @field:Parameter(property = "fxloop.inspectorEdit", defaultValue = "true")
    var inspectorEdit: Boolean = true

    /** Server jar override for the inspector helper; default: `org.lab59.fxloop:fxloop-server:jar:all` at this plugin's version. */
    @field:Parameter(property = "fxloop.serverJar")
    var serverJar: File? = null

    /** JDK to run the app with; default: the one running Maven. */
    @field:Parameter(property = "fxloop.javaHome")
    var javaHome: File? = null

    @Throws(MojoExecutionException::class, MojoFailureException::class)
    override fun execute() {
        super.execute()
        val d = LaunchDescriptor.readFrom(outputFile.toPath())
        val handshake = outputFile.toPath().toAbsolutePath().resolveSibling("handshake.json")
        Files.deleteIfExists(handshake)
        val java = javaExecutable()
        val cmd = d.runCommand(java, "${resolve(agentJar, "org.lab59.fxloop:fxloop-agent:${plugin.version}", "fxloop.agentJar")}=" +
            d.runAgentArgs(handshake, bar, inspectorEdit, ownerPid = ProcessHandle.current().pid()))
        val helperCmd = if (inspector) d.inspectorHelperCommand(java,
            resolve(serverJar, "org.lab59.fxloop:fxloop-server:jar:all:${plugin.version}", "fxloop.serverJar"),
            project.basedir.toPath().toAbsolutePath(), bar, inspectorEdit) else null
        log.info("FXLoop: running ${d.mainClass} with the dev bar. To bring in your agent, ask it to " +
            "\"connect to my running app\" (handshake: $handshake). Ctrl+C stops the app.")
        val p = ProcessBuilder(cmd).directory(File(d.workingDir)).inheritIO().start()
        // the helper waits for the app's handshake itself and exits with the app
        val helper = helperCmd?.let {
            ProcessBuilder(it).directory(project.basedir).inheritIO().apply { environment()["FXLOOP_LOG"] = "warn" }.start()
        }
        val hook = Thread { // Ctrl+C / kill: take the app down with Maven and leave no handshake behind
            if (p.isAlive) p.destroy()
            helper?.takeIf { it.isAlive }?.destroy()
            runCatching { Files.deleteIfExists(handshake) }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        val exit = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            p.destroy()
            Thread.currentThread().interrupt()
            throw MojoExecutionException("interrupted while the app was running", e)
        } finally {
            helper?.takeIf { it.isAlive }?.destroy()
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            Files.deleteIfExists(handshake) // a dead app must not be found by the next agent session
        }
        if (exit != 0) throw MojoFailureException("the app exited with code $exit")
    }

    private fun javaExecutable(): String {
        val home = javaHome?.toPath() ?: Path.of(System.getProperty("java.home"))
        val exe = home.resolve("bin").resolve(if (File.separatorChar == '\\') "java.exe" else "java")
        if (!Files.isRegularFile(exe)) throw MojoFailureException("no java executable at $exe (check fxloop.javaHome)")
        return exe.toString()
    }

    /** [override] when set, else the FXLoop artifact [coords] from the plugin repositories. */
    private fun resolve(override: File?, coords: String, property: String): Path {
        override?.let {
            if (!it.isFile) throw MojoFailureException("$property does not exist: $it")
            return it.toPath().toAbsolutePath()
        }
        return try {
            val request = ArtifactRequest(DefaultArtifact(coords), pluginRepositories, null)
            repositorySystem.resolveArtifact(repositorySession, request).artifact.file.toPath().toAbsolutePath()
        } catch (e: ArtifactResolutionException) {
            throw MojoExecutionException("cannot resolve $coords (until FXLoop is on Maven Central, run " +
                "`java -jar ~/.fxloop/fxloop-server.jar --seed-local-repo` once, or `./mvnw install` in the FXLoop repository; " +
                "or set $property)", e)
        }
    }
}

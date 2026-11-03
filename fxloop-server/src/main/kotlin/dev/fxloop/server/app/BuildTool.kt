package dev.fxloop.server.app

import dev.fxloop.protocol.LaunchDescriptor
import dev.fxloop.server.Config
import dev.fxloop.server.mcp.ToolException
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Build-tool integration (Maven first-class, Gradle parity — PRD 02 §2, PRD 03 §2). Produces a [LaunchDescriptor];
 * the reload pipeline itself is build-tool independent.
 */
class BuildTool(private val config: Config) {
    enum class Kind { MAVEN, GRADLE }

    data class Result(val exit: Int, val output: String) {
        fun tail(lines: Int) = output.lines().takeLast(lines).joinToString("\n")
    }

    private val log = LoggerFactory.getLogger(BuildTool::class.java)
    val kind: Kind = detect(config)
    private val command: List<String> = command(config, kind)

    /** `target/fxloop` (Maven) or `build/fxloop` (Gradle). */
    val fxloopDir: Path get() = config.projectDir.resolve(if (kind == Kind.GRADLE) "build" else "target").resolve("fxloop")

    private fun base(): MutableList<String> = command.toMutableList().apply {
        if (kind == Kind.MAVEN) addAll(listOf("-q", "-B", "-f", config.projectDir.resolve("pom.xml").toString()))
        // a daemon started by fxloop should not linger for Gradle's default 3 h idle time
        else addAll(listOf("-q", "-p", config.projectDir.toString(), "-Dorg.gradle.daemon.idletimeout=$GRADLE_DAEMON_IDLE_MS"))
    }

    /** Which prepare produced the current launch.json: `plugin` (fxloop build plugin) or `server` (plugin-free, PRD 11 4.2). */
    @Volatile var prepareRoute: String? = null
        private set

    /**
     * Compiles and emits launch.json. The build plugin wins when present; otherwise the server does the plugin's
     * job itself ([MavenPrepare], [GradlePrepare]) and records `prepare: server`.
     */
    fun prepare(): LaunchDescriptor {
        val launch = fxloopDir.resolve(LaunchDescriptor.FILE_NAME)
        if (!config.prepare) return read(launch)
        return if (kind == Kind.MAVEN) prepareMaven(launch) else prepareGradle(launch)
    }

    private fun prepareMaven(launch: Path): LaunchDescriptor {
        if (config.prepareRoute != "server") {
            val cmd = base().apply {
                addAll(listOf("-DskipTests", "compile", "dev.fxloop:fxloop-maven-plugin:${Versions.SERVER}:prepare"))
                config.mainClass?.let { add("-Dfxloop.mainClass=$it") }
                config.launchMode?.let { add("-Dfxloop.launchMode=$it") }
            }
            val r = run(cmd, 600)
            if (r.exit == 0) return read(launch).also { prepareRoute = "plugin" }
            if (config.prepareRoute == "plugin" || !pluginUnresolvable(r.output)) failed(r)
            log.info("prepare: fxloop-maven-plugin unavailable; the server prepares the project itself")
        }
        return prepareMavenInServer()
    }

    private fun prepareMavenInServer(): LaunchDescriptor {
        val pomFile = config.projectDir.resolve("pom.xml")
        Files.createDirectories(fxloopDir)
        val r = run(base() + MavenPrepare.goals(fxloopDir, pomFile), 600)
        if (r.exit != 0) failed(r)
        val d = try {
            val pom = MavenPrepare.loadPom(fxloopDir, pomFile)
            MavenPrepare.describe(config.projectDir, pom, MavenPrepare.readClasspath(fxloopDir), config.mainClass, launchMode = config.launchMode)
        } catch (e: IOException) {
            throw ToolException("BUILD_FAILED", "cannot read the Maven build model: ${e.message}", "check that mvn runs: ${command.joinToString(" ")} -v")
        } catch (e: org.xml.sax.SAXException) {
            throw ToolException("BUILD_FAILED", "cannot parse the effective POM: ${e.message}", "delete ${fxloopDir.resolve(MavenPrepare.EFFECTIVE_POM)} and retry")
        }
        d.writeTo(fxloopDir.resolve(LaunchDescriptor.FILE_NAME))
        prepareRoute = "server"
        log.info("prepare: server (mainClass={}, classpath={}, modulePath={})", d.mainClass, d.classpath.size, d.modulePath.size)
        return d
    }

    private fun prepareGradle(launch: Path): LaunchDescriptor {
        val applied = config.prepareRoute == "plugin" ||
            (config.prepareRoute != "server" && GradlePrepare.pluginApplied(config.projectDir))
        if (applied) {
            val r = run(base() + listOf("classes", "fxloopPrepare"), 600)
            if (r.exit == 0) return read(launch).also { prepareRoute = "plugin" }
            if (config.prepareRoute == "plugin" || !GradlePrepare.taskMissing(r.output)) failed(r)
            log.info("prepare: the project does not apply dev.fxloop; the server applies it through an init script")
        }
        val r = run(base() + gradleInit() + listOf("classes", "fxloopPrepare"), 600)
        if (r.exit != 0) {
            // the plugin was applied some way the grep missed (convention plugin, buildSrc): the plain run is right
            if (config.prepareRoute != "server" && GradlePrepare.duplicateTask(r.output)) {
                val again = run(base() + listOf("classes", "fxloopPrepare"), 600)
                if (again.exit == 0) return read(launch).also { prepareRoute = "plugin" }
                failed(again)
            }
            failed(r)
        }
        prepareRoute = "server"
        log.info("prepare: server (Gradle init script)")
        return read(launch)
    }

    private fun gradleInit(): List<String> {
        val jars = try {
            GradlePrepare.extract(GradlePrepare.defaultCacheDir(Versions.SERVER))
        } catch (e: ToolException) {
            if (e.message?.startsWith("cannot extract") != true) throw e
            GradlePrepare.extract(fxloopDir.resolve("gradle"))
        }
        val script = GradlePrepare.writeInitScript(fxloopDir.resolve(GradlePrepare.INIT_SCRIPT), jars)
        return listOf("--init-script", script.toString())
    }

    internal fun pluginUnresolvable(output: String): Boolean = "fxloop-maven-plugin" in output &&
        listOf("could not be resolved", "Could not find artifact", "Could not transfer", "No plugin found", "PluginResolutionException")
            .any { it in output }

    private fun failed(r: Result): Nothing =
        throw ToolException("BUILD_FAILED", "build failed (exit ${r.exit}):\n${r.tail(40)}",
            "fix the build error above (once the app runs, fx_reload returns compile errors as data)")

    /** Full compile through the build tool (annotation-processor fallback, PRD 02 §3). */
    fun compile(): Result = run(base() + if (kind == Kind.MAVEN) listOf("-o", "-DskipTests", "compile") else listOf("--offline", "classes"), 600)

    private fun read(launch: Path): LaunchDescriptor = try {
        LaunchDescriptor.readFrom(launch)
    } catch (e: IOException) {
        throw ToolException("CONFIG", "cannot read $launch: ${e.message}",
            if (kind == Kind.MAVEN) "run mvn compile dev.fxloop:fxloop-maven-plugin:prepare" else "run gradlew fxloopPrepare")
    }

    fun run(cmd: List<String>, timeoutSec: Long): Result {
        log.info("running {}", cmd.joinToString(" "))
        val p = try {
            ProcessTree.track(ProcessBuilder(cmd).directory(config.projectDir.toFile()).redirectErrorStream(true).start())
        } catch (e: IOException) {
            throw ToolException("BUILD_FAILED", "cannot run ${cmd[0]}: ${e.message}",
                "install Maven/Gradle, add a wrapper, or set FXLOOP_BUILD_CMD")
        }
        var finished = false
        try {
            p.outputStream.close()
            val out = StringBuilder()
            val pump = thread(isDaemon = true) { runCatching { out.append(String(p.inputStream.readAllBytes())) } }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) return Result(-1, "timed out after $timeoutSec s")
            finished = true
            pump.join(5000)
            return Result(p.exitValue(), out.toString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ToolException("BUILD_FAILED", "build interrupted", "retry the call")
        } finally {
            // timeout, interrupt or any failure: never leave the build (and its forks) running
            if (finished) ProcessTree.untrack(p) else ProcessTree.kill(p)
        }
    }

    companion object {
        const val GRADLE_DAEMON_IDLE_MS = 600_000
        private val BUILD_FILES = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

        fun detect(c: Config): Kind = when {
            c.buildTool != null -> if (c.buildTool.equals("gradle", true)) Kind.GRADLE else Kind.MAVEN
            Files.exists(c.projectDir.resolve("pom.xml")) -> Kind.MAVEN
            BUILD_FILES.any { Files.exists(c.projectDir.resolve(it)) } -> Kind.GRADLE
            else -> Kind.MAVEN
        }

        /** Prefers a wrapper found in the project dir or any ancestor; `FXLOOP_BUILD_CMD` overrides. */
        private fun command(c: Config, kind: Kind): List<String> {
            c.buildCmd?.let { return it.trim().split(Regex("\\s+")) }
            val windows = File.separatorChar == '\\'
            val wrapper = when (kind) {
                Kind.MAVEN -> if (windows) "mvnw.cmd" else "mvnw"
                Kind.GRADLE -> if (windows) "gradlew.bat" else "gradlew"
            }
            generateSequence(c.projectDir) { it.parent }.map { it.resolve(wrapper) }.firstOrNull(Files::isRegularFile)
                ?.let { return listOf(it.toString()) }
            return listOf(when (kind) {
                Kind.MAVEN -> if (windows) "mvn.cmd" else "mvn"
                Kind.GRADLE -> if (windows) "gradle.bat" else "gradle"
            })
        }
    }
}

/** Build-time version of this server (used to invoke the matching build plugin). */
object Versions {
    val SERVER: String = Versions::class.java.`package`?.implementationVersion ?: "1.0.0-SNAPSHOT"
}

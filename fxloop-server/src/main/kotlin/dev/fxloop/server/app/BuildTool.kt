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

    /** Compiles and emits launch.json; Maven falls back to the PoC classpath pipeline when the plugin is unavailable. */
    fun prepare(): LaunchDescriptor {
        val launch = fxloopDir.resolve(LaunchDescriptor.FILE_NAME)
        if (!config.prepare) return read(launch)
        val cmd = base()
        if (kind == Kind.MAVEN) {
            cmd += listOf("-DskipTests", "compile", "dev.fxloop:fxloop-maven-plugin:${Versions.SERVER}:prepare")
            config.mainClass?.let { cmd += "-Dfxloop.mainClass=$it" }
        } else {
            cmd += listOf("classes", "fxloopPrepare")
        }
        val r = run(cmd, 600)
        if (r.exit != 0) {
            if (kind == Kind.MAVEN && "fxloop-maven-plugin" in r.output) {
                log.info("fxloop-maven-plugin unavailable; falling back to dependency:build-classpath")
                return classpathFallback()
            }
            throw ToolException("BUILD_FAILED", "build failed (exit ${r.exit}):\n${r.tail(40)}",
                "fix the build error above (once the app runs, fx_reload returns compile errors as data)")
        }
        return read(launch)
    }

    private fun classpathFallback(): LaunchDescriptor {
        val main = config.mainClass ?: throw ToolException("CONFIG", "FXLOOP_MAIN_CLASS is required without fxloop-maven-plugin",
            "set FXLOOP_MAIN_CLASS or install dev.fxloop:fxloop-maven-plugin")
        val cpFile = fxloopDir.resolve("cp.txt")
        val r = run(base() + listOf("-DskipTests", "compile", "dependency:build-classpath", "-Dmdep.outputFile=$cpFile",
            "-Dmdep.pathSeparator=${File.pathSeparator}", "-Dmdep.includeScope=runtime"), 600)
        if (r.exit != 0) throw ToolException("BUILD_FAILED", "build failed (exit ${r.exit}):\n${r.tail(40)}", "fix the build")
        val cp = Files.readString(cpFile).trim()
        val dir = config.projectDir
        return LaunchDescriptor(
            buildTool = "maven", mainClass = main,
            classpath = if (cp.isEmpty()) emptyList() else cp.split(File.pathSeparator),
            workingDir = dir.toString(),
            sourceRoots = listOf(dir.resolve("src/main/java").toString()),
            resourceRoots = listOf(dir.resolve("src/main/resources").toString()),
            outputDir = dir.resolve("target/classes").toString(),
            release = Runtime.version().feature().toString(),
        ).also { it.writeTo(fxloopDir.resolve(LaunchDescriptor.FILE_NAME)) }
    }

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

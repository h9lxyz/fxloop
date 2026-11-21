package org.lab59.fxloop.server.attach

import com.sun.tools.attach.VirtualMachine
import org.lab59.fxloop.server.app.Versions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Reads [JvmFacts] from local JVMs through the Attach API. Reading properties prints nothing in the target (only
 * `loadAgent` does), but a JVM suspended in a debugger can make `attach` hang, so every probe runs on its own daemon
 * thread with a timeout and a JVM that fails or hangs is skipped.
 */
object JvmScan {
    const val PROBE_TIMEOUT_MS = 3_000L

    /** A JVM as the Attach API lists it without attaching (from its perf data): pid and `sun.java.command`. */
    data class Listed(val pid: Long, val displayName: String) {
        /** Main class as [JvmFacts.mainClass] derives it, from the display name; null when the JVM shows none. */
        val mainClass: String? get() = JvmFacts.mainClassOf(displayName)
    }

    /** Local JVMs other than this one. */
    fun list(): List<Listed> {
        val me = ProcessHandle.current().pid()
        return runCatching { VirtualMachine.list() }.getOrDefault(emptyList()).mapNotNull { d ->
            d.id().toLongOrNull()?.takeIf { it != me }?.let { Listed(it, d.displayName().orEmpty()) }
        }
    }

    /** Properties of one JVM, or null when it cannot be attached to (gone, other user, attach disabled) or hangs. */
    fun probe(pid: Long, timeoutMs: Long = PROBE_TIMEOUT_MS): JvmFacts? = probeAll(listOf(pid), timeoutMs).firstOrNull()

    /**
     * Probes [pids] concurrently; the result keeps their order and drops the ones that failed or timed out. Pids whose
     * probe did not answer in time are added to [timedOut]: their thread may hang on, so a poller should not ask again.
     */
    fun probeAll(pids: List<Long>, timeoutMs: Long = PROBE_TIMEOUT_MS, timedOut: MutableSet<Long>? = null): List<JvmFacts> {
        val futures = pids.map { pid ->
            CompletableFuture<JvmFacts?>().also { f ->
                thread(isDaemon = true, name = "fxloop-attach-probe-$pid") { f.complete(runCatching { read(pid) }.getOrNull()) }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return futures.mapIndexedNotNull { i, f ->
            try {
                f.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                timedOut?.add(pids[i])
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Every local JVM in which JavaFX is running. */
    fun javafxApps(): List<JvmFacts> = probeAll(list().map { it.pid }).filter { it.javafx }

    private fun read(pid: Long): JvmFacts {
        val vm = VirtualMachine.attach(pid.toString())
        try {
            fun map(p: java.util.Properties) = p.stringPropertyNames().associateWith { p.getProperty(it) }
            return JvmFacts(pid, map(vm.systemProperties), runCatching { map(vm.agentProperties) }.getOrDefault(emptyMap()))
        } finally {
            runCatching { vm.detach() }
        }
    }
}

/**
 * The agent jar `attach` loads, extracted once per server version and content to a per-user cache that never needs
 * overwriting: a running app keeps its agent jar open (locked on Windows) and `mvn clean` would delete one under
 * `target/`, so [org.lab59.fxloop.server.app.AppSession.agentJar] (which rewrites `<fxloopDir>/fxloop-agent.jar`) does
 * not fit an app FXLoop did not start. `FXLOOP_AGENT_JAR` still wins.
 */
object AgentJarCache {
    private const val RESOURCE = "/org/lab59/fxloop/server/fxloop-agent.jar"

    fun defaultDir(version: String = Versions.SERVER, home: Path = Path.of(System.getProperty("user.home"))): Path =
        home.resolve(".fxloop").resolve("cache").resolve(version).resolve("agent")

    fun path(configured: Path?, dir: Path = defaultDir(), bundled: () -> ByteArray? = ::bundled): Path {
        configured?.let { return it.toAbsolutePath() }
        val bytes = bundled() ?: throw AttachException("the agent jar is not bundled in this server build; set FXLOOP_AGENT_JAR")
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)
        val target = dir.resolve("fxloop-agent-$hex.jar")
        if (Files.isRegularFile(target) && Files.size(target) == bytes.size.toLong()) return target
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "fxloop-agent-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return target
    }

    private fun bundled(): ByteArray? = AgentJarCache::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
}

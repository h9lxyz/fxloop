package dev.fxloop.acceptance

import dev.fxloop.server.app.ProcessTree
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Leak guard (auto-registered for every test class via ServiceLoader): after a test class finishes, no process this
 * run spawned may still be alive. "This run" is recognised three ways — descendants of the test JVM, JVMs carrying
 * [Harness.RUN_MARKER], and (on Linux) any process whose environment carries `FXLOOP_ACCEPTANCE_RUN=<run id>`,
 * which every child inherits — so orphaned Xvfb servers are found even after their wrapper and JVM are gone.
 * The test JVM, its ancestors and processes of other runs/sessions are never reported or killed.
 * A regression of the "orphaned xvfb-run trees exhausted RAM" incident fails the build here.
 */
class NoLeakedProcesses : AfterAllCallback {
    private val self = ProcessHandle.current()
    private val ancestors: Set<Long> = generateSequence(self) { it.parent().orElse(null) }.map { it.pid() }.toSet()
    private val envTag = System.getenv("FXLOOP_ACCEPTANCE_RUN")?.takeIf { it.isNotBlank() }?.let { "FXLOOP_ACCEPTANCE_RUN=$it" }

    private fun taggedByEnv(h: ProcessHandle): Boolean {
        if (envTag == null) return false
        return try {
            val env = Files.readAllBytes(Path.of("/proc", h.pid().toString(), "environ"))
            String(env).split('\u0000').any { it == envTag }
        } catch (e: Exception) {
            false // not Linux, process gone, or not ours to read
        }
    }

    private fun ours(): List<ProcessHandle> {
        val marked = ProcessHandle.allProcesses().filter { h ->
            h.isAlive && h.pid() !in ancestors &&
                (h.info().commandLine().orElse("").contains(Harness.RUN_MARKER) || taggedByEnv(h))
        }.toList()
        return (self.descendants().toList() + marked).filter { it.isAlive && it.pid() !in ancestors }.distinctBy { it.pid() }
    }

    override fun afterAll(context: ExtensionContext) {
        val deadline = System.currentTimeMillis() + 10_000
        var leaked = ours()
        while (leaked.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            leaked = ours()
        }
        if (leaked.isEmpty()) return
        val report = leaked.joinToString("\n") { "  pid ${it.pid()}: ${it.info().commandLine().orElse("?").take(160)}" }
        leaked.forEach { ProcessTree.kill(it, graceMs = 2000) } // never leave them behind, even when failing
        throw AssertionError("${context.displayName} leaked processes:\n$report")
    }
}

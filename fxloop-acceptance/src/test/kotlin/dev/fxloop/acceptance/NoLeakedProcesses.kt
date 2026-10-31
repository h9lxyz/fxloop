package dev.fxloop.acceptance

import dev.fxloop.server.app.ProcessTree
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Leak guard (auto-registered for every test class via ServiceLoader): after a test class finishes, no process
 * this test JVM spawned may still be alive — no child of the test JVM, and no JVM carrying this run's
 * [Harness.RUN_MARKER] (app JVMs re-parented away from us, e.g. under an orphaned xvfb-run). Only our own
 * processes are considered and cleaned up; anything else on the machine is left alone. A regression of the
 * "orphaned xvfb-run trees exhausted RAM" incident fails the build here instead of filling the machine.
 */
class NoLeakedProcesses : AfterAllCallback {
    private fun ours(): List<ProcessHandle> {
        val marker = Harness.RUN_MARKER
        val marked = ProcessHandle.allProcesses().filter { h ->
            h.isAlive && h.info().commandLine().orElse("").contains(marker)
        }.toList()
        // an Xvfb started for a marked JVM is its sibling under xvfb-run: include the parent tree of marked JVMs
        val wrappers = marked.mapNotNull { it.parent().orElse(null) }.filter { it.info().command().orElse("").endsWith("sh") }
        val wrapped = wrappers.flatMap { it.descendants().toList() + it }
        return (ProcessHandle.current().descendants().toList() + marked + wrapped).filter { it.isAlive }.distinctBy { it.pid() }
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

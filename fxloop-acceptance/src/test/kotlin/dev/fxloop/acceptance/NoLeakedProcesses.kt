package dev.fxloop.acceptance

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Leak guard (auto-registered for every test class via ServiceLoader): after a test class finishes, no process it
 * spawned may still be alive — no child of the test JVM, no Xvfb server, no JVM running a FXLoop agent. A regression
 * of the "orphaned xvfb-run trees exhausted RAM" incident fails the build here instead of filling the machine.
 */
class NoLeakedProcesses : BeforeAllCallback, AfterAllCallback {
    private fun snapshot(): Set<Long> = ProcessHandle.allProcesses().filter(::suspicious).map { it.pid() }.toList().toSet()

    private fun suspicious(h: ProcessHandle): Boolean {
        val cmd = h.info().commandLine().orElse("")
        return cmd.contains("Xvfb") || cmd.contains("fxloop-agent") || cmd.contains("xvfb-run")
    }

    override fun beforeAll(context: ExtensionContext) {
        context.getStore(NS).put("before", snapshot())
    }

    override fun afterAll(context: ExtensionContext) {
        @Suppress("UNCHECKED_CAST")
        val before = context.getStore(NS).get("before") as Set<Long>
        val deadline = System.currentTimeMillis() + 10_000
        var leaked: List<ProcessHandle>
        do {
            leaked = ProcessHandle.current().descendants().filter { it.isAlive }.toList() +
                ProcessHandle.allProcesses().filter { it.isAlive && suspicious(it) && it.pid() !in before }.toList()
            if (leaked.isEmpty()) return
            Thread.sleep(200)
        } while (System.currentTimeMillis() < deadline)
        val report = leaked.distinctBy { it.pid() }.joinToString("\n") { "  pid ${it.pid()}: ${it.info().commandLine().orElse("?").take(160)}" }
        leaked.forEach { it.destroyForcibly() } // never leave them behind, even when failing
        throw AssertionError("${context.displayName} leaked processes:\n$report")
    }

    private companion object {
        val NS: ExtensionContext.Namespace = ExtensionContext.Namespace.create(NoLeakedProcesses::class.java)
    }
}

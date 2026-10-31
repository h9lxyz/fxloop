package dev.fxloop.server.app

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Process-tree lifecycle for everything the server spawns. A launched app is rarely a single process (`xvfb-run`
 * → `Xvfb` + `java`, wrapper scripts → JVMs), and killing only the direct child orphans the rest — which is how
 * stray JavaFX JVMs and Xvfb servers once exhausted a developer machine. Rules:
 *
 * - descendants are captured **before** the root is signalled (after it dies they are re-parented and unreachable);
 * - leaves are terminated first, so wrappers like `xvfb-run` can still run their own cleanup;
 * - stragglers are force-killed after a grace period;
 * - every tracked tree is killed by one JVM shutdown hook (server exit, test JVM exit). A SIGKILLed server cannot run
 *   hooks; for that case the agent's owner watchdog ends the app itself.
 */
object ProcessTree {
    private val log = LoggerFactory.getLogger(ProcessTree::class.java)
    private val tracked = ConcurrentHashMap.newKeySet<ProcessHandle>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ tracked.toList().forEach { kill(it, graceMs = 1500) } }, "fxloop-reaper"))
    }

    /** Registers a spawned root so it is reaped on JVM exit; returns the process for chaining. */
    fun track(p: Process): Process = p.also { tracked += it.toHandle() }

    /** Kills [root] and all its descendants; returns the root's exit code when known. */
    fun kill(p: Process, graceMs: Long = 3000): Int? {
        kill(p.toHandle(), graceMs)
        return if (p.isAlive) null else p.exitValue()
    }

    fun kill(root: ProcessHandle, graceMs: Long = 3000) {
        tracked -= root
        val tree = root.descendants().toList().reversed() + root // deepest first, root last
        tree.filter { it.isAlive }.forEach { it.destroy() }
        val deadline = System.currentTimeMillis() + graceMs
        for (h in tree) {
            val left = deadline - System.currentTimeMillis()
            if (left > 0) runCatching { h.onExit().get(left, TimeUnit.MILLISECONDS) }
        }
        val stubborn = tree.filter { it.isAlive }
        if (stubborn.isNotEmpty()) {
            log.info("force-killing {} process(es) of tree {}", stubborn.size, root.pid())
            stubborn.forEach { it.destroyForcibly() }
            stubborn.forEach { h -> runCatching { h.onExit().get(2, TimeUnit.SECONDS) } }
        }
    }

    /** Number of live processes still tracked (for leak assertions in tests). */
    fun trackedAlive(): Int = tracked.count { it.isAlive }
}

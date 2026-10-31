package dev.fxloop.server.app

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Process-tree lifecycle for everything the server spawns. A launched app is rarely a single process (`xvfb-run`
 * → `Xvfb` + `java`, wrapper scripts → JVMs), and killing only the direct child orphans the rest — which is how
 * stray JavaFX JVMs and Xvfb servers once exhausted a developer machine. Rules:
 *
 * - descendants are captured **before** anything is signalled (once a parent dies its children are re-parented
 *   and no longer reachable through it);
 * - only the descendants are signalled first, and the root gets the grace period to exit on its own, so wrappers
 *   like `xvfb-run` run their own cleanup (kill Xvfb, remove their temp dir);
 * - then the root, then a force-kill of every straggler;
 * - a tree stays tracked until it is confirmed dead;
 * - one JVM shutdown hook reaps every tracked tree, and nothing new may be spawned once it has started.
 *
 * A SIGKILLed server cannot run hooks; for that case the agent's owner watchdog ends the app itself.
 */
object ProcessTree {
    private val log = LoggerFactory.getLogger(ProcessTree::class.java)
    private val tracked = ConcurrentHashMap.newKeySet<ProcessHandle>()

    @Volatile
    private var shuttingDown = false

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            shuttingDown = true
            repeat(3) { // a launch racing the hook may register late: loop until nothing is left
                tracked.toList().forEach { kill(it, graceMs = 1500) }
                if (tracked.isEmpty()) return@Thread
            }
        }, "fxloop-reaper"))
    }

    /** Registers a spawned root so it is reaped on JVM exit; during shutdown the process is killed immediately. */
    fun track(p: Process): Process {
        tracked += p.toHandle()
        if (shuttingDown) {
            kill(p)
            throw IllegalStateException("fxloop server is shutting down")
        }
        return p
    }

    /** Forgets a root that exited normally (e.g. a finished build). */
    fun untrack(p: Process) {
        tracked -= p.toHandle()
    }

    /** Kills [p] and all its descendants; returns the root's exit code when known. */
    fun kill(p: Process, graceMs: Long = 3000): Int? {
        kill(p.toHandle(), graceMs)
        return if (p.isAlive) null else p.exitValue()
    }

    /**
     * Kills [root]'s tree plus [extras]: processes known to belong to it (recorded at launch) that may have been
     * re-parented away — e.g. Xvfb and the app JVM after the `xvfb-run` wrapper itself was killed externally.
     */
    fun kill(root: ProcessHandle, extras: Collection<ProcessHandle>, graceMs: Long = 3000) {
        kill(root, graceMs)
        val stray = extras.filter { it.isAlive }
        if (stray.isNotEmpty()) {
            log.info("reaping {} re-parented process(es) of tree {}", stray.size, root.pid())
            stray.forEach { kill(it, graceMs) }
        }
    }

    fun kill(root: ProcessHandle, graceMs: Long = 3000) {
        // Interrupts would cut the grace waits short and leave orphans behind: clear, do the work, restore.
        val interrupted = Thread.interrupted()
        try {
            val descendants = root.descendants().toList().reversed() // deepest first
            val tree = descendants + root
            descendants.filter { it.isAlive }.forEach { it.destroy() }
            awaitExit(descendants, System.currentTimeMillis() + graceMs)
            // A wrapper root (xvfb-run) runs its own cleanup once its child is gone — xauth, rm of its temp dir.
            // SIGTERMing it mid-cleanup skips its EXIT trap, so it gets a separate window. A plain JVM root (no
            // children) was never asked to exit, so it is signalled right away instead.
            if (descendants.isNotEmpty()) awaitExit(listOf(root), System.currentTimeMillis() + ROOT_CLEANUP_MS)
            if (root.isAlive) {
                root.destroy()
                awaitExit(listOf(root), System.currentTimeMillis() + minOf(graceMs, 1500))
            }
            val stubborn = tree.filter { it.isAlive }
            if (stubborn.isNotEmpty()) {
                log.info("force-killing {} process(es) of tree {}", stubborn.size, root.pid())
                stubborn.forEach { it.destroyForcibly() }
                awaitExit(stubborn, System.currentTimeMillis() + 2000)
            }
            val survivors = tree.filter { it.isAlive }
            if (survivors.isEmpty()) {
                tracked -= root
            } else {
                log.warn("processes {} survived SIGKILL (uninterruptible?); still tracked", survivors.map { it.pid() })
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun awaitExit(handles: List<ProcessHandle>, deadline: Long) {
        for (h in handles) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            runCatching { h.onExit().get(left, TimeUnit.MILLISECONDS) }
        }
    }

    private const val ROOT_CLEANUP_MS = 3000L

    /** Number of live processes still tracked (for leak assertions in tests). */
    fun trackedAlive(): Int = tracked.count { it.isAlive }
}

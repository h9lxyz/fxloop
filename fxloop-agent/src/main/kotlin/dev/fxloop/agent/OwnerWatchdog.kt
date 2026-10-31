package dev.fxloop.agent

import javafx.application.Platform

/**
 * Exits the application when the fxloop server that launched it is gone. A supervised app must never outlive its
 * supervisor: if the server is SIGKILLed (or its host app crashes) no shutdown hook runs there, so the child has to
 * notice by itself — otherwise JavaFX JVMs (and their xvfb servers) pile up across sessions.
 */
internal object OwnerWatchdog {
    private const val POLL_MS = 1000L
    private const val GRACE_MS = 3000L

    fun start(ownerPid: Long) {
        val owner = ProcessHandle.of(ownerPid).orElse(null)
        if (owner == null || !owner.isAlive) {
            AgentLog.info("owner process $ownerPid not found; exiting")
            halt()
            return
        }
        Thread({
            while (owner.isAlive) {
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            AgentLog.info("fxloop server (pid $ownerPid) is gone; shutting the application down")
            runCatching { Platform.exit() }
            // give the app a moment to close cleanly, then make sure the JVM really ends
            Thread({ Fx.sleep(GRACE_MS); halt() }, "fxloop-owner-halt").apply { isDaemon = false }.start()
        }, "fxloop-owner-watchdog").apply { isDaemon = true }.start()
    }

    private fun halt() {
        Runtime.getRuntime().halt(0)
    }
}

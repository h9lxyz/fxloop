package dev.fxloop.agent

import javafx.application.Platform

/**
 * Exits the application when the process that launched it (the fxloop server, or the build for `fxloop:run`) is gone.
 * An app must never outlive its launcher: if the launcher is SIGKILLed (or its host app crashes) no shutdown hook runs there, so the child has to
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
            AgentLog.info("owner process (pid $ownerPid) is gone; shutting the application down")
            // arm the hard stop first: nothing the app does on exit may keep it alive past the grace period
            Thread({ Fx.sleep(GRACE_MS); halt() }, "fxloop-owner-halt").apply { isDaemon = true }.start()
            Thread({ runCatching { Platform.exit() } }, "fxloop-owner-exit").apply { isDaemon = true }.start()
        }, "fxloop-owner-watchdog").apply { isDaemon = true }.start()
    }

    private fun halt() {
        Runtime.getRuntime().halt(0)
    }
}

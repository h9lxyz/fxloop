package org.lab59.fxloop.server

import org.lab59.fxloop.launch.LaunchDescriptor
import org.lab59.fxloop.protocol.Handshake
import org.lab59.fxloop.server.app.AppSession
import org.lab59.fxloop.server.tools.Ctx
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The loop behind the inspector helper, shared by `--sidecar` (started by `fxloop:run` / `fxloopRun` next to the app)
 * and `attach`: keeps a yielding FCP connection to a developer-run app so the dev bar's Inspect CSS (the viewer and CSS
 * inspector) works without an agent session. An agent session's server takes the app over; this reconnects once it is
 * gone. Returns when the app's JVM is gone, when it never showed up before [startDeadline], or when [stopped] says so.
 */
object InspectorHelper {
    enum class End { APP_GONE, NEVER_STARTED, STOPPED }

    private val log = LoggerFactory.getLogger("org.lab59.fxloop.server.sidecar")

    /**
     * @param descriptor handed to every (re)connect; null lets the session read `launch.json` next to the handshake
     * @param startDeadline epoch millis by which the app's handshake must name a live JVM
     * @param appPid the app's JVM when the caller knows it (`attach`): the app is gone only when that JVM is, and a
     * handshake deleted under a running app (`mvn clean`) is published again through [republish]. Null (`--sidecar`):
     * the handshake's pid decides.
     * @param onReady called once, when the helper first holds the app (connected, so the watcher is registered too) or
     * first steps aside for an agent session
     */
    fun serve(ctx: Ctx, handshake: Path, mode: AppSession.Mode, descriptor: LaunchDescriptor?, startDeadline: Long,
              stopped: () -> Boolean = { false }, appPid: Long? = null, republish: () -> Unit = {}, onReady: () -> Unit = {}): End {
        val viewer = ctx.ensureViewer(ctx.config.viewerPort ?: 0)
        var yielded = false
        var seen = false
        var ready = false
        var nextRepublish = 0L
        while (!stopped()) {
            val hs = runCatching { Handshake.readFrom(handshake) }.getOrNull()
            val alive = if (appPid != null) isAlive(appPid) else hs != null && isAlive(hs.pid)
            // attach: the file is missing or stale while the app runs; never overwrite one of another live app
            val current = hs != null && (appPid == null || hs.pid == appPid)
            if (alive && !current && (hs == null || !isAlive(hs.pid)) && System.currentTimeMillis() >= nextRepublish) {
                nextRepublish = System.currentTimeMillis() + 5_000
                log.info("the app's handshake is gone while it runs; asking its agent to publish it again")
                runCatching(republish).onFailure { log.warn("cannot publish the handshake again: {}", it.message) }
            }
            if (!alive) {
                if (seen) {
                    log.info("the app is gone; inspector helper stops")
                    return End.APP_GONE
                }
                if (System.currentTimeMillis() > startDeadline) return End.NEVER_STARTED
                pause(500, stopped)
                continue
            }
            seen = true
            if (!ctx.session.connected && current) {
                try {
                    ctx.session.connectExisting(handshake, mode, yieldToAgents = true, descriptor = descriptor)
                    if (yielded) log.info("agent session ended; inspector helper is back")
                    yielded = false
                } catch (e: Exception) { // BUSY while an agent session has the app, or the app is going away
                    if (!yielded) log.info("an agent session has the app; inspector helper waits ({})", e.message)
                    yielded = true
                }
            }
            // An agent session's inspector: while that session has the app, open pages move there; while we keep the app,
            // Ask agent prompts are forwarded to it. Either way they reach the agent that is listening.
            if (!ready && (ctx.session.connected || yielded)) {
                ready = true
                onReady()
            }
            val agent = Ctx.advertisedInspector(ctx.session.fxloopDir.resolve(Ctx.INSPECTOR_FILE))
            viewer.setMovedTo(if (yielded) agent else null)
            viewer.setPromptsRemote(if (yielded) null else agent)
            viewer.pollRemotePrompts()
            pause(1500, stopped)
        }
        return End.STOPPED
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun pause(ms: Long, stopped: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !stopped()) Thread.sleep(minOf(100, maxOf(1, until - System.currentTimeMillis())))
    }
}

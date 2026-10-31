package dev.fxloop.server.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import dev.fxloop.server.Config
import dev.fxloop.server.mcp.ToolException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One entry of the dev bar's "action needed" segment and of `fx_get_app_status.actionNeeded` (PRD 07 §7.1). */
data class ActionNeeded(val kind: String, val reason: String, val next: String, val button: String? = null) {
    fun toJson(): ObjectNode = Json.obj {
        put("kind", kind)
        put("reason", reason)
        put("next", next)
        button?.let { put("button", it) }
    }
}

/**
 * Server-side live-session state (PRD 07 §6–7): control mode, the developer's activity, the action-needed list and
 * the last reload. It is the single source of truth for the dev bar: every change is pushed to the agent as a full
 * `setBarState` and to the live viewer as an SSE `status` event. See docs/decisions/live-dev-loop.md.
 */
class LiveState(private val config: Config, private val session: AppSession) {
    enum class Control { AGENT, SHARED }

    @Volatile var control: Control = if (config.control == "shared") Control.SHARED else Control.AGENT
        private set

    /** Current watch mode (`css-only|all|off`); set by the owner of the watcher. */
    @Volatile var watchMode: () -> String = { "off" }

    /** Receives the compact JSON of every pushed state (the live viewer's `status` event). */
    @Volatile var statusSink: ((String) -> Unit)? = null

    @Volatile private var lastHumanMs = 0L
    @Volatile private var lastPickMs = 0L
    @Volatile private var lastPickTarget: JsonNode? = null
    @Volatile private var autoLocked = config.control != "auto"
    @Volatile private var drivingNow = false
    @Volatile private var lockUntilMs = 0L
    @Volatile private var lockReason: String? = null
    @Volatile private var lockReleasedBy: String? = null
    @Volatile private var activityText = ""
    @Volatile private var activitySeq = 0L
    @Volatile private var items: List<ActionNeeded> = emptyList()
    @Volatile private var lastReloadText = ""
    @Volatile private var lastReloadOk = true
    @Volatile private var reloadSeq = 0
    private val pushQueued = AtomicBoolean(false)
    private val pusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fxloop-live-push").apply { isDaemon = true } }

    /** Number of recorded reloads; lets a caller tell whether somebody else already reported one. */
    fun reloadCount(): Int = reloadSeq

    /** Switches control (`via`: tool, bar, auto) and pushes; returns `{control, previous, via}`. */
    fun setControl(mode: Control, via: String): ObjectNode {
        val previous = control
        control = mode
        if (via != "auto") autoLocked = true
        push()
        return Json.obj {
            put("control", mode.name.lowercase())
            put("previous", previous.name.lowercase())
            put("via", via)
        }
    }

    /** A `humanAction` event arrived: records activity; `FXLOOP_CONTROL=auto` switches agent → shared once. */
    fun onHumanAction(payload: JsonNode) {
        lastHumanMs = System.currentTimeMillis()
        if (!autoLocked && control == Control.AGENT) {
            autoLocked = true
            setControl(Control.SHARED, "auto")
        }
    }

    /** Connected to an app the developer started (`fxloop:run`, IDE): `FXLOOP_CONTROL=auto` starts out shared. */
    fun developerOwnsApp() {
        if (!autoLocked && control == Control.AGENT) {
            autoLocked = true
            setControl(Control.SHARED, "auto")
        }
    }

    /** A `humanPick` event arrived (remembered for `status`). */
    fun onHumanPick(payload: JsonNode) {
        lastPickMs = payload.path("ts").asLong(System.currentTimeMillis())
        lastPickTarget = payload["target"]
    }

    /** True when a restart must wait for the developer: control is shared, or human input within FXLOOP_HUMAN_IDLE_MS. */
    fun shouldDeferRestart(): Boolean =
        control == Control.SHARED || (lastHumanMs > 0 && System.currentTimeMillis() - lastHumanMs < config.humanIdleMs)

    /** Records the outcome of a reload (bar "last reload" + action-needed list) and pushes. */
    fun reloaded(lastReload: String, ok: Boolean, actionNeeded: List<ActionNeeded>) {
        lastReloadText = lastReload
        lastReloadOk = ok
        reloadSeq++
        items = actionNeeded.toList()
        push()
    }

    /** Replaces the action-needed list (e.g. cleared after a restart) and pushes. */
    fun setActionNeeded(items: List<ActionNeeded>) {
        this.items = items.toList()
        push()
    }

    /** The current action-needed entries. */
    fun actionItems(): List<ActionNeeded> = items

    fun actionNeeded(): ArrayNode = Json.array().also { a -> items.forEach { a.add(it.toJson()) } }

    /** Replay / agent-driven phases: the bar shows "Agent is driving — hands off" while active. */
    fun driving(active: Boolean) {
        drivingNow = active
        push()
    }

    /**
     * The agent's input lock: while it holds, the developer's input to the app windows is dropped, inspector editing
     * pauses and the bar says they are watching. Expires after [seconds]; the developer can always take over.
     */
    fun lock(seconds: Int, reason: String?): ObjectNode {
        val until = System.currentTimeMillis() + seconds * 1000L
        lockUntilMs = until
        lockReason = reason?.takeIf { it.isNotBlank() }
        lockReleasedBy = null
        try {
            pusher.schedule({ if (lockUntilMs == until) unlock("expired") }, seconds * 1000L, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            // executor shut down
        }
        push()
        return lockJson()
    }

    /** Releases the input lock (`via`: tool, developer, expired); returns the lock state. */
    fun unlock(via: String): ObjectNode {
        if (locked()) {
            lockReleasedBy = via
            if (via == "developer") takeOverPending = true
        }
        lockUntilMs = 0L
        lockReason = null
        push()
        return lockJson()
    }

    fun locked(): Boolean = lockUntilMs > System.currentTimeMillis()

    @Volatile private var takeOverPending = false

    /** True once after the developer took over a lock, so the next action result can tell the agent. */
    fun takeOverNotice(): Boolean {
        if (!takeOverPending) return false
        takeOverPending = false
        return true
    }

    /** Remaining lock reason for the viewer and inspector, or null when unlocked. */
    fun lockText(): String? = if (locked()) lockReason ?: "the agent is driving the app" else null

    /** `{locked, reason?, remainingMs?, releasedBy?}` for tool results and fx_get_app_status. */
    fun lockJson(): ObjectNode = Json.obj {
        val left = lockUntilMs - System.currentTimeMillis()
        put("locked", left > 0)
        if (left > 0) {
            lockReason?.let { put("reason", it) }
            put("remainingMs", left)
        }
        lockReleasedBy?.let { put("releasedBy", it) }
    }

    /**
     * What the agent is doing, for the bar ("clicking #save"); null ends it. The text stays [lingerMs] after the end so
     * fast tool sequences do not flicker.
     */
    fun activity(text: String?, lingerMs: Long = 2000) {
        val seq = ++activitySeq
        if (text != null) {
            activityText = text
            push()
            return
        }
        try {
            pusher.schedule({
                if (activitySeq == seq) {
                    activityText = ""
                    push()
                }
            }, lingerMs, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            // executor shut down
        }
    }

    /** The full `setBarState` payload. */
    fun barState(): ObjectNode = Json.obj {
        put("bar", barSetting())
        if (config.sidecar) put("sidecar", true) // the fxloop:run helper, not an agent session
        put("mode", modeText())
        put("control", control.name.lowercase())
        put("driving", drivingNow)
        if (activityText.isNotEmpty()) put("activity", activityText)
        val left = lockUntilMs - System.currentTimeMillis()
        if (left > 0) putObject("lock").put("remainingMs", left).also { o -> lockReason?.let { o.put("reason", it) } }
        put("watch", watchMode())
        putObject("lastReload").put("text", lastReloadText).put("ok", lastReloadOk).put("seq", reloadSeq)
        set<JsonNode>("actionNeeded", actionNeeded())
        putObject("config").put("recordHuman", config.recordHuman).put("pickKey", config.pickKey).put("restartKey", config.restartKey)
    }

    private fun barSetting(): String = when (config.bar) {
        "on", "pill", "off" -> config.bar
        else -> if (config.headless != null) "off" else "on"
    }

    private fun modeText(): String = when (session.mode) {
        AppSession.Mode.EMBEDDED -> "embedded"
        AppSession.Mode.ATTACHED -> "attached"
        AppSession.Mode.LAUNCHED -> when {
            config.headless == null -> "dev loop"
            config.headless.equals("xvfb", true) -> "headless (xvfb)"
            else -> "headless (monocle)"
        }
    }

    /** Best-effort, non-blocking push of [barState] to the agent and of a `status` event to the viewer. Bursts coalesce. */
    fun push() {
        if (!pushQueued.compareAndSet(false, true)) return
        try {
            pusher.execute { doPush() }
        } catch (_: RuntimeException) {
            pushQueued.set(false)
        }
    }

    private fun doPush() {
        pushQueued.set(false)
        val state = barState()
        sendBarState(state, 0)
        statusSink?.let { runCatching { it(state.toString()) } }
    }

    /**
     * Sends the bar state; a freshly launched app is often too busy starting up to answer within the timeout, and the
     * push after launch is the only one until something changes, so a failed send is retried with the then-current state.
     */
    private fun sendBarState(state: ObjectNode, attempt: Int) {
        if (!session.connected) return
        val code = try {
            session.call(Fcp.Method.SET_BAR_STATE, state, 3000)
            return
        } catch (e: FcpException) {
            e.code
        } catch (_: ToolException) {
            return // app went away between the check and the call
        }
        val delay = barRetryDelayMs(code, attempt) ?: return
        try {
            pusher.schedule({ sendBarState(barState(), attempt + 1) }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            // executor shut down
        }
    }

    /** `{control, controlSetting, human:{lastActivityMs?, focusedWindow?, …}, actionNeeded, bar}` for fx_get_app_status. */
    fun status(agentHuman: JsonNode?): ObjectNode {
        val human = (agentHuman as? ObjectNode)?.deepCopy() ?: Json.obj()
        val now = System.currentTimeMillis()
        if (lastHumanMs > 0) {
            val server = now - lastHumanMs
            val agent = if (human.has("lastActivityMs")) human.path("lastActivityMs").asLong(Long.MAX_VALUE) else Long.MAX_VALUE
            human.put("lastActivityMs", minOf(server, agent))
        }
        if (lastPickMs > 0) {
            human.put("lastPickAgoMs", now - lastPickMs)
            lastPickTarget?.let { human.set<JsonNode>("lastPickTarget", it) }
        }
        return Json.obj {
            put("control", control.name.lowercase())
            put("controlSetting", config.control)
            set<JsonNode>("human", human)
            set<JsonNode>("actionNeeded", actionNeeded())
            put("bar", barSetting())
            set<JsonNode>("inputLock", lockJson())
        }
    }

    companion object {
        private val BAR_RETRY_MS = longArrayOf(500, 1000, 2000, 4000, 8000, 15000)

        /** Delay before resending a failed `setBarState`, or null to give up (older agent, or retries exhausted). */
        fun barRetryDelayMs(code: String, attempt: Int): Long? =
            if (code == Fcp.Error.UNKNOWN_METHOD) null else BAR_RETRY_MS.getOrNull(attempt)
    }
}

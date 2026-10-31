package dev.fxloop.server.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import dev.fxloop.server.Config

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

    /** Switches control (`via`: tool, bar, auto) and pushes; returns `{control, previous, via}`. */
    fun setControl(mode: Control, via: String): ObjectNode = TODO("E5b")

    /** A `humanAction` event arrived: records activity; `FXLOOP_CONTROL=auto` switches agent → shared once. */
    fun onHumanAction(payload: JsonNode) {
        // E3
    }

    /** True when a restart must wait for the developer: control is shared, or human input within FXLOOP_HUMAN_IDLE_MS. */
    fun shouldDeferRestart(): Boolean = false

    /** Records the outcome of a reload (bar "last reload" + action-needed list) and pushes. */
    fun reloaded(lastReload: String, ok: Boolean, actionNeeded: List<ActionNeeded>) {
        // E5a
    }

    /** Replaces the action-needed list (e.g. cleared after a restart) and pushes. */
    fun setActionNeeded(items: List<ActionNeeded>) {
        // E5a
    }

    fun actionNeeded(): ArrayNode = Json.array()

    /** Replay / agent-driven phases: the bar shows "Agent is driving — hands off" while active. */
    fun driving(active: Boolean) {
        // E5b
    }

    /** The full `setBarState` payload. */
    fun barState(): ObjectNode = Json.obj()

    /** Best-effort, non-blocking push of [barState] to the agent and of a `status` event to the viewer. */
    fun push() {
        // E5a
    }

    /** `{control, controlSetting, human:{lastActivityMs?, focusedWindow?, …}, actionNeeded, bar}` for fx_get_app_status. */
    fun status(agentHuman: JsonNode?): ObjectNode = Json.obj()
}

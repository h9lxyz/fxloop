package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * One successfully executed action tool (PRD 02 §5).
 *
 * @property tool MCP tool name: `fx_click_node`, `fx_type_keys`, `fx_set_text`, `fx_set_property`
 * @property args replayable arguments: targets normalized to `path` (handles/queries removed), other params kept
 * @property target target summary `{path, type, fxId?, text?}` as resolved at execution time, or `null`
 *   (screen-coordinate clicks, untargeted key presses)
 * @property effects scene-diff effects observed after the action (`{added, removed, changed, windowChanges,
 *   overflow}`), or `null` when effects were disabled
 * @property windowFingerprint pre-action window list fingerprint, e.g. `"Stage:FXLoop Sample — Contacts|"`
 * @property source `agent` (an MCP action tool) or `human` (the developer's own input, PRD 07 §5)
 * @property redacted true for human edits of a `PasswordField`: the value was never recorded and replay skips it
 */
data class JournalEntry(
    val seq: Long,
    val tool: String,
    val args: ObjectNode,
    val target: ObjectNode?,
    val effects: ObjectNode?,
    val windowFingerprint: String,
    val ts: Long,
    val source: String = "agent",
    val redacted: Boolean = false,
)

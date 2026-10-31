package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.server.app.ActionNeeded
import dev.fxloop.server.app.AppSession

/**
 * Turns an `fx_reload` result into the developer-facing story (PRD 07 §7.1): the transient "last reload" line and the
 * "action needed" entries. Pure function of the result, so the agent's tool result and the bar never disagree.
 */
object ReloadStory {
    data class Story(val lastReload: String, val ok: Boolean, val actionNeeded: List<ActionNeeded>)

    fun of(result: ObjectNode, mode: AppSession.Mode, externalCompile: Boolean): Story {
        val tiers = result.path("tiersApplied").toList()
        fun tier(name: String) = tiers.firstOrNull { it.path("tier").asText() == name }
        val actions = ArrayList<ActionNeeded>()
        var ok = true
        val parts = ArrayList<String>()

        val errors = result.path("compileErrors").toList()
        if (errors.isNotEmpty()) {
            ok = false
            val first = errors.first()
            val where = first.path("file").asText("").substringAfterLast('/').let { f ->
                if (f.isEmpty()) first.path("message").asText("").lineSequence().firstOrNull().orEmpty().trim()
                else f + (if (first.hasNonNull("line")) ":${first.path("line").asText()}" else "")
            }
            val text = "Compile error · $where" + if (errors.size > 1) " (+${errors.size - 1} more)" else ""
            parts.add(text)
            actions.add(ActionNeeded("compileError", text, "Fix the error and save"))
        }

        tier("resources")?.let { parts.add("Resources copied") }
        tier("css")?.let { parts.add("CSS applied") }
        if (result.path("visualDiff").path("hint").asText("").startsWith("no visual change")) {
            parts.add("No visual change — selector may not match")
        }
        tier("fxml")?.let { t ->
            val files = t.path("files").map { it.asText().substringAfterLast('/') }
            val unregistered = t.path("details").path("unregistered").map { it.asText().substringAfterLast('/') }
            val swapped = files.filter { it !in unregistered }
            if (swapped.isNotEmpty()) {
                val restored = result.path("stateCarried").path("restored").asInt(0)
                parts.add("FXML ${swapped.joinToString(", ")} swapped" + if (restored > 0) " ($restored states restored)" else "")
            }
        }
        tier("hotswap")?.let { t ->
            val d = t.path("details")
            if (d.path("ok").asBoolean()) {
                val r = d.path("redefined")
                val n = if (r.isArray) r.size() else if (r.isNumber) r.asInt() else d.path("classes").asInt(0)
                parts.add("Hot-swapped $n class" + if (n == 1) "" else "es")
            }
        }
        (tier("remount") ?: tier("restage"))?.let { t ->
            val d = t.path("details")
            val remounted = d.path("remounted").map { it.asText() }
            if (t.path("tier").asText() == "restage" && d.path("restaged").asBoolean()) parts.add("restaged")
            else if (remounted.isNotEmpty()) parts.add("remounted ${remounted.joinToString(", ")}")
            d.path("failed").forEach { f ->
                ok = false
                val cause = f.path("error").asText("unknown error").lineSequence().first().trim()
                actions.add(ActionNeeded("remountFailed", "Remount failed · ${f.path("key").asText("?")}: $cause", "Check the view's onReload supplier"))
            }
        }
        tier("restart")?.let { parts.add("Restarted") }
        if (result.path("restartDeferred").isTextual) parts.add("Restart deferred")
        val waiting = result.path("waitingForBuild").asBoolean()
        if (waiting) parts.add("Waiting for a build")

        val reason = shortReasons(result)
        val needsRestart = result.path("needsRestart").asBoolean()
        val restarted = result.path("restarted").asBoolean()
        val failure = result.path("restartFailed")
        if (failure.isObject) {
            ok = false
            val msg = failure.path("message").asText("build failed")
            parts.add("Rebuild failed")
            actions.add(ActionNeeded("rebuildFailed", "Rebuild failed · $msg", failure.path("logPath").asText(msg), "copy"))
        } else if (needsRestart && !restarted) {
            if (mode == AppSession.Mode.LAUNCHED) actions.add(ActionNeeded("restart", "Restart needed · $reason", "Restart", "restart"))
            else actions.add(ActionNeeded("rebuildIde", "Rebuild and restart from your IDE · $reason", "Rebuild and restart from your IDE", "copy"))
        }
        if (waiting) {
            val how = if (externalCompile) "run your IDE build or gradle -t classes" else "run a build"
            actions.add(ActionNeeded("waitingBuild", "Waiting for a build · $how", "Run a build"))
        }

        if (parts.isEmpty()) parts.add(result.path("note").asText("No changes"))
        val total = tiers.sumOf { it.path("ms").asLong(0) }
        if (tiers.isNotEmpty()) parts.add("$total ms")
        return Story(parts.joinToString(" · "), ok, actions)
    }

    /** One-line restart reason: hotswap rejections become "fields added in EditForm"; anything else stays raw. */
    internal fun shortReasons(result: JsonNode): String {
        val reasons = result.path("restartReasons").map { shorten(it.asText()) }
        if (reasons.isEmpty()) return "restart required"
        return reasons.first() + if (reasons.size > 1) " (+${reasons.size - 1} more)" else ""
    }

    private fun shorten(raw: String): String {
        val line = raw.lineSequence().first()
        val m = Regex("""^hotswap rejected for ([^:]+): (.*)$""").find(line) ?: return line
        val cls = m.groupValues[1].split(',').first().trim().substringAfterLast('.').substringAfterLast('$')
        val why = m.groupValues[2].lowercase()
        return when {
            "add" in why && "field" in why -> "fields added in $cls"
            "add" in why && "method" in why -> "methods added in $cls"
            "remov" in why && "method" in why -> "methods removed in $cls"
            "remov" in why && "field" in why -> "fields removed in $cls"
            "hierarchy" in why || "superclass" in why -> "class hierarchy changed in $cls"
            else -> line
        }
    }
}

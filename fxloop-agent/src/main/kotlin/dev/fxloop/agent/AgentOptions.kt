package dev.fxloop.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Agent arguments, `key=value` pairs separated by commas:
 * `handshake=<path>,tokenFile=<path>,state=<path>,logs=true,fxmlHook=true,ownerPid=<pid>,recordHuman=true,bar=on`.
 * `bar=on|pill` shows the dev bar before any server connects (`fxloop:run`); otherwise the server turns it on.
 * `ownerPid` is the launching fxloop server: when it disappears (even SIGKILL) the app exits on its own. `tokenFile` (owner-only
 * file written by the server) is preferred over `token=` because command lines are visible to other users.
 */
data class AgentOptions(
    val handshake: Path?,
    val token: String?,
    val stateFile: Path?,
    val captureLogs: Boolean,
    val fxmlHook: Boolean,
    val ownerPid: Long? = null,
    val recordHuman: Boolean = true,
    val bar: String? = null,
) {
    companion object {
        @JvmStatic
        fun parse(args: String?): AgentOptions {
            val map = HashMap<String, String>()
            if (!args.isNullOrBlank()) {
                for (part in args.split(",")) {
                    val eq = part.indexOf('=')
                    if (eq > 0) map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
                }
            }
            val hs = map["handshake"]
            val state = map["state"]
            var token = map["token"]
            val tokenFile = map["tokenFile"]
            if (tokenFile != null) {
                try {
                    token = Files.readString(Path.of(tokenFile)).trim()
                } catch (e: IOException) {
                    AgentLog.warn("cannot read tokenFile", e)
                }
            }
            return AgentOptions(
                hs?.let { Path.of(it) }, token, state?.let { Path.of(it) },
                (map["logs"] ?: "false").equals("true", ignoreCase = true),
                (map["fxmlHook"] ?: "true").equals("true", ignoreCase = true),
                map["ownerPid"]?.toLongOrNull(),
                !(map["recordHuman"] ?: System.getProperty("fxloop.recordHuman") ?: "true").equals("false", ignoreCase = true),
                map["bar"]?.lowercase()?.takeIf { it == "on" || it == "pill" },
            )
        }

        /** `on`/`true` (case-insensitive) enable a switch; anything else, including absent, does not. */
        @JvmStatic
        fun isOn(v: String?): Boolean = v != null && (v.equals("on", true) || v.equals("true", true))

        /** Production guard: `-Dfxloop.enabled=false` disables the agent regardless of arguments. */
        @JvmStatic
        fun disabledBySystemProperty(): Boolean = "false".equals(System.getProperty("fxloop.enabled"), ignoreCase = true)
    }
}

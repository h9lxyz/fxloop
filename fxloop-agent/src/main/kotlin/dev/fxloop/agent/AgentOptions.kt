package dev.fxloop.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Agent arguments, `key=value` pairs separated by commas:
 * `handshake=<path>,tokenFile=<path>,state=<path>,logs=true,fxmlHook=true`. `tokenFile` (owner-only
 * file written by the server) is preferred over `token=` because command lines are visible to other users.
 */
data class AgentOptions(
    val handshake: Path?,
    val token: String?,
    val stateFile: Path?,
    val captureLogs: Boolean,
    val fxmlHook: Boolean,
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
            )
        }

        /** Production guard: `-Dfxloop.enabled=false` disables the agent regardless of arguments. */
        @JvmStatic
        fun disabledBySystemProperty(): Boolean = "false".equals(System.getProperty("fxloop.enabled"), ignoreCase = true)
    }
}

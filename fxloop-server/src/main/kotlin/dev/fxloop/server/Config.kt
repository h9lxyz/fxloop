package dev.fxloop.server

import java.nio.file.Path

/**
 * Server configuration from `FXLOOP_*` environment variables, overridable by `--key=value` CLI flags
 * (flag = env name without `FXLOOP_`, lower-case, '_' → '-'; e.g. `--project-dir=…`).
 */
data class Config(
    val projectDir: Path,
    val mainClass: String? = null,
    val jvmArgs: List<String> = emptyList(),
    val appArgs: List<String> = emptyList(),
    val buildCmd: String? = null,
    val buildTool: String? = null,
    val prepare: Boolean = true,
    val startupTimeoutMs: Long = 30_000,
    val agentJar: Path? = null,
    val headless: String? = null,
    val monocleJar: String? = null,
    val captureAppLogs: Boolean = false,
    val webview: Boolean = false,
    val viewerPort: Int? = null,
    val watchMode: String = "css-only",
    val externalCompile: Boolean = false,
    val jbr: Boolean = false,
    val screensKeep: Int = 100,
    val screenshotRatePerSec: Double = 2.0,
    val testMode: Boolean = false,
    val replayOnRestart: Boolean = true,
    val bar: String = "auto",
    val editorUrl: String? = null,
    val browser: String = "auto",
) {
    /** Dev bar in the app: `FXLOOP_BAR=on|off`, `auto` = on unless a headless mode is set. */
    val barEnabled: Boolean get() = when (bar) { "on" -> true; "off" -> false; else -> headless == null }

    /** Whether the server may open a browser for the inspector (`FXLOOP_BROWSER=none` disables). */
    val browserEnabled: Boolean get() = !"none".equals(browser, ignoreCase = true)

    companion object {
        fun load(env: Map<String, String>, args: Array<String>): Config {
            val v = env.filterKeys { it.startsWith("FXLOOP_") }.toMutableMap()
            for (a in args) {
                if (a.startsWith("--") && '=' in a) {
                    val key = "FXLOOP_" + a.substring(2, a.indexOf('=')).uppercase().replace('-', '_')
                    v[key] = a.substring(a.indexOf('=') + 1)
                }
            }
            fun str(k: String) = v[k]?.takeIf { it.isNotBlank() }?.trim()
            fun bool(k: String) = str(k)?.lowercase() in setOf("true", "1", "yes")
            return Config(
                projectDir = Path.of(str("FXLOOP_PROJECT_DIR") ?: System.getProperty("user.dir")).toAbsolutePath().normalize(),
                mainClass = str("FXLOOP_MAIN_CLASS"),
                jvmArgs = split(v["FXLOOP_JVM_ARGS"]),
                appArgs = split(v["FXLOOP_APP_ARGS"]),
                buildCmd = str("FXLOOP_BUILD_CMD") ?: str("FXLOOP_MVN_CMD"),
                buildTool = str("FXLOOP_BUILD_TOOL"),
                prepare = !"none".equals(str("FXLOOP_PREPARE"), ignoreCase = true),
                startupTimeoutMs = str("FXLOOP_STARTUP_TIMEOUT_MS")?.toLong() ?: 30_000,
                agentJar = str("FXLOOP_AGENT_JAR")?.let(Path::of),
                headless = str("FXLOOP_HEADLESS"),
                monocleJar = str("FXLOOP_MONOCLE_JAR"),
                captureAppLogs = bool("FXLOOP_APP_LOGS"),
                webview = bool("FXLOOP_WEBVIEW"),
                viewerPort = str("FXLOOP_VIEWER")?.toInt(),
                watchMode = str("FXLOOP_WATCH") ?: "css-only",
                externalCompile = bool("FXLOOP_EXTERNAL_COMPILE"),
                jbr = bool("FXLOOP_JBR"),
                screensKeep = str("FXLOOP_SCREENS_KEEP")?.toInt() ?: 100,
                screenshotRatePerSec = str("FXLOOP_SCREENSHOT_RATE")?.toDouble() ?: 2.0,
                testMode = bool("FXLOOP_TEST"),
                replayOnRestart = !"false".equals(str("FXLOOP_REPLAY"), ignoreCase = true),
                bar = str("FXLOOP_BAR")?.lowercase() ?: "auto",
                editorUrl = str("FXLOOP_EDITOR_URL"),
                browser = str("FXLOOP_BROWSER")?.lowercase() ?: "auto",
            )
        }

        /** Attach mode may be disabled on the server with `-Dfxloop.attach=false` (PRD 03 §3). */
        val attachAllowed: Boolean get() = !"false".equals(System.getProperty("fxloop.attach"), ignoreCase = true)

        /** Whitespace split honouring double quotes. */
        fun split(s: String?): List<String> {
            if (s == null) return emptyList()
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            for (c in s) {
                when {
                    c == '"' -> quoted = !quoted
                    c.isWhitespace() && !quoted -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}

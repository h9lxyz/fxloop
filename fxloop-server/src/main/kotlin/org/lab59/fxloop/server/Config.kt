package org.lab59.fxloop.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Server configuration from `FXLOOP_*` environment variables, overridable by `--key=value` CLI flags
 * (flag = env name without `FXLOOP_`, lower-case, '_' → '-'; e.g. `--project-dir=…`). Lowest precedence: an optional
 * project file `fxloop.properties` using the flag names as keys (`project-dir=app`, `app-args=--seed`), looked up in the
 * server's working directory, else in the configured project dir. A relative `project-dir` there is resolved against
 * the file's directory. It lets a project (and the `fxloop-connect` skill) configure the server without MCP edits.
 */
data class Config(
    val projectDir: Path,
    val mainClass: String? = null,
    val jvmArgs: List<String> = emptyList(),
    val appArgs: List<String> = emptyList(),
    val buildCmd: String? = null,
    val buildTool: String? = null,
    val prepare: Boolean = true,
    /** Which prepare runs: `auto` (build plugin if present, else the server's own), `plugin` or `server` (PRD 11 4.2). */
    val prepareRoute: String = "auto",
    /** Maven launch mode override: `auto`, `classpath` or `modulepath` (`-Dfxloop.launchMode` for the plugin route). */
    val launchMode: String? = null,
    val startupTimeoutMs: Long = 30_000,
    val agentJar: Path? = null,
    val headless: String? = null,
    val monocleJar: String? = null,
    val captureAppLogs: Boolean = false,
    val webview: Boolean = false,
    val viewerPort: Int? = null,
    val watchMode: String = "css-only",
    val externalCompile: Boolean = false,
    /** null = auto: enhanced redefinition when the launch JDK is JetBrains Runtime. */
    val jbr: Boolean? = null,
    val screensKeep: Int = 100,
    val screenshotRatePerSec: Double = 2.0,
    val testMode: Boolean = false,
    val replayOnRestart: Boolean = true,
    // Live dev loop (PRD 07 §9)
    val restage: Boolean = false,
    val recordHuman: Boolean = true,
    val humanIdleMs: Long = 10_000,
    val control: String = "auto",
    val bar: String = "auto",
    val pickKey: String = "Ctrl+Shift+F12",
    val restartKey: String = "Ctrl+Shift+F5",
    // CSS inspector (PRD 08)
    val editorUrl: String? = null,
    val browser: String = "auto",
    /** Inspector "Try it live" (PRD 08 §5): the Styles pane may edit a scratch stylesheet; off keeps the viewer read-only. */
    val inspectorEdit: Boolean = false,
    /** `fxloop:run` helper (no MCP): serves the viewer and CSS inspector while no agent session is connected. */
    val sidecar: Boolean = false,
) {
    /** Whether the server may open a browser for the inspector (`FXLOOP_BROWSER=none` disables). */
    val browserEnabled: Boolean get() = !"none".equals(browser, ignoreCase = true)

    companion object {
        const val PROJECT_FILE = "fxloop.properties"

        fun load(env: Map<String, String>, args: Array<String>, workDir: Path = Path.of(System.getProperty("user.dir"))): Config {
            fun key(flag: String) = "FXLOOP_" + flag.trim().uppercase().replace('-', '_')
            val explicit = env.filterKeys { it.startsWith("FXLOOP_") }.toMutableMap()
            for (a in args) {
                if (a.startsWith("--") && '=' in a) explicit[key(a.substring(2, a.indexOf('=')))] = a.substring(a.indexOf('=') + 1)
            }
            val file = sequenceOf(workDir, explicit["FXLOOP_PROJECT_DIR"]?.takeIf { it.isNotBlank() }?.let { workDir.resolve(it.trim()) })
                .filterNotNull().map { it.resolve(PROJECT_FILE) }.firstOrNull { Files.isRegularFile(it) }
            val v = HashMap<String, String>()
            if (file != null) {
                val props = Properties().apply { Files.newBufferedReader(file).use(::load) }
                props.stringPropertyNames().forEach { v[key(it)] = props.getProperty(it) }
                v["FXLOOP_PROJECT_DIR"]?.let { v["FXLOOP_PROJECT_DIR"] = file.parent.resolve(it.trim()).toString() }
            }
            v.putAll(explicit)
            fun str(k: String) = v[k]?.takeIf { it.isNotBlank() }?.trim()
            fun bool(k: String) = str(k)?.lowercase() in setOf("true", "1", "yes")
            return Config(
                projectDir = workDir.resolve(str("FXLOOP_PROJECT_DIR") ?: ".").toAbsolutePath().normalize(),
                mainClass = str("FXLOOP_MAIN_CLASS"),
                jvmArgs = split(v["FXLOOP_JVM_ARGS"]),
                appArgs = split(v["FXLOOP_APP_ARGS"]),
                buildCmd = str("FXLOOP_BUILD_CMD") ?: str("FXLOOP_MVN_CMD"),
                buildTool = str("FXLOOP_BUILD_TOOL"),
                prepare = !"none".equals(str("FXLOOP_PREPARE"), ignoreCase = true),
                prepareRoute = str("FXLOOP_PREPARE_ROUTE")?.lowercase() ?: "auto",
                launchMode = str("FXLOOP_LAUNCH_MODE")?.lowercase(),
                startupTimeoutMs = str("FXLOOP_STARTUP_TIMEOUT_MS")?.toLong() ?: 30_000,
                agentJar = str("FXLOOP_AGENT_JAR")?.let(Path::of),
                headless = str("FXLOOP_HEADLESS"),
                monocleJar = str("FXLOOP_MONOCLE_JAR"),
                captureAppLogs = bool("FXLOOP_APP_LOGS"),
                webview = bool("FXLOOP_WEBVIEW"),
                viewerPort = str("FXLOOP_VIEWER")?.toInt(),
                watchMode = str("FXLOOP_WATCH") ?: "css-only",
                externalCompile = bool("FXLOOP_EXTERNAL_COMPILE"),
                jbr = str("FXLOOP_JBR")?.let { bool("FXLOOP_JBR") },
                screensKeep = str("FXLOOP_SCREENS_KEEP")?.toInt() ?: 100,
                screenshotRatePerSec = str("FXLOOP_SCREENSHOT_RATE")?.toDouble() ?: 2.0,
                testMode = bool("FXLOOP_TEST"),
                replayOnRestart = !"false".equals(str("FXLOOP_REPLAY"), ignoreCase = true),
                restage = bool("FXLOOP_RESTAGE"),
                recordHuman = !"false".equals(str("FXLOOP_RECORD_HUMAN"), ignoreCase = true),
                humanIdleMs = str("FXLOOP_HUMAN_IDLE_MS")?.toLong() ?: 10_000,
                control = str("FXLOOP_CONTROL")?.lowercase() ?: "auto",
                bar = str("FXLOOP_BAR")?.lowercase() ?: "auto",
                pickKey = str("FXLOOP_PICK_KEY") ?: "Ctrl+Shift+F12",
                restartKey = str("FXLOOP_RESTART_KEY") ?: "Ctrl+Shift+F5",
                editorUrl = str("FXLOOP_EDITOR_URL"),
                browser = str("FXLOOP_BROWSER")?.lowercase() ?: "auto",
                inspectorEdit = bool("FXLOOP_INSPECTOR_EDIT"),
                sidecar = bool("FXLOOP_SIDECAR"),
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

package dev.fxloop.server.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Handshake
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.LaunchDescriptor
import dev.fxloop.protocol.OwnerOnly
import dev.fxloop.protocol.Tokens
import dev.fxloop.server.Config
import dev.fxloop.server.mcp.ToolException
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/**
 * Supervises the application child process and owns the FCP connection (PRD 01 §3, §8). The MCP session never
 * lives inside the app, so it survives every restart (D4).
 */
class AppSession(val config: Config) {
    enum class State { NEW, STARTING, RUNNING, EXITED, STOPPED }
    enum class Mode { LAUNCHED, EMBEDDED, ATTACHED }

    private val log = LoggerFactory.getLogger(AppSession::class.java)
    val build = BuildTool(config)
    val stdout = LogRing(2000)
    val stderr = LogRing(2000)
    val errors = EventRing(500)
    val appLogs = EventRing(2000)
    private val restartListeners = ArrayList<() -> Unit>()
    private val eventListeners = java.util.concurrent.CopyOnWriteArrayList<(String, JsonNode) -> Unit>()
    private val connectedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    @Volatile var state = State.NEW
        private set
    @Volatile var mode = Mode.LAUNCHED
        private set
    @Volatile var descriptor: LaunchDescriptor? = null
        private set
    @Volatile var lastStartupMs = 0L
        private set
    @Volatile var pid = 0L
        private set
    @Volatile private var process: Process? = null

    /** The launched tree as seen right after the handshake (wrapper children + app JVM), see [killProcess]. */
    @Volatile private var launchedTree: List<ProcessHandle> = emptyList()
    @Volatile private var fcp: FcpClient? = null
    @Volatile private var startedAt = 0L
    @Volatile private var exitCode: Int? = null
    private var launchCounter = 0L

    val fxloopDir: Path get() = build.fxloopDir

    /** Called after every (re)launch, e.g. to re-baseline reload change detection. */
    fun onLaunched(listener: () -> Unit) {
        restartListeners += listener
    }

    /** Receives agent events other than uiError/appLog (humanAction, humanPick, humanRestartRequest, controlModeChanged). */
    fun onAgentEvent(listener: (String, JsonNode) -> Unit) {
        eventListeners += listener
    }

    /** Called after [connectExisting] succeeded (embedded or attached agents; supervised launches use [onLaunched]). */
    fun onConnected(listener: () -> Unit) {
        connectedListeners += listener
    }

    /** Implicit start on first use (PRD 01 §14 Q1). Returns true when this call started the app. */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (state != State.NEW) return false
        start()
        return true
    }

    /** Connected FCP client or an actionable error. Auto-starts a never-started app. */
    fun fcp(): FcpClient {
        ensureStarted()
        val c = fcp
        if (c == null || !c.isOpen) {
            refreshExit()
            throw ToolException("APP_NOT_RUNNING",
                "the application is ${state.name.lowercase()}" + (exitCode?.let { " (exit code $it)" } ?: ""),
                "call fx_start_app (or fx_restart_app); see fx_get_logs for why it stopped")
        }
        return c
    }

    val connected: Boolean get() = fcp?.isOpen == true

    @JvmOverloads
    fun call(method: String, params: ObjectNode = Json.obj(), timeoutMs: Long = FcpClient.DEFAULT_TIMEOUT_MS): JsonNode =
        fcp().request(method, params, timeoutMs)

    @Synchronized
    fun start() {
        if (state == State.RUNNING || state == State.STARTING) {
            throw ToolException("ALREADY_RUNNING", "the application is already running (pid $pid)", "use fx_restart_app to relaunch")
        }
        val t0 = System.currentTimeMillis()
        killProcess() // leftovers of a crashed launch (wrapper gone, children alive) are reaped before relaunching
        state = State.STARTING
        mode = Mode.LAUNCHED
        exitCode = null
        var ok = false
        try {
            val d = build.prepare()
            descriptor = d
            launchWithDisplayRetry(d)
            lastStartupMs = System.currentTimeMillis() - t0
            state = State.RUNNING
            ok = true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ToolException("LAUNCH_FAILED", "launch interrupted", "call fx_start_app again")
        } catch (e: IOException) {
            throw ToolException("LAUNCH_FAILED", "cannot launch the app: ${e.message}", "check fx_get_logs, then fx_start_app")
        } finally {
            // any failure — checked exceptions included — must not leave a half-started app tree behind
            if (!ok) {
                fcp?.close()
                fcp = null
                killProcess()
                state = State.EXITED
            }
        }
        restartListeners.forEach { it() }
    }

    /**
     * `xvfb-run -a` picks a free display by probing lock files, which races when several launches (other sessions,
     * parallel test runs) start at once: the loser's Xvfb fails and the app exits immediately. Retry those.
     */
    private fun launchWithDisplayRetry(d: LaunchDescriptor) {
        var attempt = 0
        while (true) {
            try {
                launch(d)
                return
            } catch (e: ToolException) {
                val displayRace = config.headless.equals("xvfb", true) && e.code == "APP_EXITED" &&
                    DISPLAY_FAILURE.containsMatchIn(e.message.orEmpty())
                if (!displayRace || ++attempt > 2) throw e
                log.info("Xvfb display race (attempt {}), retrying launch", attempt)
                killProcess()
                Thread.sleep(200L + (System.nanoTime() % 800))
            }
        }
    }

    private fun launch(d: LaunchDescriptor) {
        val dir = fxloopDir
        Files.createDirectories(dir)
        val handshake = dir.resolve("handshake.json")
        Files.deleteIfExists(handshake)
        val token = Tokens.generate()
        val tokenFile = OwnerOnly.write(dir.resolve("token"), token)
        val cmd = ArrayList<String>()
        if (config.headless.equals("xvfb", true)) cmd += listOf("xvfb-run", "-a", "-s", "-screen 0 1600x1200x24")
        cmd += javaExecutable()
        cmd += Headless.jvmArgs(config)
        cmd += d.jvmArgs
        cmd += config.jvmArgs
        if (config.testMode) cmd += "-Dfxloop.test=true"
        // G10: JetBrains Runtime enhanced redefinition (structural hot-swaps); only meaningful on JBR via FXLOOP_JAVA_HOME
        if (config.jbr) cmd += "-XX:+AllowEnhancedClassRedefinition"
        cmd += "-javaagent:${agentJar()}=handshake=$handshake,tokenFile=$tokenFile,state=${dir.resolve("state.json")}," +
            "logs=${config.captureAppLogs},ownerPid=${ProcessHandle.current().pid()}"
        val classpath = buildList {
            if (d.mainModule == null) add(d.outputDir)
            addAll(d.classpath)
            addAll(Headless.extraClasspath(config, d))
        }
        if (d.modulePath.isNotEmpty()) cmd += listOf("--module-path", d.modulePath.joinToString(File.pathSeparator))
        if (d.addModules.isNotEmpty()) cmd += listOf("--add-modules", d.addModules.joinToString(","))
        if (classpath.isNotEmpty()) cmd += listOf("-cp", classpath.joinToString(File.pathSeparator))
        if (d.mainModule != null) cmd += listOf("-m", "${d.mainModule}/${d.mainClass}") else cmd += config.mainClass ?: d.mainClass
        cmd += d.appArgs
        cmd += config.appArgs
        log.info("launching {} ({} classpath entries)", d.mainClass, classpath.size)
        stdout.clear()
        stderr.clear()
        val p = try {
            val pb = ProcessBuilder(cmd).directory(File(d.workingDir))
            if (config.headless.equals("xvfb", true)) {
                // xvfb-run creates its auth dir via `mktemp -d`: keep it inside the project, never in shared /tmp
                val xvfbTmp = Files.createDirectories(dir.resolve("xvfb-tmp"))
                pb.environment()["TMPDIR"] = xvfbTmp.toString()
            }
            ProcessTree.track(pb.start())
        } catch (e: IOException) {
            throw ToolException("LAUNCH_FAILED", "cannot launch the app: ${e.message}", "check FXLOOP_JAVA_HOME / agent jar")
        }
        p.outputStream.close()
        process = p
        pid = p.pid()
        startedAt = System.currentTimeMillis()
        newLaunch()
        pump(p.inputStream, stdout, "stdout")
        pump(p.errorStream, stderr, "stderr")
        val hs = awaitHandshake(handshake, p)
        launchedTree = (p.descendants().toList() + listOfNotNull(ProcessHandle.of(hs.pid).orElse(null))).distinct()
        connect(hs.port, token)
    }

    private fun newLaunch() {
        val id = ++launchCounter
        errors.newLaunch(id)
        appLogs.newLaunch(id)
    }

    private fun awaitHandshake(file: Path, p: Process): Handshake {
        val deadline = System.currentTimeMillis() + config.startupTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                Thread.sleep(200) // let the pumps drain
                throw ToolException("APP_EXITED", "the app exited during startup (exit code ${p.exitValue()}). Last log lines:\n${logTail(50)}",
                    "fix the startup error above, then fx_start_app")
            }
            if (Files.exists(file)) runCatching { return Handshake.readFrom(file) }
            Thread.sleep(50)
        }
        throw ToolException("STARTUP_TIMEOUT", "no FCP handshake within ${config.startupTimeoutMs} ms (does the app start JavaFX?). " +
            "Last log lines:\n${logTail(50)}", "check the main class / JavaFX startup; raise FXLOOP_STARTUP_TIMEOUT_MS if the app is slow")
    }

    private fun connect(port: Int, token: String) {
        val c = FcpClient.connect(port, token, ::onEvent)
        fcp?.close()
        fcp = c
        // pull anything raised before we connected
        c.request(Fcp.Method.GET_UI_ERRORS, Json.obj { put("sinceSeq", 0) }).path("errors").forEach(errors::add)
    }

    private fun onEvent(event: String, payload: JsonNode?) {
        if (payload == null) return
        when (event) {
            Fcp.Event.UI_ERROR -> errors.add(payload)
            Fcp.Event.APP_LOG -> appLogs.add(payload)
            else -> eventListeners.forEach { l -> runCatching { l(event, payload) } }
        }
    }

    @Synchronized
    fun stop(): ObjectNode {
        if (mode != Mode.LAUNCHED) {
            throw ToolException("NOT_SUPERVISED", "the app is ${mode.name.lowercase()}, not launched by fxloop; stop it where it was started",
                "use fx_get_app_status")
        }
        fcp?.close()
        fcp = null
        val code = killProcess()
        state = State.STOPPED
        return Json.obj {
            put("stopped", true)
            code?.let { put("exitCode", it) }
        }
    }

    @Synchronized
    fun restart(): ObjectNode {
        if (mode != Mode.LAUNCHED) {
            throw ToolException("NOT_SUPERVISED", "restart is unavailable in ${mode.name.lowercase()} mode: fxloop did not launch this JVM",
                "relaunch the app yourself, or use the dev-loop mode (fx_start_app)")
        }
        val t0 = System.currentTimeMillis()
        fcp?.close()
        fcp = null
        killProcess()
        state = State.NEW
        start()
        return Json.obj {
            put("restarted", true)
            put("startupMs", System.currentTimeMillis() - t0)
        }
    }

    private fun killProcess(): Int? {
        val p = process ?: return null
        process = null
        val tree = launchedTree
        launchedTree = emptyList()
        val xvfbDirs = xvfbRunDirs(tree)
        // the whole tree: xvfb-run → Xvfb + java, never just the wrapper; plus members re-parented since launch
        ProcessTree.kill(p.toHandle(), tree)
        // xvfb-run removes its temp dir in an EXIT trap that a slow-dying app can make it miss: finish the job
        removeLater(xvfbDirs)
        exitCode = if (p.isAlive) null else p.exitValue()
        return exitCode
    }

    /**
     * Deletes xvfb-run temp dirs off-thread, retrying briefly: an `xauth` spawned by the wrapper's own cleanup may
     * still be writing into the dir right after the tree was killed.
     */
    private fun removeLater(dirs: List<Path>) {
        if (dirs.isEmpty()) return
        thread(isDaemon = true, name = "fxloop-xvfb-tmp-cleanup") {
            repeat(10) {
                dirs.filter { it.toFile().exists() }.forEach { runCatching { it.toFile().deleteRecursively() } }
                if (dirs.none { it.toFile().exists() }) return@thread
                Thread.sleep(300)
            }
        }
    }

    /** `/tmp/xvfb-run.XXXXXX` dirs of Xvfb servers in [tree] (from their `-auth <dir>/Xauthority` argument). */
    private fun xvfbRunDirs(tree: List<ProcessHandle>): List<Path> = tree.mapNotNull { h ->
        XVFB_AUTH.find(h.info().commandLine().orElse(""))?.groupValues?.get(1)?.let(Path::of)
    }.filter { it.fileName.toString().startsWith("xvfb-run.") }

    private fun refreshExit() {
        val p = process ?: return
        // a dead wrapper with a live app (still connected) is not "exited": its tree must still be reaped by stop
        if (!p.isAlive && mode == Mode.LAUNCHED && launchedTree.none { it.isAlive }) {
            exitCode = p.exitValue()
            state = State.EXITED
        }
    }

    /** Connects to an embedded (`FxLoopDev.install()`) or attached agent via its handshake file. */
    @Synchronized
    fun connectExisting(handshakeFile: Path, newMode: Mode): ObjectNode {
        if (mode == Mode.LAUNCHED && process != null) {
            // switching modes would drop the supervised app from supervision: stop it first
            fcp?.close()
            fcp = null
            killProcess()
            state = State.STOPPED
        }
        try {
            val hs = Handshake.readFrom(handshakeFile)
            val token = hs.token ?: throw ToolException("CONFIG", "handshake has no token (it belongs to a supervised launch)",
                "use fx_start_app for supervised apps")
            connect(hs.port, token)
            mode = newMode
            // embedded apps are built from this project: reload tiers need its source roots and output dir (attach has none)
            if (newMode == Mode.EMBEDDED && descriptor == null) descriptor = try { build.prepare() } catch (e: RuntimeException) { null }
            state = State.RUNNING
            pid = hs.pid
            startedAt = hs.startedAt
            process = null
            newLaunch()
            connectedListeners.forEach { l -> runCatching { l() } }
            return status()
        } catch (e: IOException) {
            throw ToolException("CONNECT_FAILED", "cannot connect via $handshakeFile: ${e.message}",
                "check that the app called FxLoopDev.install() and the path is right")
        } catch (e: FcpException) {
            throw ToolException("CONNECT_FAILED", e.message.orEmpty(), "check the handshake file is current")
        }
    }

    fun status(): ObjectNode {
        refreshExit()
        val s = Json.obj {
            put("state", state.name.lowercase())
            put("mode", mode.name.lowercase())
            if (pid > 0) put("pid", pid)
            if (startedAt > 0 && state == State.RUNNING) put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000)
            exitCode?.let { put("exitCode", it) }
            put("fcpConnected", connected)
        }
        val c = fcp
        if (c != null && c.isOpen) {
            try {
                val st = c.request(Fcp.Method.STATUS, Json.obj(), 5000)
                s.set<JsonNode>("windows", st["windows"])
                s.set<JsonNode>("fxml", st["fxml"])
                s.put("redefineSupported", st.path("redefineSupported").asBoolean())
                listOf("human", "mounts", "pickActive").forEach { k -> st[k]?.let { s.set<JsonNode>(k, it) } }
                s.put("fcpVersion", c.hello.path("fcpVersion").asInt())
                s.put("javaVersion", c.hello.path("javaVersion").asText())
                s.put("vmName", c.hello.path("vmName").asText())
            } catch (e: FcpException) {
                s.put("statusError", e.message)
            }
        } else {
            s.set<JsonNode>("windows", Json.array())
        }
        descriptor?.let {
            s.put("mainClass", it.mainClass)
            s.put("buildTool", it.buildTool)
        }
        s.put("uiErrors", errors.lastSeq())
        return s
    }

    fun logTail(n: Int): String =
        (stdout.tail(n).map { "[out] $it" } + stderr.tail(n).map { "[err] $it" }).takeLast(n).joinToString("\n")

    private fun pump(input: InputStream, ring: LogRing, name: String) {
        thread(isDaemon = true, name = "fxloop-$name-pump") {
            runCatching { BoundedLines(input, MAX_LINE_CHARS).forEach(ring::add) }
        }
    }

    /** The bundled agent jar, extracted to `<fxloopDir>/fxloop-agent.jar` (or `FXLOOP_AGENT_JAR`). */
    fun agentJar(): Path {
        config.agentJar?.let { return it.toAbsolutePath() }
        val target = fxloopDir.resolve("fxloop-agent.jar")
        AppSession::class.java.getResourceAsStream("/dev/fxloop/server/fxloop-agent.jar").use { input ->
            input ?: throw ToolException("CONFIG", "agent jar not bundled in this server build", "set FXLOOP_AGENT_JAR")
            Files.createDirectories(target.parent)
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    /**
     * Lock-free on purpose: runs from the shutdown hook while start()/restart() may hold the monitor for minutes
     * (a build). Reads the volatile fields and kills the tree directly; [ProcessTree]'s reaper is the backstop.
     */
    fun shutdown() {
        fcp?.close()
        val p = process
        if (p != null && mode == Mode.LAUNCHED) {
            val tree = launchedTree
            val xvfbDirs = xvfbRunDirs(tree)
            ProcessTree.kill(p.toHandle(), tree, graceMs = 1500)
            removeLater(xvfbDirs)
        }
    }

    companion object {
        /** Longest captured log line; longer output (e.g. a binary dump) is truncated, not buffered. */
        const val MAX_LINE_CHARS = 8192
        private val DISPLAY_FAILURE = Regex("Xvfb failed to start|server already active|[Uu]nable to open display|[Cc]annot open display")
        private val XVFB_AUTH = Regex("""-auth (\S+)/Xauthority""")

        fun javaExecutable(): String {
            val exe = if (File.separatorChar == '\\') "java.exe" else "java"
            val home = System.getenv("FXLOOP_JAVA_HOME") ?: System.getProperty("java.home")
            return Path.of(home, "bin", exe).toString()
        }
    }
}

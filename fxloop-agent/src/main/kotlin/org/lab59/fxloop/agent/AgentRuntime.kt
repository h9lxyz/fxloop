package org.lab59.fxloop.agent

import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.Handshake
import org.lab59.fxloop.protocol.Tokens
import java.lang.instrument.Instrumentation
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform

/** Process-wide agent state. One instance per JVM regardless of how the agent was loaded. */
class AgentRuntime private constructor(
    val options: AgentOptions,
    val instrumentation: Instrumentation?,
    val mode: String,
    internal val token: String,
) {
    /**
     * When the app started, for the server's "files changed since the app started" baseline: the JVM's start in attach
     * mode (the agent arrives late, and edits made before it must still reload), else the moment the agent started.
     */
    val startedAt: Long = if (mode == "attach") jvmStart() else System.currentTimeMillis()

    /**
     * This JVM's pid, read once here: [writeHandshake] runs on the listener thread after the FXML hook installed its
     * class transformer, and resolving `RuntimeMXBean` there (lambda bootstrap) has failed with a ClassCircularityError
     * (JavaFX 27 on CI), which left the app without a handshake. `ProcessHandle` needs no management beans.
     */
    private val pid: Long = ProcessHandle.current().pid()
    val errors: ErrorRing = ErrorRing(200)
    val logs: LogCapture = LogCapture()
    val handles: HandleTable = HandleTable()
    val fxml: FxmlRegistry = FxmlRegistry()
    val mounts: MountRegistry = MountRegistry()
    val human: HumanInput = HumanInput(this)
    val bar: DevBar = DevBar(this)
    val pick: PickMode = PickMode(this)
    internal val windows: WindowsWatcher = WindowsWatcher(this::emitEvent)

    @Volatile
    internal var server: FcpServer? = null

    /** The FCP port once the listener is up (0 before), and the handshake files a later `agentmain` asked for meanwhile. */
    private var port = 0
    private val pendingHandshakes = ArrayList<Path>()

    private fun boot() {
        if (instrumentation != null && options.fxmlHook) {
            FxmlHook.install(instrumentation)
        }
        if (instrumentation != null) {
            MountRegistry.installHooks(instrumentation)
        }
        Thread(::awaitFxAndListen, "fxloop-agent-init").apply { isDaemon = true }.start()
        options.ownerPid?.let(OwnerWatchdog::start)
    }

    /**
     * Waits for the JavaFX toolkit, then listens. A `-javaagent` or embedded agent gives up after 60 s; an attached one
     * waits as long as the JVM runs: `attach` follows app restarts by loading the agent into a JVM that may start JavaFX
     * much later (a slow startup, a splash phase, a debugger pause).
     */
    private fun awaitFxAndListen() {
        val deadline = if (mode == "attach") Long.MAX_VALUE else System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            try {
                Platform.runLater { onFxThread() }
                return
            } catch (notYet: IllegalStateException) {
                sleep(100)
            } catch (t: Throwable) {
                AgentLog.warn("JavaFX toolkit probe failed", t)
                return
            }
        }
        AgentLog.info("JavaFX toolkit did not start within 60 s; agent idle")
    }

    private fun onFxThread() {
        Fx.rememberFxThread(Thread.currentThread())
        errors.installOn(Thread.currentThread())
        if (options.captureLogs) {
            logs.install()
        }
        options.stateFile?.let { GeometryStore(it).install() }
        human.install() // hotkeys work even when recording is off (options.recordHuman)
        windows.install()
        if (options.bar != null) bar.standalone(options.bar)
        Thread(::listen, "fxloop-agent-listen").apply { isDaemon = true }.start()
    }

    private fun listen() {
        try {
            val s = FcpServer(this)
            server = s
            val bound = s.bind()
            published(bound)
            AgentLog.info("FCP listening on 127.0.0.1:$bound ($mode)")
            s.acceptLoop()
        } catch (e: Exception) {
            AgentLog.warn("FCP listener failed", e)
        }
    }

    /** The listener is up on [bound]: writes the handshake and every copy a later `agentmain` asked for meanwhile. */
    private fun published(bound: Int) {
        val files = synchronized(this) {
            port = bound
            listOfNotNull(options.handshake) + pendingHandshakes.toList().also { pendingHandshakes.clear() }
        }
        // one copy that cannot be written (a deleted dir, a full disk) must not take the listener down with it
        files.forEach { f ->
            try {
                writeHandshake(f, bound)
            } catch (e: Exception) {
                AgentLog.warn("cannot write handshake $f", e)
            }
        }
    }

    /**
     * Same content rules for every copy: the token is written only when the agent generated it itself (a supervised
     * launch's server owns its token, so its handshake never carries one).
     */
    private fun writeHandshake(file: Path, port: Int) {
        val writeToken = options.token == null
        Handshake(Fcp.VERSION, pid, port, startedAt, mode, if (writeToken) token else null, options.inspectorEdit.takeIf { it })
            .writeTo(file.toAbsolutePath())
    }

    /**
     * A later `agentmain` with a `handshake=` argument on a JVM where the agent already runs (`attach` after `mvn clean`
     * deleted the file, or with a different path): publish the handshake there too, now or once the listener is up.
     */
    internal fun republish(file: Path) {
        val bound = synchronized(this) {
            if (port == 0) pendingHandshakes.add(file)
            port
        }
        if (bound == 0) return
        try {
            writeHandshake(file, bound)
            AgentLog.info("handshake published again at $file")
        } catch (e: Exception) {
            AgentLog.warn("cannot write handshake $file", e)
        }
    }

    /**
     * A server just connected: it and its inspector pages have not seen this app's windows yet (first connect, or the
     * app restarted under a page that stayed open), so push the current list.
     */
    internal fun onConnected() {
        Platform.runLater { windows.emitNow() }
    }

    /**
     * The server connection ended: the agent's input lock goes with it, and a `bar=` agent falls back to its standalone
     * bar instead of a stale server state.
     */
    internal fun onDisconnected() {
        human.setLock(null)
        val b = options.bar ?: return
        Platform.runLater { bar.standalone(b) }
    }

    /** Sends an unsolicited event to the connected server; dropped when none is connected. */
    internal fun emitEvent(event: String, payload: com.fasterxml.jackson.databind.node.ObjectNode) {
        server?.emit(event, payload)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** When this JVM started (attach mode: the agent arrives late), from the process table, else now. */
        private fun jvmStart(): Long =
            ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(System.currentTimeMillis())

        private val INSTANCE = AtomicReference<AgentRuntime?>()

        /**
         * Starts the agent once; later calls return the existing runtime. A later attach (`agentmain`) with a handshake
         * path re-publishes the running agent's handshake there ([republish]).
         */
        @JvmStatic
        fun start(options: AgentOptions, inst: Instrumentation?, mode: String): AgentRuntime {
            INSTANCE.get()?.let { running ->
                if (mode == "attach") options.handshake?.let(running::republish)
                return running
            }
            val token = options.token ?: Tokens.generate()
            val fresh = AgentRuntime(options, inst, mode, token)
            if (!INSTANCE.compareAndSet(null, fresh)) {
                return INSTANCE.get()!!.also { running -> if (mode == "attach") options.handshake?.let(running::republish) }
            }
            fresh.boot()
            return fresh
        }

        @JvmStatic
        fun get(): AgentRuntime? = INSTANCE.get()

        /** The running agent; handlers only execute after [start], so absence is a programming error. */
        internal fun current(): AgentRuntime = INSTANCE.get() ?: error("fxloop agent is not running")

        /** Test hook: installs a runtime without booting threads, sockets or instrumentation. */
        internal fun startForTest(): AgentRuntime {
            val fresh = AgentRuntime(AgentOptions(null, null, null, false, false), null, "test", "test-token")
            INSTANCE.set(fresh)
            return fresh
        }

        /** Test hook: a runtime that is not installed process-wide, with the listener's [port] set as if it were up. */
        internal fun forTest(options: AgentOptions, mode: String, port: Int = 0): AgentRuntime =
            AgentRuntime(options, null, mode, "test-token").also { it.port = port }

        /** Test hook: the listener came up on [port] (what [listen] does after binding). */
        internal fun listenedForTest(rt: AgentRuntime, port: Int) = rt.published(port)
    }
}

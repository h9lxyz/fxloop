package dev.fxloop.agent

import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Handshake
import dev.fxloop.protocol.Tokens
import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform

/** Process-wide agent state. One instance per JVM regardless of how the agent was loaded. */
class AgentRuntime private constructor(
    val options: AgentOptions,
    val instrumentation: Instrumentation?,
    val mode: String,
    internal val token: String,
) {
    val startedAt: Long = System.currentTimeMillis()
    val errors: ErrorRing = ErrorRing(200)
    val logs: LogCapture = LogCapture(2000)
    val handles: HandleTable = HandleTable()
    val fxml: FxmlRegistry = FxmlRegistry()

    @Volatile
    internal var server: FcpServer? = null

    /** Sends an FCP event to the connected server, if any; never blocks. */
    internal fun emit(event: String, payload: com.fasterxml.jackson.databind.node.ObjectNode) {
        server?.emit(event, payload)
    }

    private fun boot() {
        if (instrumentation != null && options.fxmlHook) {
            FxmlHook.install(instrumentation)
        }
        Thread(::awaitFxAndListen, "fxloop-agent-init").apply { isDaemon = true }.start()
        options.ownerPid?.let(OwnerWatchdog::start)
    }

    private fun awaitFxAndListen() {
        val deadline = System.currentTimeMillis() + 60_000
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
        if (options.bar) {
            try {
                DevBar.start()
            } catch (t: Throwable) {
                AgentLog.warn("dev bar failed to start", t)
            }
        }
        Thread(::listen, "fxloop-agent-listen").apply { isDaemon = true }.start()
    }

    private fun listen() {
        try {
            val s = FcpServer(this)
            server = s
            val port = s.bind()
            val pid = ManagementFactory.getRuntimeMXBean().pid
            val writeToken = options.token == null
            Handshake(Fcp.VERSION, pid, port, startedAt, mode, if (writeToken) token else null)
                .writeTo(options.handshake!!.toAbsolutePath())
            AgentLog.info("FCP listening on 127.0.0.1:$port ($mode)")
            s.acceptLoop()
        } catch (e: Exception) {
            AgentLog.warn("FCP listener failed", e)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val INSTANCE = AtomicReference<AgentRuntime?>()

        /** Starts the agent once; later calls return the existing runtime. */
        @JvmStatic
        fun start(options: AgentOptions, inst: Instrumentation?, mode: String): AgentRuntime {
            val token = options.token ?: Tokens.generate()
            val fresh = AgentRuntime(options, inst, mode, token)
            if (!INSTANCE.compareAndSet(null, fresh)) {
                return INSTANCE.get()!!
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
    }
}

package dev.fxloop.agent;

import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.Handshake;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;

/** Process-wide agent state. One instance per JVM regardless of how the agent was loaded. */
public final class AgentRuntime {
    private static final AtomicReference<AgentRuntime> INSTANCE = new AtomicReference<>();

    private final AgentOptions options;
    private final Instrumentation instrumentation;
    private final String mode;
    private final String token;
    private final long startedAt = System.currentTimeMillis();
    private final ErrorRing errors = new ErrorRing(200);
    private final LogCapture logs = new LogCapture(2000);
    private final HandleTable handles = new HandleTable();
    private final FxmlRegistry fxml = new FxmlRegistry();
    private volatile FcpServer server;

    private AgentRuntime(AgentOptions options, Instrumentation instrumentation, String mode, String token) {
        this.options = options;
        this.instrumentation = instrumentation;
        this.mode = mode;
        this.token = token;
    }

    /** Starts the agent once; later calls return the existing runtime. */
    public static AgentRuntime start(AgentOptions options, Instrumentation inst, String mode) {
        String token = options.token() != null ? options.token() : dev.fxloop.protocol.Tokens.generate();
        AgentRuntime fresh = new AgentRuntime(options, inst, mode, token);
        if (!INSTANCE.compareAndSet(null, fresh)) {
            return INSTANCE.get();
        }
        fresh.boot();
        return fresh;
    }

    public static AgentRuntime get() {
        return INSTANCE.get();
    }

    private void boot() {
        if (instrumentation != null && options.fxmlHook()) {
            FxmlHook.install(instrumentation);
        }
        Thread t = new Thread(this::awaitFxAndListen, "fxloop-agent-init");
        t.setDaemon(true);
        t.start();
    }

    private void awaitFxAndListen() {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Platform.runLater(this::onFxThread);
                return;
            } catch (IllegalStateException notYet) {
                sleep(100);
            } catch (Throwable t) {
                AgentLog.warn("JavaFX toolkit probe failed", t);
                return;
            }
        }
        AgentLog.info("JavaFX toolkit did not start within 60 s; agent idle");
    }

    private void onFxThread() {
        Fx.rememberFxThread(Thread.currentThread());
        errors.installOn(Thread.currentThread());
        if (options.captureLogs()) {
            logs.install();
        }
        if (options.stateFile() != null) {
            new GeometryStore(options.stateFile()).install();
        }
        Thread t = new Thread(this::listen, "fxloop-agent-listen");
        t.setDaemon(true);
        t.start();
    }

    private void listen() {
        try {
            server = new FcpServer(this);
            int port = server.bind();
            long pid = ManagementFactory.getRuntimeMXBean().getPid();
            boolean writeToken = options.token() == null;
            new Handshake(Fcp.VERSION, pid, port, startedAt, mode, writeToken ? token : null)
                    .writeTo(options.handshake().toAbsolutePath());
            AgentLog.info("FCP listening on 127.0.0.1:" + port + " (" + mode + ")");
            server.acceptLoop();
        } catch (Exception e) {
            AgentLog.warn("FCP listener failed", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public AgentOptions options() { return options; }
    public Instrumentation instrumentation() { return instrumentation; }
    public String mode() { return mode; }
    String token() { return token; }
    public long startedAt() { return startedAt; }
    public ErrorRing errors() { return errors; }
    public LogCapture logs() { return logs; }
    public HandleTable handles() { return handles; }
    public FxmlRegistry fxml() { return fxml; }
    FcpServer server() { return server; }
}

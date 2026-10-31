package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpCodec;
import dev.fxloop.protocol.FcpError;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.FcpFrame;
import dev.fxloop.protocol.Json;
import dev.fxloop.protocol.Tokens;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback-only FCP endpoint. One authenticated connection at a time; a new authenticated connection
 * replaces the previous one (server restarts / reconnects).
 */
final class FcpServer {
    private final AgentRuntime runtime;
    private final Dispatcher dispatcher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fxloop-agent-worker");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket serverSocket;
    private volatile Connection current;

    FcpServer(AgentRuntime runtime) {
        this.runtime = runtime;
        this.dispatcher = new Dispatcher(runtime);
        runtime.errors().onError(e -> emit(Fcp.Event.UI_ERROR, e));
        runtime.logs().onRecord(r -> emit(Fcp.Event.APP_LOG, r));
    }

    int bind() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return serverSocket.getLocalPort();
    }

    void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Thread t = new Thread(() -> serve(socket), "fxloop-agent-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                AgentLog.warn("accept failed", e);
            }
        }
    }

    private void serve(Socket socket) {
        Connection conn;
        try {
            conn = new Connection(socket, new FcpCodec(socket.getInputStream(), socket.getOutputStream()));
        } catch (IOException e) {
            closeQuietly(socket);
            return;
        }
        try {
            if (!handshake(conn)) {
                closeQuietly(socket);
                return;
            }
            Connection previous = current;
            current = conn;
            if (previous != null) {
                closeQuietly(previous.socket);
            }
            while (true) {
                FcpFrame frame = conn.codec.read();
                if (!frame.isRequest()) {
                    continue;
                }
                worker.submit(() -> handle(conn, frame));
            }
        } catch (EOFException eof) {
            AgentLog.debug("FCP peer disconnected");
        } catch (IOException e) {
            AgentLog.debug("FCP connection closed: " + e.getMessage());
        } finally {
            if (current == conn) {
                current = null;
            }
            closeQuietly(socket);
        }
    }

    private boolean handshake(Connection conn) throws IOException {
        FcpFrame hello = conn.codec.read();
        if (!Fcp.Method.HELLO.equals(hello.method())) {
            conn.codec.write(FcpFrame.error(hello.id(), new FcpError(Fcp.Error.BAD_REQUEST, "first frame must be hello")));
            return false;
        }
        ObjectNode p = hello.params();
        if (!Tokens.constantTimeEquals(runtime.token(), p.path("token").asText(null))) {
            conn.codec.write(FcpFrame.error(hello.id(), new FcpError(Fcp.Error.UNAUTHORIZED, "invalid token")));
            return false;
        }
        int requested = p.path("fcpVersion").asInt(1);
        int maxClient = p.path("maxFcpVersion").asInt(requested);
        int negotiated = Math.min(Fcp.VERSION, maxClient);
        if (!Fcp.isSupported(negotiated) || negotiated < requested && !Fcp.isSupported(requested)) {
            ObjectNode data = Json.object().put("agentVersion", Fcp.VERSION).put("agentMinVersion", Fcp.MIN_VERSION);
            conn.codec.write(FcpFrame.error(hello.id(), new FcpError(Fcp.Error.VERSION_MISMATCH,
                    "FCP version " + requested + " unsupported; agent speaks " + Fcp.MIN_VERSION + ".." + Fcp.VERSION
                            + " — upgrade fxloop-server and fxloop-agent together", data)));
            return false;
        }
        ObjectNode result = Json.object();
        result.put("fcpVersion", negotiated);
        result.put("pid", ProcessHandle.current().pid());
        result.put("mode", runtime.mode());
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("vmName", System.getProperty("java.vm.name"));
        result.put("javafxVersion", System.getProperty("javafx.runtime.version", System.getProperty("javafx.version")));
        result.put("redefineSupported", runtime.instrumentation() != null
                && runtime.instrumentation().isRedefineClassesSupported());
        conn.codec.write(FcpFrame.result(hello.id(), result));
        return true;
    }

    private void handle(Connection conn, FcpFrame frame) {
        FcpFrame reply;
        try {
            JsonNode result = dispatcher.dispatch(frame.method(), frame.params());
            reply = FcpFrame.result(frame.id(), result);
        } catch (FcpException e) {
            reply = FcpFrame.error(frame.id(), e.toError());
        } catch (Throwable t) {
            AgentLog.warn("request " + frame.method() + " failed", t);
            reply = FcpFrame.error(frame.id(), new FcpError(Fcp.Error.INTERNAL, t.toString()));
        }
        try {
            conn.codec.write(reply);
        } catch (IOException e) {
            AgentLog.debug("reply dropped: " + e.getMessage());
        }
    }

    private void emit(String event, ObjectNode payload) {
        Connection conn = current;
        if (conn == null) {
            return;
        }
        try {
            conn.codec.write(FcpFrame.event(event, payload));
        } catch (IOException e) {
            AgentLog.debug("event dropped: " + e.getMessage());
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private record Connection(Socket socket, FcpCodec codec) {}
}

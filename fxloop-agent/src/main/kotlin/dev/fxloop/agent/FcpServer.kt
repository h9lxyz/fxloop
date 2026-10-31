package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpCodec
import dev.fxloop.protocol.FcpError
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.FcpFrame
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.Tokens
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Loopback-only FCP endpoint. One authenticated connection at a time; a new authenticated connection
 * replaces the previous one (server restarts / reconnects).
 */
internal class FcpServer(private val runtime: AgentRuntime) {
    private val dispatcher = Dispatcher(runtime)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "fxloop-agent-worker").apply { isDaemon = true } }
    private lateinit var serverSocket: ServerSocket

    @Volatile
    private var current: Connection? = null

    private class Connection(val socket: Socket, val codec: FcpCodec)

    init {
        runtime.errors.onError { e -> emit(Fcp.Event.UI_ERROR, e) }
        runtime.logs.onRecord { r -> emit(Fcp.Event.APP_LOG, r) }
    }

    fun bind(): Int {
        serverSocket = ServerSocket()
        serverSocket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        return serverSocket.localPort
    }

    fun acceptLoop() {
        while (!serverSocket.isClosed) {
            try {
                val socket = serverSocket.accept()
                socket.tcpNoDelay = true
                Thread({ serve(socket) }, "fxloop-agent-conn").apply { isDaemon = true }.start()
            } catch (e: IOException) {
                AgentLog.warn("accept failed", e)
            }
        }
    }

    private fun serve(socket: Socket) {
        val conn: Connection
        try {
            conn = Connection(socket, FcpCodec(socket.getInputStream(), socket.getOutputStream()))
        } catch (e: IOException) {
            closeQuietly(socket)
            return
        }
        try {
            if (!handshake(conn)) {
                closeQuietly(socket)
                return
            }
            val previous = current
            current = conn
            if (previous != null) closeQuietly(previous.socket)
            while (true) {
                val frame = conn.codec.read()
                if (!frame.isRequest) continue
                worker.submit(Runnable { handle(conn, frame) })
            }
        } catch (eof: EOFException) {
            AgentLog.debug("FCP peer disconnected")
        } catch (e: IOException) {
            AgentLog.debug("FCP connection closed: ${e.message}")
        } finally {
            if (current === conn) current = null
            closeQuietly(socket)
        }
    }

    private fun handshake(conn: Connection): Boolean {
        val hello = conn.codec.read()
        if (Fcp.Method.HELLO != hello.method) {
            conn.codec.write(FcpFrame.error(hello.id, FcpError(Fcp.Error.BAD_REQUEST, "first frame must be hello")))
            return false
        }
        val p = hello.params ?: Json.obj()
        if (!Tokens.constantTimeEquals(runtime.token, p.path("token").asText(null))) {
            conn.codec.write(FcpFrame.error(hello.id, FcpError(Fcp.Error.UNAUTHORIZED, "invalid token")))
            return false
        }
        val requested = p.path("fcpVersion").asInt(1)
        val maxClient = p.path("maxFcpVersion").asInt(requested)
        val negotiated = minOf(Fcp.VERSION, maxClient)
        if (!Fcp.isSupported(negotiated) || negotiated < requested && !Fcp.isSupported(requested)) {
            val data = Json.obj().put("agentVersion", Fcp.VERSION).put("agentMinVersion", Fcp.MIN_VERSION)
            conn.codec.write(
                FcpFrame.error(
                    hello.id,
                    FcpError(
                        Fcp.Error.VERSION_MISMATCH,
                        "FCP version $requested unsupported; agent speaks ${Fcp.MIN_VERSION}..${Fcp.VERSION}" +
                            " — upgrade fxloop-server and fxloop-agent together",
                        data,
                    ),
                ),
            )
            return false
        }
        val result = Json.obj()
        result.put("fcpVersion", negotiated)
        result.put("pid", ProcessHandle.current().pid())
        result.put("mode", runtime.mode)
        result.put("javaVersion", System.getProperty("java.version"))
        result.put("javaVendor", System.getProperty("java.vendor"))
        result.put("vmName", System.getProperty("java.vm.name"))
        result.put("javafxVersion", System.getProperty("javafx.runtime.version", System.getProperty("javafx.version")))
        result.put("redefineSupported", runtime.instrumentation?.isRedefineClassesSupported == true)
        conn.codec.write(FcpFrame.result(hello.id, result))
        return true
    }

    private fun handle(conn: Connection, frame: FcpFrame) {
        val reply: FcpFrame = try {
            FcpFrame.result(frame.id, dispatcher.dispatch(frame.method!!, frame.params))
        } catch (e: FcpException) {
            FcpFrame.error(frame.id, e.toError())
        } catch (t: Throwable) {
            AgentLog.warn("request ${frame.method} failed", t)
            FcpFrame.error(frame.id, FcpError(Fcp.Error.INTERNAL, t.toString()))
        }
        try {
            conn.codec.write(reply)
        } catch (e: IOException) {
            AgentLog.debug("reply dropped: ${e.message}")
        }
    }

    internal fun emit(event: String, payload: ObjectNode) {
        val conn = current ?: return
        try {
            conn.codec.write(FcpFrame.event(event, payload))
        } catch (e: IOException) {
            AgentLog.debug("event dropped: ${e.message}")
        }
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (ignored: IOException) {
            // best effort
        }
    }
}

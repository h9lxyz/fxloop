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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Loopback-only FCP endpoint. One authenticated connection at a time; a new authenticated connection
 * replaces the previous one (server restarts / reconnects).
 *
 * Resource rules (the agent lives inside someone else's app and must never hurt it): application threads never
 * touch the socket — events go through a bounded drop-oldest queue drained by one daemon writer; the request
 * queue is bounded; a write stuck for [WRITE_STALL_MS] closes the connection; unauthenticated sockets time out.
 */
internal class FcpServer(private val runtime: AgentRuntime) {
    private val dispatcher = Dispatcher(runtime)
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(MAX_PENDING_REQUESTS)) { r ->
        Thread(r, "fxloop-agent-worker").apply { isDaemon = true }
    }
    private val events = ArrayBlockingQueue<FcpFrame>(MAX_PENDING_EVENTS)
    private lateinit var serverSocket: ServerSocket

    @Volatile
    private var current: Connection? = null

    private class Connection(val socket: Socket, val codec: FcpCodec) {
        @Volatile var writeStartedAt = 0L

        fun write(frame: FcpFrame) {
            writeStartedAt = System.currentTimeMillis()
            try {
                codec.write(frame)
            } finally {
                writeStartedAt = 0L
            }
        }
    }

    init {
        runtime.errors.onError { e -> emit(Fcp.Event.UI_ERROR, e) }
        runtime.logs.onRecord { r -> emit(Fcp.Event.APP_LOG, r) }
        Thread(::drainEvents, "fxloop-agent-events").apply { isDaemon = true }.start()
        Thread(::watchStalledWrites, "fxloop-agent-write-watchdog").apply { isDaemon = true }.start()
    }

    private fun drainEvents() {
        while (true) {
            val frame = try {
                events.take()
            } catch (e: InterruptedException) {
                return
            }
            val conn = current ?: continue
            try {
                conn.write(frame)
            } catch (e: IOException) {
                AgentLog.debug("event dropped: ${e.message}")
            }
        }
    }

    /** A peer that stops reading would block writers forever; drop it instead (it can reconnect). */
    private fun watchStalledWrites() {
        while (true) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return
            }
            val conn = current ?: continue
            val started = conn.writeStartedAt
            if (started != 0L && System.currentTimeMillis() - started > WRITE_STALL_MS) {
                AgentLog.info("FCP peer stopped reading; closing the connection")
                closeQuietly(conn.socket)
            }
        }
    }

    fun bind(): Int {
        serverSocket = ServerSocket()
        serverSocket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        return serverSocket.localPort
    }

    fun acceptLoop() {
        var backoff = 100L
        while (!serverSocket.isClosed) {
            try {
                val socket = serverSocket.accept()
                socket.tcpNoDelay = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS // unauthenticated peers may not hold a thread forever
                Thread({ serve(socket) }, "fxloop-agent-conn").apply { isDaemon = true }.start()
                backoff = 100L
            } catch (e: IOException) {
                if (serverSocket.isClosed) return
                AgentLog.warn("accept failed (retrying in $backoff ms)", e)
                try {
                    Thread.sleep(backoff)
                } catch (ie: InterruptedException) {
                    return
                }
                backoff = minOf(backoff * 2, 2000L)
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
            socket.soTimeout = 0 // authenticated: requests may be minutes apart
            val previous = current
            current = conn
            if (previous != null) closeQuietly(previous.socket)
            while (true) {
                val frame = conn.codec.read()
                if (!frame.isRequest) continue
                try {
                    worker.execute { handle(conn, frame) }
                } catch (e: RejectedExecutionException) {
                    conn.write(FcpFrame.error(frame.id, FcpError(Fcp.Error.INTERNAL, "agent busy: too many queued requests")))
                }
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
            conn.write(FcpFrame.error(hello.id, FcpError(Fcp.Error.BAD_REQUEST, "first frame must be hello")))
            return false
        }
        val p = hello.params ?: Json.obj()
        if (!Tokens.constantTimeEquals(runtime.token, p.path("token").asText(null))) {
            conn.write(FcpFrame.error(hello.id, FcpError(Fcp.Error.UNAUTHORIZED, "invalid token")))
            return false
        }
        val requested = p.path("fcpVersion").asInt(1)
        val maxClient = p.path("maxFcpVersion").asInt(requested)
        val negotiated = minOf(Fcp.VERSION, maxClient)
        if (!Fcp.isSupported(negotiated) || negotiated < requested && !Fcp.isSupported(requested)) {
            val data = Json.obj().put("agentVersion", Fcp.VERSION).put("agentMinVersion", Fcp.MIN_VERSION)
            conn.write(
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
        conn.write(FcpFrame.result(hello.id, result))
        return true
    }

    private fun handle(conn: Connection, frame: FcpFrame) {
        if (conn !== current) return // the requester is gone (replaced or disconnected): don't run stale UI work
        val reply: FcpFrame = try {
            FcpFrame.result(frame.id, dispatcher.dispatch(frame.method!!, frame.params))
        } catch (e: FcpException) {
            FcpFrame.error(frame.id, e.toError())
        } catch (t: Throwable) {
            AgentLog.warn("request ${frame.method} failed", t)
            FcpFrame.error(frame.id, FcpError(Fcp.Error.INTERNAL, t.toString()))
        }
        try {
            conn.write(reply)
        } catch (e: IOException) {
            AgentLog.debug("reply dropped: ${e.message}")
        }
    }

    /** Called on application threads (FX thread, loggers): never blocks — drops the oldest event when full. */
    private fun emit(event: String, payload: ObjectNode) {
        if (current == null) return
        val frame = FcpFrame.event(event, payload)
        while (!events.offer(frame)) events.poll()
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (ignored: IOException) {
            // best effort
        }
    }

    private companion object {
        const val MAX_PENDING_REQUESTS = 64
        const val MAX_PENDING_EVENTS = 1000
        const val HANDSHAKE_TIMEOUT_MS = 5000
        const val WRITE_STALL_MS = 10_000L
    }
}

package dev.fxloop.server.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpCodec
import dev.fxloop.protocol.FcpError
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.FcpFrame
import dev.fxloop.protocol.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Server side of FCP: correlates responses, forwards events, enforces the outer 10 s timeout (PRD 01 §8). */
class FcpClient private constructor(private val socket: Socket, private val events: (String, JsonNode?) -> Unit) : AutoCloseable {
    private val log = LoggerFactory.getLogger(FcpClient::class.java)
    private val codec = FcpCodec(socket.getInputStream(), socket.getOutputStream())
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<FcpFrame>>()

    @Volatile private var closed = false
    lateinit var hello: ObjectNode
        private set

    val isOpen: Boolean get() = !closed

    private fun readLoop() {
        try {
            while (!closed) {
                val f = codec.read()
                when {
                    f.isEvent -> runCatching { events(f.event!!, f.payload) }.onFailure { log.warn("event handler failed", it) }
                    f.id != null -> pending.remove(f.id)?.complete(f)
                }
            }
        } catch (e: IOException) {
            log.debug("FCP connection ended: {}", e.message)
        } finally {
            closed = true
            val lost = FcpFrame.error(null, FcpError(Fcp.Error.DISCONNECTED, "FCP connection to the app was lost"))
            pending.values.forEach { it.complete(lost) }
            pending.clear()
        }
    }

    @JvmOverloads
    fun request(method: String, params: ObjectNode = Json.obj(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonNode {
        if (closed) throw FcpException(Fcp.Error.DISCONNECTED, "not connected to the app")
        val id = ids.incrementAndGet()
        val fut = CompletableFuture<FcpFrame>()
        pending[id] = fut
        try {
            codec.write(FcpFrame.request(id, method, params))
            val reply = fut.get(timeoutMs, TimeUnit.MILLISECONDS)
            reply.error?.let { throw FcpException(it) }
            return reply.result ?: Json.obj()
        } catch (e: TimeoutException) {
            throw FcpException(Fcp.Error.FX_TIMEOUT, "agent did not answer $method within $timeoutMs ms")
        } catch (e: IOException) {
            throw FcpException(Fcp.Error.DISCONNECTED, "FCP write failed: ${e.message}")
        } catch (e: ExecutionException) {
            throw FcpException(Fcp.Error.INTERNAL, e.cause.toString())
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** @param yieldToAgents connect as the `fxloop:run` helper: refused (BUSY) while an agent session is connected */
        fun connect(port: Int, token: String, events: (String, JsonNode?) -> Unit, yieldToAgents: Boolean = false): FcpClient {
            val s = try {
                Socket(InetAddress.getLoopbackAddress(), port).apply { tcpNoDelay = true }
            } catch (e: IOException) {
                throw FcpException(Fcp.Error.DISCONNECTED, "cannot reach the agent on port $port: ${e.message}")
            }
            val c = try {
                FcpClient(s, events)
            } catch (e: Exception) {
                runCatching { s.close() }
                throw FcpException(Fcp.Error.DISCONNECTED, "FCP setup failed: ${e.message}")
            }
            thread(isDaemon = true, name = "fxloop-fcp-reader") { c.readLoop() }
            try {
                c.hello = c.request(Fcp.Method.HELLO, Json.obj {
                    put("token", token)
                    put("fcpVersion", Fcp.VERSION)
                    put("maxFcpVersion", Fcp.VERSION)
                    if (yieldToAgents) put("yield", true)
                }, 5000) as ObjectNode
            } catch (e: Exception) {
                c.close()
                throw e
            }
            return c
        }
    }
}

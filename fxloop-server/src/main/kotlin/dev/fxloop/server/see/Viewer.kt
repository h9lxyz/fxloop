package dev.fxloop.server.see

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.fxloop.protocol.Tokens
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Opt-in, loopback-only, token-guarded, read-only live viewer (PRD 04 §3.7). */
class Viewer(private val requestedPort: Int, private val token: String, private val store: ScreenStore) : AutoCloseable {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val subscribers: MutableSet<LinkedBlockingQueue<String>> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var closed = false

    /** Bound port; valid after [start]. */
    val port: Int get() = server!!.address.port

    fun port(): Int = port

    @Synchronized
    fun start() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0)
        val ex = Executors.newCachedThreadPool { r ->
            Thread(r, "fxloop-viewer").also { it.isDaemon = true }
        }
        s.executor = ex
        s.createContext("/") { handle(it) }
        store.onNewFrame { f ->
            val msg = "event: frame\ndata: {\"seq\":${f.seq}}\n\n"
            for (q in subscribers) q.offer(msg)
        }
        executor = ex
        server = s
        s.start()
    }

    fun url(): String = "http://127.0.0.1:$port/?t=$token"

    @Synchronized
    override fun close() {
        closed = true
        server?.let {
            for (q in subscribers) q.offer("")
            it.stop(0)
            executor?.shutdownNow()
            server = null
        }
    }

    private fun handle(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "GET") {
                send(ex, 405, "text/plain", "method not allowed".toByteArray())
                return
            }
            val t = queryParam(ex.requestURI.rawQuery, "t")
            if (t == null || !Tokens.constantTimeEquals(token, t)) {
                send(ex, 403, "text/plain", "forbidden".toByteArray())
                return
            }
            val path = ex.requestURI.path
            when (path) {
                "/" -> send(ex, 200, "text/html; charset=utf-8", page().toByteArray())
                "/latest.png" -> {
                    val f = store.latestScreenshot()
                    if (f == null) {
                        send(ex, 404, "text/plain", "no frame".toByteArray())
                    } else {
                        send(ex, 200, "image/png", Files.readAllBytes(f.file))
                    }
                }
                "/index" -> send(
                    ex, 200, "text/html; charset=utf-8",
                    Gallery.render(store.list(), "/screens/", "?t=$token").toByteArray(),
                )
                "/sse" -> sse(ex)
                else -> if (path.startsWith("/screens/")) {
                    screen(ex, path.removePrefix("/screens/"))
                } else {
                    send(ex, 404, "text/plain", "not found".toByteArray())
                }
            }
        } catch (_: IOException) {
            // client went away
        } finally {
            ex.close()
        }
    }

    private fun screen(ex: HttpExchange, name: String) {
        if (!SAFE_NAME.matches(name)) {
            send(ex, 404, "text/plain", "not found".toByteArray())
            return
        }
        val p = store.dir.resolve(name)
        if (!Files.isRegularFile(p)) {
            send(ex, 404, "text/plain", "not found".toByteArray())
            return
        }
        send(ex, 200, "image/png", Files.readAllBytes(p))
    }

    private fun sse(ex: HttpExchange) {
        val q = LinkedBlockingQueue<String>()
        subscribers.add(q)
        try {
            ex.responseHeaders.set("Content-Type", "text/event-stream")
            ex.responseHeaders.set("Cache-Control", "no-store")
            ex.sendResponseHeaders(200, 0)
            val os = ex.responseBody
            os.write(": connected\n\n".toByteArray(StandardCharsets.UTF_8))
            os.flush()
            while (!closed) {
                var msg = q.poll(15, TimeUnit.SECONDS)
                if (closed) break
                if (msg == null) msg = ": keep-alive\n\n" else if (msg.isEmpty()) break
                os.write(msg.toByteArray(StandardCharsets.UTF_8))
                os.flush() // throws IOException when the client disconnects
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            subscribers.remove(q)
        }
    }

    private fun page(): String {
        val t = token
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>FXLoop viewer</title>" +
            "<style>body{font:14px system-ui,sans-serif;margin:0;background:#1e1f22;color:#ddd;text-align:center}" +
            "img{max-width:100%;margin-top:8px;border:1px solid #444}a{color:#8ab4f8}</style></head><body>" +
            "<div style=\"padding:8px\"><a href=\"/index?t=$t\">gallery</a></div>" +
            "<img id=\"f\" src=\"/latest.png?t=$t\" alt=\"latest frame\">" +
            "<script>var t=${jsString(t)};var img=document.getElementById('f');" +
            "var es=new EventSource('/sse?t='+encodeURIComponent(t));" +
            "es.addEventListener('frame',function(){img.src='/latest.png?t='+encodeURIComponent(t)+'&r='+Date.now();});" +
            "</script></body></html>"
    }

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

    private fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery == null) return null
        for (kv in rawQuery.split("&")) {
            val i = kv.indexOf('=')
            val k = if (i < 0) kv else kv.substring(0, i)
            if (k == name) {
                return if (i < 0) "" else URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)
            }
        }
        return null
    }

    private companion object {
        val SAFE_NAME = Regex("^\\d{4,}-[a-z0-9-]+\\.png$")
    }
}

package dev.fxloop.server.see

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.Tokens
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Backend of the inspector API: an FCP call (method, params, timeout ms) against the running app. */
typealias ViewerApi = (method: String, params: ObjectNode, timeoutMs: Long) -> JsonNode

/**
 * Opt-in, loopback-only, token-guarded live viewer (PRD 04 §3.7) plus the CSS inspector API (PRD 08): read-only
 * except `POST /api/highlight` and `POST /api/pick`, which are visual-only and ephemeral. While [editEnabled]
 * (`FXLOOP_INSPECTOR_EDIT`, or a developer-run app started with it) the scratch-CSS endpoints exist too (PRD 08 §5): they accept CSS text only, which the
 * agent parses before installing. `POST /api/scratch/apply` (`?dryRun=1` previews) writes the scratch rules into the
 * project's stylesheets through [scratchApply]. Without the flag they answer 404; while the agent holds the input lock
 * ([lockText]) the editing endpoints answer 423 and `POST /api/lock/release` lets the developer take over.
 * With [prompts], `POST /api/prompt` (JSON `{text, window?, h?, path?, property?, …}`) queues a developer prompt for
 * the agent, `POST /api/prompt/cancel?id=` withdraws one and `GET /api/prompts` lists them; changes arrive as SSE `prompts`.
 */
class Viewer(
    private val requestedPort: Int,
    private val token: String,
    private val store: ScreenStore,
    private val api: ViewerApi? = null,
    private val connected: () -> Boolean = { false },
    private val editorUrl: String? = null,
    private val editEnabled: () -> Boolean = { false },
    private val lockText: () -> String? = { null },
    private val releaseLock: (() -> Unit)? = null,
    private val scratchApply: ((dryRun: Boolean) -> JsonNode)? = null,
    private val prompts: Prompts? = null,
) : AutoCloseable {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val subscribers: MutableMap<LinkedBlockingQueue<String>, String> = ConcurrentHashMap()
    private val bucket = TokenBucket(RATE_PER_SEC, RATE_PER_SEC)

    @Volatile
    private var closed = false

    @Volatile
    private var lastStatus: String? = null

    /** Publishes the compact bar-state JSON as an SSE `status` event and keeps it for new subscribers. */
    fun publishStatus(json: String) {
        lastStatus = json
        val msg = "event: status\ndata: $json\n\n"
        for (q in subscribers.keys) q.offer(msg)
    }

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
            for (q in subscribers.keys) q.offer(msg)
        }
        executor = ex
        server = s
        s.start()
    }

    fun url(): String = "http://127.0.0.1:$port/?t=$token"

    /** Base of the inspector page URL (callers append `&h=…&path=…`). */
    fun inspectUrl(): String = "http://127.0.0.1:$port/inspect?t=${URLEncoder.encode(token, StandardCharsets.UTF_8)}"

    /** Broadcasts a named SSE event to every subscriber. */
    fun publish(event: String, json: JsonNode) {
        val msg = "event: $event\ndata: ${Json.write(json).replace("\n", " ")}\n\n"
        for (q in subscribers.keys) q.offer(msg)
    }

    /** Connected `/sse?from=<page>` subscribers, e.g. `from=inspect` for the inspector page. */
    fun subscriberCount(from: String): Int = subscribers.values.count { it == from }

    @Synchronized
    override fun close() {
        closed = true
        server?.let {
            for (q in subscribers.keys) q.offer("")
            it.stop(0)
            executor?.shutdownNow()
            server = null
        }
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            if (!editEnabled() && path.startsWith(SCRATCH_PREFIX)) {
                send(ex, 404, "text/plain", "not found".toByteArray())
                return
            }
            val allowed = if (path in POST_PATHS) "POST" else "GET"
            if (ex.requestMethod != allowed) {
                send(ex, 405, "text/plain", "method not allowed".toByteArray())
                return
            }
            val t = queryParam(ex.requestURI.rawQuery, "t")
            if (t == null || !Tokens.constantTimeEquals(token, t)) {
                send(ex, 403, "text/plain", "forbidden".toByteArray())
                return
            }
            when (path) {
                "/inspect" -> inspectPage(ex)
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
                else -> if (path.startsWith("/api/")) {
                    api(ex, path.removePrefix("/api/"))
                } else if (path.startsWith("/screens/")) {
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

    private fun inspectPage(ex: HttpExchange) {
        val body = Viewer::class.java.getResourceAsStream(INSPECT_RESOURCE)?.use { it.readAllBytes() }
        if (body == null) send(ex, 404, "text/plain", "inspector page missing".toByteArray())
        else send(ex, 200, "text/html; charset=utf-8", body)
    }

    private fun api(ex: HttpExchange, name: String) {
        if (!bucket.tryTake()) {
            send(ex, 429, "application/json", Json.write(error("RATE_LIMITED", "too many requests")).toByteArray())
            return
        }
        val q = ex.requestURI.rawQuery
        fun p(k: String) = queryParam(q, k)?.takeIf { it.isNotEmpty() }
        val params = Json.obj()
        // h (handle) is preferred; path is the fallback
        fun target() {
            p("h")?.toIntOrNull()?.let { params.put("handle", it) }
            p("path")?.let { params.put("path", it) }
        }
        if (name.startsWith("scratch/")) {
            lockText()?.let { why ->
                send(ex, 423, "application/json", Json.write(error("LOCKED", "editing is paused: $why")).toByteArray())
                return
            }
        }
        if (name == "lock/release") {
            releaseLock?.invoke()
            send(ex, 200, "application/json", Json.write(Json.obj { put("locked", false) }).toByteArray())
            return
        }
        if (name.startsWith("prompt")) {
            prompt(ex, name, prompts)
            return
        }
        if (name == "scratch/apply") {
            val apply = scratchApply
            if (apply == null || !connected()) {
                send(ex, 503, "application/json", Json.write(error("NO_APP", "no application is connected")).toByteArray())
                return
            }
            val out: JsonNode = try {
                apply(p("dryRun") in TRUE).also { if (it.path("applied").asBoolean()) publish("scratch", Json.obj { put("active", false); put("rules", 0) }) }
            } catch (e: FcpException) {
                error(e.code, e.message ?: e.code)
            } catch (e: dev.fxloop.server.mcp.ToolException) {
                error(e.code, e.message ?: e.code)
            } catch (e: Exception) {
                error("INTERNAL", e.message ?: e.javaClass.simpleName)
            }
            send(ex, 200, "application/json", Json.write(out).toByteArray())
            return
        }
        val method: String
        var timeout = 15_000L
        when (name) {
            "windows" -> method = Fcp.Method.STATUS
            "tree" -> {
                method = Fcp.Method.GET_SCENE_GRAPH
                p("window")?.toIntOrNull()?.let { params.put("windowIndex", it) }
                if (p("skin") in TRUE) params.put("skin", true)
                target()
                params.put("maxDepth", 64).put("maxNodes", 3000)
            }
            "styles" -> { method = Fcp.Method.CSS_CASCADE; target() }
            "layout" -> { method = Fcp.Method.EXPLAIN_LAYOUT; target() }
            "suggest" -> {
                method = Fcp.Method.CSS_SUGGEST
                target()
                p("property")?.let { params.put("property", it) }
                p("value")?.let { params.put("value", it) }
                p("file")?.let { params.put("file", it) }
            }
            "highlight" -> {
                method = Fcp.Method.HIGHLIGHT
                if (p("clear") in TRUE) params.put("clear", true) else target()
            }
            "pick" -> {
                method = Fcp.Method.PICK_START
                p("window")?.toIntOrNull()?.let { params.put("windowIndex", it) }
                if (p("cancel") in TRUE) params.put("cancel", true)
            }
            "scratch" -> method = Fcp.Method.STATUS
            "scratch/set" -> {
                method = Fcp.Method.CSS_SCRATCH_SET
                val type = ex.requestHeaders.getFirst("Content-Type").orEmpty().lowercase()
                if (!type.startsWith("text/css") && !type.startsWith("text/plain")) {
                    send(ex, 415, "application/json", Json.write(error("BAD_REQUEST", "send the scratch CSS as text/css")).toByteArray())
                    return
                }
                val body = ex.requestBody.readNBytes(MAX_SCRATCH_BYTES + 1)
                if (body.size > MAX_SCRATCH_BYTES) {
                    send(ex, 413, "application/json", Json.write(error("BAD_REQUEST", "scratch CSS is larger than 64 KB")).toByteArray())
                    return
                }
                params.put("css", String(body, StandardCharsets.UTF_8))
            }
            "scratch/clear" -> method = Fcp.Method.CSS_SCRATCH_CLEAR
            else -> {
                send(ex, 404, "text/plain", "not found".toByteArray())
                return
            }
        }
        val backend = api
        if (backend == null || !connected()) {
            send(ex, 503, "application/json", Json.write(error("NO_APP", "no application is connected")).toByteArray())
            return
        }
        val out: JsonNode = try {
            val r = backend(method, params, timeout)
            when (name) {
                "windows" -> r.path("windows")
                "styles" -> {
                    if (r is ObjectNode && editorUrl != null) r.put("editorUrl", editorUrl)
                    if (r is ObjectNode && editEnabled()) r.put("edit", true)
                    r
                }
                "scratch" -> r.path("scratchCss").takeIf { it.isObject } ?: Json.obj { put("active", false); put("rules", 0) }
                "scratch/set", "scratch/clear" -> r.also { publish("scratch", it) }
                else -> r
            }
        } catch (e: FcpException) {
            error(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            error("INTERNAL", e.message ?: e.javaClass.simpleName)
        }
        send(ex, 200, "application/json", Json.write(out).toByteArray())
    }

    private fun prompt(ex: HttpExchange, name: String, queue: Prompts?) {
        fun json(code: Int, body: JsonNode) = send(ex, code, "application/json", Json.write(body).toByteArray())
        if (queue == null) return send(ex, 404, "text/plain", "not found".toByteArray())
        when (name) {
            "prompts" -> json(200, queue.snapshot())
            "prompt/cancel" -> {
                val id = queryParam(ex.requestURI.rawQuery, "id")?.toIntOrNull()
                    ?: return json(400, error("BAD_REQUEST", "id is required"))
                json(200, Json.obj { put("cancelled", queue.cancel(id)) })
            }
            "prompt" -> {
                val type = ex.requestHeaders.getFirst("Content-Type").orEmpty().lowercase()
                if (!type.startsWith("application/json")) return json(415, error("BAD_REQUEST", "send the prompt as application/json"))
                val raw = ex.requestBody.readNBytes(MAX_PROMPT_BYTES + 1)
                if (raw.size > MAX_PROMPT_BYTES) return json(413, error("BAD_REQUEST", "prompt is larger than 16 KB"))
                val body = runCatching { Json.MAPPER.readTree(raw) }.getOrNull() as? ObjectNode
                    ?: return json(400, error("BAD_REQUEST", "body must be a JSON object"))
                val text = body.path("text").asText("").trim()
                if (text.isEmpty() || text.length > MAX_PROMPT_CHARS) {
                    return json(400, error("BAD_REQUEST", "text must be 1–$MAX_PROMPT_CHARS characters"))
                }
                // Only known context fields, so the page cannot smuggle arbitrary structure into the agent's result.
                val context = Json.obj {
                    body.path("window").takeIf { it.isInt }?.let { put("window", it.asInt()) }
                    body.path("h").takeIf { it.isInt }?.let { put("h", it.asInt()) }
                    for (k in CONTEXT_STRINGS) body.path(k).takeIf { it.isTextual }?.let { put(k, it.asText().take(MAX_CONTEXT_CHARS)) }
                    body.path("line").takeIf { it.isInt }?.let { put("line", it.asInt()) }
                }
                json(200, queue.toJson(queue.submit(text, context)))
            }
            else -> send(ex, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun error(code: String, message: String): ObjectNode =
        Json.obj { putObject("error").put("code", code).put("message", message) }

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
        subscribers[q] = queryParam(ex.requestURI.rawQuery, "from") ?: ""
        try {
            ex.responseHeaders.set("Content-Type", "text/event-stream")
            ex.responseHeaders.set("Cache-Control", "no-store")
            ex.sendResponseHeaders(200, 0)
            val os = ex.responseBody
            os.write(": connected\n\n".toByteArray(StandardCharsets.UTF_8))
            lastStatus?.let { os.write("event: status\ndata: $it\n\n".toByteArray(StandardCharsets.UTF_8)) }
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
            "img{max-width:100%;margin-top:8px;border:1px solid #444}a{color:#8ab4f8}" +
            "#st{padding:6px 8px;background:#2b2d30;color:#bbb;font-size:13px}#st.warn{color:#f0b429}</style></head><body>" +
            "<div id=\"st\">connecting…</div>" +
            "<div style=\"padding:8px\"><a href=\"/index?t=$t\">gallery</a></div>" +
            "<img id=\"f\" src=\"/latest.png?t=$t\" alt=\"latest frame\">" +
            "<script>var t=${jsString(t)};var img=document.getElementById('f');" +
            "var es=new EventSource('/sse?t='+encodeURIComponent(t));" +
            "es.addEventListener('frame',function(){img.src='/latest.png?t='+encodeURIComponent(t)+'&r='+Date.now();});" +
            "var st=document.getElementById('st');" +
            "es.addEventListener('status',function(e){try{var s=JSON.parse(e.data);var a=s.actionNeeded||[];" +
            "var txt=(s.mode||'')+' \\u00b7 control: '+(s.control||'')+(s.driving?' \\u00b7 agent driving':'')+" +
            "' \\u00b7 '+(a.length?'action needed: '+a.map(function(x){return x.reason+' \\u2192 '+x.next;}).join('; '):'no action needed');" +
            "st.textContent=txt;st.className=a.length?'warn':'';}catch(x){}});" +
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

    /** Simple thread-safe token bucket: [capacity] burst, [perSec] refill. */
    private class TokenBucket(private val capacity: Int, private val perSec: Int) {
        private var tokens = capacity.toDouble()
        private var last = System.nanoTime()

        @Synchronized
        fun tryTake(): Boolean {
            val now = System.nanoTime()
            tokens = minOf(capacity.toDouble(), tokens + (now - last) / 1e9 * perSec)
            last = now
            if (tokens < 1) return false
            tokens -= 1
            return true
        }
    }

    private companion object {
        const val RATE_PER_SEC = 30
        const val INSPECT_RESOURCE = "/dev/fxloop/server/inspect/inspect.html"
        val POST_PATHS = setOf("/api/highlight", "/api/pick", "/api/scratch/set", "/api/scratch/clear", "/api/scratch/apply",
            "/api/lock/release", "/api/prompt", "/api/prompt/cancel")
        const val SCRATCH_PREFIX = "/api/scratch"
        const val MAX_SCRATCH_BYTES = 64 * 1024
        const val MAX_PROMPT_BYTES = 16 * 1024
        const val MAX_PROMPT_CHARS = 4000
        const val MAX_CONTEXT_CHARS = 512
        val CONTEXT_STRINGS = listOf("path", "type", "fxId", "property", "selector", "file", "tab")
        val TRUE = setOf("1", "true")
        val SAFE_NAME = Regex("^\\d{4,}-[a-z0-9-]+\\.png$")
    }
}

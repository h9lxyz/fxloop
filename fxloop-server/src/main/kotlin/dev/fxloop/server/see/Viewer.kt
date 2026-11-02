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
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
 * The inspector's stylesheet mappings ([CssMappings]): a sheet outside the project → its local source file. [set]
 * throws [IllegalArgumentException] with a message for the developer on bad input.
 */
interface SheetMap {
    /** `{url, resource?, suggestions:[file]}`: existing files the sheet is likely built from. */
    fun suggest(url: String): JsonNode

    /** Maps [url] to [file], or removes its mapping when [file] is blank: `{url, file}`. */
    fun set(url: String, file: String?): JsonNode
}

/**
 * Opt-in, loopback-only, token-guarded live viewer (PRD 04 §3.7) plus the CSS inspector API (PRD 08): read-only
 * except `POST /api/highlight` and `POST /api/pick`, which are visual-only and ephemeral. While [editEnabled]
 * (`FXLOOP_INSPECTOR_EDIT`, or a developer-run app started with it) the scratch-CSS endpoints exist too (PRD 08 §5): they accept CSS text only, which the
 * agent parses before installing. `POST /api/scratch/apply` (`?dryRun=1` previews; JSON body `{targets:{"<n>": file}}`
 * picks a stylesheet per scratch rule) writes the scratch rules into the project's stylesheets, or a library's mapped
 * source, through [scratchApply]. With [sheetMap], `GET /api/sheet-map/suggest?url=` and `POST /api/sheet-map`
 * (JSON `{url, file}`) map a stylesheet outside the project to its source. Without the flag they answer 404; while the
 * agent holds the input lock ([lockText]) the editing endpoints answer 423 and `POST /api/lock/release` lets the
 * developer take over.
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
    private val scratchApply: ((dryRun: Boolean, targets: Map<Int, String>) -> JsonNode)? = null,
    private val prompts: Prompts? = null,
    private val sheetMap: SheetMap? = null,
) : AutoCloseable {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val subscribers: MutableMap<LinkedBlockingQueue<String>, String> = ConcurrentHashMap()
    private val bucket = TokenBucket(RATE_PER_SEC, RATE_PER_SEC)

    @Volatile
    private var closed = false

    @Volatile
    private var lastStatus: String? = null

    @Volatile
    private var movedTo: String? = null

    /**
     * Another server's inspector now serves the app (the `fxloop:run` helper yielded to an agent session): pages get
     * an SSE `moved {url}` event, also on connect, and switch over; null when the app is back here.
     */
    fun setMovedTo(url: String?) {
        if (url == movedTo) return
        movedTo = url
        publish("moved", Json.obj { if (url != null) put("url", url) else putNull("url") })
    }

    private class Remote(val origin: String, val token: String)

    @Volatile
    private var remote: Remote? = null

    @Volatile
    private var remoteSnapshot: String? = null

    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() }

    /**
     * The `fxloop:run` helper still holds the app, but an agent session's server is listening for prompts at
     * [inspectUrl]: the prompt endpoints forward there (pending local prompts are handed over first) and
     * [pollRemotePrompts] relays its queue to the pages. Null serves the local queue again.
     */
    fun setPromptsRemote(inspectUrl: String?) {
        val r = inspectUrl?.let {
            Remote(it.substringBefore("/inspect"), URLDecoder.decode(it.substringAfter("?t=").substringBefore('&'), StandardCharsets.UTF_8))
        }
        if (r?.origin == remote?.origin && r?.token == remote?.token) return
        remote = r
        remoteSnapshot = null
        val queue = prompts ?: return
        if (r == null) {
            publish("prompts", queue.snapshot())
            return
        }
        for (item in queue.snapshot().path("items")) {
            if (item.path("status").asText() != Prompts.PENDING) continue
            val body = Json.obj { put("text", item.path("text").asText()); setAll<JsonNode>(forwardable(item.path("context"))) }
            if (runCatching { remoteCall(r, "POST", "prompt", "", body).first == 200 }.getOrDefault(false)) {
                queue.cancel(item.path("id").asInt())
            }
        }
        pollRemotePrompts()
    }

    /** Re-publishes the remote queue to the pages when it changed (no-op without a remote). */
    fun pollRemotePrompts() {
        val r = remote ?: return
        val (code, body) = runCatching { remoteCall(r, "GET", "prompts", "", null) }.getOrNull() ?: return
        if (code != 200 || body == remoteSnapshot) return
        remoteSnapshot = body
        val msg = "event: prompts\ndata: ${body.replace("\n", " ")}\n\n"
        for (q in subscribers.keys) q.offer(msg)
    }

    /** `(status, body)` of a call to the remote viewer's prompt API. */
    private fun remoteCall(r: Remote, method: String, name: String, query: String, json: JsonNode?): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI.create("${r.origin}/api/$name?t=${URLEncoder.encode(r.token, StandardCharsets.UTF_8)}$query"))
            .timeout(Duration.ofSeconds(5))
        if (json != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(json)))
        else b.method(method, HttpRequest.BodyPublishers.noBody())
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body()
    }

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
            if (!editEnabled() && EDIT_PREFIXES.any(path::startsWith)) {
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
        if (name.startsWith("scratch/") || name == "sheet-map") {
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
        if (name == "sheet-map" || name == "sheet-map/suggest") {
            sheetMapCall(ex, name)
            return
        }
        if (name == "scratch/apply") {
            val apply = scratchApply
            if (apply == null || !connected()) {
                send(ex, 503, "application/json", Json.write(error("NO_APP", "no application is connected")).toByteArray())
                return
            }
            val targets = HashMap<Int, String>()
            if (isJson(ex)) {
                val body = readJson(ex) ?: return send(ex, 400, "application/json", Json.write(error("BAD_REQUEST", "body must be a JSON object")).toByteArray())
                body.path("targets").fields().forEach { (k, v) ->
                    val n = k.toIntOrNull()
                    if (n != null && n > 0 && v.isTextual && v.asText().length <= MAX_PATH_CHARS) targets[n] = v.asText()
                }
            }
            val out: JsonNode = try {
                apply(p("dryRun") in TRUE, targets).also {
                    if (it.path("applied").asBoolean()) {
                        publish("scratch", it.path("scratch").takeIf { s -> s.isObject } ?: Json.obj { put("active", false); put("rules", 0) })
                    }
                }
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
            "selector" -> {
                method = Fcp.Method.CSS_SELECTOR
                target()
                params.put("selector", p("selector").orEmpty().take(MAX_SELECTOR_CHARS))
            }
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

    /** `GET /api/sheet-map/suggest?url=` and `POST /api/sheet-map {url, file}`; a change is pushed as SSE `sheetMap`. */
    private fun sheetMapCall(ex: HttpExchange, name: String) {
        fun json(code: Int, body: JsonNode) = send(ex, code, "application/json", Json.write(body).toByteArray())
        val map = sheetMap ?: return send(ex, 404, "text/plain", "not found".toByteArray())
        try {
            if (name == "sheet-map/suggest") {
                val url = queryParam(ex.requestURI.rawQuery, "url")?.takeIf { it.isNotEmpty() } ?: return json(400, error("BAD_REQUEST", "url is required"))
                return json(200, map.suggest(url))
            }
            if (!isJson(ex)) return json(415, error("BAD_REQUEST", "send the mapping as application/json"))
            val body = readJson(ex) ?: return json(400, error("BAD_REQUEST", "body must be a JSON object"))
            val url = body.path("url").asText("")
            val file = body.path("file").takeIf { it.isTextual }?.asText()
            if (file != null && file.length > MAX_PATH_CHARS) return json(400, error("BAD_REQUEST", "file is too long"))
            val r = map.set(url, file)
            publish("sheetMap", r)
            json(200, r)
        } catch (e: IllegalArgumentException) {
            json(200, error("BAD_REQUEST", e.message ?: "bad request"))
        }
    }

    private fun isJson(ex: HttpExchange) = ex.requestHeaders.getFirst("Content-Type").orEmpty().lowercase().startsWith("application/json")

    /** The request body as a JSON object (at most 16 KB), or null. */
    private fun readJson(ex: HttpExchange): ObjectNode? {
        val raw = ex.requestBody.readNBytes(MAX_PROMPT_BYTES + 1)
        if (raw.size > MAX_PROMPT_BYTES) return null
        return runCatching { Json.MAPPER.readTree(raw) }.getOrNull() as? ObjectNode
    }

    private fun prompt(ex: HttpExchange, name: String, queue: Prompts?) {
        fun json(code: Int, body: JsonNode) = send(ex, code, "application/json", Json.write(body).toByteArray())
        if (queue == null) return send(ex, 404, "text/plain", "not found".toByteArray())
        val r = remote
        // Forwarded to a listening agent session's server; if it cannot be reached, the local queue serves.
        fun forward(method: String, query: String, body: JsonNode?): Boolean {
            if (r == null) return false
            val (code, text) = runCatching { remoteCall(r, method, name, query, body) }.getOrNull() ?: return false
            send(ex, code, "application/json", text.toByteArray())
            pollRemotePrompts()
            return true
        }
        when (name) {
            "prompts" -> if (!forward("GET", "", null)) json(200, queue.snapshot())
            "prompt/cancel" -> {
                val id = queryParam(ex.requestURI.rawQuery, "id")?.toIntOrNull()
                    ?: return json(400, error("BAD_REQUEST", "id is required"))
                if (!forward("POST", "&id=$id", null)) json(200, Json.obj { put("cancelled", queue.cancel(id)) })
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
                if (!forward("POST", "", Json.obj { put("text", text); setAll<JsonNode>(forwardable(context)) })) {
                    json(200, queue.toJson(queue.submit(text, context)))
                }
            }
            else -> send(ex, 404, "text/plain", "not found".toByteArray())
        }
    }

    /** Context for another server: `h` is a handle of this server's session and means nothing there; `path` stays. */
    private fun forwardable(context: JsonNode): ObjectNode =
        (context.deepCopy<JsonNode>() as? ObjectNode ?: Json.obj()).apply { remove("h") }

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
            movedTo?.let { os.write("event: moved\ndata: ${Json.write(Json.obj { put("url", it) })}\n\n".toByteArray(StandardCharsets.UTF_8)) }
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
    internal class TokenBucket(private val capacity: Int, private val perSec: Int, private val clock: () -> Long = System::nanoTime) {
        private var tokens = capacity.toDouble()
        private var last = clock()

        @Synchronized
        fun tryTake(): Boolean {
            val now = clock()
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
            "/api/lock/release", "/api/prompt", "/api/prompt/cancel", "/api/sheet-map")

        /** Endpoints that only exist while inspector editing is on. */
        val EDIT_PREFIXES = listOf("/api/scratch", "/api/sheet-map")
        const val MAX_PATH_CHARS = 4096
        const val MAX_SCRATCH_BYTES = 64 * 1024
        const val MAX_SELECTOR_CHARS = 500
        const val MAX_PROMPT_BYTES = 16 * 1024
        const val MAX_PROMPT_CHARS = 4000
        const val MAX_CONTEXT_CHARS = 512
        val CONTEXT_STRINGS = listOf("path", "type", "fxId", "property", "selector", "file", "tab")
        val TRUE = setOf("1", "true")
        val SAFE_NAME = Regex("^\\d{4,}-[a-z0-9-]+\\.png$")
    }
}

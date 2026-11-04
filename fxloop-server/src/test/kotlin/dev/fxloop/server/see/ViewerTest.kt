package dev.fxloop.server.see

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ViewerTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }

    private val http = HttpClient.newHttpClient()

    private fun get(url: String): Int =
        http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    @Test
    fun tokenAndEndpoints(@TempDir dir: Path) {
        val store = ScreenStore(dir, 10)
        Viewer(0, "sekrit", store).use { v ->
            v.start()
            val base = "http://127.0.0.1:" + v.port
            assertThat(v.url()).isEqualTo("$base/?t=sekrit")
            assertThat(get("$base/")).isEqualTo(403)
            assertThat(get("$base/?t=wrong")).isEqualTo(403)
            assertThat(get("$base/?t=sekrit")).isEqualTo(200)
            assertThat(get("$base/latest.png?t=sekrit")).isEqualTo(404)
            val f = store.save(ImageOpsTest.png(30, 20, Color.WHITE, 0, 0, 0, 0), "hello", "screenshot")
            assertThat(get("$base/latest.png?t=sekrit")).isEqualTo(200)
            assertThat(get("$base/screens/${f.file.fileName}?t=sekrit")).isEqualTo(200)
            assertThat(get("$base/screens/..%2F..%2Fetc%2Fpasswd?t=sekrit")).isEqualTo(404)
            assertThat(get("$base/screens/index.html?t=sekrit")).isEqualTo(404)
            assertThat(get("$base/screens/${f.file.fileName}")).isEqualTo(403)
            val idx = http.send(HttpRequest.newBuilder(URI.create("$base/index?t=sekrit")).build(), HttpResponse.BodyHandlers.ofString())
            assertThat(idx.body()).contains("/screens/0001-hello.png?t=sekrit")
            val post = http.send(
                HttpRequest.newBuilder(URI.create("$base/?t=sekrit")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertThat(post.statusCode()).isEqualTo(405)
        }
    }

    @Test
    fun sseSendsLastStatusImmediatelyAndEveryPublish(@TempDir dir: Path) {
        Viewer(0, "tok", ScreenStore(dir, 10)).use { v ->
            v.start()
            v.publishStatus("{\"control\":\"agent\"}")
            val conn = URI.create("http://127.0.0.1:${v.port}/sse?t=tok").toURL().openConnection() as HttpURLConnection
            conn.readTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val lines = ArrayList<String>()
            while (lines.size < 4) lines += reader.readLine()
            assertThat(lines).contains("event: status", "data: {\"control\":\"agent\"}")
            v.publishStatus("{\"control\":\"shared\"}")
            var line: String
            do line = reader.readLine() while (!line.startsWith("data: {\"control\":\"shared"))
            conn.disconnect()
            val page = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${v.port}/?t=tok")).build(),
                HttpResponse.BodyHandlers.ofString()).body()
            assertThat(page).contains("addEventListener('status'").doesNotContain("<button")
        }
    }

    @Test
    fun sseDeliversEvent(@TempDir dir: Path) {
        val store = ScreenStore(dir, 10)
        Viewer(0, "tok", store).use { v ->
            v.start()
            val conn = URI.create("http://127.0.0.1:${v.port}/sse?t=tok").toURL().openConnection() as HttpURLConnection
            conn.readTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            assertThat(reader.readLine()).startsWith(":")
            reader.readLine()
            val got = CompletableFuture.supplyAsync {
                try {
                    val sb = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        sb.append(line).append('\n')
                        if (sb.toString().contains("data:")) break
                    }
                    sb.toString()
                } catch (e: Exception) {
                    "ERR $e"
                }
            }
            store.save(ImageOpsTest.png(10, 10, Color.WHITE, 0, 0, 0, 0), "a", "screenshot")
            assertThat(got.get(10, TimeUnit.SECONDS)).contains("event: frame").contains("\"seq\":1")
            conn.disconnect()
        }
    }

    private fun req(url: String, method: String = "GET"): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun viewer(dir: Path, connected: Boolean = true, api: ViewerApi? = null) =
        Viewer(0, "tok", ScreenStore(dir, 10), api, { connected }, "idea://open?file={file}&line={line}")

    @Test
    fun inspectorRequiresTokenAndServesPage(@TempDir dir: Path) {
        viewer(dir).use { v ->
            v.start()
            val base = "http://127.0.0.1:${v.port}"
            assertThat(req("$base/inspect").statusCode()).isEqualTo(403)
            assertThat(req("$base/api/styles?h=1").statusCode()).isEqualTo(403)
            val page = req("$base/inspect?t=tok")
            assertThat(page.statusCode()).isEqualTo(200)
            assertThat(page.headers().firstValue("Content-Type").get()).startsWith("text/html")
            assertThat(page.body()).contains("/api/states", "/api/find", "/api/props", "/api/sheets", "/api/lint", "/api/evidence")
        }
    }

    @Test
    fun postOnlyOnHighlightAndPick(@TempDir dir: Path) {
        val calls = mutableListOf<Pair<String, String>>()
        viewer(dir, api = { m, p, _ -> calls += m to p.toString(); Json.obj { put("shown", true) } }).use { v ->
            v.start()
            val base = "http://127.0.0.1:${v.port}"
            assertThat(req("$base/api/styles?t=tok&h=1", "POST").statusCode()).isEqualTo(405)
            assertThat(req("$base/api/tree?t=tok", "POST").statusCode()).isEqualTo(405)
            assertThat(req("$base/api/highlight?t=tok&h=1").statusCode()).isEqualTo(405)
            assertThat(req("$base/api/pick?t=tok").statusCode()).isEqualTo(405)
            assertThat(req("$base/api/highlight?t=tok&h=7", "POST").statusCode()).isEqualTo(200)
            assertThat(req("$base/api/highlight?t=tok&clear=1", "POST").statusCode()).isEqualTo(200)
            assertThat(req("$base/api/pick?t=tok&window=2", "POST").statusCode()).isEqualTo(200)
            assertThat(req("$base/api/highlight?t=tok&h=7&hold=1", "POST").statusCode()).isEqualTo(200)
            assertThat(req("$base/api/highlight?t=tok&release=1", "POST").statusCode()).isEqualTo(200)
            assertThat(calls.map { it.first }).containsExactly("highlight", "highlight", "pickStart", "highlight", "highlight")
            assertThat(calls[3].second).contains("\"handle\":7").contains("\"hold\":true")
            assertThat(calls[4].second).contains("\"release\":true")
            assertThat(calls[0].second).contains("\"handle\":7")
            assertThat(calls[1].second).contains("\"clear\":true")
            assertThat(calls[2].second).contains("\"windowIndex\":2")
        }
    }

    @Test
    fun apiMapsParamsAndErrors(@TempDir dir: Path) {
        val seen = mutableListOf<Triple<String, ObjectNode, Long>>()
        viewer(dir, api = { m, p, t ->
            seen += Triple(m, p, t)
            if (p.has("path") && p.path("path").asText() == "bad") throw FcpException("NODE_NOT_FOUND", "no such node")
            Json.obj { put("ok", m) }
        }).use { v ->
            v.start()
            val base = "http://127.0.0.1:${v.port}"
            val styles = req("$base/api/styles?t=tok&h=12")
            assertThat(styles.statusCode()).isEqualTo(200)
            assertThat(styles.headers().firstValue("Content-Type").get()).isEqualTo("application/json")
            assertThat(styles.body()).contains("\"editorUrl\":\"idea://open")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.CSS_CASCADE)
            assertThat(seen.last().second["handle"].asInt()).isEqualTo(12)
            req("$base/api/tree?t=tok&window=1&skin=true")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.GET_SCENE_GRAPH)
            assertThat(seen.last().second.toString()).contains("\"windowIndex\":1", "\"skin\":true", "\"maxDepth\":64", "\"maxNodes\":3000")
            req("$base/api/suggest?t=tok&path=Stage%5B0%5D&property=-fx-padding")
            assertThat(seen.last().second.toString()).contains("\"path\":\"Stage[0]\"", "\"property\":\"-fx-padding\"")
            val err = req("$base/api/layout?t=tok&path=bad")
            assertThat(err.statusCode()).isEqualTo(200)
            assertThat(err.body()).contains("\"code\":\"NODE_NOT_FOUND\"", "no such node")
        }
    }

    private fun postCss(url: String, css: String, type: String = "text/css; charset=utf-8"): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(css)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun scratchEndpointsDoNotExistWithoutTheFlag(@TempDir dir: Path) {
        val calls = mutableListOf<String>()
        viewer(dir, api = { m, _, _ -> calls += m; Json.obj() }).use { v ->
            v.start()
            val base = "http://127.0.0.1:${v.port}"
            assertThat(req("$base/api/scratch?t=tok").statusCode()).isEqualTo(404)
            assertThat(postCss("$base/api/scratch/set?t=tok", ".a { -fx-padding: 1; }").statusCode()).isEqualTo(404)
            assertThat(req("$base/api/scratch/clear?t=tok", "POST").statusCode()).isEqualTo(404)
            assertThat(req("$base/api/styles?t=tok&h=1").body()).doesNotContain("\"edit\"")
            assertThat(calls).containsExactly(Fcp.Method.CSS_CASCADE)
        }
    }

    @Test
    fun scratchEndpointsWithTheFlag(@TempDir dir: Path) {
        val seen = mutableListOf<Pair<String, ObjectNode>>()
        Viewer(0, "tok", ScreenStore(dir, 10), { m, p, _ ->
            seen += m to p
            when (m) {
                Fcp.Method.STATUS -> Json.obj { putObject("scratchCss").put("active", true).put("rules", 1) }
                Fcp.Method.CSS_SCRATCH_SET ->
                    if (p.path("css").asText().contains("oops")) throw FcpException("BAD_REQUEST", "scratch CSS has parse errors")
                    else Json.obj { put("active", true); put("rules", 1) }
                else -> Json.obj { put("active", false); put("rules", 0) }
            }
        }, { true }, editEnabled = { true }).use { v ->
            v.start()
            val base = "http://127.0.0.1:${v.port}"
            assertThat(postCss("$base/api/scratch/set", ".a { }").statusCode()).isEqualTo(403)
            assertThat(req("$base/api/scratch/set?t=tok").statusCode()).isEqualTo(405)
            assertThat(postCss("$base/api/scratch/set?t=tok", ".a { }", "application/json").statusCode()).isEqualTo(415)
            assertThat(postCss("$base/api/scratch/set?t=tok", "x".repeat(64 * 1024 + 1)).statusCode()).isEqualTo(413)
            assertThat(seen).isEmpty()

            val ok = postCss("$base/api/scratch/set?t=tok", ".a { -fx-padding: 1; }")
            assertThat(ok.statusCode()).isEqualTo(200)
            assertThat(ok.body()).contains("\"active\":true")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.CSS_SCRATCH_SET)
            assertThat(seen.last().second["css"].asText()).isEqualTo(".a { -fx-padding: 1; }")
            assertThat(postCss("$base/api/scratch/set?t=tok", ".oops {").body()).contains("\"code\":\"BAD_REQUEST\"")
            assertThat(req("$base/api/scratch?t=tok").body()).isEqualTo("{\"active\":true,\"rules\":1}")
            assertThat(req("$base/api/scratch/clear?t=tok", "POST").body()).contains("\"active\":false")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.CSS_SCRATCH_CLEAR)
            assertThat(req("$base/api/styles?t=tok&h=1").body()).contains("\"edit\":true")
        }
    }

    @Test
    fun applyToFileAndTheInputLock(@TempDir dir: Path) {
        var lock: String? = null
        var released = 0
        val dryRuns = mutableListOf<Boolean>()
        Viewer(0, "tok", ScreenStore(dir, 10), { _, _, _ -> Json.obj { put("active", false) } }, { true }, editEnabled = { true },
            lockText = { lock }, releaseLock = { released++; lock = null },
            scratchApply = { dry, _ -> dryRuns += dry; Json.obj { put("applied", !dry); putArray("files").addObject().put("file", "app.css") } },
        ).use { v ->
            v.start()
            val base = "http://127.0.0.1:${v.port}"
            assertThat(req("$base/api/scratch/apply?t=tok").statusCode()).isEqualTo(405)
            assertThat(req("$base/api/scratch/apply?t=tok&dryRun=1", "POST").body()).contains("\"applied\":false")
            assertThat(req("$base/api/scratch/apply?t=tok", "POST").body()).contains("\"applied\":true")
            assertThat(dryRuns).containsExactly(true, false)

            lock = "walking through checkout"
            for (r in listOf(req("$base/api/scratch/apply?t=tok", "POST"), postCss("$base/api/scratch/set?t=tok", ".a { -fx-padding: 1; }"),
                req("$base/api/scratch/clear?t=tok", "POST"))) {
                assertThat(r.statusCode()).isEqualTo(423)
                assertThat(r.body()).contains("\"code\":\"LOCKED\"").contains("walking through checkout")
            }
            assertThat(dryRuns).hasSize(2)
            assertThat(req("$base/api/scratch?t=tok").statusCode()).isEqualTo(200) // reading stays possible
            assertThat(req("$base/api/lock/release").statusCode()).isEqualTo(405)
            assertThat(req("$base/api/lock/release?t=tok", "POST").body()).isEqualTo("{\"locked\":false}")
            assertThat(released).isEqualTo(1)
            assertThat(postCss("$base/api/scratch/set?t=tok", ".a { -fx-padding: 1; }").statusCode()).isEqualTo(200)
        }
    }

    @Test
    fun sheetMappingAndApplyTargets(@TempDir dir: Path) {
        var edit = false
        var lock: String? = null
        val sets = mutableListOf<Pair<String, String?>>()
        val targets = mutableListOf<Map<Int, String>>()
        val map = object : SheetMap {
            override fun suggest(url: String) = Json.obj { put("url", url); putArray("suggestions").add("/src/w.css") }
            override fun set(url: String, file: String?): JsonNode {
                require(file != "bad.css") { "bad.css does not exist" }
                sets += url to file
                return Json.obj { put("url", url); put("file", file) }
            }
        }
        Viewer(0, "tok", ScreenStore(dir, 10), { _, _, _ -> Json.obj() }, { true }, editEnabled = { edit }, lockText = { lock },
            scratchApply = { _, t -> targets += t; Json.obj { put("applied", false) } }, sheetMap = map,
        ).use { v ->
            v.start()
            val base = "http://127.0.0.1:${v.port}"
            val url = "jar:file:/w.jar!/w.css"
            val q = URLEncoder.encode(url, StandardCharsets.UTF_8)
            assertThat(req("$base/api/sheet-map/suggest?t=tok&url=$q").statusCode()).isEqualTo(404) // editing off
            edit = true
            assertThat(req("$base/api/sheet-map/suggest?t=tok&url=$q").body()).contains("/src/w.css")
            assertThat(req("$base/api/sheet-map/suggest?t=tok").statusCode()).isEqualTo(400)
            assertThat(req("$base/api/sheet-map?t=tok").statusCode()).isEqualTo(405)
            assertThat(postCss("$base/api/sheet-map?t=tok", "{}", "text/plain").statusCode()).isEqualTo(415)
            assertThat(postCss("$base/api/sheet-map?t=tok", "{\"url\":\"$url\",\"file\":\"/src/w.css\"}", "application/json").body())
                .contains("\"file\":\"/src/w.css\"")
            assertThat(postCss("$base/api/sheet-map?t=tok", "{\"url\":\"$url\",\"file\":\"bad.css\"}", "application/json").body())
                .contains("\"code\":\"BAD_REQUEST\"").contains("does not exist")
            assertThat(sets).containsExactly(url to "/src/w.css")

            val body = "{\"targets\":{\"1\":\"src/main/resources/app.css\",\"x\":\"no\",\"2\":7}}"
            assertThat(postCss("$base/api/scratch/apply?t=tok&dryRun=1", body, "application/json").statusCode()).isEqualTo(200)
            assertThat(postCss("$base/api/scratch/apply?t=tok&dryRun=1", "[1]", "application/json").statusCode()).isEqualTo(400)
            assertThat(req("$base/api/scratch/apply?t=tok&dryRun=1", "POST").statusCode()).isEqualTo(200)
            assertThat(targets).containsExactly(mapOf(1 to "src/main/resources/app.css"), emptyMap())

            lock = "agent driving"
            assertThat(postCss("$base/api/sheet-map?t=tok", "{\"url\":\"$url\",\"file\":null}", "application/json").statusCode()).isEqualTo(423)
            assertThat(req("$base/api/sheet-map/suggest?t=tok&url=$q").statusCode()).isEqualTo(200) // reading stays possible
            assertThat(sets).hasSize(1)
        }
    }

    @Test
    fun noAppIs503(@TempDir dir: Path) {
        viewer(dir, connected = false, api = { _, _, _ -> Json.obj() }).use { v ->
            v.start()
            val r = req("http://127.0.0.1:${v.port}/api/windows?t=tok")
            assertThat(r.statusCode()).isEqualTo(503)
            assertThat(r.body()).contains("NO_APP")
        }
        Viewer(0, "tok", ScreenStore(dir, 10)).use { v ->
            v.start()
            assertThat(req("http://127.0.0.1:${v.port}/api/tree?t=tok").statusCode()).isEqualTo(503)
        }
    }

    @Test
    fun rateCap429(@TempDir dir: Path) {
        viewer(dir, api = { _, _, _ -> Json.obj() }).use { v ->
            v.start()
            // One concurrent burst: sequential requests on a slow runner can stay under the 30/s refill rate.
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${v.port}/api/windows?t=tok")).build()
            val codes = (1..120).map { http.sendAsync(req, HttpResponse.BodyHandlers.discarding()) }.map { it.get(10, TimeUnit.SECONDS).statusCode() }
            assertThat(codes).contains(200, 429)
        }
    }

    @Test
    fun tokenBucketBurstsThenRefills() {
        var now = 0L
        val bucket = Viewer.TokenBucket(3, 2) { now }
        assertThat((1..4).map { bucket.tryTake() }).containsExactly(true, true, true, false)
        now += 500_000_000
        assertThat(bucket.tryTake()).isTrue()
        assertThat(bucket.tryTake()).isFalse()
        now += 10_000_000_000
        assertThat((1..4).map { bucket.tryTake() }).containsExactly(true, true, true, false)
    }

    @Test
    fun publishReachesSseClient(@TempDir dir: Path) {
        viewer(dir).use { v ->
            v.start()
            val conn = URI.create("http://127.0.0.1:${v.port}/sse?t=tok&from=inspect").toURL().openConnection() as HttpURLConnection
            conn.readTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            reader.readLine()
            reader.readLine()
            assertThat(v.subscriberCount("inspect")).isEqualTo(1)
            assertThat(v.subscriberCount("other")).isEqualTo(0)
            v.publish("reload", Json.obj { putArray("files").add("app.css") })
            assertThat(reader.readLine()).isEqualTo("event: reload")
            assertThat(reader.readLine()).isEqualTo("data: {\"files\":[\"app.css\"]}")
            conn.disconnect()
        }
    }

    private fun postJson(url: String, json: String, type: String = "application/json"): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(json)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun promptEndpointsQueueForTheAgent(@TempDir dir: Path) {
        val prompts = Prompts()
        Viewer(0, "tok", ScreenStore(dir, 10), prompts = prompts).use { v ->
            v.start()
            prompts.onChange = { v.publish("prompts", it) }
            val base = "http://127.0.0.1:${v.port}"
            assertThat(req("$base/api/prompt?t=tok").statusCode()).isEqualTo(405)
            assertThat(postJson("$base/api/prompt?t=tok", "{}", "text/plain").statusCode()).isEqualTo(415)
            assertThat(postJson("$base/api/prompt?t=tok", "{\"text\":\"  \"}").statusCode()).isEqualTo(400)
            assertThat(postJson("$base/api/prompt?t=tok", "not json").statusCode()).isEqualTo(400)
            assertThat(postJson("$base/api/prompt?t=wrong", "{\"text\":\"x\"}").statusCode()).isEqualTo(403)
            val r = postJson("$base/api/prompt?t=tok",
                "{\"text\":\"make it blue\",\"h\":7,\"path\":\"Stage[0]//Button#ok\",\"property\":\"-fx-background-color\",\"evil\":{\"a\":1}}")
            assertThat(r.statusCode()).isEqualTo(200)
            val j = Json.parse(r.body())
            assertThat(j["status"].asText()).isEqualTo("pending")
            assertThat(j["context"]["h"].asInt()).isEqualTo(7)
            assertThat(j["context"]["property"].asText()).isEqualTo("-fx-background-color")
            assertThat(j["context"].has("evil")).isFalse()
            val taken = prompts.take(1000)!!
            assertThat(taken.text).isEqualTo("make it blue")
            prompts.reply(taken.id, "done: app.css:12", true)
            val list = Json.parse(req("$base/api/prompts?t=tok").body())
            assertThat(list["listening"].asBoolean()).isFalse()
            assertThat(list["items"][0]["status"].asText()).isEqualTo("answered")
            assertThat(list["items"][0]["replies"][0]["text"].asText()).isEqualTo("done: app.css:12")
            val second = Json.parse(postJson("$base/api/prompt?t=tok", "{\"text\":\"never mind\"}").body())
            assertThat(Json.parse(req("$base/api/prompt/cancel?t=tok&id=${second["id"]}", "POST").body())["cancelled"].asBoolean()).isTrue()
            assertThat(prompts.take(50)).isNull()
        }
        Viewer(0, "tok", ScreenStore(dir, 10)).use { v ->
            v.start()
            assertThat(req("http://127.0.0.1:${v.port}/api/prompts?t=tok").statusCode()).isEqualTo(404)
        }
    }

    @Test
    fun movedIsSentOnConnectAndOnChange(@TempDir dir: Path) {
        Viewer(0, "tok", ScreenStore(dir, 10)).use { v ->
            v.start()
            v.setMovedTo("http://127.0.0.1:1234/inspect?t=abc")
            val conn = URI.create("http://127.0.0.1:${v.port}/sse?t=tok&from=inspect").toURL().openConnection() as HttpURLConnection
            conn.readTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String
            do line = reader.readLine() while (line != "event: moved")
            assertThat(reader.readLine()).isEqualTo("data: {\"url\":\"http://127.0.0.1:1234/inspect?t=abc\"}")
            v.setMovedTo(null)
            do line = reader.readLine() while (line != "event: moved")
            assertThat(reader.readLine()).isEqualTo("data: {\"url\":null}")
            conn.disconnect()
        }
    }

    @Test
    fun helperForwardsPromptsToTheListeningAgentServer(@TempDir dir: Path) {
        val local = Prompts()
        val agent = Prompts()
        Viewer(0, "agenttok", ScreenStore(dir.resolve("a"), 10), prompts = agent).use { a ->
            Viewer(0, "helpertok", ScreenStore(dir.resolve("h"), 10), prompts = local).use { h ->
                a.start()
                h.start()
                val hb = "http://127.0.0.1:${h.port}"
                local.submit("sent before the agent appeared", Json.obj { put("h", 3); put("path", "Stage[0]//Button#ok") })
                h.setPromptsRemote(a.inspectUrl())
                assertThat(local.snapshot()["items"][0]["status"].asText()).isEqualTo(Prompts.CANCELLED)
                val handed = agent.take(1000)!!
                assertThat(handed.text).isEqualTo("sent before the agent appeared")
                assertThat(handed.context.has("h")).isFalse()
                assertThat(handed.context["path"].asText()).isEqualTo("Stage[0]//Button#ok")

                val r = postJson("$hb/api/prompt?t=helpertok", "{\"text\":\"why grey?\",\"h\":5,\"path\":\"Stage[0]//Label\",\"property\":\"-fx-text-fill\"}")
                assertThat(r.statusCode()).isEqualTo(200)
                val p = agent.take(1000)!!
                assertThat(p.text).isEqualTo("why grey?")
                assertThat(p.context.has("h")).isFalse()
                assertThat(p.context["property"].asText()).isEqualTo("-fx-text-fill")
                agent.reply(p.id, "inline style wins", true)
                val list = Json.parse(req("$hb/api/prompts?t=helpertok").body())
                assertThat(list["items"].map { it["text"].asText() }).containsExactly("sent before the agent appeared", "why grey?")
                assertThat(list["items"][1]["replies"][0]["text"].asText()).isEqualTo("inline style wins")

                val third = Json.parse(postJson("$hb/api/prompt?t=helpertok", "{\"text\":\"never mind\"}").body())
                assertThat(Json.parse(req("$hb/api/prompt/cancel?t=helpertok&id=${third["id"]}", "POST").body())["cancelled"].asBoolean()).isTrue()
                assertThat(agent.take(50)).isNull()

                h.setPromptsRemote(null)
                postJson("$hb/api/prompt?t=helpertok", "{\"text\":\"local again\"}")
                assertThat(local.take(1000)!!.text).isEqualTo("local again")
            }
        }
    }
}

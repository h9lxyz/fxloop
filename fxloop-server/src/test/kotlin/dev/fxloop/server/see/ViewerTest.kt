package dev.fxloop.server.see

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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
            assertThat(calls.map { it.first }).containsExactly("highlight", "highlight", "pickStart")
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
            val codes = (1..120).map { req("http://127.0.0.1:${v.port}/api/windows?t=tok").statusCode() }
            assertThat(codes).contains(429)
            assertThat(codes.first()).isEqualTo(200)
        }
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
}

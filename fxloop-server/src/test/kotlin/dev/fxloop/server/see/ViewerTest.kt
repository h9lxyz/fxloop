package dev.fxloop.server.see

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
}

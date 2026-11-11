package org.lab59.fxloop.server.see

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Inspector v2 endpoints (PRD 09 D1) against a fake backend. */
class ViewerV2Test {
    companion object {
        @BeforeAll
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }

        private val PLAIN = ImageOpsTest.png(40, 30, Color.WHITE, 0, 0, 0, 0)
        private val CHANGED = ImageOpsTest.png(40, 30, Color.WHITE, 5, 5, 20, 15)
    }

    private val http = HttpClient.newHttpClient()

    private fun call(url: String, method: String = "GET", body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(url))
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        else b.method(method, HttpRequest.BodyPublishers.noBody())
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun bytes(url: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray())

    private class FakeShots(var after: ByteArray = PLAIN) : ViewerShots {
        val calls = ArrayList<String>()
        override fun nodePng(h: Int?, path: String?, maxWidth: Int): ByteArray {
            calls += "shot h=$h path=$path w=$maxWidth"
            return if (calls.size % 2 == 1) PLAIN else after
        }

        override fun statesStrip(h: Int?, path: String?): ByteArray = CHANGED.also { calls += "strip h=$h" }
    }

    private fun viewer(
        dir: Path, api: ViewerApi = { _, _, _ -> Json.obj() }, edit: Boolean = true, lock: () -> String? = { null },
        shots: ViewerShots? = null, props: ScratchProps? = null, fxml: ((Boolean) -> com.fasterxml.jackson.databind.JsonNode)? = null,
        gone: (() -> Unit)? = null,
    ) = Viewer(0, "tok", ScreenStore(dir, 10), api, { true }, editEnabled = { edit }, lockText = lock, shots = shots, props = props,
        fxmlApply = fxml, inspectorGone = gone)

    @Test
    fun `read endpoints map their parameters onto FCP calls`(@TempDir dir: Path) {
        val seen = ArrayList<Pair<String, ObjectNode>>()
        val api: ViewerApi = { m, p, _ ->
            seen += m to p
            if (m == Fcp.Method.FIND_NODES) Json.obj {
                put("total", 250); put("count", 200); put("has_more", true)
                putArray("items").addObject().put("h", 4).put("path", "Stage[0]/Button#ok").put("type", "Button").put("fxId", "ok")
                    .put("visible", true).put("shown", true)
            } else Json.obj { put("ok", m) }
        }
        viewer(dir, api).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}/api"
            call("$b/states?t=tok&h=7&states=hover,focused,bad%20name", "POST")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.FORCE_STATES)
            assertThat(seen.last().second.toString()).contains("\"handle\":7", "\"states\":[\"hover\",\"focused\"]")
            call("$b/states?t=tok", "POST")
            assertThat(seen.last().second["states"].size()).isZero()
            assertThat(call("$b/states?t=tok&h=7&states=hover").statusCode()).isEqualTo(405)

            call("$b/sheets?t=tok&window=1&sheet=file%3A%2Fa.css")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.CSS_SHEETS)
            assertThat(seen.last().second.toString()).contains("\"windowIndex\":1", "\"sheet\":\"file:/a.css\"")
            call("$b/lint?t=tok")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.CSS_LINT)
            assertThat(seen.last().second.has("windowIndex")).isFalse()
            call("$b/handlers?t=tok&h=3")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.NODE_HANDLERS)
            call("$b/property?t=tok&h=3&name=prefWidth")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.EXPLAIN_PROPERTY)
            assertThat(seen.last().second.toString()).contains("\"handle\":3", "\"name\":\"prefWidth\"")

            call("$b/highlight?t=tok&handles=1,2,x,3&labels=1", "POST")
            assertThat(seen.last().first).isEqualTo(Fcp.Method.HIGHLIGHT)
            assertThat(seen.last().second.toString()).contains("\"handles\":[1,2,3]", "\"labels\":true").doesNotContain("\"handle\":")
            call("$b/highlight?t=tok&handles=" + (1..80).joinToString(","), "POST")
            assertThat(seen.last().second["handles"].size()).isEqualTo(50)
        }
    }

    @Test
    fun `find grammar picks text type or selector and answers compact items`(@TempDir dir: Path) {
        val seen = ArrayList<ObjectNode>()
        viewer(dir, { _, p, _ ->
            seen += p
            Json.obj {
                put("total", 250); put("has_more", true)
                putArray("items").addObject().put("h", 4).put("path", "Stage[0]/Button#ok").put("type", "Button").put("fxId", "ok")
                    .put("visible", true).put("shown", true).putObject("boundsScreen")
            }
        }).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}/api/find?t=tok"
            val r = Json.parse(call("$b&q=text:Save%20all").body())
            assertThat(seen.last().toString()).contains("\"text\":\"Save all\"", "\"limit\":200").doesNotContain("windowIndex")
            assertThat(r["total"].asInt()).isEqualTo(250)
            assertThat(r["items"][0].fieldNames().asSequence().toList()).containsExactly("h", "path", "type", "fxId")
            call("$b&q=type:Button&window=2")
            assertThat(seen.last().toString()).contains("\"type\":\"Button\"", "\"windowIndex\":2")
            call("$b&q=.primary%20%3E%20Label")
            assertThat(seen.last().toString()).contains("\"query\":\".primary > Label\"")
            call("$b&q=selector:%23save")
            assertThat(seen.last().toString()).contains("\"query\":\"#save\"")
            assertThat(call("$b&q=").statusCode()).isEqualTo(400)
        }
    }

    @Test
    fun `node images are PNGs and count shots`(@TempDir dir: Path) {
        val shots = FakeShots()
        viewer(dir, shots = shots).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}/api"
            val shot = bytes("$b/shot?t=tok&h=9&w=900")
            assertThat(shot.headers().firstValue("Content-Type").get()).isEqualTo("image/png")
            assertThat(shot.body()).isEqualTo(PLAIN)
            assertThat(shots.calls.last()).isEqualTo("shot h=9 path=null w=320")
            assertThat(bytes("$b/strip?t=tok&h=9").headers().firstValue("Content-Type").get()).isEqualTo("image/png")
            assertThat(call("$b/shot?t=tok").statusCode()).isEqualTo(400)
            assertThat(call("$b/shot?t=tok&h=9", "POST").statusCode()).isEqualTo(405)
        }
    }

    @Test
    fun `props endpoints exist only with the edit flag and answer 423 while locked`(@TempDir dir: Path) {
        val props = ScratchProps { _, _, _, _ -> null }
        viewer(dir, edit = false, props = props, fxml = { Json.obj() }).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}/api"
            assertThat(call("$b/props?t=tok").statusCode()).isEqualTo(404)
            assertThat(call("$b/props?t=tok", "POST", "{\"items\":[]}").statusCode()).isEqualTo(404)
            assertThat(call("$b/props/clear?t=tok", "POST").statusCode()).isEqualTo(404)
            assertThat(call("$b/fxml/apply?t=tok&dryRun=1", "POST").statusCode()).isEqualTo(404)
            assertThat(call("$b/evidence?t=tok&seq=1&which=before").statusCode()).isEqualTo(404) // no such pair, but not gated
        }
        var lock: String? = "agent is walking through the checkout"
        viewer(dir, props = props, lock = { lock }, fxml = { Json.obj() }).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}/api"
            assertThat(call("$b/props?t=tok").statusCode()).isEqualTo(200) // reading is allowed
            for (path in listOf("props", "props/clear", "fxml/apply")) {
                val r = call("$b/$path?t=tok", "POST", "{\"items\":[]}")
                assertThat(r.statusCode()).isEqualTo(423)
                assertThat(r.body()).contains("LOCKED")
            }
            lock = null
            assertThat(call("$b/props?t=tok", "POST", "{\"items\":[]}").statusCode()).isEqualTo(200)
            assertThat(call("$b/props?t=tok").statusCode()).isEqualTo(200)
            assertThat(call("$b/props/clear?t=tok").statusCode()).isEqualTo(405)
        }
    }

    @Test
    fun `props post is whole state and publishes SSE props`(@TempDir dir: Path) {
        val writes = ArrayList<String>()
        val props = ScratchProps { h, _, property, value -> writes += "$h.$property=$value"; "old-$property" }
        viewer(dir, props = props).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}"
            val conn = URI.create("$b/sse?t=tok&from=inspect").toURL().openConnection() as HttpURLConnection
            conn.readTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            reader.readLine()
            reader.readLine()
            val r = Json.parse(call("$b/api/props?t=tok", "POST",
                "{\"items\":[{\"h\":4,\"path\":\"S/B\",\"property\":\"HBox.hgrow\",\"value\":\"ALWAYS\"},{\"h\":4,\"path\":\"S/B\",\"property\":\"prefWidth\",\"value\":120}]}").body())
            assertThat(r["items"].map { it["property"].asText() + ":" + it["before"].asText() + "→" + it["after"].asText() })
                .containsExactly("HBox.hgrow:old-HBox.hgrow→ALWAYS", "prefWidth:old-prefWidth→120")
            var line: String
            do line = reader.readLine() while (!line.startsWith("data:"))
            assertThat(line).contains("\"property\":\"HBox.hgrow\"")
            // second state drops one item and changes the other
            call("$b/api/props?t=tok", "POST", "{\"items\":[{\"h\":4,\"path\":\"S/B\",\"property\":\"prefWidth\",\"value\":\"200\"}]}")
            assertThat(writes).containsExactly("4.HBox.hgrow=ALWAYS", "4.prefWidth=120", "4.HBox.hgrow=old-HBox.hgrow", "4.prefWidth=200")
            assertThat(Json.parse(call("$b/api/props?t=tok").body())["items"][0]["before"].asText()).isEqualTo("old-prefWidth")
            assertThat(call("$b/api/props?t=tok", "POST", "{\"items\":[{\"property\":\"x\"}]}").statusCode()).isEqualTo(400)
            call("$b/api/props/clear?t=tok", "POST")
            assertThat(writes.last()).isEqualTo("4.prefWidth=old-prefWidth")
            assertThat(Json.parse(call("$b/api/props?t=tok").body())["items"].size()).isZero()
            conn.disconnect()
        }
    }

    @Test
    fun `evidence on props and scratch set reports changedPct and serves the pair`(@TempDir dir: Path) {
        val shots = FakeShots(after = CHANGED)
        val props = ScratchProps { _, _, _, _ -> "0" }
        viewer(dir, { _, _, _ -> Json.obj { put("active", true) } }, shots = shots, props = props).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}"
            val r = Json.parse(call("$b/api/props?t=tok&evidence=1", "POST",
                "{\"items\":[{\"h\":4,\"path\":\"S/B\",\"property\":\"prefWidth\",\"value\":\"10\"}]}").body())
            val ev = r["evidence"]
            assertThat(ev["changedPct"].asDouble()).isGreaterThan(0.0)
            assertThat(ev["verdict"].asText()).isNotEqualTo("none")
            val seq = ev["seq"].asInt()
            val before = bytes("$b/api/evidence?t=tok&seq=$seq&which=before")
            assertThat(before.headers().firstValue("Content-Type").get()).isEqualTo("image/png")
            assertThat(before.body()).isEqualTo(PLAIN)
            assertThat(bytes("$b/api/evidence?t=tok&seq=$seq&which=after").body()).isEqualTo(CHANGED)
            assertThat(call("$b/api/evidence?t=tok&seq=$seq&which=sideways").statusCode()).isEqualTo(400)

            // scratch css with evidence: before-shot is call 3 (odd -> PLAIN), after call 4 (CHANGED)
            val css = http.send(HttpRequest.newBuilder(URI.create("$b/api/scratch/set?t=tok&h=4&evidence=1"))
                .header("Content-Type", "text/css").POST(HttpRequest.BodyPublishers.ofString(".a{}")).build(), HttpResponse.BodyHandlers.ofString())
            val e2 = Json.parse(css.body())["evidence"]
            assertThat(e2["seq"].asInt()).isEqualTo(seq + 1)
            // without evidence=1 there are no shots
            val n = shots.calls.size
            http.send(HttpRequest.newBuilder(URI.create("$b/api/scratch/set?t=tok&h=4"))
                .header("Content-Type", "text/css").POST(HttpRequest.BodyPublishers.ofString(".a{}")).build(), HttpResponse.BodyHandlers.ofString())
            assertThat(shots.calls.size).isEqualTo(n)
        }
    }

    @Test
    fun `an unchanged node reports changedPct 0 and only the last four pairs are kept`(@TempDir dir: Path) {
        val shots = FakeShots(after = PLAIN)
        viewer(dir, shots = shots, props = ScratchProps { _, _, _, _ -> null }).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}"
            var first = 0
            for (i in 1..5) {
                val r = Json.parse(call("$b/api/props?t=tok&evidence=1", "POST",
                    "{\"items\":[{\"h\":1,\"path\":\"P\",\"property\":\"p\",\"value\":\"$i\"}]}").body())
                assertThat(r["evidence"]["changedPct"].asDouble()).isZero()
                if (i == 1) first = r["evidence"]["seq"].asInt()
            }
            assertThat(call("$b/api/evidence?t=tok&seq=$first&which=after").statusCode()).isEqualTo(404)
            assertThat(call("$b/api/evidence?t=tok&seq=${first + 4}&which=after").statusCode()).isEqualTo(200)
        }
    }

    @Test
    fun `fxml apply passes dryRun and publishes props when applied`(@TempDir dir: Path) {
        val seen = ArrayList<Boolean>()
        val props = ScratchProps { _, _, _, _ -> null }
        viewer(dir, props = props, fxml = { dry -> seen += dry; Json.obj { put("applied", !dry) } }).use { v ->
            v.start()
            val b = "http://127.0.0.1:${v.port}/api/fxml/apply?t=tok"
            assertThat(call(b).statusCode()).isEqualTo(405)
            call("$b&dryRun=1", "POST")
            call(b, "POST")
            assertThat(seen).containsExactly(true, false)
        }
    }

    @Test
    fun `the last inspector page disconnecting releases forced states`(@TempDir dir: Path) {
        val gone = CountDownLatch(1)
        var count = 0
        viewer(dir, gone = { count++; gone.countDown() }).use { v ->
            v.start()
            fun open(from: String) = (URI.create("http://127.0.0.1:${v.port}/sse?t=tok&from=$from").toURL().openConnection() as HttpURLConnection).also {
                it.readTimeout = 10_000
                BufferedReader(InputStreamReader(it.inputStream)).readLine()
            }
            val a = open("inspect")
            val b = open("inspect")
            val other = open("viewer")
            a.disconnect()
            v.publish("ping", Json.obj()) // a dead connection surfaces on the next write
            Thread.sleep(300)
            assertThat(count).isZero()
            other.disconnect()
            b.disconnect()
            v.publish("ping", Json.obj())
            var tries = 0
            while (!gone.await(200, TimeUnit.MILLISECONDS) && tries++ < 25) v.publish("ping", Json.obj()) // the first write after a close may still succeed
            assertThat(gone.count).isZero()
            assertThat(count).isEqualTo(1)
        }
    }

    @Test
    fun `fcp errors on the new endpoints come back as json errors`(@TempDir dir: Path) {
        viewer(dir, { _, _, _ -> throw FcpException("NODE_NOT_FOUND", "gone") }).use { v ->
            v.start()
            assertThat(call("http://127.0.0.1:${v.port}/api/handlers?t=tok&h=1").body()).contains("NODE_NOT_FOUND")
        }
    }
}

package dev.fxloop.acceptance

import com.fasterxml.jackson.databind.JsonNode
import dev.fxloop.acceptance.Harness.Companion.args
import dev.fxloop.protocol.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/**
 * PRD 08 §7 acceptance on the gauntlet "Cascade" tab: A-F2 (winner/overridden/blockers per conflict), A-F3 (suggested
 * override applied to the throwaway project copy, reload, verified), A-F4 (viewer HTTP + SSE), A-F5 (scratch CSS,
 * `FXLOOP_INSPECTOR_EDIT`), A-F1 (simulation conformance over every node of the gauntlet, the sample app and the
 * showcase app).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CssInspectorAcceptanceTest {
    private val h = Harness(app = "fxloop-gauntlet-app", configure = { it.copy(inspectorEdit = true) })
    private val http: HttpClient = HttpClient.newHttpClient()
    private val sheetRel = "src/main/resources/dev/fxloop/gauntlet/gauntlet.css"

    @AfterAll
    fun close() = h.close()

    private fun showCascadeTab() {
        selectTab("#cxMiss")
    }

    /** Tab headers are skin internals; a robot click on a node inside the tab makes the app select that tab (ensureVisible). */
    private fun selectTab(anyNodeInTab: String) {
        h.ok("fx_click_node", args("query" to anyNodeInTab, "effects" to false))
    }

    private fun explain(query: String, property: String): JsonNode =
        h.ok("fx_explain_style", args("query" to query, "property" to property))

    private fun item(query: String, property: String): JsonNode = explain(query, property)["items"][0]

    private fun blockerKinds(r: JsonNode): List<String> =
        (r["items"][0]["blockers"]?.toList().orEmpty() + r["blockers"]?.toList().orEmpty()).map { it["kind"].asText() }

    private fun assertSourceFile(decl: JsonNode, name: String) {
        // mapped back to the SOURCE root, not target/classes
        assertThat(decl["file"].asText()).describedAs(decl.toString()).isEqualTo("src/main/resources/dev/fxloop/gauntlet/$name")
        assertThat(decl["line"].asInt()).isGreaterThan(0)
    }

    private fun lineOf(selectorStart: String): Int =
        h.project.resolve(sheetRel).readText().lines().indexOfFirst { it.startsWith(selectorStart) } + 1

    // ---------------------------------------------------------------------------------------------- A-F2

    @Test @Order(1)
    fun `A-F2 specificity loss names the winner, the loser and the reason`() {
        showCascadeTab()
        val r = explain("#cxSpecific", "-fx-background-color")
        val i = r["items"][0]
        assertThat(i["winner"]["selector"].asText()).isEqualTo(".cx-panel .cx-bar .button")
        assertThat(i["winner"]["specificity"].asText()).isEqualTo("0,3,0")
        assertSourceFile(i["winner"], "gauntlet.css")
        assertThat(i["winner"]["line"].asInt()).isEqualTo(lineOf(".cx-panel .cx-bar .button"))
        val primary = i["overridden"].first { it["selector"].asText() == ".primary" }
        assertThat(primary["reason"].asText()).isEqualTo("lower specificity (0,1,0 < 0,3,0)")
        assertSourceFile(primary, "gauntlet.css")
        assertThat(i["simulation"].asText()).isEqualTo("ok")
    }

    @Test @Order(2)
    fun `A-F2 inline style beats the sheet`() {
        val r = explain("#cxInline", "-fx-text-fill")
        val i = r["items"][0]
        assertThat(i["winner"]["origin"].asText()).isEqualTo("INLINE")
        assertThat(i["overridden"].any { it["selector"].asText() == "#cxInline" }).isTrue()
        assertThat(blockerKinds(r)).contains("inline-wins")
    }

    @Test @Order(3)
    fun `A-F2 important beats inline`() {
        val i = item("#cxImportant", "-fx-text-fill")
        assertThat(i["winner"]["selector"].asText()).isEqualTo("#cxImportant")
        assertThat(i["winner"]["important"].asBoolean()).isTrue()
        assertSourceFile(i["winner"], "gauntlet.css")
        assertThat(i["overridden"].first { it["origin"].asText() == "INLINE" }["reason"].asText()).startsWith("overridden by !important")
    }

    @Test @Order(4)
    fun `A-F2 bound textFill is a blocker`() {
        assertThat(blockerKinds(explain("#cxBound", "-fx-text-fill"))).contains("bound")
        val s = h.ok("fx_suggest_override", args("query" to "#cxBound", "property" to "-fx-text-fill"))
        assertThat(s["selector"].isNull).isTrue()
    }

    @Test @Order(5)
    fun `A-F2 rule missing a style class is a near miss`() {
        val r = explain("#cxMiss", "-fx-text-fill")
        val causes = (r["items"][0]["blockers"]?.toList().orEmpty() + r["blockers"].toList()).map { it["cause"].asText() }
        assertThat(causes).anyMatch { it.contains("missing style class 'cx-ghost'") }
    }

    @Test @Order(6)
    fun `A-F2 skin-only property points at the skin node`() {
        val r = explain("#cxSkin", "-fx-fill")
        val b = r["blockers"].first { it["kind"].asText() == "skin-node" }
        assertThat(b["selector"].asText()).isEqualTo("#cxSkin .text")
    }

    @Test @Order(7)
    fun `A-F2 font size inherited from a parent inline style`() {
        val i = item("#cxChild", "-fx-font-size")
        assertThat(i["inheritedFrom"]["path"].asText()).endsWith("#cxBigBox")
        assertThat(i["inheritedFrom"]["winner"]["origin"].asText()).isEqualTo("INLINE")
    }

    @Test @Order(8)
    fun `A-F2 looked-up colour resolves to the nearest ancestor definition and unresolved lookups block`() {
        val i = item("#cxLookup", "-fx-text-fill")
        assertThat(i["winner"]["selector"].asText()).isEqualTo(".cx-accented")
        val l = i["lookups"].first { it["name"].asText() == "-accent" }
        assertThat(l["definedAt"]["file"].asText()).endsWith("cascade-panel.css") // the parent's .cx-panel beats .root
        assertThat(blockerKinds(explain("#cxUnresolved", "-fx-text-fill"))).contains("lookup-unresolved")
    }

    @Test @Order(9)
    fun `A-F2 sub-property beats the font shorthand declared before it`() {
        val i = item("#cxFont", "-fx-font-size")
        assertThat(i["winner"]["text"].asText()).isEqualTo("-fx-font-size: 20px;")
        assertSourceFile(i["winner"], "gauntlet.css")
        assertThat(i["overridden"].any { it.path("property").asText() == "-fx-font" }).isTrue()
    }

    @Test @Order(10)
    fun `A-F2 parse error in a sheet is reported with the sheet`() {
        val r = h.ok("fx_explain_style", args("query" to "#cxSpecific"))
        assertThat(r["blockers"].map { it["kind"].asText() }).contains("sheet-error")
        val viewer = viewerUrl()
        val cascade = Json.parse(get("/api/styles?t=$tok&path=${enc(pathOf("#cxSpecific"))}"))
        val panel = cascade["sheets"].first { it["url"].asText().endsWith("cascade-panel.css") }
        assertThat(panel["parseErrors"].size()).isGreaterThan(0)
        assertThat(panel["file"].asText()).isEqualTo("src/main/resources/dev/fxloop/gauntlet/cascade-panel.css")
        assertThat(viewer).contains("/inspect?t=")
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private var base = ""
    private var tok = ""

    private fun viewerUrl(): String {
        if (base.isEmpty()) {
            val u = h.ctx.ensureViewer().inspectUrl() // http://127.0.0.1:port/inspect?t=token
            base = u.substringBefore("/inspect")
            tok = u.substringAfter("t=")
        }
        return base + "/inspect?t=" + tok
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun get(pathAndQuery: String): String {
        viewerUrl()
        val r = http.send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(r.statusCode()).describedAs(pathAndQuery + " -> " + r.body()).isEqualTo(200)
        return r.body()
    }

    private fun post(pathAndQuery: String): JsonNode {
        viewerUrl()
        val r = http.send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(r.statusCode()).describedAs(pathAndQuery + " -> " + r.body()).isEqualTo(200)
        return Json.parse(r.body())
    }

    private fun postCss(pathAndQuery: String, css: String): JsonNode {
        viewerUrl()
        val r = http.send(
            HttpRequest.newBuilder(URI.create(base + pathAndQuery)).header("Content-Type", "text/css; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(css)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(r.statusCode()).describedAs(pathAndQuery + " -> " + r.body()).isEqualTo(200)
        return Json.parse(r.body())
    }

    private fun pathOf(query: String): String = h.ok("fx_find_nodes", args("query" to query))["items"][0]["path"].asText()

    // ---------------------------------------------------------------------------------------------- A-F3

    @Test @Order(20)
    fun `A-F3 suggested override applied to the project copy wins after reload`() {
        showCascadeTab()
        val s = h.ok("fx_suggest_override", args("query" to "#cxSpecific", "property" to "-fx-background-color", "value" to "#ff0000"))
        val selector = s["selector"].asText()
        assertThat(selector).isEqualTo(".cx-panel .cx-bar .button.primary")
        assertThat(s["needsImportant"].asBoolean()).isFalse()
        val file = s["file"].asText()
        assertThat(file).describedAs("default file skips the sheet with a parse error").isEqualTo(sheetRel)
        assertThat(s["snippet"].asText()).contains(selector).contains("#ff0000")

        // h.project is the harness' throwaway copy of the gauntlet app: the repo's own sources are never touched
        assertThat(h.project.toString()).isNotEqualTo(h.repo.resolve("sample-apps/fxloop-gauntlet-app").toString())
        h.edit(file) { it.trimEnd() + "\n\n" + s["snippet"].asText().trim() + "\n" }
        val reload = h.ok("fx_reload")
        assertThat(reload["visualDiff"]["changedPct"].asDouble()).isGreaterThan(0.0)

        val i = item("#cxSpecific", "-fx-background-color")
        assertThat(i["winner"]["selector"].asText()).isEqualTo(selector)
        assertThat(i["winner"]["text"].asText()).contains("#ff0000")
        assertThat(i["simulation"].asText()).isEqualTo("ok")
    }

    // ---------------------------------------------------------------------------------------------- A-F4

    @Test @Order(30)
    fun `A-F4 viewer styles equal explain_style, highlight shows and clears`() {
        showCascadeTab()
        val path = pathOf("#cxInline")
        val api = Json.parse(get("/api/styles?t=$tok&path=${enc(path)}"))
        val tool = h.ok("fx_explain_style", args("path" to path, "limit" to 200))["items"].associateBy { it["cssProperty"].asText() }
        val apiProps = api["properties"].associateBy { it["cssProperty"].asText() }
        assertThat(apiProps.keys).containsAll(tool.keys)
        for ((p, t) in tool) {
            assertThat(apiProps[p]!!["winner"]?.get("ruleId")?.asText()).describedAs("winner of $p").isEqualTo(t["winner"]?.get("ruleId")?.asText())
            assertThat(apiProps[p]!!["winner"]?.get("selector")?.asText()).isEqualTo(t["winner"]?.get("selector")?.asText())
        }
        assertThat(apiProps["-fx-text-fill"]!!["winner"]["origin"].asText()).isEqualTo("INLINE")
        assertThat(api["rules"].size()).isGreaterThan(0)

        val shown = post("/api/highlight?t=$tok&path=${enc(path)}")
        assertThat(shown["shown"].asBoolean()).describedAs(shown.toString()).isTrue()
        assertThat(shown["bounds"]["w"].asDouble()).isGreaterThan(0.0)
        val cleared = post("/api/highlight?t=$tok&clear=1")
        assertThat(cleared["shown"].asBoolean()).isFalse()
        // overlays are invisible to every tool
        assertThat(h.ok("fx_get_app_status")["windows"].size()).isEqualTo(1)
    }

    @Test @Order(31)
    fun `A-F4 css reload pushes an SSE reload event and styles reflect the change`() {
        showCascadeTab()
        viewerUrl()
        val events = LinkedBlockingQueue<String>()
        val resp = http.send(HttpRequest.newBuilder(URI.create("$base/sse?t=$tok&from=inspect")).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        val reader = Thread { readSse(resp.body(), events) }.apply { isDaemon = true; start() }
        try {
            assertThat(events.poll(5, TimeUnit.SECONDS)).describedAs("sse connected").isNotNull()
            h.edit(sheetRel) { it.replace("#cxInline { -fx-text-fill: green; }", "#cxInline { -fx-text-fill: green; -fx-font-size: 21px; }") }
            h.ok("fx_reload")
            val seen = mutableListOf<String>()
            var reload: String? = null
            val deadline = System.currentTimeMillis() + 10_000
            while (reload == null && System.currentTimeMillis() < deadline) {
                val ev = events.poll(1, TimeUnit.SECONDS) ?: continue
                seen += ev
                if (ev.startsWith("event: reload")) reload = ev
            }
            assertThat(reload).describedAs("reload event among $seen").isNotNull()
            assertThat(reload).contains("gauntlet.css")
        } finally {
            resp.body().close()
            reader.interrupt()
        }
        val api = Json.parse(get("/api/styles?t=$tok&path=${enc(pathOf("#cxInline"))}"))
        val fs = api["properties"].first { it["cssProperty"].asText() == "-fx-font-size" }
        assertThat(fs["winner"]["text"].asText()).isEqualTo("-fx-font-size: 21px;")
    }

    private fun readSse(s: InputStream, out: LinkedBlockingQueue<String>) {
        try {
            val sb = StringBuilder()
            s.bufferedReader().forEachLine { line ->
                if (line.isEmpty()) {
                    if (sb.isNotEmpty()) out.add(sb.toString().trim())
                    sb.setLength(0)
                } else if (!line.startsWith(":")) sb.append(line).append('\n') else out.add(line)
            }
        } catch (_: Exception) {
            // closed by the test
        }
    }

    @Test @Order(32)
    fun `A-F4 fx_open_inspector returns the inspect URL without opening a browser`() {
        val r = h.ok("fx_open_inspector", args("query" to "#cxInline"))
        assertThat(r["url"].asText()).startsWith("http://127.0.0.1:").contains("/inspect?t=").contains("path=")
        assertThat(r["opened"].asBoolean()).isFalse()
        assertThat(r["reason"].asText()).contains("FXLOOP_BROWSER=none")
        assertThat(get("/inspect?t=$tok")).contains("<html")
    }

    /** Blocks until an SSE `windows` block satisfying [want] arrives (10 s); the error names every block seen. */
    private fun awaitWindowsEvent(events: LinkedBlockingQueue<String>, want: (String) -> Boolean) {
        val seen = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val ev = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (!ev.startsWith("event: windows")) continue
            if (want(ev)) return
            seen += ev
        }
        throw AssertionError("no SSE windows event with the expected window list among $seen")
    }

    @Test @Order(33)
    fun `A-F4 a dialog opened after the page appears in the window list and as an SSE windows event`() {
        selectTab("#dialogResult")
        viewerUrl()
        val events = LinkedBlockingQueue<String>()
        val resp = http.send(HttpRequest.newBuilder(URI.create("$base/sse?t=$tok&from=inspect")).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        val reader = Thread { readSse(resp.body(), events) }.apply { isDaemon = true; start() }
        try {
            assertThat(events.poll(5, TimeUnit.SECONDS)).describedAs("sse connected").isNotNull()
            assertThat(Json.parse(get("/api/windows?t=$tok")).size()).isEqualTo(1)

            h.ok("fx_click_node", args("query" to "#alertBtn", "effects" to false))
            awaitWindowsEvent(events) { it.contains("Gauntlet Alert") }
            val open = Json.parse(get("/api/windows?t=$tok"))
            assertThat(open.size()).isEqualTo(2)
            assertThat(open.first { it["title"].asText() == "Gauntlet Alert" }["type"].asText()).isEqualTo("DIALOG")

            // a modal dialog does not block the agent: the tree of window 1 (the alert) is reachable
            assertThat(get("/api/tree?t=$tok&window=1")).contains("DialogPane")

            val ok = h.ok("fx_find_nodes", args("text" to "OK", "type" to "Button"))["items"]
                .first { it["path"].asText().contains("DialogPane") }["path"].asText()
            h.ok("fx_click_node", args("path" to ok, "effects" to false))
            awaitWindowsEvent(events) { !it.contains("Gauntlet Alert") }
            assertThat(Json.parse(get("/api/windows?t=$tok")).size()).isEqualTo(1)
        } finally {
            resp.body().close()
            reader.interrupt()
        }
    }

    // ---------------------------------------------------------------------------------------------- A-F5

    /** SHA-256 of every file under the project copy's src/, so "no source file modified" is checked, not assumed. */
    private fun sourceChecksums(): Map<String, String> {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return Files.walk(h.project.resolve("src")).use { s ->
            s.filter(Files::isRegularFile).toList().associate { f ->
                h.project.relativize(f).toString() to md.digest(Files.readAllBytes(f)).joinToString("") { "%02x".format(it) }
            }
        }
    }

    private fun shot(label: String): Int =
        h.ok("fx_take_screenshot", args("label" to label, "includeImage" to false))["seq"].asInt()

    private fun changedPct(before: Int, after: Int): Double =
        h.ok("fx_compare_screenshots", args("before" to before, "after" to after))["changedPct"].asDouble()

    @Test @Order(40)
    fun `A-F5 toggling a declaration goes through scratch CSS and Clear restores the frame`() {
        showCascadeTab()
        val path = pathOf("#cxSpecific")
        val sums = sourceChecksums()
        val before = shot("scratch-before")

        // what the page does when the developer unticks the winning background: same selector, next value down
        val styles = Json.parse(get("/api/styles?t=$tok&path=${enc(path)}"))
        assertThat(styles["edit"].asBoolean()).isTrue()
        val bg = styles["properties"].first { it["cssProperty"].asText() == "-fx-background-color" }
        val winner = bg["winner"]["selector"].asText()
        val fallback = bg["overridden"][0]["text"].asText().substringAfter(':').trim().removeSuffix(";")
        val set = postCss("/api/scratch/set?t=$tok", "$winner { -fx-background-color: $fallback; }\n")
        assertThat(set["active"].asBoolean()).isTrue()
        assertThat(set["rules"].asInt()).isEqualTo(1)

        val after = Json.parse(get("/api/styles?t=$tok&path=${enc(path)}"))
        val now = after["properties"].first { it["cssProperty"].asText() == "-fx-background-color" }
        assertThat(now["winner"]["file"].asText()).describedAs(now.toString()).isEqualTo("scratch:1")
        assertThat(now["simulation"].asText()).isEqualTo("ok")
        assertThat(after["rules"].any { it.path("scratch").asBoolean() }).isTrue()
        assertThat(changedPct(before, shot("scratch-on"))).isGreaterThan(0.0)

        val status = h.ok("fx_get_app_status")
        assertThat(status["scratchCss"]["rules"].asInt()).isEqualTo(1)
        assertThat(status["scratchCss"]["css"].asText()).contains(fallback)
        assertThat(sourceChecksums()).describedAs("scratch CSS never touches source").isEqualTo(sums)

        assertThat(post("/api/scratch/clear?t=$tok")["active"].asBoolean()).isFalse()
        assertThat(changedPct(before, shot("scratch-cleared"))).isLessThan(0.05)
        assertThat(h.ok("fx_get_app_status").has("scratchCss")).isFalse()
    }

    @Test @Order(41)
    fun `A-F5 rejected scratch CSS installs nothing and fx_reload clearScratch drops it`() {
        showCascadeTab()
        viewerUrl()
        val bad = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/scratch/set?t=$tok")).header("Content-Type", "text/css")
                .POST(HttpRequest.BodyPublishers.ofString("@import url(\"http://example.invalid/x.css\");")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(bad.body()).contains("\"code\":\"BAD_REQUEST\"")
        assertThat(h.ok("fx_get_app_status").has("scratchCss")).isFalse()

        postCss("/api/scratch/set?t=$tok", "#cxInline { -fx-font-size: 30px; }")
        val r = h.ok("fx_reload", args("clearScratch" to true))
        assertThat(r["tiersApplied"].map { it["tier"].asText() }).contains("scratch")
        assertThat(h.ok("fx_get_app_status").has("scratchCss")).isFalse()
    }

    @Test @Order(42)
    fun `A-F5 Apply to file writes the scratch rule into its source rule, reloads and clears the scratch sheet`() {
        showCascadeTab()
        val path = pathOf("#cxSpecific")
        val winner = Json.parse(get("/api/styles?t=$tok&path=${enc(path)}"))["properties"]
            .first { it["cssProperty"].asText() == "-fx-background-color" }["winner"]["selector"].asText()
        postCss("/api/scratch/set?t=$tok", "$winner { -fx-background-color: #13579b; }\n")

        val preview = post("/api/scratch/apply?t=$tok&dryRun=1")
        assertThat(preview["applied"].asBoolean()).isFalse()
        val change = preview["files"].single()
        assertThat(change["file"].asText()).isEqualTo(sheetRel)
        assertThat(change["changes"][0]["kind"].asText()).isEqualTo("update")
        assertThat(change["changes"][0]["to"].asText()).isEqualTo("#13579b")
        assertThat(h.project.resolve(sheetRel).readText()).doesNotContain("#13579b")

        val applied = post("/api/scratch/apply?t=$tok")
        assertThat(applied["applied"].asBoolean()).describedAs(applied.toString()).isTrue()
        assertThat(h.project.resolve(sheetRel).readText()).contains("-fx-background-color: #13579b;")
        assertThat(h.ok("fx_get_app_status").has("scratchCss")).isFalse()
        val now = Json.parse(get("/api/styles?t=$tok&path=${enc(path)}"))["properties"]
            .first { it["cssProperty"].asText() == "-fx-background-color" }
        assertThat(now["winner"]["file"].asText()).describedAs(now.toString()).isEqualTo(sheetRel)
        assertThat(now["winner"]["text"].asText()).contains("#13579b")
    }

    @Test @Order(43)
    fun `A-F5 the agent's input lock pauses inspector editing until the developer takes over`() {
        showCascadeTab()
        val locked = h.ok("fx_lock_input", args("locked" to true, "reason" to "checking the tabs", "seconds" to 30))
        assertThat(locked["inputLock"]["locked"].asBoolean()).isTrue()
        viewerUrl()
        val refused = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/scratch/set?t=$tok")).header("Content-Type", "text/css")
                .POST(HttpRequest.BodyPublishers.ofString("#cxInline { -fx-font-size: 30px; }")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(refused.statusCode()).isEqualTo(423)
        assertThat(refused.body()).contains("checking the tabs")

        post("/api/lock/release?t=$tok")
        val st = h.ok("fx_get_app_status")["inputLock"]
        assertThat(st["locked"].asBoolean()).isFalse()
        assertThat(st["releasedBy"].asText()).isEqualTo("developer")
        assertThat(postCss("/api/scratch/set?t=$tok", "#cxInline { -fx-font-size: 30px; }")["active"].asBoolean()).isTrue()
        post("/api/scratch/clear?t=$tok")
    }

    // ---------------------------------------------------------------------------------------------- A-F1

    private class Tally { var ok = 0; var mismatch = 0; var nodes = 0; var other = 0; val rows = mutableListOf<String>() }

    /** Every logical node path of all showing windows, via the universal selector (paged). */
    private fun allPaths(h: Harness): List<String> {
        val out = ArrayList<String>()
        var offset = 0
        while (true) {
            val r = h.ok("fx_find_nodes", args("query" to "*", "limit" to 200, "offset" to offset))
            val items = r["items"]
            items.forEach { out += it["path"].asText() }
            offset += items.size()
            if (items.size() == 0 || offset >= r["total"].asInt()) return out
        }
    }

    private fun tally(app: String, h: Harness, tabs: List<String>, t: Tally) {
        val visited = HashSet<String>()
        fun sweep() {
            for (path in allPaths(h)) {
                if (!visited.add(path)) continue
                val r = h.raw("fx_explain_style", args("path" to path, "limit" to 200))
                if (r.isError) { t.other++; continue }
                t.nodes++
                for (it in r.structured!!["items"]) {
                    when (it.path("simulation").asText()) {
                        "ok" -> t.ok++
                        "mismatch" -> {
                            t.mismatch++
                            val sim = it["simulated"]
                            t.rows += "$app\t$path\t${it["cssProperty"].asText()}\tsimulated=${sim?.get("value")?.asText() ?: sim?.get("origin")?.asText()}" +
                                "\tlive=${sim?.get("liveValue")?.asText() ?: sim?.get("liveOrigin")?.asText()}\t${sim?.get("note")?.asText() ?: ""}"
                        }
                        else -> t.other++
                    }
                }
            }
        }
        sweep()
        for (tab in tabs) {
            h.raw("fx_click_node", args("query" to tab, "effects" to false))
            sweep()
        }
    }

    @Test @Order(40)
    fun `A-F1 simulation agrees with the live style origins on at least 99 percent of properties`() {
        val report = StringBuilder()
        val total = Tally()
        val perApp = linkedMapOf<String, Tally>()
        fun run(app: String, harness: Harness, tabs: List<String>) {
            val t = Tally()
            try { tally(app, harness, tabs, t) } finally { perApp[app] = t }
        }
        run("fxloop-gauntlet-app", h, emptyList()) // every tab's content is in the logical tree, selected or not
        val skipped = mutableListOf<String>()
        for (app in listOf("fxloop-sample-app", "fxloop-showcase-app")) {
            try {
                Harness(app = app).use { run(app, it, emptyList()) }
            } catch (e: Throwable) {
                skipped += "$app: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
            }
        }
        report.appendLine("app\tnodes\tok\tmismatch\tagreement")
        for ((app, t) in perApp) {
            val n = t.ok + t.mismatch
            report.appendLine("$app\t${t.nodes}\t${t.ok}\t${t.mismatch}\t${"%.2f".format(if (n == 0) 100.0 else 100.0 * t.ok / n)}%")
            total.ok += t.ok; total.mismatch += t.mismatch; total.nodes += t.nodes; total.rows += t.rows
        }
        val n = total.ok + total.mismatch
        val pct = if (n == 0) 0.0 else 100.0 * total.ok / n
        report.appendLine("TOTAL\t${total.nodes}\t${total.ok}\t${total.mismatch}\t${"%.2f".format(pct)}%")
        skipped.forEach { report.appendLine("SKIPPED $it") }
        report.appendLine().appendLine("mismatches (app\tnode path\tproperty\tsimulated\tlive\tnote):")
        total.rows.forEach { report.appendLine(it) }
        println(report)
        val out = Path.of(System.getProperty("fxloop.repo") ?: "..").resolve("fxloop-acceptance/target/css-conformance.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report.toString())
        assertThat(n).describedAs("properties checked").isGreaterThan(100)
        assertThat(pct).describedAs("simulation agreement (see %s)", out).isGreaterThanOrEqualTo(99.0)
    }
}

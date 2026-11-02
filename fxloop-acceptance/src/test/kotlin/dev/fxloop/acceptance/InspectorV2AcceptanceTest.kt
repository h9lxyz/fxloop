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
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.readText

/**
 * PRD 09 §12 acceptance on the gauntlet "Cascade" and "Layout" tabs: forced states (A-G1), the Layout tab's data and
 * FXML write-back (A-G2, A-G3), looked-up chains (A-G4), `fx_lint_css` (A-G5), tree search with multi-highlight
 * (A-G6), before/after evidence (A-G7) and whole-state scratch re-sends that undo relies on (A-G8).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class InspectorV2AcceptanceTest {
    private val h = Harness(app = "fxloop-gauntlet-app", configure = { it.copy(inspectorEdit = true) })
    private val http: HttpClient = HttpClient.newHttpClient()
    private val layoutRel = "src/main/resources/dev/fxloop/gauntlet/layout.fxml"

    @AfterAll
    fun close() = h.close()

    private var base = ""
    private var tok = ""

    private fun viewer() {
        if (base.isNotEmpty()) return
        val u = h.ctx.ensureViewer().inspectUrl()
        base = u.substringBefore("/inspect")
        tok = u.substringAfter("t=")
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun send(req: HttpRequest.Builder, what: String): HttpResponse<ByteArray> {
        val r = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray())
        assertThat(r.statusCode()).describedAs(what + " -> " + String(r.body())).isEqualTo(200)
        return r
    }

    private fun get(q: String): JsonNode {
        viewer()
        return Json.parse(String(send(HttpRequest.newBuilder(URI.create("$base$q&t=$tok")).GET(), q).body()))
    }

    private fun getBytes(q: String): HttpResponse<ByteArray> {
        viewer()
        return send(HttpRequest.newBuilder(URI.create("$base$q&t=$tok")).GET(), q)
    }

    private fun post(q: String, json: JsonNode? = null, css: String? = null): JsonNode {
        viewer()
        val b = HttpRequest.newBuilder(URI.create("$base$q&t=$tok"))
        when {
            json != null -> b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(json)))
            css != null -> b.header("Content-Type", "text/css; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(css))
            else -> b.POST(HttpRequest.BodyPublishers.noBody())
        }
        return Json.parse(String(send(b, q).body()))
    }

    private fun node(query: String): JsonNode = h.ok("fx_find_nodes", args("query" to query))["items"][0]

    private fun showTab(anyNodeInTab: String) {
        h.ok("fx_click_node", args("query" to anyNodeInTab, "effects" to false))
    }

    private fun shot(label: String): Int = h.ok("fx_take_screenshot", args("label" to label, "includeImage" to false))["seq"].asInt()

    private fun changedPct(before: Int, after: Int): Double =
        h.ok("fx_compare_screenshots", args("before" to before, "after" to after))["changedPct"].asDouble()

    private fun prop(styles: JsonNode, name: String): JsonNode = styles["properties"].first { it["cssProperty"].asText() == name }

    private fun lineOf(rel: String, needle: String): Int = h.project.resolve(rel).readText().lines().indexOfFirst { it.contains(needle) } + 1

    // ---------------------------------------------------------------------------------------------- A-G1

    @Test @Order(1)
    fun `A-G1 forcing hover makes the hover rule win, survives real mouse moves and clears back to rest`() {
        showTab("#cxHover")
        val n = node("#cxHover")
        val p = "?path=${enc(n["path"].asText())}"
        h.ok("fx_click_node", args("query" to "#cxMiss", "effects" to false)) // mouse away from the button
        val rest = shot("g1-rest")

        val forced = post("/api/states$p&states=hover")
        assertThat(forced["forced"].map { it.asText() }).contains("hover")
        val styles = get("/api/styles$p")
        assertThat(styles["pseudoClasses"].any { it["name"].asText() == "hover" && it["forced"].asBoolean() }).isTrue()
        val bg = prop(styles, "-fx-background-color")
        assertThat(bg["winner"]["selector"].asText()).describedAs(bg.toString()).isEqualTo(".cx-hoverable:hover")

        val hovered = h.ok("fx_take_screenshot", args("label" to "g1-hover", "includeImage" to false))
        assertThat(hovered["forcedStates"]["forced"].map { it.asText() }).contains("hover")
        assertThat(changedPct(rest, hovered["seq"].asInt())).isGreaterThan(0.0)

        // the real pointer moving over and off the node must not end the forced state
        h.ok("fx_click_node", args("query" to "#cxHover", "effects" to false))
        h.ok("fx_click_node", args("query" to "#cxMiss", "effects" to false))
        assertThat(get("/api/styles$p")["pseudoClasses"].any { it["name"].asText() == "hover" }).isTrue()

        post("/api/states$p&states=")
        assertThat(h.ok("fx_get_app_status").has("forcedStates")).isFalse()
        assertThat(changedPct(rest, shot("g1-cleared"))).isLessThan(0.05)
    }

    @Test @Order(2)
    fun `A-G1 fx_explain_style simulates a state without touching the node`() {
        showTab("#cxHover")
        val r = h.ok("fx_explain_style", args("query" to "#cxHover", "property" to "-fx-background-color", "states" to listOf("hover")))
        assertThat(r["items"][0]["winner"]["selector"].asText()).isEqualTo(".cx-hoverable:hover")
        assertThat(r["items"][0]["simulation"].asText()).isEqualTo("assumed")
        val live = h.ok("fx_explain_style", args("query" to "#cxHover", "property" to "-fx-background-color"))
        assertThat(live["items"][0]["winner"]["selector"].asText()).isEqualTo(".cx-hoverable")
    }

    // ---------------------------------------------------------------------------------------------- A-G2

    @Test @Order(10)
    fun `A-G2 the Layout tab data equals fx_explain_layout and FXML-set values link to their line`() {
        showTab("#lySave")
        for (id in listOf("lyRow", "lySave", "lyCancel", "lyGrid", "lySpan", "lyCell", "lyAnchor", "lyAnchored", "lyUnmanaged", "lyTranslated")) {
            val path = node("#$id")["path"].asText()
            val tool = h.ok("fx_explain_layout", args("path" to path))
            val page = get("/api/layout?path=${enc(path)}")
            for (k in listOf("layoutBounds", "sizes", "constraints", "hints", "sizes2", "editable")) {
                assertThat(page[k]).describedAs("$id $k").isEqualTo(tool[k])
            }
        }
        val save = h.ok("fx_explain_layout", args("query" to "#lySave"))
        val pref = save["sizes2"]["prefWidth"]
        assertThat(pref["value"].asDouble()).isEqualTo(60.0)
        assertThat(pref["source"]["kind"].asText()).isEqualTo("fxml")
        assertThat(pref["source"]["file"].asText()).isEqualTo(layoutRel)
        assertThat(pref["source"]["line"].asInt()).isEqualTo(lineOf(layoutRel, "fx:id=\"lySave\""))
        assertThat(save["hintCodes"].map { it["code"].asText() }).contains("below-pref")
        assertThat(save["parentKind"].asText()).isEqualTo("HBox")

        val span = h.ok("fx_explain_layout", args("query" to "#lySpan"))
        val colSpan = span["editable"].first { it["key"].asText() == "GridPane.columnSpan" }
        assertThat(colSpan["value"].asText()).isEqualTo("2")
        assertThat(colSpan["source"]["kind"].asText()).isEqualTo("fxml")
        assertThat(h.ok("fx_explain_layout", args("query" to "#lyUnmanaged"))["hintCodes"].map { it["code"].asText() }).contains("unmanaged")
        assertThat(h.ok("fx_explain_layout", args("query" to "#lyTranslated"))["hintCodes"].map { it["code"].asText() }).contains("translated")
    }

    // ---------------------------------------------------------------------------------------------- A-G3

    private fun checksums(): Map<String, String> {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return Files.walk(h.project.resolve("src")).use { s ->
            s.filter(Files::isRegularFile).toList().associate { f ->
                h.project.relativize(f).toString() to md.digest(Files.readAllBytes(f)).joinToString("") { "%02x".format(it) }
            }
        }
    }

    @Test @Order(11)
    fun `A-G3 a layout edit grows the node live, applies to the FXML and empties the scratch property set`() {
        showTab("#lySave")
        val save = node("#lySave")
        val sums = checksums()
        val wBefore = h.ok("fx_explain_layout", args("query" to "#lySave"))["layoutBounds"]["w"].asDouble()
        val items = Json.array().add(Json.obj {
            put("h", save["h"].asInt()); put("path", save["path"].asText()); put("property", "HBox.hgrow"); put("value", "ALWAYS")
        })
        val set = post("/api/props?", Json.obj { set<JsonNode>("items", items) })
        assertThat(set["items"].size()).isEqualTo(1)
        assertThat(set["items"][0]["before"].isNull || set["items"][0]["before"].asText().isEmpty()).isTrue()
        assertThat(h.ok("fx_get_app_status")["scratchProps"].size()).isEqualTo(1)
        // prefWidth=60 caps a growing Button at 60: reset it too, as the developer would in §2
        val items2 = Json.array().add(items[0]).add(Json.obj {
            put("h", save["h"].asInt()); put("path", save["path"].asText()); put("property", "prefWidth"); put("value", "-1")
        })
        post("/api/props?", Json.obj { set<JsonNode>("items", items2) })
        val wAfter = h.ok("fx_explain_layout", args("query" to "#lySave"))["layoutBounds"]["w"].asDouble()
        assertThat(wAfter).isGreaterThan(wBefore)

        val plan = post("/api/fxml/apply?dryRun=1")
        assertThat(plan["files"].single()["file"].asText()).isEqualTo(layoutRel)
        assertThat(h.project.resolve(layoutRel).readText()).doesNotContain("HBox.hgrow")

        val applied = post("/api/fxml/apply?")
        assertThat(applied["applied"].asBoolean()).describedAs(applied.toString()).isTrue()
        val text = h.project.resolve(layoutRel).readText()
        assertThat(text).contains("HBox.hgrow=\"ALWAYS\"").contains("prefWidth=\"-1\"")
        assertThat(applied["reload"]["tiersApplied"].map { it["tier"].asText() }).contains("fxml")
        assertThat(get("/api/props?")["items"].size()).isEqualTo(0)
        assertThat(h.ok("fx_get_app_status").has("scratchProps")).isFalse()
        val changed = checksums().filter { (k, v) -> sums[k] != v }.keys
        assertThat(changed).containsExactly(layoutRel)
        assertThat(h.ok("fx_explain_layout", args("query" to "#lySave"))["constraints"]["hgrow"].asText()).isEqualTo("ALWAYS")
    }

    // ---------------------------------------------------------------------------------------------- A-G4

    @Test @Order(20)
    fun `A-G4 the derived color's lookup chain has three steps and ends in the live value`() {
        showTab("#cxDerived")
        val path = node("#cxDerived")["path"].asText()
        val bg = prop(get("/api/styles?path=${enc(path)}"), "-fx-background-color")
        val chain = bg["lookups"]
        assertThat(chain.map { it["name"].asText() }).describedAs(bg.toString()).containsExactly("-gx-hover", "-gx-mid", "-gx-base")
        assertThat(chain.last()["definedAt"]["selector"].asText()).isEqualTo(".root")
        val live = bg["value"].asText()
        val color = bg["lookupValue"].asText().removePrefix("#").lowercase().take(6)
        assertThat(live.lowercase()).describedAs("live background %s vs chain %s", live, color).contains(color)
    }

    // ---------------------------------------------------------------------------------------------- A-G5

    @Test @Order(30)
    fun `A-G5 fx_lint_css reports the dead rules, the misspelt selectors and the parse error`() {
        val r = h.ok("fx_lint_css")
        val unused = r["unused"].map { it["selector"].asText() }
        assertThat(unused).describedAs(r.toString()).containsExactlyInAnyOrder(".cx-bar > .label", ".cx-derive-box .button")
        r["unused"].forEach { assertThat(it["line"].asInt()).isEqualTo(lineOf("src/main/resources/dev/fxloop/gauntlet/gauntlet.css", it["selector"].asText() + " {")) }
        val unknown = r["unknownSelectors"].map { it["selector"].asText() }
        // .button.cx-ghost is PRD 08's deliberate near miss: no node carries cx-ghost either
        assertThat(unknown).containsExactlyInAnyOrder(".cx-pannel .button", ".button.cx-ghost")
        val errors = r["sheets"].flatMap { s -> s["errors"].map { s["file"].asText() to it } }
        assertThat(errors.map { it.first }).containsExactly("src/main/resources/dev/fxloop/gauntlet/cascade-panel.css")
        val sheets = get("/api/sheets?window=0")
        assertThat(sheets["sheets"].map { it["file"].asText() }).contains("src/main/resources/dev/fxloop/gauntlet/gauntlet.css")
    }

    // ---------------------------------------------------------------------------------------------- A-G6

    @Test @Order(40)
    fun `A-G6 tree search finds as many Buttons as fx_find_nodes and highlights them all`() {
        val tool = h.ok("fx_find_nodes", args("type" to "Button", "limit" to 200))["total"].asInt()
        val found = get("/api/find?q=${enc("type:Button")}")
        assertThat(found["total"].asInt()).isEqualTo(tool)
        val handles = found["items"].take(50).joinToString(",") { it["h"].asText() }
        val hl = post("/api/highlight?handles=$handles&labels=1")
        assertThat(hl["shown"].asInt() + hl.path("notShown").asInt()).isEqualTo(minOf(tool, 50))
        post("/api/highlight?clear=1")
        assertThat(get("/api/find?q=${enc("#cxHover")}")["total"].asInt()).isEqualTo(1)
    }

    // ---------------------------------------------------------------------------------------------- A-G7

    @Test @Order(50)
    fun `A-G7 a scratch edit yields a before-after pair, a selector that matches nothing yields 0`() {
        showTab("#cxHover")
        val n = node("#cxHover")
        val q = "?h=${n["h"].asInt()}&path=${enc(n["path"].asText())}&evidence=1"
        val hit = post("/api/scratch/set$q", css = "#cxHover { -fx-background-color: #00ff00; }\n")
        assertThat(hit["evidence"]["changedPct"].asDouble()).describedAs(hit.toString()).isGreaterThan(0.0)
        val seq = hit["evidence"]["seq"].asInt()
        assertThat(getBytes("/api/evidence?seq=$seq&which=before").headers().firstValue("Content-Type").get()).startsWith("image/png")
        assertThat(getBytes("/api/shot?h=${n["h"].asInt()}&w=320").headers().firstValue("Content-Type").get()).startsWith("image/png")
        val miss = post("/api/scratch/set$q", css = "#cxHover { -fx-background-color: #00ff00; }\n#nothingHere { -fx-padding: 40; }\n")
        assertThat(miss["evidence"]["changedPct"].asDouble()).isEqualTo(0.0)
        post("/api/scratch/clear?")
    }

    // ---------------------------------------------------------------------------------------------- A-G8

    @Test @Order(60)
    fun `A-G8 three edits, two undos and a redo leave the scratch CSS of the second edit`() {
        val states = listOf(
            "#cxHover { -fx-padding: 4; }\n",
            "#cxHover { -fx-padding: 8; }\n",
            "#cxHover { -fx-padding: 12; }\n",
        )
        // the page's history re-sends whole states: 3 edits, undo → 2, undo → 1, redo → 2
        for (css in states + states[1] + states[0] + states[1]) post("/api/scratch/set?", css = css)
        assertThat(h.ok("fx_get_app_status")["scratchCss"]["css"].asText()).contains("-fx-padding: 8")
        post("/api/scratch/clear?")
    }
}

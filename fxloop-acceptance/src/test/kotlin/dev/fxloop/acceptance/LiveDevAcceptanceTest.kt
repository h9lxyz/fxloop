package dev.fxloop.acceptance

import com.fasterxml.jackson.databind.JsonNode
import dev.fxloop.acceptance.Harness.Companion.args
import dev.fxloop.protocol.Json
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.see.Viewer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * PRD 07 §10 acceptance workflows A-E1..A-E6 (live dev loop: remount, state carry-over, human journal, etiquette, dev bar,
 * control modes, headless status, pick mode). Every workflow owns a throwaway app copy so the tests are order independent.
 */
class LiveDevAcceptanceTest {
    private val gauntlet = "fxloop-gauntlet-app"
    private val gauntletSrc = "src/main/java/dev/fxloop/gauntlet/GauntletApp.java"

    private fun tier(r: JsonNode, name: String) = r["tiersApplied"].firstOrNull { it["tier"].asText() == name }

    private fun pid(h: Harness): Long = h.ok("fx_get_app_status")["pid"].asLong()

    private fun humanKeys(h: Harness, query: String, code: String, vararg mods: String) =
        h.ok("fx_test_human_input", args("kind" to "keys", "query" to query,
            "keys" to listOf(mapOf("code" to code, "modifiers" to mods.toList()))))

    /** A TabPane's scene-graph `value` is its selected tab's title. */
    private fun onMenusTab(h: Harness): Boolean =
        h.ok("fx_get_scene_graph", args("query" to "#tabs", "maxDepth" to 0))["root"].path("value").asText() == "Menus"

    private fun await(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(150)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Human navigation to the gauntlet Menus tab: arrow key on the TabPane (tab headers are skin internals, not addressable). */
    private fun humanToMenusTab(h: Harness) {
        assertThat(onMenusTab(h)).describedAs("Menus tab selected before navigation").isFalse()
        humanKeys(h, "#tabs", "RIGHT")
        await("Menus tab selected") { onMenusTab(h) }
    }

    @Test
    fun `A-E1 remount - a view-builder edit is applied in place without a restart`() {
        Harness(app = gauntlet).use { h ->
            val pid = pid(h)
            assertThat(h.prop("#dialogResult", "text").asText()).isEqualTo("none")
            h.edit(gauntletSrc) { it.replace("label(\"dialogResult\", \"none\")", "label(\"dialogResult\", \"remounted!\")") }
            val r = h.ok("fx_reload")
            assertThat(tier(r, "hotswap")!!["details"]["ok"].asBoolean()).isTrue()
            val remount = tier(r, "remount")
            assertThat(remount).describedAs(r.toString()).isNotNull()
            assertThat(remount!!["details"]["remounted"].map { it.asText() }).contains("dialogs")
            assertThat(r.has("restarted")).isFalse()
            assertThat(tier(r, "restart")).isNull()
            assertThat(h.prop("#dialogResult", "text").asText()).isEqualTo("remounted!")
            assertThat(h.ok("fx_find_nodes", args("text" to "remounted!"))["total"].asInt()).isGreaterThan(0)
            assertThat(h.raw("fx_take_screenshot").images).hasSize(1)
            assertThat(pid(h)).isEqualTo(pid)
        }
    }

    @Test
    fun `A-E2 state carry-over - text, selection and focus survive an FXML swap`() {
        // the controller seeds itself in initialize(), so a swapped-in controller has the rows the selection refers to
        Harness(configure = { it.copy(jvmArgs = listOf("-Dfxloop.sample.seed=true")) }).use { h ->
            h.ok("fx_get_app_status")
            val row3 = h.ok("fx_find_nodes", args("text" to "Grace Hopper"))["items"].first { it["type"].asText() == "TableRow" }["path"].asText()
            assertThat(row3).endsWith("TableRow[2]")
            h.ok("fx_click_node", args("path" to row3))
            assertThat(h.ok("fx_get_property", args("path" to row3, "name" to "selected"))["value"].asBoolean()).isTrue()
            h.ok("fx_click_node", args("query" to "#nameField"))
            h.ok("fx_set_text", args("query" to "#nameField", "text" to "Ada search"))
            h.edit(Harness.FXML) { it.replace("promptText=\"Name\"", "promptText=\"Name or alias\"") }
            val r = h.ok("fx_reload")
            assertThat(tier(r, "fxml")).describedAs(r.toString()).isNotNull()
            assertThat(tier(r, "restart")).isNull()
            assertThat(h.prop("#nameField", "promptText").asText()).isEqualTo("Name or alias")
            assertThat(h.prop("#nameField", "text").asText()).isEqualTo("Ada search")
            assertThat(h.ok("fx_get_property", args("path" to row3, "name" to "selected"))["value"].asBoolean())
                .describedAs("row 3 selected again").isTrue()
            assertThat(r["stateCarried"]["restored"].asInt()).describedAs(r["stateCarried"].toString()).isGreaterThanOrEqualTo(3)
        }
    }

    @Test
    fun `A-E3 human journal - tab navigation and a redacted password edit survive a structural restart with replay`() {
        Harness(app = gauntlet).use { h ->
            val pid = pid(h)
            humanToMenusTab(h)
            h.ok("fx_test_human_input", args("kind" to "click", "query" to "#menuFile"))
            await("menu popup") { h.ok("fx_get_app_status")["windows"].any { it["type"].asText() == "POPUP" } }
            h.ok("fx_test_human_input", args("kind" to "text", "query" to "#pwField", "text" to "s3cret-pw"))
            await("human journal entries") { h.ok("fx_get_journal")["entries"].size() >= 2 }
            val entries = h.ok("fx_get_journal")["entries"]
            assertThat(entries.all { it["source"].asText() == "human" }).describedAs(entries.toString()).isTrue()
            assertThat(entries.any { it["tool"].asText() == "fx_click_node" && it["args"]["path"].asText().endsWith("MenuBarButton#menuFile") }).isTrue()
            val pw = entries.first { it["target"]["fxId"]?.asText() == "pwField" }
            assertThat(pw["redacted"].asBoolean()).isTrue()
            assertThat(pw["args"].has("text")).isFalse()
            assertThat(entries.toString()).doesNotContain("s3cret-pw")

            // structural change: a new field cannot be hot-swapped
            h.edit(gauntletSrc) { it.replace("private Stage primary;", "private Stage primary;\n    private int e3Marker;") }
            val r = h.ok("fx_reload", args("restartWhenHumanActive" to true))
            assertThat(r["restarted"].asBoolean()).describedAs(r.toString()).isTrue()
            assertThat(r.has("restartDeferred")).isFalse()
            assertThat(r["replay"]["replayed"].asInt()).describedAs(r["replay"].toString()).isGreaterThanOrEqualTo(1)
            assertThat(r["replay"].has("failedAt")).isFalse()
            assertThat(pid(h)).isNotEqualTo(pid)
            assertThat(onMenusTab(h)).describedAs("app is back on the Menus tab").isTrue()
            assertThat(h.prop("#pwField", "text").asText()).isEmpty() // the secret was never recorded, so never replayed
        }
    }

    @Test
    fun `A-E4 etiquette - a restart is deferred while the developer is active and the hotkey performs it with replay`() {
        Harness(app = gauntlet, configure = { it.copy(control = "agent", humanIdleMs = 8_000) }).use { h ->
            val pid = pid(h)
            humanToMenusTab(h)
            h.ok("fx_test_human_input", args("kind" to "click", "query" to "#menuFile"))
            await("human click journaled") { h.ok("fx_get_journal")["entries"].size() >= 1 }
            h.edit(gauntletSrc) { it.replace("private Stage primary;", "private Stage primary;\n    private int e4Marker;") }
            val r = h.ok("fx_reload")
            assertThat(r["restartDeferred"].asText()).describedAs(r.toString()).isEqualTo("humanActive")
            assertThat(r.has("restarted")).isFalse()
            assertThat(pid(h)).isEqualTo(pid)
            val needed = h.ok("fx_get_app_status")["actionNeeded"]
            assertThat(needed.any { it["kind"].asText() == "restart" }).describedAs(needed.toString()).isTrue()

            // the developer presses the restart hotkey (injected as human keys); the restart may kill the app before it answers
            val keys = h.raw("fx_test_human_input", args("kind" to "keys", "query" to "#tabs",
                "keys" to listOf(mapOf("code" to "F5", "modifiers" to listOf("CONTROL", "SHIFT")))))
            if (keys.isError) assertThat(keys.structured?.path("code")?.asText()).describedAs(keys.text).isEqualTo("DISCONNECTED")
            var newPid = 0L
            await("restart with a new pid", 60_000) {
                val st = h.ctx.session.status() // not fx_get_app_status: a NEW session state would auto-start a second app
                newPid = st.path("pid").asLong()
                st.path("state").asText() == "running" && newPid != 0L && newPid != pid && st.path("fcpConnected").asBoolean()
            }
            assertThat(newPid).isNotEqualTo(pid)
            await("replay back on Menus tab", 40_000) { onMenusTab(h) }
            await("restart entry cleared", 10_000) { h.ok("fx_get_app_status")["actionNeeded"].none { it["kind"].asText() == "restart" } }
        }
    }

    @Test
    fun `A-E5a bar invisibility - the dev bar leaves no trace in what the agent sees and does not move the layout`() {
        fun layout(h: Harness): Map<String, String> {
            val m = sortedMapOf<String, String>()
            for (q in listOf("#alertBtn", "#dialogBtn", "#dialogResult", "#tabs")) {
                val n = h.ok("fx_find_nodes", args("query" to q))["items"][0]
                m[q] = n["boundsScreen"].toString()
            }
            val w = h.ok("fx_get_app_status")["windows"][0]
            m["window"] = listOf("x", "y", "w", "h", "focused").joinToString { w[it].toString() }
            return m
        }

        val off = Harness(app = gauntlet, configure = { it.copy(bar = "off") }).use { h ->
            h.ok("fx_get_app_status")
            assertThat(h.ok("fx_test_get_bar_state")["rendered"].asInt()).isZero()
            layout(h)
        }
        Harness(app = gauntlet, configure = { it.copy(bar = "on") }).use { h ->
            h.ok("fx_get_app_status")
            await("bar rendered") { h.ok("fx_test_get_bar_state")["rendered"].asInt() >= 1 }
            // On Linux the bar is a stage: it takes focus as it shows and hands it straight back. A focused button's bounds
            // include its focus ring, so for a moment after showing they differ from the bar-off run without moving the layout.
            runCatching { await("the focus ring back after the bar's focus hand-back", 5_000) { layout(h) == off } }
            assertThat(layout(h)).describedAs("layout of the freshly launched app, bar on vs off").isEqualTo(off)
            val bar = h.ok("fx_test_get_bar_state")
            assertThat(bar["rendered"].asInt()).isGreaterThanOrEqualTo(1)
            assertThat(bar["texts"]["session"].asText()).isNotBlank()

            val traces = Regex("devbar|fxloop-|dev bar|Agent mode|You can interact|Agent is driving", RegexOption.IGNORE_CASE)
            val windows = h.ok("fx_get_app_status")["windows"]
            assertThat(windows.size()).describedAs(windows.toString()).isEqualTo(1)
            val graph = Json.write(h.ok("fx_get_scene_graph"))
            assertThat(traces.find(graph)?.value).describedAs("scene graph mentions the bar").isNull()
            assertThat(h.ok("fx_find_nodes", args("text" to "Agent"))["total"].asInt()).isZero()
            assertThat(h.ok("fx_find_nodes", args("text" to "interact"))["total"].asInt()).isZero()
            val lint = Json.write(h.ok("fx_lint_visual"))
            assertThat(traces.find(lint)?.value).describedAs("lint mentions the bar").isNull()
            val shot = h.raw("fx_take_screenshot")
            assertThat(shot.images).hasSize(1)
            assertThat(shot.structured!!.path("legend").toString()).doesNotContainIgnoringCase("devbar")

            // an action's effects (a dialog opens) mention the dialog only
            val click = h.ok("fx_click_node", args("query" to "#dialogBtn"))
            assertThat(traces.find(Json.write(click["effects"]))?.value).describedAs("effects mention the bar").isNull()
            await("bar still rendered while the app acts") { h.ok("fx_test_get_bar_state")["rendered"].asInt() >= 1 }
            h.ok("fx_type_keys", args("keys" to listOf(mapOf("code" to "ESCAPE"))))
            await("dialog closed") { h.ok("fx_get_app_status")["windows"].size() == 1 }
            assertThat(h.ok("fx_get_app_status")["windows"].size()).isEqualTo(1)
        }
    }

    @Test
    fun `A-E5b action needed in embedded mode - a Java edit asks for an IDE rebuild and the bar says the same`() {
        Harness(app = gauntlet, configure = { it.copy(bar = "on") }).use { h ->
            val d = h.ctx.session.build.prepare()
            val handshake = h.project.resolve("target/fxloop/handshake.json")
            val cmd = buildList {
                if (System.getenv("FXLOOP_ACCEPTANCE_HEADLESS") != "none") addAll(listOf("xvfb-run", "-a"))
                add(AppSession.javaExecutable())
                add("-Dfxloop.dev=true")
                add("-Dfxloop.test=true")
                add("-Dfxloop.handshake=$handshake")
                add("-cp")
                add((listOf(d.outputDir) + d.classpath).joinToString(File.pathSeparator))
                add(d.mainClass)
            }
            val p = ProcessBuilder(cmd).directory(h.project.toFile()).redirectErrorStream(true)
                .redirectOutput(h.project.resolve("embedded.log").toFile()).start()
            try {
                await("handshake file", 30_000) { handshake.toFile().isFile && handshake.toFile().length() > 0 }
                Thread.sleep(2000)
                val st = h.ok("fx_connect_existing")
                assertThat(st["mode"].asText()).isEqualTo("embedded")
                assertThat(h.ok("fx_get_app_status")["mode"].asText()).isEqualTo("embedded")

                h.edit(gauntletSrc) { it.replace("private Stage primary;", "private Stage primary;\n    private int e5bMarker;") }
                val r = h.ok("fx_reload")
                assertThat(r["needsRestart"].asBoolean()).describedAs(r.toString()).isTrue()
                assertThat(r.has("restarted")).isFalse()
                assertThat(r.has("restartDeferred")).isFalse()
                assertThat(r["restartReasons"].toString()).isNotBlank()

                val needed = h.ok("fx_get_app_status")["actionNeeded"]
                val entry = needed.firstOrNull { it["next"].asText().contains("Rebuild and restart from your IDE") }
                assertThat(entry).describedAs(needed.toString()).isNotNull()
                assertThat(entry!!["kind"].asText()).isEqualTo("rebuildIde")
                assertThat(entry["reason"].asText()).startsWith("Rebuild and restart from your IDE").contains("·")
                assertThat(entry["reason"].asText().substringAfter("·").trim()).isNotEmpty()

                var barNeeded: List<String> = emptyList()
                await("bar shows the action") {
                    barNeeded = h.ok("fx_test_get_bar_state")["texts"]["actionNeeded"].map { it.asText() }
                    barNeeded.any { it.contains("Rebuild and restart from your IDE") }
                }
                assertThat(barNeeded.first { it.contains("Rebuild and restart from your IDE") }).contains(entry["reason"].asText().substringAfter("·").trim())
                assertThat(h.ok("fx_test_get_bar_state")["texts"]["session"].asText()).contains("embedded")
            } finally {
                p.descendants().forEach { it.destroy() }
                p.destroy()
                p.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `A-E5c shared control - agent input becomes events, robot input is announced on the bar`() {
        Harness(app = gauntlet, configure = { it.copy(bar = "on") }).use { h ->
            h.ok("fx_get_app_status")
            val set = h.ok("fx_set_control_mode", args("mode" to "shared"))
            assertThat(set["control"].asText()).isEqualTo("shared")
            val ev = h.ok("fx_click_node", args("query" to "#dialogBtn", "effects" to false))
            // No robot-independent pointer probe exists for the app's X display; the mode is the honest signal here.
            assertThat(ev["mode"].asText()).isEqualTo("event")
            h.ok("fx_type_keys", args("keys" to listOf(mapOf("code" to "ESCAPE"))))
            await("dialog closed") { h.ok("fx_get_app_status")["windows"].size() == 1 }
            await("bar shows shared control") { h.ok("fx_test_get_bar_state")["texts"]["session"].asText().contains("You can interact") }

            val robot = h.ok("fx_click_node", args("query" to "#dialogBtn", "mode" to "robot", "effects" to false))
            assertThat(robot["mode"].asText()).isEqualTo("robot")
            await("bar announced the robot") { h.ok("fx_test_get_bar_state")["controlHistory"].any { it.asText() == "Agent is using your mouse" } }
            h.ok("fx_type_keys", args("keys" to listOf(mapOf("code" to "ESCAPE"))))
        }
    }

    @Test
    fun `A-E5d headless status - the viewer stream carries the monocle mode and no bar popup exists`() {
        Harness(headlessOverride = "monocle").use { h ->
            val viewer = Viewer(0, "tok", h.ctx.visuals.store)
            viewer.start()
            h.ctx.viewer = viewer
            h.ok("fx_get_app_status")
            val conn = URI.create("http://127.0.0.1:${viewer.port}/sse?t=tok").toURL().openConnection() as HttpURLConnection
            conn.readTimeout = 15_000
            val status = StringBuilder()
            conn.inputStream.bufferedReader().use { reader ->
                var event = ""
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("event:")) event = line.substringAfter(':').trim()
                    if (line.startsWith("data:") && event == "status") {
                        status.append(line.substringAfter(':').trim())
                        break
                    }
                }
            }
            val s = Json.parse(status.toString())
            assertThat(s["mode"].asText()).describedAs(status.toString()).isEqualTo("headless (monocle)")
            assertThat(s["bar"].asText()).isEqualTo("off")
            val bar = h.ok("fx_test_get_bar_state")
            assertThat(bar["rendered"].asInt()).isZero()
            assertThat(h.ok("fx_get_app_status")["windows"].size()).isEqualTo(1)
        }
    }

    @Test
    fun `A-E6 pick - a pick click is not delivered, is returned by fx_get_picked and targets fx_set_property`() {
        Harness(app = gauntlet).use { h ->
            h.ok("fx_get_app_status")
            humanToMenusTab(h)
            assertThat(h.prop("#saveCount", "text").asText()).isEqualTo("0")
            h.ok("fx_test_set_pick_mode", args("on" to true))
            assertThat(h.ok("fx_get_app_status")["pickActive"].asBoolean()).isTrue()
            h.ok("fx_test_human_input", args("kind" to "click", "query" to "#saveBtn"))
            await("pick registered") { h.raw("fx_get_picked", args("includeImage" to false)).isError.not() }
            assertThat(h.prop("#saveCount", "text").asText()).describedAs("the button's action must not fire").isEqualTo("0")
            val picked = h.ok("fx_get_picked", args("includeImage" to false))
            assertThat(picked.toString()).contains("saveBtn")
            h.ok("fx_set_property", args("picked" to true, "name" to "text", "value" to "Picked!"))
            assertThat(h.prop("#saveBtn", "text").asText()).isEqualTo("Picked!")
            assertThat(h.ok("fx_get_app_status")["pickActive"].asBoolean()).isFalse()
            // outside pick mode the button works again
            h.ok("fx_click_node", args("query" to "#saveBtn", "mode" to "event"))
            assertThat(h.prop("#saveCount", "text").asText()).isEqualTo("1")
        }
    }
}

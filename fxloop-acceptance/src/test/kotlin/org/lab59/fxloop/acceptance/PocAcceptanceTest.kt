package org.lab59.fxloop.acceptance

import org.lab59.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files

/** PRD 01 §10 acceptance script, steps 1–10, driven purely through the tool surface. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PocAcceptanceTest {
    private val h = Harness()

    @AfterAll
    fun close() = h.close()

    @Test @Order(1)
    fun `1 status auto-starts the app with one window`() {
        val st = h.ok("fx_get_app_status")
        assertThat(st["state"].asText()).isEqualTo("running")
        assertThat(st["windows"].filter { it["type"].asText() == "STAGE" }).hasSize(1)
        assertThat(st["windows"][0]["title"].asText()).isEqualTo("FXLoop Sample — Contacts")
    }

    @Test @Order(2)
    fun `2 baseline screenshot is small and persisted`() {
        val r = h.raw("fx_take_screenshot", args("target" to "window", "label" to "baseline"))
        assertThat(r.isError).isFalse()
        assertThat(r.images).hasSize(1)
        assertThat(r.images[0].size).isLessThan(300 * 1024)
        assertThat(Files.exists(h.project.resolve(r.structured!!["file"].asText()))).isTrue()
        assertThat(r.structured!!["file"].asText()).startsWith("target/fxloop/screens/").endsWith("-baseline.png")
    }

    @Test @Order(3)
    fun `3-4 find, set text and observe the disable binding`() {
        assertThat(h.ok("fx_find_nodes", args("query" to "#nameField"))["total"].asInt()).isEqualTo(1)
        assertThat(h.prop("#addBtn", "disabled").asBoolean()).isTrue()
        h.ok("fx_set_text", args("query" to "#nameField", "text" to "Ada Lovelace"))
        assertThat(h.prop("#addBtn", "disabled").asBoolean()).isFalse()
    }

    @Test @Order(5)
    fun `5 combo popup is a window and robot-clicking an item selects it`() {
        h.ok("fx_click_node", args("query" to "#categoryBox"))
        val windows = h.ok("fx_get_app_status")["windows"]
        assertThat(windows.map { it["type"].asText() }).contains("POPUP")
        val hits = h.ok("fx_find_nodes", args("text" to "Work"))["items"].filter { it["path"].asText().startsWith("Popup") }
        assertThat(hits).isNotEmpty()
        h.ok("fx_click_node", args("path" to hits[0]["path"].asText()))
        assertThat(h.prop("#categoryBox", "value").asText()).isEqualTo("Work")
    }

    @Test @Order(6)
    fun `6 add shows a row, the status label and effects`() {
        val r = h.ok("fx_click_node", args("query" to "#addBtn"))
        val changed = r["effects"]["changed"]
        assertThat(changed.any { it["path"].asText().endsWith("Label#statusLabel") && it["new"].asText() == "1 contacts" }).isTrue()
        val graph = h.ok("fx_get_scene_graph", args("query" to "#contactTable"))
        assertThat(graph["root"]["itemCount"].asInt()).isEqualTo(1)
        assertThat(graph["root"]["visibleCells"][0].asText()).contains("Ada Lovelace")
        assertThat(h.prop("#statusLabel", "text").asText()).isEqualTo("1 contacts")
    }

    @Test @Order(7)
    fun `7 boom is captured as a UI error and the app survives`() {
        h.ok("fx_click_node", args("query" to "#boomBtn"))
        Thread.sleep(300)
        val errors = h.ok("fx_get_ui_errors")["errors"]
        assertThat(errors.any { it["exceptionClass"].asText() == "java.lang.IllegalStateException" && it["message"].asText() == "boom" }).isTrue()
        assertThat(h.ok("fx_get_app_status")["state"].asText()).isEqualTo("running")
    }

    @Test @Order(8)
    fun `8 restart relaunches with fresh state within 15 s`() {
        val t0 = System.currentTimeMillis()
        val r = h.ok("fx_restart_app")
        assertThat(System.currentTimeMillis() - t0).isLessThan(15_000)
        assertThat(r["state"].asText()).isEqualTo("running")
        assertThat(h.ok("fx_get_scene_graph", args("query" to "#contactTable"))["root"]["itemCount"].asInt()).isZero()
    }

    @Test @Order(9)
    fun `9 writing a bound property fails with PROPERTY_BOUND and a hint`() {
        val e = h.err("fx_set_property", args("query" to "#statusLabel", "name" to "text", "value" to "x"))
        assertThat(e["code"].asText()).isEqualTo("PROPERTY_BOUND")
        assertThat(e["hint"].asText()).contains("binding")
    }

    @Test @Order(10)
    fun `10 a blocked FX thread yields FX_TIMEOUT with the FX stack`() {
        h.ok("fx_test_block_fx_thread", args("ms" to 8000))
        val e = h.err("fx_get_scene_graph")
        assertThat(e["code"].asText()).isEqualTo("FX_TIMEOUT")
        assertThat(e["data"]["fxThreadStack"].size()).isGreaterThan(0)
        Thread.sleep(4000) // let the block expire
        assertThat(h.ok("fx_get_app_status")["state"].asText()).isEqualTo("running")
    }

    @Test @Order(11)
    fun `restart loop is stable 10 out of 10 (H1)`() {
        repeat(10) { assertThat(h.ok("fx_restart_app")["state"].asText()).isEqualTo("running") }
    }
}

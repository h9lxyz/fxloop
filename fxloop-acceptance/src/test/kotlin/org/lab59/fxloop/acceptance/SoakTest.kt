package org.lab59.fxloop.acceptance

import org.lab59.fxloop.acceptance.Harness.Companion.CSS
import org.lab59.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Soak harness (PRD 02 §11: 2 h, PRD 03 §10: 8 h): an edit → reload → act → observe loop for
 * `-Dfxloop.soak.minutes=N`. Asserts zero FCP disconnects and a bounded handle table. Not part of the default run.
 */
class SoakTest {
    @Test
    @EnabledIfSystemProperty(named = "fxloop.soak.minutes", matches = "\\d+")
    fun `edit-reload-act loop stays healthy`() {
        val minutes = System.getProperty("fxloop.soak.minutes").toLong()
        Harness().use { h ->
            h.ok("fx_get_app_status")
            val pid = h.ctx.session.pid
            val deadline = System.currentTimeMillis() + minutes * 60_000
            var i = 0
            val colors = listOf("#2e7d32", "#c62828")
            while (System.currentTimeMillis() < deadline) {
                h.edit(CSS) { it.replace(colors[i % 2], colors[(i + 1) % 2]) }
                h.ok("fx_reload", args("visualDiff" to (i % 10 == 0)))
                h.ok("fx_set_text", args("query" to "#nameField", "text" to "soak$i"))
                h.ok("fx_click_node", args("query" to "#addBtn"))
                h.ok("fx_get_scene_graph", args("maxNodes" to 200))
                if (i % 20 == 0) h.ok("fx_take_screenshot", args("includeImage" to false, "label" to "soak"))
                i++
            }
            val st = h.ok("fx_get_app_status")
            assertThat(st["fcpConnected"].asBoolean()).isTrue()
            assertThat(st["pid"].asLong()).describedAs("no unexpected restarts").isEqualTo(pid)
            assertThat(h.ok("fx_get_ui_errors")["errors"].size()).isZero()
            println("soak: $i iterations in $minutes min")
        }
    }
}

package org.lab59.fxloop.acceptance

import org.lab59.fxloop.acceptance.Harness.Companion.CONTROLLER
import org.lab59.fxloop.acceptance.Harness.Companion.CSS
import org.lab59.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** PRD 03 §2 (G1): the dev loop on the Gradle twin — fxloopPrepare launch, CSS tier, HotSwap tier, restart. */
class GradleLaneTest {
    @Test
    fun `gradle twin runs the same loop`() {
        Harness(app = "fxloop-sample-app-gradle").use { h ->
            val st = h.ok("fx_get_app_status")
            assertThat(st["state"].asText()).isEqualTo("running")
            assertThat(st["buildTool"].asText()).isEqualTo("gradle")

            h.edit(CSS) { it.replace("#2e7d32", "#1565c0") }
            val css = h.ok("fx_reload")
            assertThat(css["tiersApplied"].map { it["tier"].asText() }).contains("css")
            assertThat(css["visualDiff"]["changedPct"].asDouble()).isGreaterThan(0.0)

            h.edit(CONTROLLER) { it.replace("new Contact(nameField.getText(),", "new Contact(nameField.getText() + \"?\",") }
            val swap = h.ok("fx_reload")
            assertThat(swap["tiersApplied"].first { it["tier"].asText() == "hotswap" }["details"]["ok"].asBoolean()).isTrue()
            h.ok("fx_set_text", args("query" to "#nameField", "text" to "Gus"))
            val fx = h.ok("fx_click_node", args("query" to "#addBtn"))["effects"]
            assertThat(fx["added"].any { it.path("text").asText().contains("Gus?") }).isTrue()

            assertThat(h.ok("fx_restart_app", args("replay" to true))["replay"]["replayed"].asInt()).isEqualTo(2)
        }
    }
}

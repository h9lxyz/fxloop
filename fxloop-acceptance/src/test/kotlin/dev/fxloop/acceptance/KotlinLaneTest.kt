package dev.fxloop.acceptance

import dev.fxloop.acceptance.Harness.Companion.CSS
import dev.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** PRD 06 §6: the Kotlin twin (Gradle Kotlin DSL, `MainKt` launcher) — `.kt` edits compile via the build tool, then HotSwap. */
class KotlinLaneTest {
    @Test
    fun `kotlin twin hot-swaps kt edits through the build tool`() {
        Harness(app = "fxloop-sample-app-kotlin").use { h ->
            val st = h.ok("fx_get_app_status")
            assertThat(st["state"].asText()).isEqualTo("running")

            h.edit(CSS) { it.replace("#2e7d32", "#1565c0") }
            assertThat(h.ok("fx_reload")["tiersApplied"].map { it["tier"].asText() }).contains("css")

            h.edit(KT_CONTROLLER) { it.replace("Contact(nameField.text, categoryBox.value)", "Contact(nameField.text + \"?\", categoryBox.value)") }
            val swap = h.ok("fx_reload")
            val hotswap = swap["tiersApplied"].firstOrNull { it["tier"].asText() == "hotswap" }
            assertThat(hotswap).describedAs("reload: %s", swap).isNotNull()
            hotswap!!
            assertThat(hotswap["details"]["ok"].asBoolean()).isTrue()
            assertThat(hotswap["details"]["compiler"].asText()).isEqualTo("gradle")
            assertThat(swap.has("needsRestart")).isFalse()
            h.ok("fx_set_text", args("query" to "#nameField", "text" to "Gus"))
            val fx = h.ok("fx_click_node", args("query" to "#addBtn"))["effects"]
            assertThat(fx["added"].any { it.path("text").asText().contains("Gus?") }).isTrue()
        }
    }

    companion object {
        const val KT_CONTROLLER = "src/main/kotlin/dev/fxloop/sample/MainController.kt"
    }
}

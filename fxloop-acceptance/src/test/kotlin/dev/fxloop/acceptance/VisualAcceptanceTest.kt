package dev.fxloop.acceptance

import dev.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** PRD 04 A-V1 (+ production G12): seeded visual bugs found via one annotated screenshot + fx_lint_visual. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisualAcceptanceTest {
    private val h = Harness(appArgs = listOf("--seed", "--broken-css"))

    @AfterAll
    fun close() = h.close()

    @Test
    fun `A-V1 annotated screenshot and visual lint name both seeded bugs`() {
        val shot = h.raw("fx_take_screenshot", args("annotate" to "interactive", "label" to "broken"))
        assertThat(shot.isError).describedAs(shot.text).isFalse()
        val legend = shot.structured!!["legend"].map { it["path"].asText() }
        assertThat(legend).anyMatch { it.endsWith("#helpBtn") }
        assertThat(legend).anyMatch { it.endsWith("#hintLabel") }

        val lint = h.ok("fx_lint_visual")["items"]
        assertThat(lint.any { it["check"].asText() == "siblingOverlap" && it["path"].asText().endsWith("#helpBtn") }).isTrue()
        assertThat(lint.any { it["check"].asText() == "lowContrast" && it["path"].asText().endsWith("#hintLabel") }).isTrue()
    }

    @Test
    fun `G12 state strip renders every state and restores the node`() {
        val r = h.raw("fx_capture_states", args("query" to "#addBtn"))
        assertThat(r.isError).describedAs(r.text).isFalse()
        assertThat(r.structured!!["states"].size()).isEqualTo(5)
        assertThat(r.images).hasSize(1)
        assertThat(h.prop("#addBtn", "disable").asBoolean()).isFalse()
    }

    @Test
    fun `G5 explain tools answer why`() {
        val p = h.ok("fx_explain_property", args("query" to "#addBtn", "name" to "disable"))
        assertThat(p["bound"].asBoolean()).isTrue()
        val style = h.ok("fx_explain_style", args("query" to "#hintLabel"))
        assertThat(style["items"].map { it["cssProperty"].asText() }).contains("-fx-text-fill")
        val layout = h.ok("fx_explain_layout", args("query" to "#helpBtn"))
        assertThat(layout["hints"].map { it.asText() }).anyMatch { it.contains("translate") }
        val at = layout["boundsInScreen"]
        val d = h.ok("fx_describe_at", args("x" to at["x"].asDouble() + at["w"].asDouble() / 2, "y" to at["y"].asDouble() + at["h"].asDouble() / 2))
        assertThat(d["node"]["path"].asText()).endsWith("#helpBtn")
    }
}

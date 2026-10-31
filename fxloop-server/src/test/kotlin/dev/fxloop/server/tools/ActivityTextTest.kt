package dev.fxloop.server.tools

import dev.fxloop.protocol.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The dev bar's "Agent: …" text for tool calls. */
class ActivityTextTest {
    @Test
    fun `tool calls read as what the agent is doing`() {
        assertThat(Ctx.activityText("fx_click_node", Json.obj { put("path", "Stage[0]/Scene/BorderPane/Button#save") }))
            .isEqualTo("clicking Button#save")
        assertThat(Ctx.activityText("fx_click_node", Json.obj { put("query", ".primary") })).isEqualTo("clicking .primary")
        val keys = Json.obj { putArray("keys").addObject().put("code", "S").putArray("modifiers").add("CONTROL") }
        assertThat(Ctx.activityText("fx_type_keys", keys)).isEqualTo("pressing Control+S")
        assertThat(Ctx.activityText("fx_set_text", Json.obj { put("query", "#name") })).isEqualTo("typing into #name")
        assertThat(Ctx.activityText("fx_take_screenshot", Json.obj())).isEqualTo("taking a screenshot")
        assertThat(Ctx.activityText("fx_something_new", Json.obj())).isEqualTo("something new")
    }
}

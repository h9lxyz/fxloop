package org.lab59.fxloop.server.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.lab59.fxloop.protocol.Json

class WatchTextTest {
    @Test
    fun `the dev bar names the tiers a watched save applied`() {
        val r = Json.parse("""{"tiersApplied":[{"tier":"css","files":["app.css"],"ms":12},{"tier":"fxml","files":[],"ms":3}]}""")
        assertThat(Ctx.watchText(r)).isEqualTo("watch: css, fxml")
        assertThat(Ctx.watchText(Json.parse("""{"tiersApplied":[]}"""))).isEqualTo("watch: no change")
    }
}

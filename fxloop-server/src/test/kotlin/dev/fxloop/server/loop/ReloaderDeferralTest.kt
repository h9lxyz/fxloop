package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.JsonNode
import dev.fxloop.protocol.Json
import dev.fxloop.server.app.AppSession.Mode
import dev.fxloop.server.loop.Reloader.RestartDecision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReloaderDeferralTest {
    private fun d(allow: Boolean, mode: Mode, force: Boolean, active: Boolean) = Reloader.restartDecision(allow, mode, force, active)

    @Test
    fun launchedIdleRestarts() {
        assertThat(d(true, Mode.LAUNCHED, false, false)).isEqualTo(RestartDecision.RESTART)
    }

    @Test
    fun launchedHumanActiveDefers() {
        assertThat(d(true, Mode.LAUNCHED, false, true)).isEqualTo(RestartDecision.DEFER)
    }

    @Test
    fun overrideRestartsDespiteHuman() {
        assertThat(d(true, Mode.LAUNCHED, true, true)).isEqualTo(RestartDecision.RESTART)
    }

    @Test
    fun neverRestartsOtherwise() {
        assertThat(d(false, Mode.LAUNCHED, false, false)).isEqualTo(RestartDecision.NONE)
        assertThat(d(true, Mode.EMBEDDED, true, false)).isEqualTo(RestartDecision.NONE)
        assertThat(d(true, Mode.ATTACHED, true, false)).isEqualTo(RestartDecision.NONE)
    }

    @Test
    fun remountNoteworthiness() {
        assertThat(Reloader.remountNoteworthy(Json.obj { put("mounts", 0) })).isFalse()
        assertThat(Reloader.remountNoteworthy(Json.obj { withArray("remounted").add("a") })).isTrue()
        assertThat(Reloader.remountNoteworthy(Json.obj { withArray("skipped").add("a") })).isTrue()
        assertThat(Reloader.remountNoteworthy(Json.obj { set<JsonNode>("callbacks", Json.obj { put("ran", 1) }) })).isTrue()
    }
}

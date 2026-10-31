package dev.fxloop.server.app

import dev.fxloop.protocol.Json
import dev.fxloop.server.Config
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveStateTest {
    private fun live(dir: Path, cfg: (Config) -> Config = { it }): LiveState {
        val c = cfg(Config(projectDir = dir))
        return LiveState(c, AppSession(c))
    }

    private val human = Json.obj { put("tool", "fx_click_node") }

    @Test
    fun `auto control starts as agent and switches to shared once on the first human action`(@TempDir dir: Path) {
        val l = live(dir)
        assertThat(l.control).isEqualTo(LiveState.Control.AGENT)
        l.onHumanAction(human)
        assertThat(l.control).isEqualTo(LiveState.Control.SHARED)
        l.setControl(LiveState.Control.AGENT, "tool")
        l.onHumanAction(human)
        assertThat(l.control).isEqualTo(LiveState.Control.AGENT)
    }

    @Test
    fun `fixed control settings never auto switch`(@TempDir dir: Path) {
        val l = live(dir) { it.copy(control = "agent") }
        l.onHumanAction(human)
        assertThat(l.control).isEqualTo(LiveState.Control.AGENT)
        assertThat(live(dir) { it.copy(control = "shared") }.control).isEqualTo(LiveState.Control.SHARED)
    }

    @Test
    fun `setControl reports previous and via`(@TempDir dir: Path) {
        val r = live(dir).setControl(LiveState.Control.SHARED, "bar")
        assertThat(r["control"].asText()).isEqualTo("shared")
        assertThat(r["previous"].asText()).isEqualTo("agent")
        assertThat(r["via"].asText()).isEqualTo("bar")
    }

    @Test
    fun `restart is deferred by shared control and by the human idle window`(@TempDir dir: Path) {
        val l = live(dir) { it.copy(control = "agent", humanIdleMs = 150) }
        assertThat(l.shouldDeferRestart()).isFalse()
        l.onHumanAction(human)
        assertThat(l.shouldDeferRestart()).isTrue()
        Thread.sleep(250)
        assertThat(l.shouldDeferRestart()).isFalse()
        l.setControl(LiveState.Control.SHARED, "tool")
        assertThat(l.shouldDeferRestart()).isTrue()
    }

    @Test
    fun `barState mode and bar strings follow the configuration`(@TempDir dir: Path) {
        val plain = live(dir).barState()
        assertThat(plain["bar"].asText()).isEqualTo("on")
        assertThat(plain["mode"].asText()).isEqualTo("dev loop")
        assertThat(plain["config"]["pickKey"].asText()).isEqualTo("Ctrl+Shift+F12")
        assertThat(plain["lastReload"]["seq"].asInt()).isZero()

        val monocle = live(dir) { it.copy(headless = "monocle") }.barState()
        assertThat(monocle["bar"].asText()).isEqualTo("off")
        assertThat(monocle["mode"].asText()).isEqualTo("headless (monocle)")
        val xvfb = live(dir) { it.copy(headless = "xvfb", bar = "pill") }.barState()
        assertThat(xvfb["bar"].asText()).isEqualTo("pill")
        assertThat(xvfb["mode"].asText()).isEqualTo("headless (xvfb)")
        assertThat(live(dir) { it.copy(headless = "monocle", bar = "on") }.barState()["bar"].asText()).isEqualTo("on")
    }

    @Test
    fun `reloaded replaces the action needed list and bumps the sequence`(@TempDir dir: Path) {
        val l = live(dir)
        l.watchMode = { "css-only" }
        l.reloaded("CSS applied · 12 ms", true, listOf(ActionNeeded("restart", "fields added", "Restart", "restart")))
        var s = l.barState()
        assertThat(s["lastReload"]["text"].asText()).isEqualTo("CSS applied · 12 ms")
        assertThat(s["lastReload"]["seq"].asInt()).isEqualTo(1)
        assertThat(s["watch"].asText()).isEqualTo("css-only")
        assertThat(s["actionNeeded"].map { it["kind"].asText() }).containsExactly("restart")
        l.reloaded("FXML swapped", true, listOf(ActionNeeded("rebuildIde", "x", "y", "copy")))
        s = l.barState()
        assertThat(s["lastReload"]["seq"].asInt()).isEqualTo(2)
        assertThat(s["actionNeeded"].map { it["kind"].asText() }).containsExactly("rebuildIde")
        l.setActionNeeded(emptyList())
        assertThat(l.actionNeeded().size()).isZero()
        assertThat(l.status(null)["actionNeeded"].size()).isZero()
    }

    @Test
    fun `push publishes to the status sink and coalesces`(@TempDir dir: Path) {
        val l = live(dir)
        val got = CountDownLatch(1)
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        l.statusSink = { seen += it; got.countDown() }
        l.driving(true)
        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(seen.first()).contains("\"driving\":true").doesNotContain("\n")
    }

    @Test
    fun `status merges server side activity into the agent's human block`(@TempDir dir: Path) {
        val l = live(dir)
        l.onHumanPick(Json.obj { put("ts", System.currentTimeMillis()); put("target", "Stage[0]/Button#ok") })
        l.onHumanAction(human)
        val s = l.status(Json.obj { put("lastActivityMs", 60_000); put("recorded", 3) })
        assertThat(s["human"]["recorded"].asInt()).isEqualTo(3)
        assertThat(s["human"]["lastActivityMs"].asLong()).isLessThan(5_000)
        assertThat(s["human"]["lastPickTarget"].asText()).isEqualTo("Stage[0]/Button#ok")
        assertThat(s["control"].asText()).isEqualTo("shared")
        assertThat(s["controlSetting"].asText()).isEqualTo("auto")
    }
}

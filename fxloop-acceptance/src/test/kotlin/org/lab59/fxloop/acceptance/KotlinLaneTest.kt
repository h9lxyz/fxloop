package org.lab59.fxloop.acceptance

import org.lab59.fxloop.acceptance.Harness.Companion.CSS
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.acceptance.Harness.Companion.args
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /**
     * Watch mode `all` with a slow build-tool compile: a restart arriving while the watcher's reload compiles used to
     * deadlock (restart held the session lock and waited for the reloader; the reload waited for the session lock), and
     * the bar state push after launch never came. The explicit reload must not compile the watcher's edit again.
     */
    @Test
    fun `watch-mode compile overlapping a reload and a restart neither deadlocks nor compiles twice`() {
        Harness(app = "fxloop-sample-app-kotlin", configure = { it.copy(watchMode = "all", bar = "on") }).use { h ->
            h.ok("fx_get_app_status")
            h.edit(KT_CONTROLLER) { it.replace("Contact(nameField.text, categoryBox.value)", "Contact(nameField.text + \"!\", categoryBox.value)") }
            val reload = h.ok("fx_reload") // races the watcher's reload of the same edit
            val watched = await("watch-mode reload") { h.ctx.watch.lastResult }
            val compiles = listOf(reload, watched).count { r -> r["tiersApplied"].any { it["tier"].asText() == "hotswap" } }
            assertThat(compiles).describedAs("explicit: %s\nwatch: %s", reload, watched).isEqualTo(1)

            val restart = Executors.newSingleThreadExecutor().let { ex ->
                try {
                    val f = ex.submit<ObjectNode> { h.ok("fx_restart_app") }
                    // an edit while the app relaunches: the watcher compiles it (holding the reloader lock) and then
                    // calls into the session while the restart still holds the session lock
                    Thread.sleep(500)
                    h.edit(KT_CONTROLLER) { it.replace("nameField.text + \"!\"", "nameField.text + \"?\"") }
                    f.get(180, TimeUnit.SECONDS)
                } finally {
                    ex.shutdownNow()
                }
            }
            assertThat(restart["restarted"].asBoolean()).isTrue()
            await("bar rendered after restart") { h.ok("fx_test_get_bar_state")["rendered"].asInt().takeIf { it >= 1 } }
        }
    }

    private fun <T : Any> await(what: String, timeoutMs: Long = 120_000, value: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            value()?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for $what")
    }

    companion object {
        const val KT_CONTROLLER = "src/main/kotlin/org/lab59/fxloop/sample/MainController.kt"
    }
}

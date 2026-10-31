package dev.fxloop.acceptance

import dev.fxloop.acceptance.Harness.Companion.args
import dev.fxloop.protocol.Handshake
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.app.ProcessTree
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `fxloop:run` / `fxloopRun`: the developer starts the app (agent with its own token, standalone dev bar), explores,
 * then an agent session connects on first use instead of launching a second copy.
 */
class RunModeAcceptanceTest {
    @Test
    fun `server connects to the developer-run app, keeps it unsupervised and reloads edits made before connecting`() {
        Harness().use { h ->
            val d = h.ctx.session.build.prepare()
            val handshake = h.ctx.session.fxloopDir.resolve("handshake.json")
            Files.deleteIfExists(handshake)
            // exactly what the build plugins start (LaunchDescriptor.runCommand), plus the leak-guard marker
            val run = d.copy(jvmArgs = d.jvmArgs + Harness.RUN_MARKER)
                .runCommand(AppSession.javaExecutable(), "${h.ctx.session.agentJar()}=${d.runAgentArgs(handshake, "on")}", listOf("--seed"))
            val cmd = (if (System.getenv("FXLOOP_ACCEPTANCE_HEADLESS") != "none") listOf("xvfb-run", "-a") else emptyList()) + run
            val p = ProcessTree.track(ProcessBuilder(cmd).directory(h.project.toFile()).redirectErrorStream(true)
                .redirectOutput(h.project.resolve("run-mode.log").toFile()).start())
            try {
                val hs = awaitHandshake(handshake)
                assertThat(hs.token).describedAs("developer-run agents write their own token").isNotNull()
                // the developer edits a stylesheet before bringing in the agent
                val css = h.project.resolve("src/main/resources/dev/fxloop/sample/app.css")
                Files.writeString(css, Files.readString(css) + "\n#statusLabel { -fx-text-fill: #123456; }\n")

                val st = h.ok("fx_get_app_status")
                assertThat(st["mode"].asText()).isEqualTo("embedded")
                assertThat(st["pid"].asLong()).isEqualTo(hs.pid)
                assertThat(st["control"].asText()).isEqualTo("shared")
                assertThat(h.prop("#statusLabel", "text").asText()).isEqualTo("3 contacts")
                assertThat(h.err("fx_restart_app")["code"].asText()).isEqualTo("NOT_SUPERVISED")

                val r = h.ok("fx_reload")
                assertThat(r["tiersApplied"].map { it.path("tier").asText() }).contains("css")
                val style = h.ok("fx_explain_style", args("query" to "#statusLabel", "property" to "-fx-text-fill"))
                assertThat(style["items"][0]["value"].asText()).isEqualTo("#123456ff")
            } finally {
                ProcessTree.kill(p)
            }
        }
    }

    private fun awaitHandshake(file: Path): Handshake {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(file)) return Handshake.readFrom(file)
            Thread.sleep(100)
        }
        error("no handshake from the developer-run app")
    }
}

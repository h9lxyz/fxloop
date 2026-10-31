package dev.fxloop.acceptance

import dev.fxloop.acceptance.Harness.Companion.args
import dev.fxloop.protocol.Handshake
import dev.fxloop.server.app.AppSession
import dev.fxloop.server.app.ProcessTree
import dev.fxloop.server.app.Versions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

    @Test
    fun `inspector helper serves the CSS inspector without an agent and yields to agent sessions`() {
        Harness().use { h ->
            val d = h.ctx.session.build.prepare()
            val handshake = h.ctx.session.fxloopDir.resolve("handshake.json")
            Files.deleteIfExists(handshake)
            val java = AppSession.javaExecutable()
            val run = d.copy(jvmArgs = d.jvmArgs + Harness.RUN_MARKER)
                .runCommand(java, "${h.ctx.session.agentJar()}=${d.runAgentArgs(handshake, "on")}", listOf("--seed"))
            val xvfb = if (System.getenv("FXLOOP_ACCEPTANCE_HEADLESS") != "none") listOf("xvfb-run", "-a") else emptyList()
            val app = ProcessTree.track(ProcessBuilder(xvfb + run).directory(h.project.toFile()).redirectErrorStream(true)
                .redirectOutput(h.project.resolve("run-mode.log").toFile()).start())
            val serverJar = h.repo.resolve("fxloop-server/target/fxloop-server-${Versions.SERVER}-all.jar")
            val helperLog = h.project.resolve("inspector.log")
            val helper = ProcessTree.track(ProcessBuilder(d.inspectorHelperCommand(java, serverJar, h.project, "on"))
                .directory(h.project.toFile()).redirectErrorStream(true).redirectOutput(helperLog.toFile())
                .apply { environment()["FXLOOP_BROWSER"] = "none" }.start())
            try {
                awaitHandshake(handshake)
                val url = awaitLine(helperLog, Regex("http://\\S+/inspect\\?t=\\w+"))
                val tree = url.replace("/inspect?", "/api/tree?")
                assertThat(awaitBody(tree) { it.contains("\"root\"") }).contains("#statusLabel")

                // an agent session takes the app over; the helper steps aside instead of fighting for it
                assertThat(h.ok("fx_get_app_status")["mode"].asText()).isEqualTo("embedded")
                assertThat(awaitBody(tree) { it.contains("NO_APP") }).contains("NO_APP")
                Thread.sleep(3000) // a few helper retry rounds: the agent session must keep the app
                assertThat(h.prop("#statusLabel", "text").asText()).isEqualTo("3 contacts")

                // the agent session ends: the helper is back
                h.ctx.session.shutdown()
                assertThat(awaitBody(tree) { it.contains("\"root\"") }).contains("#statusLabel")
            } finally {
                ProcessTree.kill(helper)
                ProcessTree.kill(app)
            }
        }
    }

    private fun awaitLine(file: Path, re: Regex): String {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(file)) re.find(Files.readString(file))?.let { return it.value }
            Thread.sleep(200)
        }
        error("no match for $re in $file")
    }

    private fun awaitBody(url: String, done: (String) -> Boolean): String {
        val client = HttpClient.newHttpClient()
        val deadline = System.currentTimeMillis() + 30_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = runCatching { client.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofString()).body() }
                .getOrDefault("")
            if (done(last)) return last
            Thread.sleep(300)
        }
        return last
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

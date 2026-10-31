package dev.fxloop.acceptance

import dev.fxloop.server.app.ProcessTree
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Regression test for the incident where orphaned app trees exhausted the machine: the real server runs as its own
 * JVM (exactly as Claude Code launches it) and must take its app with it when the client goes away — whether the
 * client closes stdio (crash / reconnect) or the server itself is SIGKILLed.
 */
class ServerLifecycleTest {
    private val repo = Path.of(System.getProperty("fxloop.repo") ?: "..").toAbsolutePath().normalize()

    private fun appJvms(): List<ProcessHandle> = ProcessHandle.allProcesses().filter {
        it.isAlive && it.info().command().orElse("").endsWith("java") &&
            it.info().commandLine().orElse("").contains(Harness.RUN_MARKER) &&
            it.info().commandLine().orElse("").contains("dev.fxloop.sample.Launcher")
    }.toList()

    private class Server(val process: Process) {
        val out: BufferedReader = process.inputStream.bufferedReader()

        fun send(json: String) {
            process.outputStream.write((json + "\n").toByteArray())
            process.outputStream.flush()
        }

        fun awaitId(id: Int): String {
            while (true) {
                val line = out.readLine() ?: error("server closed stdout")
                if (line.contains("\"id\":$id")) return line
            }
        }
    }

    private fun startServerWithApp(project: Path): Server {
        val pb = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), "dev.fxloop.server.MainKt",
        ).redirectError(project.resolve("server.log").toFile())
        pb.environment().putAll(mapOf(
            "FXLOOP_PROJECT_DIR" to project.toString(),
            "FXLOOP_BUILD_CMD" to repo.resolve("mvnw").toString(),
            "FXLOOP_HEADLESS" to (System.getenv("FXLOOP_ACCEPTANCE_HEADLESS")?.takeIf { it != "none" } ?: "xvfb"),
            "FXLOOP_JVM_ARGS" to Harness.RUN_MARKER,
            "FXLOOP_WATCH" to "off",
        ))
        val server = Server(ProcessTree.track(pb.start()))
        server.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"lifecycle-test","version":"1"}}}""")
        server.awaitId(1)
        server.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        server.send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"fx_get_app_status","arguments":{}}}""")
        assertThat(server.awaitId(2)).contains("running")
        assertThat(appJvms()).hasSize(1)
        return server
    }

    private fun awaitNoAppJvms(seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (appJvms().isEmpty()) return true
            Thread.sleep(200)
        }
        return false
    }

    @Test
    fun `closing stdio ends the server and its app tree`() {
        Harness.copyOf(repo, "fxloop-sample-app").let { project ->
            var server: Server? = null
            try {
                server = startServerWithApp(project)
                server.process.outputStream.close() // the MCP client went away
                assertThat(server.process.waitFor(20, TimeUnit.SECONDS)).describedAs("server exits on stdin EOF").isTrue()
                assertThat(awaitNoAppJvms(10)).describedAs("app JVM reaped with the server").isTrue()
            } finally {
                server?.let { ProcessTree.kill(it.process) }
                appJvms().forEach { ProcessTree.kill(it) }
                Harness.deleteProject(project)
            }
        }
    }

    @Test
    fun `a SIGKILLed server still takes its app down (owner watchdog)`() {
        Harness.copyOf(repo, "fxloop-sample-app").let { project ->
            var server: Server? = null
            try {
                server = startServerWithApp(project)
                server.process.destroyForcibly() // no shutdown hooks can run
                server.process.waitFor(10, TimeUnit.SECONDS)
                assertThat(awaitNoAppJvms(15)).describedAs("app exits by itself when its server is gone").isTrue()
            } finally {
                server?.let { ProcessTree.kill(it.process) }
                appJvms().forEach { ProcessTree.kill(it) }
                Harness.deleteProject(project)
            }
        }
    }

    @Suppress("unused")
    private val keepImports = listOf(File.separator, Files::class)
}

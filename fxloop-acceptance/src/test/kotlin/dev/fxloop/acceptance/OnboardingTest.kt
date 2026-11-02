package dev.fxloop.acceptance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.fxloop.server.app.ProcessTree
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * PRD 11 section 8, "Scripted" bullet (A-O1 assertions on the Linux lane, plus A-O6): a local HTTP server stands in for
 * GitHub and serves the built server jar at the release URL path; the README's bash install line runs against it in a
 * throwaway HOME; the jar installed there is registered exactly as section 4.1 does (`java -jar <jar>`, cwd = project)
 * and driven over stdio MCP on a copy of the sample app that has no FXLoop plugin or guard.
 *
 * Deliberate deviations from a user's machine, all about not downloading the world: the child server shares the real
 * Maven home (MAVEN_USER_HOME: wrapper distribution and local repository), uses the repo's mvnw as build command, and
 * the headless lane comes from FXLOOP_ACCEPTANCE_HEADLESS like every other acceptance test. The "no dev/fxloop in
 * ~/.m2" assertion is therefore checked as "the run did not change the real ~/.m2/repository/dev/fxloop".
 * A-O6 stops at the seeded files: a full `mvn fxloop:run` against a fresh repo needs Central for everything else.
 */
class OnboardingTest {
    private val repo = Path.of(System.getProperty("fxloop.repo") ?: "..").toAbsolutePath().normalize()
    private val mapper = ObjectMapper()

    private fun serverJar(): Path {
        val jar = Files.list(repo.resolve("fxloop-server/target")).use { s ->
            s.filter { it.fileName.toString().matches(Regex("fxloop-server-.*-all\\.jar")) }.findFirst().orElse(null)
        }
        assumeTrue(jar != null, "fxloop-server shaded jar not built (./mvnw install -DskipTests -pl fxloop-server)")
        return jar
    }

    private fun java() = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** Path -> (size, mtime) for everything below [root]; empty when it does not exist. */
    private fun snapshot(root: Path): Map<String, Pair<Long, Long>> {
        if (!root.exists()) return emptyMap()
        return Files.walk(root).use { s ->
            s.filter { it.isRegularFile() }.toList().associate {
                root.relativize(it).toString() to (Files.size(it) to Files.getLastModifiedTime(it).toMillis())
            }
        }
    }

    private fun runProcess(cmd: List<String>, home: Path, cwd: Path? = null, seconds: Long = 120): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        cwd?.let { pb.directory(it.toFile()) }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertThat(p.waitFor(seconds, TimeUnit.SECONDS)).describedAs("`${cmd.joinToString(" ")}` finished").isTrue()
        return p.exitValue() to out
    }

    @Test
    fun `readme install then fx_get_app_status launches a plugin-free app and takes a screenshot`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"), "bash install line: Linux/macOS lane only")
        assumeTrue(runCatching { ProcessBuilder("curl", "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false), "curl not installed")
        val started = System.nanoTime()
        val jar = serverJar()
        val home = Files.createTempDirectory("fxloop-onboarding-home-")
        val realM2 = Path.of(System.getProperty("user.home"), ".m2")
        val fxloopInRealRepo = realM2.resolve("repository/dev/fxloop")
        val before = snapshot(fxloopInRealRepo)
        val project = Harness.copyOf(repo, "fxloop-sample-app")
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var server: Process? = null
        try {
            http.createContext("/h9lxyz/fxloop/releases/download/edge/fxloop-server-all.jar") { ex ->
                val bytes = Files.readAllBytes(jar)
                ex.responseHeaders.add("Content-Type", "application/java-archive")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            http.start()
            val host = "http://127.0.0.1:${http.address.port}"

            // 1. README "Install", bash block, verbatim except the host.
            val installLine = Regex("```sh\\n# bash / zsh\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
                .find(repo.resolve("README.md").readText())?.groupValues?.get(1)
                ?: error("README Install bash block not found")
            val script = installLine.replace("https://github.com", host)
            assertThat(script).contains("releases/download/edge/fxloop-server-all.jar").doesNotContain("github.com")
            val (code, out) = runProcess(listOf("bash", "-c", script), home)
            assertThat(code).describedAs("install line output:\n$out").isZero()
            assertThat(home.resolve(".fxloop/fxloop-server.jar")).exists()
            assertThat(out).describedAs("--version prints the version").containsPattern("\\d+\\.\\d+\\.\\d+")
            assertThat(Files.size(home.resolve(".fxloop/fxloop-server.jar"))).isEqualTo(Files.size(jar))

            // 2. A-O1: nothing outside ~/.fxloop yet.
            assertThat(Files.list(home).use { s -> s.map { it.fileName.toString() }.toList() }).containsExactly(".fxloop")

            // 3. Plugin-free project: no dev.fxloop plugin or guard in the build file (the parent is kept for versions).
            val pom = project.resolve("pom.xml").readText()
            assertThat(pom).doesNotContain("fxloop-maven-plugin").doesNotContain("FxloopBootstrap")

            // 4. Register as section 4.1 does and drive it over stdio.
            val installed = home.resolve(".fxloop/fxloop-server.jar")
            val pb = ProcessBuilder(java(), "-jar", installed.toString()).directory(project.toFile())
                .redirectError(project.resolve("server.log").toFile())
            pb.environment().putAll(mapOf(
                "HOME" to home.toString(),
                "MAVEN_USER_HOME" to realM2.toString(),
                "FXLOOP_BUILD_CMD" to repo.resolve("mvnw").toString(),
                "FXLOOP_PREPARE_ROUTE" to "server", // the real ~/.m2 has the plugin installed; this run must not use it
                "FXLOOP_HEADLESS" to (System.getenv("FXLOOP_ACCEPTANCE_HEADLESS")?.takeIf { it != "none" } ?: "xvfb"),
                "FXLOOP_JVM_ARGS" to Harness.RUN_MARKER,
                "FXLOOP_BROWSER" to "none",
                "FXLOOP_WATCH" to "off",
                "FXLOOP_STARTUP_TIMEOUT_MS" to "120000",
            ))
            pb.environment().remove("FXLOOP_PROJECT_DIR")
            val proc = ProcessTree.track(pb.start())
            server = proc
            val rpc = Rpc(proc)
            rpc.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"onboarding-test","version":"1"}}}""")
            rpc.await(1, 60)
            rpc.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

            val status = rpc.call(2, "fx_get_app_status", "{}", 300)
            assertThat(status["state"].asText()).describedAs(status.toString()).isEqualTo("running")
            assertThat(status["prepare"].asText()).isEqualTo("server")
            assertThat(status["buildTool"].asText()).isEqualTo("maven")

            val shot = rpc.call(3, "fx_take_screenshot", """{"label":"onboarding"}""", 60)
            val file = project.resolve(shot["file"].asText())
            assertThat(file).describedAs("screenshot file ${shot["file"]}").exists()
            assertThat(Files.size(file)).isGreaterThan(1000)
            assertThat(Files.readAllBytes(file).copyOfRange(1, 4)).isEqualTo("PNG".toByteArray())
            val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
            println("ONBOARDING wall time: ${seconds}s from install line to screenshot (PRD 11 A-O1 budget: 300s)")

            // 5. A-O1 assertions: nothing outside ~/.fxloop and the project.
            // JavaFX/fontconfig write platform caches under ~/.cache and ~/.openjfx on first use; those are the toolkit's, not FXLoop's.
            assertThat(Files.list(home).use { s -> s.map { it.fileName.toString() }.toList() }).describedAs("temp HOME contents")
                .contains(".fxloop").allMatch { it in setOf(".fxloop", ".cache", ".openjfx", ".java") }
            assertThat(Files.walk(home).use { s -> s.filter { it != home && it != home.resolve(".fxloop") && !it.startsWith(home.resolve(".fxloop")) && it.fileName.toString().contains("fxloop", true) }.toList() })
                .describedAs("FXLoop files outside ~/.fxloop").isEmpty()
            assertThat(snapshot(fxloopInRealRepo)).describedAs("real ~/.m2/repository/dev/fxloop untouched").isEqualTo(before)
            assertThat(project.resolve("server.log").readText()).describedAs("server log").contains("prepare: server")
        } finally {
            server?.let { ProcessTree.kill(it.toHandle(), it.toHandle().descendants().toList()) }
            http.stop(0)
            Harness.deleteProject(project)
            home.toFile().deleteRecursively()
        }
    }

    /** A-O6 (files only; see class comment). */
    @Test
    fun `seed-local-repo writes the plugin, launch, agent, protocol and parent with metadata`() {
        val jar = serverJar()
        val home = Files.createTempDirectory("fxloop-onboarding-seed-")
        val target = Files.createTempDirectory("fxloop-onboarding-repo-")
        try {
            val (code, out) = runProcess(listOf(java(), "-jar", jar.toString(), "--seed-local-repo", "--repo=$target"), home)
            assertThat(code).describedAs(out).isZero()
            for (id in listOf("fxloop-parent", "fxloop-protocol", "fxloop-launch", "fxloop-agent", "fxloop-maven-plugin")) {
                val dir = target.resolve("dev/fxloop/$id")
                assertThat(dir.resolve("maven-metadata-local.xml")).describedAs(id).exists()
                val version = Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }.single().fileName.toString()
                val vdir = dir.resolve(version)
                assertThat(vdir.resolve("$id-$version.pom")).exists()
                if (id != "fxloop-parent") assertThat(Files.size(vdir.resolve("$id-$version.jar"))).isGreaterThan(0)
                assertThat(vdir.resolve("_remote.repositories")).exists()
                assertThat(dir.resolve("maven-metadata-local.xml").readText()).contains("<version>$version</version>")
            }
            // nothing but dev/fxloop was written, and the seeding run left no trace in HOME
            assertThat(Files.list(target).use { s -> s.map { it.fileName.toString() }.toList() }).containsExactly("dev")
            assertThat(Files.list(target.resolve("dev")).use { s -> s.map { it.fileName.toString() }.toList() }).containsExactly("fxloop")
            assertThat(Files.list(home).use { it.count() }).isZero()
        } finally {
            target.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    private inner class Rpc(val process: Process) {
        private val out = process.inputStream.bufferedReader()
        fun send(json: String) { process.outputStream.write((json + "\n").toByteArray()); process.outputStream.flush() }

        fun await(id: Int, seconds: Long): JsonNode {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val line = out.readLine() ?: error("server closed stdout")
                val n = runCatching { mapper.readTree(line) }.getOrNull() ?: continue
                if (n.path("id").asInt(-1) == id) return n
            }
            error("no response to request $id within ${seconds}s")
        }

        fun call(id: Int, tool: String, args: String, seconds: Long): JsonNode {
            send("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""")
            val r = await(id, seconds)
            assertThat(r.path("result").path("isError").asBoolean(false)).describedAs("$tool: $r").isFalse()
            return r["result"]["structuredContent"] ?: error("$tool returned no structuredContent: $r")
        }
    }
}

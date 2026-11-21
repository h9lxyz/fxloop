package org.lab59.fxloop.acceptance

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.acceptance.Harness.Companion.CONTROLLER
import org.lab59.fxloop.acceptance.Harness.Companion.CSS
import org.lab59.fxloop.acceptance.Harness.Companion.FXML
import org.lab59.fxloop.acceptance.Harness.Companion.args
import org.lab59.fxloop.launch.LaunchDescriptor
import org.lab59.fxloop.protocol.Handshake
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.server.app.AppSession
import org.lab59.fxloop.server.app.ProcessTree
import org.lab59.fxloop.server.attach.AttachCommand
import org.lab59.fxloop.server.attach.JvmScan
import org.lab59.fxloop.server.mcp.ToolRegistry
import org.lab59.fxloop.server.registry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.concurrent.thread
import kotlin.streams.asSequence

/**
 * `java -jar fxloop-server.jar attach`: the developer starts the app with plain `java` (no agent, no
 * `-XX:+EnableDynamicAgentLoading`), then attaches. Drives the same [AttachCommand] the CLI runs, in-process.
 */
class AttachCommandAcceptanceTest {
    private val xvfb = if (System.getenv("FXLOOP_ACCEPTANCE_HEADLESS") != "none") listOf("xvfb-run", "-a") else emptyList()

    /** One running `attach`: its output, its thread and the tool surface on its serving context. */
    private inner class Attached(val cmd: AttachCommand, val output: ByteArrayOutputStream, val thread: Thread) : AutoCloseable {
        @Volatile var exit: Int? = null
        val text: String get() = output.toString()
        val tools: ToolRegistry by lazy { registry(cmd.ctx!!) }

        fun ok(name: String, a: ObjectNode = Json.obj()): ObjectNode {
            val r = tools.call(name, a)
            assertThat(r.isError).describedAs("%s failed: %s", name, r.text).isFalse()
            return r.structured!!
        }

        fun err(name: String, a: ObjectNode = Json.obj()): ObjectNode {
            val r = tools.call(name, a)
            assertThat(r.isError).describedAs("%s should fail: %s", name, r.text).isTrue()
            return r.structured!!
        }

        /**
         * Waits for the announcement (the summary, or "Attached again" after a restart), which `attach` prints only once it
         * serves: saves and inspector requests right after it must work, so nothing here waits any longer.
         */
        fun awaitConnected(pid: Long? = null) = await({ "attach serving (exit $exit, output: $text)" }) {
            if (pid == null) "FXLoop attached to" in text else "attached to" in text.lowercase() && "(pid $pid)" in text
        }

        override fun close() {
            cmd.stop()
            thread.join(20_000)
        }
    }

    private fun attach(project: Path, home: Path, vararg args: String): Attached {
        val out = ByteArrayOutputStream()
        val cmd = AttachCommand(args.toList(), env = mapOf("FXLOOP_BROWSER" to "none"), out = PrintStream(out, true), workDir = project,
            home = home)
        lateinit var a: Attached
        val t = thread(start = false, name = "attach-command") { a.exit = cmd.run() }
        a = Attached(cmd, out, t)
        t.start()
        return a
    }

    /** Starts the app with plain `java` under xvfb-run; returns the wrapper process and the app JVM's pid. */
    private fun startApp(dir: Path, classpath: List<String>, mainClass: String, log: String, vararg jvm: String): Pair<Process, Long> {
        val cmd = xvfb + listOf(AppSession.javaExecutable(), Harness.RUN_MARKER) + jvm + listOf("-cp", classpath.joinToString(File.pathSeparator),
            mainClass, "--seed")
        val p = ProcessTree.track(ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
            .redirectOutput(dir.resolve(log).toFile()).start())
        return p to awaitJava(p)
    }

    private fun awaitJava(p: Process): Long {
        var pid: Long? = null
        await("an app JVM") {
            pid = (listOf(p.toHandle()) + p.descendants().toList()).firstOrNull { it.info().command().orElse("").endsWith("java") }?.pid()
            pid != null
        }
        return pid!!
    }

    private fun await(what: String, timeoutMs: Long = 60_000, done: () -> Boolean) = await({ what }, timeoutMs, done)

    /** Polls [done] (failures count as not yet); the timeout names [what] (evaluated then) and the last failure. */
    private fun await(what: () -> String, timeoutMs: Long = 60_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val r = runCatching(done)
            if (r.getOrDefault(false)) return
            last = r.exceptionOrNull() ?: last
            Thread.sleep(200)
        }
        throw IllegalStateException("timed out waiting for ${what()}", last)
    }

    /** `attach <pid>` wants JavaFX running in that JVM, as a developer's app would be by the time they attach. */
    private fun awaitJavaFx(pid: Long) = await("JavaFX in $pid") { JvmScan.probe(pid)?.javafx == true }

    private fun mavenApp(h: Harness): Pair<LaunchDescriptor, List<String>> {
        val d = h.ctx.session.build.prepare()
        Files.deleteIfExists(h.ctx.session.fxloopDir.resolve(LaunchDescriptor.FILE_NAME)) // attach must write its own
        return d to listOf(d.outputDir) + d.classpath
    }

    private fun styleOf(a: Attached): com.fasterxml.jackson.databind.JsonNode =
        a.ok("fx_explain_style", args("query" to "#statusLabel", "property" to "-fx-text-fill"))["items"][0]

    @Test
    fun `attach serves a plain java app like fxloop run's helper`() {
        Harness().use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val (d, cp) = mavenApp(h)
            val (app, pid) = startApp(h.project, cp, d.mainClass, "app.log")
            try {
                awaitJavaFx(pid)
                attach(h.project, home, pid.toString()).use { a ->
                    a.awaitConnected()
                    val dir = h.project.resolve("target/fxloop")
                    assertThat(Handshake.readFrom(dir.resolve("handshake.json")).pid).isEqualTo(pid)
                    val launch = LaunchDescriptor.readFrom(dir.resolve(LaunchDescriptor.FILE_NAME))
                    assertThat(launch.outputDir).isEqualTo(h.project.resolve("target/classes").toString())
                    assertThat(a.text).contains("FXLoop attached to").contains("(Maven)").contains("/inspect?t=")
                    val ctx = a.cmd.ctx!!
                    assertThat(ctx.session.status()["mode"].asText()).isEqualTo("embedded")

                    // the developer saves the stylesheet; nobody calls fx_reload
                    Files.writeString(h.project.resolve(CSS), Files.readString(h.project.resolve(CSS)) + "\n#statusLabel { -fx-text-fill: #123456; }\n")
                    await("the saved stylesheet applied") { styleOf(a)["value"].asText() == "#123456ff" }
                    assertThat(styleOf(a)["winner"]["file"].asText()).isEqualTo(CSS)
                    await("the dev bar names the tier") { ctx.live.barState()["lastReload"]["text"].asText() == "watch: css" }
                    assertThat(ctx.live.barState()["sidecar"].asBoolean()).describedAs("the fxloop:run helper's wording").isTrue()

                    h.edit(CONTROLLER) { it.replace("new Contact(nameField.getText(),", "new Contact(nameField.getText() + \"!\",") }
                    val swap = a.ok("fx_reload", args("files" to listOf(CONTROLLER)))
                    assertThat(swap["tiersApplied"].first { it["tier"].asText() == "hotswap" }["details"]["ok"].asBoolean()).isTrue()
                    a.ok("fx_set_text", args("query" to "#nameField", "text" to "Ada"))
                    val fx = a.ok("fx_click_node", args("query" to "#addBtn"))["effects"]
                    assertThat(fx["added"].any { it.path("text").asText().contains("Ada!") }).isTrue()

                    h.edit(FXML) { it.replace("promptText=\"Name\"", "promptText=\"Name or alias\"") }
                    val fxml = a.ok("fx_reload", args("files" to listOf(FXML), "allowRestart" to false))
                    assertThat(fxml["restartReasons"].toString()).contains("not in the load registry")
                    assertThat(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)).isTrue()
                    assertThat(a.err("fx_restart_app")["code"].asText()).isEqualTo("NOT_SUPERVISED")
                }
                assertThat(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)).describedAs("the app outlives attach").isTrue()
                assertThat(h.project.resolve("target/fxloop/handshake.json")).describedAs("kept while the app lives").exists()
            } finally {
                ProcessTree.kill(app)
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `an agent session joins the attached app, the helper steps aside and comes back`() {
        Harness().use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val (d, cp) = mavenApp(h)
            val (app, pid) = startApp(h.project, cp, d.mainClass, "app.log")
            try {
                awaitJavaFx(pid)
                attach(h.project, home, pid.toString(), "--once").use { a ->
                    a.awaitConnected()
                    val helper = a.cmd.ctx!!
                    // a second server (an agent session) on the same project finds the app through the handshake
                    val st = h.ok("fx_get_app_status")
                    assertThat(st["mode"].asText()).isEqualTo("embedded")
                    assertThat(st["pid"].asLong()).isEqualTo(pid)
                    assertThat(st["mainClass"].asText()).isEqualTo(d.mainClass) // the launch.json attach wrote
                    await("the helper steps aside") { !helper.session.connected }
                    Thread.sleep(3000) // a few helper rounds: the agent session keeps the app
                    assertThat(h.prop("#statusLabel", "text").asText()).isEqualTo("3 contacts")
                    h.ctx.session.shutdown()
                    await("the helper is back") { helper.session.connected }
                }
            } finally {
                ProcessTree.kill(app)
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `attach again after the handshake was deleted, and follow the app across a restart`() {
        Harness().use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val (d, cp) = mavenApp(h)
            var (app, pid) = startApp(h.project, cp, d.mainClass, "app.log")
            try {
                awaitJavaFx(pid)
                attach(h.project, home, pid.toString()).use { it.awaitConnected() }
                val handshake = h.project.resolve("target/fxloop/handshake.json")
                Files.delete(handshake) // `mvn clean` while the app runs
                attach(h.project, home, pid.toString()).use { a ->
                    a.awaitConnected()
                    assertThat(Handshake.readFrom(handshake).pid).describedAs("re-published by the running agent").isEqualTo(pid)
                    assertThat(a.ok("fx_get_property", args("query" to "#statusLabel", "name" to "text"))["value"].asText()).isEqualTo("3 contacts")

                    val viewer = a.cmd.ctx!!.viewer!!.inspectUrl()
                    ProcessTree.kill(app)
                    await("the exit noticed") { "Waiting for it to start again" in a.text }
                    val restarted = startApp(h.project, cp, d.mainClass, "app2.log")
                    app = restarted.first
                    pid = restarted.second
                    a.awaitConnected(pid)
                    assertThat(a.text).contains("Attached again to")
                    assertThat(a.cmd.ctx!!.viewer!!.inspectUrl()).isEqualTo(viewer)
                    await("the restarted app answers") {
                        a.ok("fx_get_property", args("query" to "#statusLabel", "name" to "text"))["value"].asText() == "3 contacts"
                    }
                    assertThat(a.cmd.ctx!!.session.status()["pid"].asLong()).isEqualTo(pid)
                    // save-to-apply follows the app too
                    Files.writeString(h.project.resolve(CSS), Files.readString(h.project.resolve(CSS)) + "\n#statusLabel { -fx-text-fill: #345678; }\n")
                    await("the save applied after the restart") { styleOf(a)["value"].asText() == "#345678ff" }
                }
            } finally {
                ProcessTree.kill(app)
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a JVM that forbids dynamic agents gets the explained failure`() {
        Harness().use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val (d, cp) = mavenApp(h)
            val (app, pid) = startApp(h.project, cp, d.mainClass, "app.log", "-XX:-EnableDynamicAgentLoading")
            try {
                awaitJavaFx(pid)
                val a = attach(h.project, home, pid.toString())
                a.thread.join(60_000)
                assertThat(a.exit).isEqualTo(1)
                assertThat(a.text).contains("does not allow loading an agent").contains("-XX:+EnableDynamicAgentLoading")
            } finally {
                ProcessTree.kill(app)
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `the Gradle twin started from its class and resource folders`() {
        Harness(app = "fxloop-sample-app-gradle").use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val d = h.ctx.session.build.prepare()
            Files.deleteIfExists(h.ctx.session.fxloopDir.resolve(LaunchDescriptor.FILE_NAME))
            val classes = h.project.resolve("build/classes/java/main").toString()
            val resources = h.project.resolve("build/resources/main").toString()
            assertThat(Path.of(resources)).isDirectory()
            val cp = listOf(classes, resources) + d.classpath.filter { it.endsWith(".jar") }
            val (app, pid) = startApp(h.project, cp, d.mainClass, "app.log")
            try {
                awaitJavaFx(pid)
                attach(h.project, home, pid.toString(), "--once").use { a ->
                    a.awaitConnected()
                    assertThat(a.text).contains("(Gradle)")
                    assertThat(h.project.resolve("build/fxloop/handshake.json")).exists()
                    Files.writeString(h.project.resolve(CSS), Files.readString(h.project.resolve(CSS)) + "\n#statusLabel { -fx-text-fill: #123456; }\n")
                    await("the saved stylesheet reached build/resources/main") { styleOf(a)["value"].asText() == "#123456ff" }
                    assertThat(styleOf(a)["winner"]["file"].asText()).isEqualTo(CSS)
                }
            } finally {
                ProcessTree.kill(app)
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `an app that runs from jars only is inspect-only`() {
        Harness().use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val (d, _) = mavenApp(h)
            val jarDir = Files.createTempDirectory("fxloop-attach-jars-")
            val jar = jarDir.resolve("contacts.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                val root = Path.of(d.outputDir)
                Files.walk(root).use { s ->
                    s.asSequence().filter { Files.isRegularFile(it) }.forEach { f ->
                        out.putNextEntry(ZipEntry(root.relativize(f).toString().replace('\\', '/')))
                        Files.copy(f, out)
                        out.closeEntry()
                    }
                }
            }
            val (app, pid) = startApp(jarDir, listOf(jar.toString()) + d.classpath, d.mainClass, "app.log")
            var attached: Path? = null
            try {
                awaitJavaFx(pid)
                attach(jarDir, home, pid.toString(), "--once").use { a ->
                    a.awaitConnected()
                    assertThat(a.text).contains("inspect-only").contains("jars")
                    assertThat(a.cmd.ctx!!.session.status()["mode"].asText()).isEqualTo("attached")
                    assertThat(a.ok("fx_get_property", args("query" to "#statusLabel", "name" to "text"))["value"].asText()).isEqualTo("3 contacts")
                    attached = home.resolve(".fxloop/attached/$pid")
                    assertThat(attached).isDirectory()
                    ProcessTree.kill(app)
                    a.thread.join(30_000)
                    assertThat(a.exit).describedAs("--once ends with the app").isEqualTo(0)
                }
                assertThat(attached).describedAs("removed once the app is gone").doesNotExist()
            } finally {
                ProcessTree.kill(app)
                home.toFile().deleteRecursively()
                jarDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `scan without a pid, list, disabled attach, and an edit made before attaching reloads`() {
        Harness().use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val (d, cp) = mavenApp(h)
            val (app, pid) = startApp(h.project, cp, d.mainClass, "app.log")
            try {
                awaitJavaFx(pid)
                val list = attach(h.project, home, "--list")
                list.thread.join(30_000)
                assertThat(list.exit).isEqualTo(0)
                assertThat(list.text).contains("$pid  ${d.mainClass}  ${h.project}")

                System.setProperty("fxloop.attach", "false")
                try {
                    val off = attach(h.project, home, pid.toString())
                    off.thread.join(30_000)
                    assertThat(off.exit).isEqualTo(1)
                    assertThat(off.text).contains("-Dfxloop.attach=false").doesNotContain("fxloop:run")
                } finally {
                    System.clearProperty("fxloop.attach")
                }

                // edited after the app started, before attaching: the app's start is the baseline, so it still reloads
                h.edit(CONTROLLER) { it.replace("new Contact(nameField.getText(),", "new Contact(nameField.getText() + \"#\",") }
                attach(h.project, home, "--once").use { a -> // no pid: the one app of this project is taken
                    a.awaitConnected(pid)
                    val r = a.ok("fx_reload")
                    assertThat(r["tiersApplied"].first { it["tier"].asText() == "hotswap" }["details"]["ok"].asBoolean()).isTrue()
                }
            } finally {
                ProcessTree.kill(app)
                home.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `apps that already have FXLoop are refused and their files left alone`() {
        Harness().use { h ->
            val home = Files.createTempDirectory("fxloop-attach-home-")
            val (d, cp) = mavenApp(h)
            val dir = h.ctx.session.fxloopDir
            val handshake = dir.resolve("handshake.json")
            val launch = dir.resolve(LaunchDescriptor.FILE_NAME)
            try {
                // fxloop:run: the agent from the start, its own handshake and launch.json
                d.writeTo(launch)
                val run = d.copy(jvmArgs = d.jvmArgs + Harness.RUN_MARKER)
                    .runCommand(AppSession.javaExecutable(), "${h.ctx.session.agentJar()}=${d.runAgentArgs(handshake, "on")}", listOf("--seed"))
                val p = ProcessTree.track(ProcessBuilder(xvfb + run).directory(h.project.toFile()).redirectErrorStream(true)
                    .redirectOutput(h.project.resolve("run.log").toFile()).start())
                try {
                    val pid = awaitJava(p)
                    await("its handshake") { Handshake.readFrom(handshake).pid == pid }
                    awaitJavaFx(pid)
                    val before = Files.readString(handshake) to Files.readString(launch)
                    val a = attach(h.project, home, pid.toString())
                    a.thread.join(60_000)
                    assertThat(a.exit).isEqualTo(1)
                    assertThat(a.text).contains("already has the FXLoop dev bar").contains("fxloop:run")
                    assertThat(Files.readString(handshake) to Files.readString(launch)).isEqualTo(before)
                } finally {
                    ProcessTree.kill(p)
                }

                // a supervised launch: an agent session started it
                Files.deleteIfExists(handshake)
                val pid = h.ok("fx_get_app_status")["pid"].asLong()
                val before = Files.readString(handshake) to Files.readString(launch)
                val a = attach(h.project, home, pid.toString())
                a.thread.join(60_000)
                assertThat(a.exit).isEqualTo(1)
                assertThat(a.text).contains("agent session already runs")
                assertThat(Files.readString(handshake) to Files.readString(launch)).isEqualTo(before)
                assertThat(h.ok("fx_get_app_status")["fcpConnected"].asBoolean()).describedAs("the session still has it").isTrue()
                h.ok("fx_stop_app")

                // another attach already serves the app
                val (app, appPid) = startApp(h.project, cp, d.mainClass, "app.log")
                try {
                    awaitJavaFx(appPid)
                    attach(h.project, home, appPid.toString()).use { first ->
                        first.awaitConnected()
                        val second = attach(h.project, home, appPid.toString())
                        second.thread.join(60_000)
                        assertThat(second.exit).isEqualTo(1)
                        assertThat(second.text).contains("Another attach")
                        assertThat(Handshake.readFrom(handshake).pid).isEqualTo(appPid)
                        assertThat(first.ok("fx_get_property", args("query" to "#statusLabel", "name" to "text"))["value"].asText())
                            .isEqualTo("3 contacts")
                    }
                } finally {
                    ProcessTree.kill(app)
                }
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }
}

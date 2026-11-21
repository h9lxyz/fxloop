package org.lab59.fxloop.server.attach

import com.sun.tools.attach.VirtualMachine
import org.lab59.fxloop.launch.LaunchDescriptor
import org.lab59.fxloop.protocol.Handshake
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.protocol.OwnerOnly
import org.lab59.fxloop.server.Config
import org.lab59.fxloop.server.InspectorHelper
import org.lab59.fxloop.server.app.AppSession
import org.lab59.fxloop.server.tools.Ctx
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * `java -jar fxloop-server.jar attach [<pid>] [--list] [--once] [--key=value…]`: the dev bar, CSS inspector and
 * save-to-apply for a JavaFX app the developer started themselves (IDE Run button, `mvn javafx:run`, plain `java`),
 * without build-file edits. Finds the app, loads the agent into it through the Attach API, infers its project from the
 * class path (no Maven or Gradle run: the app is built and running) and then serves exactly like the helper `fxloop:run`
 * starts ([InspectorHelper], `sidecar`), so a coding-agent session can join the same window. Follows the app across
 * restarts unless `--once`.
 *
 * In-process friendly: [Config] input, output and console are injected and nothing here calls `exitProcess`; [run]
 * returns the exit code (0 normal end, 1 failure, 2 several apps and no console to ask).
 */
class AttachCommand(
    private val args: List<String>,
    private val env: Map<String, String> = System.getenv(),
    private val out: PrintStream = System.out,
    /** Where to ask which app when several run; null = no interactive console. */
    private val console: BufferedReader? = null,
    private val workDir: Path = Path.of(System.getProperty("user.dir")),
    private val home: Path = Path.of(System.getProperty("user.home")),
) {
    private val audit = LoggerFactory.getLogger("org.lab59.fxloop.audit")
    private val log = LoggerFactory.getLogger(AttachCommand::class.java)

    /** The serving context, once the first attach got that far (same one across app restarts). */
    @Volatile var ctx: Ctx? = null
        private set

    /** The app JVM currently served (0 before the first attach). */
    @Volatile var pid: Long = 0
        private set

    @Volatile private var stopping = false
    private var finished = false
    private var inspectOnlyDir: Path? = null
    @Volatile private var handshakeFile: Path? = null

    /** Who serves the app, recorded next to its handshake ([OWNER_FILE]): this process, and this command within it. */
    private val me = ProcessHandle.current().pid()
    private val id = java.util.UUID.randomUUID().toString()

    /** Ends [run] at its next check (what Ctrl+C does through the shutdown hook). */
    fun stop() {
        stopping = true
    }

    data class Args(val pid: Long?, val list: Boolean, val once: Boolean, val config: List<String>)

    fun run(): Int = try {
        attachAndServe()
    } catch (e: AttachException) {
        out.println(e.message)
        e.exitCode
    } catch (e: Stopped) {
        0
    } catch (e: Exception) { // a connect or I/O failure: one line for the developer, the trace for FXLOOP_LOG=debug
        log.debug("attach failed", e)
        out.println("attach failed: ${e.message ?: e.javaClass.simpleName}")
        1
    } finally {
        finish()
    }

    /** Ctrl+C (or [stop]) while waiting: end quietly with exit code 0. */
    private class Stopped : RuntimeException()

    /** The app's JVM died while the agent was being loaded or was starting: while following restarts, wait for the next one. */
    private class AppGone(message: String) : RuntimeException(message)

    private fun attachAndServe(): Int {
        val a = parse(args)
        if (!Config.attachAllowed) throw AttachException("attach is disabled: this FXLoop was started with -Dfxloop.attach=false.")
        val explicit = explicitProjectDir(a)
        if (a.list) {
            listApps()
            return 0
        }
        var target = pick(a, explicit)
        var first = true
        while (true) {
            val served = try {
                attach(target, explicit, a, first)
            } catch (e: AppGone) {
                if (first) throw AttachException(e.message.orEmpty())
                null
            }
            if (served != null) {
                val announce = served.announce
                val end = InspectorHelper.serve(served.ctx, served.handshake, served.mode, served.descriptor,
                    System.currentTimeMillis() + HANDSHAKE_WAIT_MS, stopped = { stopping }, appPid = target.facts.pid,
                    republish = { loadAgent(target.facts, served.handshake, served.ctx.config) }, onReady = announce)
                if (end == InspectorHelper.End.STOPPED) return 0
                first = false
                deleteHandshakeIfDead()
            }
            if (a.once) {
                out.println("${target.facts.displayName} exited.")
                return 0
            }
            out.println("${target.facts.displayName} exited. Waiting for it to start again, Ctrl+C to stop.")
            target = awaitRestart(target, explicit) ?: return 0
        }
    }

    private class Served(val ctx: Ctx, val handshake: Path, val mode: AppSession.Mode, val descriptor: LaunchDescriptor?,
                         val announce: () -> Unit)

    /**
     * Loads the agent into [c]'s JVM (or reuses the handshake an earlier `attach` left there) and prepares serving it.
     * Never touches the files of an app that has FXLoop through someone else: a supervised launch, `fxloop:run` or
     * `FxLoopDev.install()`, or another `attach` that serves it.
     */
    private fun attach(c: AttachInference.Candidate, explicit: Path?, a: Args, first: Boolean): Served {
        val facts = c.facts
        val inferred = AttachInference.infer(facts, explicit)
        val project = inferred.project
        val ctx = ctx ?: Ctx(config(project, facts.pid, a)).also { ctx = it }
        val handshake = ctx.session.fxloopDir.resolve("handshake.json")
        handshakeFile = handshake
        if (',' in handshake.toString()) {
            throw AttachException("FXLoop cannot attach with a project path that contains a comma ($handshake): the agent " +
                "splits its arguments on commas. Move or link the project to a path without one, or use fxloop:run.")
        }
        Files.createDirectories(handshake.parent)
        audit.info("AUDIT attach requested pid={} by attach command", facts.pid)
        val existing = runCatching { Handshake.readFrom(handshake) }.getOrNull()
        if (existing != null && alive(existing.pid)) {
            if (existing.pid != facts.pid) {
                throw AttachException("${facts.displayName} (pid ${facts.pid}) cannot be attached: $handshake belongs to another running " +
                    "app (pid ${existing.pid}) of the same project. Stop that one, or attach to it instead: attach ${existing.pid}")
            }
            refuseIfTaken(existing, facts, handshake)
        } else {
            if (existing != null) Files.deleteIfExists(handshake) // its JVM is dead: a stale file
            loadAgent(facts, handshake, ctx.config)
            awaitHandshake(handshake, facts, if (first) System.currentTimeMillis() + HANDSHAKE_WAIT_MS else null)
            val hs = Handshake.readFrom(handshake)
            try {
                refuseIfTaken(hs, facts, handshake)
            } catch (e: AttachException) {
                // the copy our loadAgent asked for: the app keeps its own handshake where it already had one
                runCatching { if (Handshake.readFrom(handshake).pid == facts.pid) Files.deleteIfExists(handshake) }
                throw e
            }
        }
        OwnerOnly.write(handshake.resolveSibling(OWNER_FILE), Json.write(Json.obj { put("pid", me); put("id", id) }))
        val descriptor = project?.let { AttachInference.descriptor(facts, it) }
        descriptor?.writeTo(handshake.resolveSibling(LaunchDescriptor.FILE_NAME))
        pid = facts.pid
        audit.info("AUDIT attached pid={}", facts.pid)
        val viewer = ctx.ensureViewer(ctx.config.viewerPort ?: 0)
        // announced once connected and watching (InspectorHelper.onReady): a save or inspector request right after the
        // URL appears must already work
        val announce = {
            if (first) out.print(summary(facts, project, inferred.whyNone, viewer.inspectUrl(), ctx.config.watchMode))
            else out.println("Attached again to ${facts.displayName} (pid ${facts.pid}).")
            out.flush()
        }
        return Served(ctx, handshake, if (project != null) AppSession.Mode.EMBEDDED else AppSession.Mode.ATTACHED, descriptor, announce)
    }

    /** Refuses an app that already has FXLoop through its own helper or session. */
    private fun refuseIfTaken(hs: Handshake, facts: JvmFacts, handshake: Path) {
        val app = "${facts.displayName} (pid ${facts.pid})"
        if (hs.token == null) {
            throw AttachException("An FXLoop agent session already runs $app: it started the app, so its dev bar and inspector are already there.")
        }
        if (hs.mode != "attach") {
            throw AttachException("$app already has the FXLoop dev bar and inspector: it was started with fxloop:run / fxloopRun or calls " +
                "FxLoopDev.install(), which run their own inspector helper. Use Open CSS inspector in its dev bar.")
        }
        val owner = runCatching { Json.parse(Files.readString(handshake.resolveSibling(OWNER_FILE))) }.getOrNull()
        val ownerPid = owner?.path("pid")?.asLong(0) ?: 0
        if (owner != null && alive(ownerPid) && !(ownerPid == me && owner.path("id").asText() == id)) {
            throw AttachException("Another attach (pid $ownerPid) already serves $app. Use its inspector, or stop it (Ctrl+C) first.")
        }
    }

    private fun alive(pid: Long): Boolean = pid > 0 && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /** The server config for serving this app: the `fxloop:run` helper's settings, with the project's `fxloop.properties`. */
    private fun config(project: AttachProject?, pid: Long, a: Args): Config {
        val dir = project?.projectDir ?: home.resolve(".fxloop").resolve("attached").resolve(pid.toString()).also { inspectOnlyDir = it }
        val base = Config.load(env, a.config.filterNot { it.startsWith("--project-dir=") }.toTypedArray(), project?.projectDir ?: workDir)
        val editOff = raw("inspector-edit", a, project?.projectDir)?.lowercase() in setOf("false", "0", "no")
        return base.copy(projectDir = dir, sidecar = true, bar = if (base.bar == "auto") "on" else base.bar, inspectorEdit = !editOff,
            headless = null)
    }

    /** A setting as given (flag, `FXLOOP_*`, then the project's `fxloop.properties`), to tell "unset" from a default. */
    private fun raw(flag: String, a: Args, projectDir: Path?): String? {
        a.config.lastOrNull { it.startsWith("--$flag=") }?.let { return it.substringAfter('=') }
        env["FXLOOP_" + flag.uppercase().replace('-', '_')]?.let { return it }
        val file = listOfNotNull(projectDir, workDir).map { it.resolve(Config.PROJECT_FILE) }.firstOrNull { Files.isRegularFile(it) } ?: return null
        return runCatching { Properties().apply { Files.newBufferedReader(file).use(::load) }.getProperty(flag) }.getOrNull()
    }

    private fun explicitProjectDir(a: Args): Path? =
        (a.config.lastOrNull { it.startsWith("--project-dir=") }?.substringAfter('=') ?: env["FXLOOP_PROJECT_DIR"])
            ?.takeIf { it.isNotBlank() }?.let { workDir.resolve(it.trim()).toAbsolutePath().normalize() }

    private fun listApps() {
        val apps = JvmScan.javafxApps()
        if (apps.isEmpty()) {
            out.println("No running JavaFX app found.")
            return
        }
        apps.forEach { out.println(line(AttachInference.Candidate(it, inferQuietly(it)))) }
    }

    private fun inferQuietly(f: JvmFacts): AttachProject? = runCatching { AttachInference.infer(f).project }.getOrNull()

    private fun pick(a: Args, explicit: Path?): AttachInference.Candidate {
        a.pid?.let { given ->
            val p = jvmOf(given)
            val f = JvmScan.probe(p) ?: throw AttachException("Cannot read Java process $p: it is not running, belongs to another user, " +
                "does not allow attaching (-XX:+DisableAttachMechanism), or is not responding (suspended in a debugger?).")
            if (!f.javafx) throw AttachException("JavaFX is not running in process $p (${f.displayName}).")
            return AttachInference.Candidate(f, inferQuietly(f))
        }
        val apps = JvmScan.javafxApps()
        if (apps.isEmpty()) {
            throw AttachException("No running JavaFX app found. Start your app (IDE Run button, mvn javafx:run, java …), then run attach again.")
        }
        val here = explicit ?: AttachInference.nearestBuildDir(workDir.toAbsolutePath().normalize())
        return pickFrom(apps.map { AttachInference.Candidate(it, inferQuietly(it)) }, here)
    }

    /**
     * [pid] itself when it is a JVM; else the one JVM among its descendants: a wrapper such as `xvfb-run` or a launcher
     * script (the pid an agent session reports for an app it started headless is the wrapper's).
     */
    private fun jvmOf(pid: Long): Long {
        val jvms = JvmScan.list().map { it.pid }.toSet()
        if (pid in jvms) return pid
        val inside = ProcessHandle.of(pid).map { h -> h.descendants().map { it.pid() }.filter { it in jvms }.toList() }.orElse(emptyList())
        return inside.singleOrNull()?.also { out.println("Process $pid is not a JVM; using the Java process it started, $it.") } ?: pid
    }

    /** [AttachInference.choose], else a numbered list: answered on the console, or exit code 2 without one. */
    internal fun pickFrom(candidates: List<AttachInference.Candidate>, here: Path?): AttachInference.Candidate {
        AttachInference.choose(candidates, here)?.let { return it }
        val list = candidates.mapIndexed { i, c -> "  ${i + 1}) ${line(c)}" }.joinToString("\n")
        if (console == null) throw AttachException("Several JavaFX apps are running:\n$list\nPass the pid of the one to attach to: attach <pid>", 2)
        out.println("Several JavaFX apps are running:\n$list")
        out.print("Attach to which one (1-${candidates.size})? ")
        out.flush()
        val n = console.readLine()?.trim()?.toIntOrNull()
        return candidates.getOrNull((n ?: 0) - 1) ?: throw AttachException("No app chosen.")
    }

    private fun liveHandshake(file: Path, pid: Long): Handshake? = runCatching { Handshake.readFrom(file) }.getOrNull()
        ?.takeIf { it.pid == pid && alive(pid) }

    private var warnedAboutAgentWarning = false

    private fun loadAgent(facts: JvmFacts, handshake: Path, config: Config) {
        val jar = AgentJarCache.path(config.agentJar, AgentJarCache.defaultDir(home = home))
        val agentArgs = "handshake=$handshake,logs=true,bar=on,inspectorEdit=${config.inspectorEdit}"
        try {
            val vm = VirtualMachine.attach(facts.pid.toString())
            try {
                vm.loadAgent(jar.toString(), agentArgs)
            } finally {
                runCatching { vm.detach() }
            }
        } catch (e: Exception) {
            if (!alive(facts.pid)) throw AppGone("${facts.displayName} exited while attaching.")
            throw AttachException(loadFailure(facts, e.message.orEmpty()))
        }
        if (!warnedAboutAgentWarning && facts.javaRelease >= 21 && DYNAMIC_AGENT_FLAG !in facts.jvmArgs) {
            warnedAboutAgentWarning = true
            out.println("Note: your app's console now shows a JDK warning about a dynamically loaded agent. That is expected; " +
                "starting the app with $DYNAMIC_AGENT_FLAG silences it.")
        }
    }

    /**
     * Waits for the agent's handshake. [deadline] null (following a restart): as long as the JVM lives, since the agent
     * waits for a JavaFX toolkit that a slow app starts late.
     */
    private fun awaitHandshake(file: Path, facts: JvmFacts, deadline: Long?) {
        while (deadline == null || System.currentTimeMillis() < deadline) {
            if (stopping) throw Stopped()
            if (liveHandshake(file, facts.pid) != null) return
            if (!alive(facts.pid)) throw AppGone("${facts.displayName} exited while attaching.")
            Thread.sleep(100)
        }
        throw AttachException("The FXLoop agent was loaded into ${facts.displayName} (pid ${facts.pid}) but did not report back within " +
            "${HANDSHAKE_WAIT_MS / 1000} s. Does the app start JavaFX? Run with FXLOOP_LOG=info for details.")
    }

    /** Polls about twice a second for the same app in a new JVM (same main class and project). Null when stopped. */
    private fun awaitRestart(before: AttachInference.Candidate, explicit: Path?): AttachInference.Candidate? {
        val ruledOut = HashSet<Long>().apply { add(before.facts.pid) }
        val timedOut = HashSet<Long>() // a probe that hung may still hang: do not start another thread for it every poll
        while (!stopping) {
            val listed = JvmScan.list().filter { it.pid !in ruledOut && it.pid !in timedOut && it.mainClass == before.facts.mainClass }
            for (f in JvmScan.probeAll(listed.map { it.pid }, timedOut = timedOut)) {
                // JavaFX may not run yet in the new JVM: the agent waits for its toolkit
                val project = runCatching { AttachInference.infer(f, explicit).project }.getOrNull()
                val now = AttachInference.Candidate(f, project)
                if (AttachInference.sameApp(before, now)) return now
                ruledOut += f.pid
            }
            Thread.sleep(500)
        }
        return null
    }

    /** Deletes the handshake only when it names the app this command attached and that app's JVM is dead. */
    private fun deleteHandshakeIfDead() {
        val f = handshakeFile ?: return
        val p = pid
        if (p == 0L || alive(p)) return
        runCatching { if (Handshake.readFrom(f).pid == p) Files.deleteIfExists(f) }
    }

    /**
     * Ctrl+C or the end of [run]: stop serving. The app keeps running with the agent and its standalone dev bar; its
     * handshake stays while its JVM lives, so a later `attach` reuses it. Only files this command owns are removed.
     */
    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        stopping = true
        val c = ctx ?: return
        runCatching { c.shutdown() }
        val f = handshakeFile ?: return
        runCatching {
            val owner = f.resolveSibling(OWNER_FILE)
            val o = Json.parse(Files.readString(owner))
            if (o.path("pid").asLong() == me && o.path("id").asText() == id) Files.deleteIfExists(owner)
        }
        deleteHandshakeIfDead()
        val dir = inspectOnlyDir ?: return
        // inspect-only: the per-pid folder goes once nothing in it belongs to a live app or another attach
        val liveHandshake = runCatching { alive(Handshake.readFrom(f).pid) }.getOrDefault(false)
        if (!liveHandshake && !Files.exists(f.resolveSibling(OWNER_FILE))) runCatching { dir.toFile().deleteRecursively() }
    }

    companion object {
        const val HANDSHAKE_WAIT_MS = 20_000L
        const val DYNAMIC_AGENT_FLAG = "-XX:+EnableDynamicAgentLoading"

        /** Owner-only record of the `attach` serving an app (`{pid, id}`), next to its handshake: a second `attach` refuses. */
        const val OWNER_FILE = "attach-owner.json"

        /** CLI entry (`Main.kt`): a shutdown hook turns Ctrl+C into [finish]. */
        fun main(args: List<String>): Int {
            val cmd = AttachCommand(args, console = terminal()?.let { BufferedReader(it.reader()) })
            Runtime.getRuntime().addShutdownHook(Thread { cmd.finish() })
            return cmd.run()
        }

        /**
         * The console when stdin and stdout are a terminal. From JDK 22 `System.console()` exists even with redirected
         * streams; `Console.isTerminal()` (22+, called reflectively: this server runs on 21) tells.
         */
        private fun terminal(): java.io.Console? {
            val c = System.console() ?: return null
            return runCatching { c.javaClass.getMethod("isTerminal").invoke(c) as Boolean }.getOrDefault(true).let { if (it) c else null }
        }

        /**
         * `<pid>` is the first all-digit argument; `--list` and `--once` are the command's own flags; every `--key=value`
         * goes to [Config.load]. Anything else is a mistake worth saying so.
         */
        fun parse(args: List<String>): Args {
            var pid: Long? = null
            val config = ArrayList<String>()
            for (arg in args) {
                when {
                    arg == "--list" || arg == "--once" -> Unit
                    pid == null && arg.isNotEmpty() && arg.all(Char::isDigit) -> pid = arg.toLongOrNull()
                        ?: throw AttachException("not a process id: $arg")
                    arg.startsWith("--") && '=' in arg -> config += arg
                    else -> throw AttachException("attach: unknown argument '$arg'. Usage: attach [<pid>] [--list] [--once] [--key=value …]")
                }
            }
            return Args(pid, "--list" in args, "--once" in args, config)
        }

        /** Why the agent could not be loaded, in the developer's terms. */
        fun loadFailure(facts: JvmFacts, message: String): String = when {
            "Dynamic agent loading is not enabled" in message ->
                "${facts.displayName} (pid ${facts.pid}) does not allow loading an agent while it runs (it was started with " +
                    "-XX:-EnableDynamicAgentLoading, or its JDK no longer allows it by default). Start the app with " +
                    "$DYNAMIC_AGENT_FLAG and run attach again."
            else -> "Cannot load the FXLoop agent into ${facts.displayName} (pid ${facts.pid}): $message\nAttach may be disabled in " +
                "that JVM (-XX:+DisableAttachMechanism), it may belong to another user, or its runtime lacks the java.instrument module."
        }

        /** One line per app for `--list` and the choice: pid, main class, project dir or why there is none. */
        fun line(c: AttachInference.Candidate): String =
            "${c.facts.pid}  ${c.facts.mainClass ?: c.facts.displayName}  ${c.project?.projectDir ?: "(no project: runs from jars or outside a build)"}"

        /** What `attach` prints once it serves the app. */
        fun summary(facts: JvmFacts, project: AttachProject?, whyNone: String?, inspectUrl: String, watch: String): String = buildString {
            val inspector = "  Inspector  $inspectUrl  (or click Open CSS inspector in the dev bar)"
            if (project == null) {
                appendLine("FXLoop attached to ${facts.displayName} (pid ${facts.pid}), inspect-only.")
                appendLine("  Project    none found: ${whyNone ?: "no project"}")
                appendLine(inspector)
                appendLine("  Live       picking, inspecting and trying CSS live work; nothing is saved to files")
            } else {
                appendLine("FXLoop attached to ${facts.displayName} (pid ${facts.pid}).")
                appendLine("  Project    ${project.projectDir} (${project.buildToolName})")
                appendLine(inspector)
                appendLine("  Live       " + when (watch) {
                    "all" -> "saving a stylesheet applies it at once, a Java method body is hot-swapped"
                    "off" -> "saving applies nothing (--watch=off); --watch=css-only applies stylesheets"
                    else -> "saving a stylesheet applies it at once; --watch=all also hot-swaps Java method bodies"
                })
                appendLine("  Limits     restart the app yourself; FXML views that are already open reload only after that restart")
            }
            appendLine("Ctrl+C stops FXLoop. Your app keeps running.")
        }
    }
}

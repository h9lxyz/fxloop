package org.lab59.fxloop.server.cli

import com.fasterxml.jackson.databind.ObjectMapper
import org.lab59.fxloop.launch.JavaFxModules
import org.lab59.fxloop.launch.LaunchMode
import org.lab59.fxloop.launch.MainClassException
import org.lab59.fxloop.launch.MainClassResolver
import org.lab59.fxloop.launch.MainClassStep
import org.lab59.fxloop.server.Config
import org.lab59.fxloop.server.app.BuildTool
import org.lab59.fxloop.server.app.Versions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/** Verdict of one check. Serialized lower-case in `--json`. */
enum class DoctorStatus(val icon: String) {
    ok("✅"), warn("⚠️"), fail("❌");

    /** The symbol on a UTF-8 terminal, else `OK` / `WARN` / `FAIL` (a POSIX locale over SSH prints the symbols as `?`). */
    fun marker(utf8: Boolean): String = if (utf8) icon else name.uppercase()
}

/** One `doctor` line: stable `id`, a one-line [summary], an optional one-line [fix], optional machine [data]. */
data class DoctorCheck(
    val id: String,
    val status: DoctorStatus,
    val summary: String,
    val fix: String? = null,
    val data: Map<String, Any?>? = null,
)

/** The running JVM and the JDK environment, as facts (so verdicts are pure functions). */
data class JdkInfo(
    val version: String,
    val feature: Int,
    val vendor: String,
    val runtimeName: String,
    val vmName: String,
    val runningHome: String,
    val hasCompiler: Boolean,
    val javaHomeEnv: String?,
    /** JDK home of the `java` found on PATH, when it can be resolved. */
    val pathJavaHome: String?,
) {
    val jetBrainsRuntime: Boolean
        get() = listOf(vendor, runtimeName, vmName).any { it.contains("JetBrains", true) || it.contains("JBR") }
}

/** Everything `doctor` reads from the machine; replaceable in tests. */
class DoctorEnv(
    val os: String,
    val env: Map<String, String>,
    val onPath: (String) -> Path?,
    val home: Path,
    val workDir: Path,
    val jdk: JdkInfo,
    /** The running server jar, or null when not running from a jar (tests, IDE). */
    val serverJar: Path?,
) {
    val linux: Boolean get() = os.startsWith("Linux", true)
    val windows: Boolean get() = os.startsWith("Windows", true)

    companion object {
        fun system(): DoctorEnv {
            val env = System.getenv()
            val onPath = { name: String -> findOnPath(name, env["PATH"], System.getProperty("os.name").startsWith("Windows")) }
            val jh = System.getProperty("java.home")
            val running = real(Path.of(jh))
            val hasCompiler = ToolProvider.getSystemJavaCompiler() != null ||
                listOf("javac", "javac.exe").any { Files.exists(Path.of(jh, "bin", it)) }
            val pathHome = onPath("java")?.let { real(it) }?.parent?.parent
            val jar = runCatching { Path.of(Doctor::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
                ?.takeIf { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
            return DoctorEnv(
                os = System.getProperty("os.name"), env = env, onPath = onPath,
                home = Path.of(System.getProperty("user.home")), workDir = Path.of(System.getProperty("user.dir")),
                jdk = JdkInfo(
                    version = System.getProperty("java.version"), feature = Runtime.version().feature(),
                    vendor = System.getProperty("java.vendor", ""), runtimeName = System.getProperty("java.runtime.name", ""),
                    vmName = System.getProperty("java.vm.name", ""), runningHome = running.toString(), hasCompiler = hasCompiler,
                    javaHomeEnv = env["JAVA_HOME"]?.takeIf { it.isNotBlank() }, pathJavaHome = pathHome?.toString(),
                ),
                serverJar = jar?.toAbsolutePath(),
            )
        }

        private fun real(p: Path): Path = runCatching { p.toRealPath() }.getOrDefault(p)

        fun findOnPath(name: String, path: String?, windows: Boolean): Path? {
            val exts = if (windows) listOf("", ".exe", ".cmd", ".bat") else listOf("")
            return path.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.firstNotNullOfOrNull { dir ->
                exts.firstNotNullOfOrNull { e -> runCatching { Path.of(dir, name + e) }.getOrNull()?.takeIf { Files.isRegularFile(it) } }
            }
        }
    }
}

/**
 * `java -jar fxloop-server.jar doctor [--json] [--client=<name>|all]` (PRD 11 §6): seven read-only, static checks of
 * the project in the working directory. Never writes, never runs a build. [run] returns the structured result so
 * `fx_get_app_status` can include a summary.
 */
object Doctor {
    private val mapper = ObjectMapper()

    /** CLI entry (arguments after `doctor`); returns the exit code: 0 when no check failed, else 1. */
    fun main(args: List<String>): Int {
        val config = try {
            Config.load(System.getenv(), args.toTypedArray())
        } catch (e: RuntimeException) {
            System.err.println("doctor: cannot read configuration: ${e.message}")
            return 1
        }
        val client = args.firstOrNull { it.startsWith("--client=") }?.substringAfter('=')
        val checks = run(config, DoctorEnv.system(), client)
        if ("--json" in args) {
            // JSON is for tools: always UTF-8, whatever the console encoding
            val out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
            out.println(toJson(checks))
            out.flush()
        } else {
            println(render(config, checks))
        }
        return if (checks.any { it.status == DoctorStatus.fail }) 1 else 0
    }

    /** The seven checks in PRD order. [client] is `all` or one client id for which the config snippet is added. */
    fun run(config: Config, env: DoctorEnv = DoctorEnv.system(), client: String? = null): List<DoctorCheck> {
        val dir = config.projectDir
        val pomFile = dir.resolve("pom.xml")
        val kind = BuildTool.detect(config)
        val pom = if (kind == BuildTool.Kind.MAVEN && Files.isRegularFile(pomFile)) PomModel.load(pomFile) else null
        val gradle = if (kind == BuildTool.Kind.GRADLE) gradleFacts(dir) else null
        return listOf(
            jdkCheck(env.jdk),
            buildToolCheck(config, kind, env),
            javafxCheck(config, kind, pom, gradle),
            mainClassCheck(config, kind, pom, gradle),
            pluginCheck(kind, pom, gradle),
            displayCheck(config, env),
            clientCheck(config, env, client),
        )
    }

    /** One line for `fx_get_app_status`: `doctor: 5 ok, 1 warn, 1 fail (jdk: …)`. */
    fun summary(checks: List<DoctorCheck>): String {
        val n = DoctorStatus.entries.joinToString(", ") { s -> "${checks.count { it.status == s }} ${s.name}" }
        val bad = checks.filter { it.status != DoctorStatus.ok }.joinToString("; ") { "${it.id}: ${it.summary}" }
        return "doctor: $n" + if (bad.isEmpty()) "" else " ($bad)"
    }

    // ---- 1 JDK ----------------------------------------------------------------------------------------------------

    /** Pure verdict for the JDK facts. */
    fun jdkCheck(j: JdkInfo): DoctorCheck {
        val name = "JDK ${j.version}" + (j.vendor.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            if (j.jetBrainsRuntime) ", JetBrains Runtime (enhanced hot-swap)" else ""
        val data = mapOf("version" to j.version, "vendor" to j.vendor, "home" to j.runningHome, "javaHome" to j.javaHomeEnv,
            "pathJavaHome" to j.pathJavaHome, "jdk" to j.hasCompiler, "jetBrainsRuntime" to j.jetBrainsRuntime)
        JdkCheck.problem(j.feature, j.hasCompiler)?.let { fix ->
            val why = if (j.feature < JdkCheck.MIN_FEATURE) "is too old" else "is a JRE (no javac)"
            return DoctorCheck("jdk", DoctorStatus.fail, "$name $why", fix.substringAfter(": "), data)
        }
        val a = j.javaHomeEnv?.let(::realOf)
        val b = j.pathJavaHome?.let(::realOf)
        if (a != null && b != null && a != b) return DoctorCheck("jdk", DoctorStatus.warn,
            "$name; JAVA_HOME ($a) differs from the java on PATH ($b)",
            "make them the same JDK: export JAVA_HOME=$b (or put \$JAVA_HOME/bin first on PATH)", data)
        return DoctorCheck("jdk", DoctorStatus.ok, name + if (j.javaHomeEnv == null) "; JAVA_HOME unset, builds use this JDK" else "", data = data)
    }

    private fun realOf(s: String): String = runCatching { Path.of(s).toRealPath().toString() }.getOrDefault(s)

    // ---- 2 build tool ---------------------------------------------------------------------------------------------

    private fun buildToolCheck(c: Config, kind: BuildTool.Kind, env: DoctorEnv): DoctorCheck {
        val dir = c.projectDir
        val buildFiles = listOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts").filter { Files.exists(dir.resolve(it)) }
        if (buildFiles.isEmpty() && c.buildTool == null) return DoctorCheck("build-tool", DoctorStatus.fail,
            "no pom.xml or build.gradle(.kts) in $dir",
            "run doctor in the project directory, or set project-dir=<module> in fxloop.properties")
        val label = if (kind == BuildTool.Kind.GRADLE) "Gradle" else "Maven"
        val data = mapOf("tool" to label.lowercase(), "buildFiles" to buildFiles)
        c.buildCmd?.let { return DoctorCheck("build-tool", DoctorStatus.ok, "$label; custom build command '$it'", data = data) }
        val wrapper = if (kind == BuildTool.Kind.GRADLE) (if (env.windows) "gradlew.bat" else "gradlew") else (if (env.windows) "mvnw.cmd" else "mvnw")
        val found = generateSequence(dir) { it.parent }.map { it.resolve(wrapper) }.firstOrNull(Files::isRegularFile)
        if (found != null) {
            val where = if (found.parent == dir) "in the project" else "in ${found.parent}"
            return if (!env.windows && !Files.isExecutable(found)) DoctorCheck("build-tool", DoctorStatus.warn, "$label; wrapper $wrapper $where is not executable",
                "chmod +x $found", data + ("wrapper" to found.toString()))
            else DoctorCheck("build-tool", DoctorStatus.ok, "$label; wrapper $wrapper $where", data = data + ("wrapper" to found.toString()))
        }
        val bare = if (kind == BuildTool.Kind.GRADLE) "gradle" else "mvn"
        env.onPath(bare)?.let { return DoctorCheck("build-tool", DoctorStatus.ok, "$label; no wrapper, using $bare on PATH ($it)", data = data + ("path" to it.toString())) }
        return DoctorCheck("build-tool", DoctorStatus.fail, "$label project but no $wrapper wrapper and no $bare on PATH",
            if (kind == BuildTool.Kind.GRADLE) "add a wrapper (gradle wrapper) or install Gradle" else "add a wrapper (mvn wrapper:wrapper) or install Maven", data)
    }

    // ---- 3 JavaFX -------------------------------------------------------------------------------------------------

    private fun javafxCheck(c: Config, kind: BuildTool.Kind, pom: PomModel?, gradle: GradleFacts?): DoctorCheck {
        val dir = c.projectDir
        if (kind == BuildTool.Kind.MAVEN && pom == null) return DoctorCheck("javafx", DoctorStatus.warn, "cannot read pom.xml in $dir",
            "fix the pom.xml syntax, or run doctor in the module directory")
        val artifacts: List<Pair<String, String?>> = when {
            pom != null -> pom.dependencies.filter { it.groupId == JavaFxModules.GROUP_ID && JavaFxModules.moduleName(it.artifactId) != null }
                .map { it.artifactId to it.version }
            gradle != null -> gradle.javafxArtifacts.filter { JavaFxModules.moduleName(it.first) != null }
            else -> emptyList()
        }
        val modules = (JavaFxModules.moduleNames(artifacts.map { it.first }) + gradle?.javafxPluginModules.orEmpty()).distinct()
        val version = artifacts.firstNotNullOfOrNull { it.second }
            ?: pom?.properties?.let { it["javafx.version"] ?: it["openjfx.version"] } ?: gradle?.javafxPluginVersion
        val moduleInfo = dir.resolve("src/main/java/module-info.java")
        val moduleName = if (Files.isRegularFile(moduleInfo)) runCatching { LaunchMode.parseModuleName(Files.readString(moduleInfo)) }.getOrNull() else null
        val requested = pom?.let { p -> p.properties["fxloop.launchMode"] ?: p.plugin("fxloop-maven-plugin")?.descendant("launchMode") }
            ?: gradle?.let { Regex("""launchMode\s*(?:=|\.set\()\s*["'](\w+)["']""").find(it.text)?.groupValues?.get(1) }
        val mode = try {
            LaunchMode.resolve(requested?.let { pom?.resolve(it) ?: it }, moduleName != null || Files.isRegularFile(moduleInfo))
        } catch (e: IllegalArgumentException) {
            return DoctorCheck("javafx", DoctorStatus.fail, e.message ?: "invalid launch mode", "use fxloop.launchMode = auto, classpath or modulepath")
        }
        val data = mapOf("modules" to modules, "version" to version, "moduleInfo" to Files.isRegularFile(moduleInfo), "moduleName" to moduleName,
            "launchMode" to mode)
        if (modules.isEmpty() && gradle?.javafxPlugin != true) {
            val agg = pom?.hasModules == true
            return DoctorCheck("javafx", DoctorStatus.warn, "no javafx-* dependency found in the build file" + if (agg) " (aggregator pom)" else "",
                if (agg) "run doctor in the app module, or set project-dir=<module> in fxloop.properties"
                else "add org.openjfx:javafx-controls (ignore this if a parent pom or BOM supplies JavaFX)", data)
        }
        val summary = (if (modules.isEmpty()) "JavaFX Gradle plugin" else modules.joinToString(", ") { it.replace('.', '-') }) +
            (version?.let { " $it" } ?: "") +
            "; " + (if (Files.isRegularFile(moduleInfo)) "module-info.java" + (moduleName?.let { " ($it)" } ?: "") else "no module-info.java") +
            " -> launch mode $mode" + if (requested != null) " (set explicitly)" else ""
        return DoctorCheck("javafx", DoctorStatus.ok, summary, data = data)
    }

    // ---- 4 main class ---------------------------------------------------------------------------------------------

    private fun mainClassCheck(c: Config, kind: BuildTool.Kind, pom: PomModel?, gradle: GradleFacts?): DoctorCheck {
        val dir = c.projectDir
        val steps = buildList {
            add(MainClassStep("main-class in fxloop.properties / FXLOOP_MAIN_CLASS", c.mainClass))
            if (pom != null) {
                for (p in listOf("javafx-maven-plugin", "exec-maven-plugin", "maven-jar-plugin")) {
                    val v = pom.plugin(p)?.descendant("mainClass")?.let(pom::resolve)
                    add(MainClassStep("$p <mainClass> in pom.xml", v))
                }
            }
            if (gradle != null) add(MainClassStep("mainClass in the Gradle build file", gradle.mainClass))
        }
        val outputs = if (kind == BuildTool.Kind.GRADLE) listOf("build/classes/java/main", "build/classes/kotlin/main") else listOf("target/classes")
        val outDirs = outputs.map { dir.resolve(it) }
        val explicit = steps.any { !it.value.isNullOrBlank() }
        val compiled = outDirs.any { d -> Files.isDirectory(d) && Files.walk(d).use { s -> s.anyMatch { it.toString().endsWith(".class") } } }
        if (!explicit && !compiled) return DoctorCheck("main-class", DoctorStatus.warn,
            "not compiled yet (no classes in ${outputs.joinToString(" or ")}), so the main class cannot be scanned",
            "compile first (${if (kind == BuildTool.Kind.GRADLE) "gradlew classes" else "mvn compile"}) or pin it: main-class=<fully.qualified.Name> in fxloop.properties",
            mapOf("outputDirs" to outDirs.map { it.toString() }))
        return try {
            val r = MainClassResolver.resolve(steps, outDirs)
            val name = (r.mainModule?.let { "$it/" } ?: "") + r.mainClass
            DoctorCheck("main-class", DoctorStatus.ok, "$name (rule: ${r.source})",
                data = mapOf("mainClass" to r.mainClass, "mainModule" to r.mainModule, "rule" to r.source))
        } catch (e: MainClassException) {
            if (e.noneFound) DoctorCheck("main-class", DoctorStatus.warn, "no Application subclass or main method found in the compiled classes",
                "compile first, or pin it: main-class=<fully.qualified.Name> in fxloop.properties",
                mapOf("outputDirs" to outDirs.map { it.toString() }))
            else {
                val pin = (e.candidates.firstOrNull { it.extendsApplication } ?: e.candidates.first()).name
                DoctorCheck("main-class", DoctorStatus.fail,
                    "ambiguous: ${e.candidates.joinToString(", ") { it.describe() }}",
                    "pin one in fxloop.properties: main-class=$pin",
                    mapOf("candidates" to e.candidates.map {
                        mapOf("name" to it.name, "extendsApplication" to it.extendsApplication, "declaresMain" to it.declaresMain, "launcher" to it.launcher)
                    }, "pin" to e.candidates.map { "main-class=${it.name}" }))
            }
        }
    }

    // ---- 5 build plugin -------------------------------------------------------------------------------------------

    private fun pluginCheck(kind: BuildTool.Kind, pom: PomModel?, gradle: GradleFacts?): DoctorCheck {
        val server = Versions.SERVER
        val (present, version, name) = when {
            pom != null -> Triple(pom.plugin("fxloop-maven-plugin") != null, pom.plugin("fxloop-maven-plugin")?.coord?.version, "fxloop-maven-plugin")
            gradle != null -> Triple(gradle.fxloopPlugin, gradle.fxloopPluginVersion, "org.lab59.fxloop Gradle plugin")
            else -> Triple(false, null, "FXLoop build plugin")
        }
        if (!present) return DoctorCheck("build-plugin", DoctorStatus.ok,
            "no FXLoop build plugin in the build (fine: the server prepares the project itself)",
            if (kind == BuildTool.Kind.GRADLE) "to run the app yourself with the dev bar (./gradlew fxloopRun), add the org.lab59.fxloop plugin $server"
            else "to run the app yourself with the dev bar (mvn fxloop:run), add org.lab59.fxloop:fxloop-maven-plugin:$server to the build",
            mapOf("present" to false, "serverVersion" to server))
        val data = mapOf("present" to true, "version" to version, "serverVersion" to server)
        if (version != null && version != server) return DoctorCheck("build-plugin", DoctorStatus.warn,
            "$name $version does not match the server $server",
            if (kind == BuildTool.Kind.GRADLE) "use plugin version $server" else "set the plugin <version> to $server", data)
        return DoctorCheck("build-plugin", DoctorStatus.ok, "$name ${version ?: "(version managed elsewhere)"} matches the server $server", data = data)
    }

    // ---- 6 display ------------------------------------------------------------------------------------------------

    private fun displayCheck(c: Config, env: DoctorEnv): DoctorCheck {
        val hasDisplay = !env.linux || !env.env["DISPLAY"].isNullOrBlank() || !env.env["WAYLAND_DISPLAY"].isNullOrBlank()
        val xvfb = env.onPath("xvfb-run") != null || env.onPath("Xvfb") != null
        val data = mapOf("display" to hasDisplay, "headless" to c.headless, "xvfb" to xvfb)
        if (c.headless != null) {
            if (c.headless.equals("xvfb", true) && env.linux && !xvfb) return DoctorCheck("display", DoctorStatus.fail,
                "headless=xvfb is configured but xvfb-run is not on PATH", "install xvfb (e.g. apt install xvfb), or use headless=monocle", data)
            return DoctorCheck("display", DoctorStatus.ok, "headless=${c.headless} configured" + if (hasDisplay) " (a display is also available)" else "", data = data)
        }
        if (hasDisplay) return DoctorCheck("display", DoctorStatus.ok, if (env.linux) "display found (DISPLAY/WAYLAND_DISPLAY set)" else "desktop session (${env.os})", data = data)
        return if (xvfb) DoctorCheck("display", DoctorStatus.warn, "Linux without DISPLAY or WAYLAND_DISPLAY: the app cannot open a window here; xvfb is available",
            "add headless=xvfb to fxloop.properties (or FXLOOP_HEADLESS=xvfb)", data + ("suggest" to "xvfb"))
        else DoctorCheck("display", DoctorStatus.warn, "Linux without DISPLAY or WAYLAND_DISPLAY and no xvfb-run on PATH: the app cannot open a window here",
            "add headless=monocle to fxloop.properties (needs the openjfx-monocle jar: a test dependency or monocle-jar=<path>), or install xvfb and use headless=xvfb",
            data + ("suggest" to "monocle"))
    }

    // ---- 7 MCP clients --------------------------------------------------------------------------------------------

    private fun clientCheck(c: Config, env: DoctorEnv, clientArg: String?): DoctorCheck {
        val dir = c.projectDir
        val roots = buildList {
            add(dir)
            var p: Path? = dir
            var n = 0
            while (p != null && !Files.exists(p.resolve(".git")) && n++ < 3) { p = p.parent; p?.let(::add) }
            add(env.workDir)
        }.distinct()
        val states = McpClients.scan(roots, env.home)
        val configured = states.filter { it.hasFxloop }
        val broken = configured.filter { it.missingPaths.isNotEmpty() }
        val jar = env.serverJar?.toString() ?: "/path/to/fxloop-server-all.jar"
        val explicit: List<McpClient> = when {
            clientArg == null -> emptyList()
            clientArg.equals("all", true) -> McpClient.entries.toList()
            else -> listOfNotNull(McpClient.parse(clientArg))
        }
        val snippetFor: List<McpClient> = explicit.ifEmpty {
            if (configured.isNotEmpty()) emptyList()
            else states.filter { it.file != null }.map { it.client }.ifEmpty { listOf(McpClient.CLAUDE_CODE) }
        }
        val java = javaCommand(env)
        val snippets = snippetFor.map { cl -> mapOf("client" to cl.id, "file" to cl.file, "content" to McpClients.snippet(cl, jar, dir, env.home, env.windows, java)) }
        val data = mapOf(
            "clients" to states.map { mapOf("client" to it.client.id, "file" to it.file?.toString(), "hasFxloop" to it.hasFxloop, "missingPaths" to it.missingPaths, "scope" to it.scope) },
            "snippets" to snippets,
        )
        if (clientArg != null && explicit.isEmpty()) return DoctorCheck("mcp-client", DoctorStatus.warn,
            "unknown client '$clientArg'", "use --client=${McpClient.entries.joinToString("|") { it.id }}|all", data)
        if (broken.isNotEmpty()) {
            val b = broken.first()
            return DoctorCheck("mcp-client", DoctorStatus.fail,
                "${b.client.label} config ${b.file} points at ${b.missingPaths.joinToString()}, which does not exist",
                "fix the path in ${b.client.file} to the real jar (this one: $jar)", data)
        }
        if (configured.isNotEmpty()) return DoctorCheck("mcp-client", DoctorStatus.ok,
            "fxloop is registered for " + configured.joinToString(", ") { "${it.client.label} (${it.where})" }, data = data)
        val existing = states.filter { it.file != null }
        return DoctorCheck("mcp-client", DoctorStatus.warn,
            if (existing.isEmpty()) "no MCP client config in the workspace (looked for ${McpClient.entries.joinToString(", ") { it.file }})"
            else "${existing.joinToString(", ") { it.client.label }} config found, but without an fxloop entry",
            "add the snippet below to " + snippetFor.joinToString(" / ") { it.file } + " (--client=all for every client)", data)
    }

    /**
     * `java` when the first `java` on PATH is the JDK running doctor; otherwise its absolute path, since a JDK from
     * mise/SDKMAN/asdf is often missing from the PATH the agent starts the server with.
     */
    internal fun javaCommand(env: DoctorEnv): String {
        val j = env.jdk
        if (j.pathJavaHome != null && realOf(j.pathJavaHome) == realOf(j.runningHome)) return "java"
        return Path.of(j.runningHome, "bin", if (env.windows) "java.exe" else "java").toString()
    }

    // ---- output ---------------------------------------------------------------------------------------------------

    fun toJson(checks: List<DoctorCheck>): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
        checks.map { c ->
            linkedMapOf<String, Any?>("id" to c.id, "status" to c.status.name, "summary" to c.summary).also {
                c.fix?.let { f -> it["fix"] = f }
                c.data?.let { d -> it["data"] = d }
            }
        })

    /** Whether stdout can show the status symbols (JDK 19+ `stdout.encoding`, older `sun.stdout.encoding` / `file.encoding`). */
    fun stdoutUtf8(): Boolean {
        val enc = System.getProperty("stdout.encoding") ?: System.getProperty("sun.stdout.encoding") ?: System.getProperty("file.encoding") ?: return true
        return enc.equals("UTF-8", true) || enc.equals("UTF8", true)
    }

    fun render(config: Config, checks: List<DoctorCheck>, utf8: Boolean = stdoutUtf8()): String = buildString {
        appendLine("FXLoop doctor: ${config.projectDir}")
        val titles = listOf("JDK", "Build tool", "JavaFX", "Main class", "FXLoop build plugin", "Display", "Agent (MCP client)")
        checks.forEachIndexed { i, c ->
            appendLine("${c.status.marker(utf8)} ${i + 1} ${titles.getOrElse(i) { c.id }}: ${c.summary}")
            c.fix?.let { appendLine("   → $it") }
        }
        val snippets = checks.lastOrNull { it.id == "mcp-client" }?.data?.get("snippets") as? List<*>
        snippets?.forEach { s ->
            @Suppress("UNCHECKED_CAST") val m = s as Map<String, Any?>
            appendLine()
            appendLine("${m["file"]}:")
            appendLine(m["content"].toString().prependIndent("  "))
        }
        appendLine()
        val fails = checks.count { it.status == DoctorStatus.fail }
        val warns = checks.count { it.status == DoctorStatus.warn }
        append(if (fails + warns == 0) "All good." else "$fails problem(s), $warns warning(s).")
    }.let { if (utf8) it else it.replace("→", "->") }

    private fun gradleFacts(dir: Path): GradleFacts? =
        listOf("build.gradle.kts", "build.gradle").map { dir.resolve(it) }.firstOrNull(Files::isRegularFile)
            ?.let { GradleFacts(runCatching { Files.readString(it) }.getOrDefault("")) }
}

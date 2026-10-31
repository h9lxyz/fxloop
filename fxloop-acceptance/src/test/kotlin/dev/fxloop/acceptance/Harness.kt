package dev.fxloop.acceptance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import dev.fxloop.server.Config
import dev.fxloop.server.mcp.ToolRegistry
import dev.fxloop.server.mcp.ToolResult
import dev.fxloop.server.registry
import dev.fxloop.server.tools.Ctx
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence

/**
 * Drives the real tool surface in-process against a throwaway copy of the sample app (the MVP workflows edit its
 * sources). Headless lane: `xvfb-run` per launch (FXLOOP_ACCEPTANCE_HEADLESS=xvfb|monocle|none, default xvfb).
 */
class Harness(
    appArgs: List<String> = emptyList(),
    app: String = "fxloop-sample-app",
    webview: Boolean = false,
    headlessOverride: String? = null,
    private val browser: String = "none", // acceptance runs never open a real browser (inspector tests)
) : AutoCloseable {
    val repo: Path = Path.of(System.getProperty("fxloop.repo") ?: "..").toAbsolutePath().normalize()
    val project: Path = copyOf(repo, app)
    val ctx: Ctx
    val tools: ToolRegistry

    init {
        try {
            ctx = createCtx(appArgs, webview, headlessOverride)
            tools = registry(ctx)
        } catch (e: Throwable) {
            deleteProject(project) // a failed constructor never reaches close()
            throw e
        }
    }

    private fun createCtx(appArgs: List<String>, webview: Boolean, headlessOverride: String?): Ctx {
        val headless = headlessOverride ?: System.getenv("FXLOOP_ACCEPTANCE_HEADLESS") ?: "xvfb"
        val config = Config(
            projectDir = project,
            // Maven apps use the repo wrapper; Gradle apps find their own gradlew (wrapper preference, PRD 03 §2)
            // Gradle runs without a daemon here: test runs must not leave ~1 GB daemons behind.
            buildCmd = if (Files.exists(project.resolve("pom.xml"))) repo.resolve("mvnw").toString()
            else project.resolve("gradlew").toString() + " --no-daemon",
            headless = headless.takeIf { it != "none" },
            appArgs = appArgs,
            jvmArgs = listOf(RUN_MARKER), // lets the leak guard recognise exactly this run's app JVMs
            webview = webview,
            testMode = true,
            screenshotRatePerSec = 0.0,
            watchMode = "off",
            browser = browser,
            startupTimeoutMs = 60_000,
        )
        return Ctx(config)
    }

    fun raw(name: String, args: ObjectNode = Json.obj()): ToolResult = tools.call(name, args)

    fun ok(name: String, args: ObjectNode = Json.obj()): ObjectNode {
        val r = raw(name, args)
        assertThat(r.isError).describedAs("%s failed: %s", name, r.text).isFalse()
        return r.structured!!
    }

    fun err(name: String, args: ObjectNode = Json.obj()): ObjectNode {
        val r = raw(name, args)
        assertThat(r.isError).describedAs("%s should fail but returned: %s", name, r.text).isTrue()
        return r.structured!!
    }

    fun prop(query: String, name: String): JsonNode = ok("fx_get_property", args("query" to query, "name" to name))["value"]

    fun edit(rel: String, transform: (String) -> String) {
        val f = project.resolve(rel)
        val before = f.readText()
        val after = transform(before)
        check(before != after) { "edit of $rel changed nothing" }
        f.writeText(after)
        // mtime granularity on some file systems is coarse; make sure the change is observable
        f.toFile().setLastModified(System.currentTimeMillis() + 1000)
    }

    override fun close() {
        try {
            ctx.shutdown() // the app tree must be dead before its files go away
        } finally {
            if (System.getProperty("fxloop.keepProjects") == null) deleteProject(project)
        }
    }

    companion object {
        /** Unique per test JVM; passed to every app JVM this run launches (see [NoLeakedProcesses]). */
        val RUN_MARKER = "-Dfxloop.acceptance.run=" + java.util.UUID.randomUUID()

        const val CONTROLLER = "src/main/java/dev/fxloop/sample/MainController.java"
        const val CSS = "src/main/resources/dev/fxloop/sample/app.css"
        const val FXML = "src/main/resources/dev/fxloop/sample/main-view.fxml"

        fun args(vararg pairs: Pair<String, Any?>): ObjectNode = Json.obj().also { o ->
            pairs.forEach { (k, v) ->
                when (v) {
                    null -> o.putNull(k)
                    is String -> o.put(k, v)
                    is Int -> o.put(k, v)
                    is Long -> o.put(k, v)
                    is Double -> o.put(k, v)
                    is Boolean -> o.put(k, v)
                    is JsonNode -> o.set<JsonNode>(k, v)
                    is List<*> -> o.set<JsonNode>(k, Json.tree(v))
                    else -> o.put(k, v.toString())
                }
            }
        }

        /** Deletes a project copy; retries once because a dying app may still flush a last file (state.json). */
        fun deleteProject(dir: Path) {
            dir.toFile().deleteRecursively()
            Thread.sleep(500)
            dir.toFile().deleteRecursively()
        }

        /** Throwaway copy of a sample app (the MVP workflows edit sources); the caller deletes it. */
        fun copyOf(repo: Path, app: String): Path {
            val src = repo.resolve("sample-apps/$app")
            val dst = Files.createTempDirectory("fxloop-acceptance-")
            Files.walk(src).use { s ->
                s.asSequence().filter { rel -> listOf("target", "build", ".gradle").none { src.relativize(rel).startsWith(it) } }.forEach {
                    val t = dst.resolve(src.relativize(it).toString())
                    if (Files.isDirectory(it)) Files.createDirectories(t) else Files.copy(it, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
            val pom = dst.resolve("pom.xml")
            if (pom.exists()) pom.writeText(pom.readText().replace(Regex("\\s*<relativePath>.*?</relativePath>"), ""))
            return dst
        }
    }
}

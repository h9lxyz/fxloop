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
) : AutoCloseable {
    val repo: Path = Path.of(System.getProperty("fxloop.repo") ?: "..").toAbsolutePath().normalize()
    val project: Path = copySampleApp(repo, app)
    val ctx: Ctx
    val tools: ToolRegistry

    init {
        val headless = headlessOverride ?: System.getenv("FXLOOP_ACCEPTANCE_HEADLESS") ?: "xvfb"
        val config = Config(
            projectDir = project,
            buildCmd = repo.resolve("mvnw").toString(),
            headless = headless.takeIf { it != "none" },
            appArgs = appArgs,
            webview = webview,
            testMode = true,
            screenshotRatePerSec = 0.0,
            watchMode = "off",
            startupTimeoutMs = 60_000,
        )
        ctx = Ctx(config)
        tools = registry(ctx)
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
        ctx.shutdown()
    }

    companion object {
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

        private fun copySampleApp(repo: Path, app: String): Path {
            val src = repo.resolve("sample-apps/$app")
            val dst = Files.createTempDirectory("fxloop-acceptance-")
            Files.walk(src).use { s ->
                s.asSequence().filter { !src.relativize(it).startsWith("target") }.forEach {
                    val t = dst.resolve(src.relativize(it).toString())
                    if (Files.isDirectory(it)) Files.createDirectories(t) else Files.copy(it, t, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val pom = dst.resolve("pom.xml")
            if (pom.exists()) pom.writeText(pom.readText().replace(Regex("\\s*<relativePath>.*?</relativePath>"), ""))
            return dst
        }
    }
}

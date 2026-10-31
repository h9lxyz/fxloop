package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.node.ArrayNode
import dev.fxloop.protocol.Json
import dev.fxloop.protocol.LaunchDescriptor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/** Tier-3 compile via `javax.tools` with the launch classpath; diagnostics returned as data (PRD 02 §3). */
object InProcessCompiler {
    @Volatile private var warmed = false

    /**
     * Compiles a trivial class once in the background so the first real hot-swap doesn't pay javac's class
     * loading/JIT warm-up (PRD budget: single-class HotSwap < 2 s).
     */
    fun warmUpAsync() {
        if (warmed) return
        warmed = true
        kotlin.concurrent.thread(isDaemon = true, name = "fxloop-javac-warmup") {
            runCatching {
                val dir = Files.createTempDirectory("fxloop-javac-warmup")
                try {
                    val src = Files.writeString(dir.resolve("Warm.java"), "class Warm { int f(int x) { return x * 2; } }")
                    val javac = ToolProvider.getSystemJavaCompiler() ?: return@runCatching
                    javac.run(null, null, null, "-d", dir.toString(), "-proc:none", src.toString())
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }
    }

    data class Result(val success: Boolean, val diagnostics: ArrayNode, val classes: Map<String, ByteArray>, val failure: String?)

    fun compile(d: LaunchDescriptor, sources: List<Path>): Result {
        val javac = ToolProvider.getSystemJavaCompiler()
            ?: return Result(false, Json.array(), emptyMap(), "no system Java compiler (server runs on a JRE?)")
        val diags = DiagnosticCollector<JavaFileObject>()
        val written = LinkedHashMap<String, Path>()
        val out = Path.of(d.outputDir)
        return try {
            javac.getStandardFileManager(diags, Locale.ROOT, null).use { std ->
                std.setLocation(StandardLocation.CLASS_PATH, listOf(out.toFile()) + (d.classpath + d.modulePath).map(::File))
                std.setLocation(StandardLocation.CLASS_OUTPUT, listOf(out.toFile()))
                val fm = object : ForwardingJavaFileManager<StandardJavaFileManager>(std) {
                    override fun getJavaFileForOutput(location: JavaFileManager.Location, className: String, kind: JavaFileObject.Kind,
                        sibling: FileObject?): JavaFileObject {
                        val f = super.getJavaFileForOutput(location, className, kind, sibling)
                        if (kind == JavaFileObject.Kind.CLASS) written[className] = Path.of(f.toUri())
                        return f
                    }
                }
                val options = mutableListOf("-g", "-proc:none", "-implicit:none", "-Xlint:none")
                d.release?.takeIf { it.isNotBlank() }?.let { options += listOf("--release", it) }
                val ok = javac.getTask(null, fm, diags, options, null, std.getJavaFileObjectsFromPaths(sources)).call()
                val list = Json.array()
                for (diag in diags.diagnostics) {
                    val kind = when (diag.kind) {
                        Diagnostic.Kind.ERROR -> "error"
                        Diagnostic.Kind.WARNING, Diagnostic.Kind.MANDATORY_WARNING -> "warning"
                        else -> continue
                    }
                    list.addObject().apply {
                        put("file", diag.source?.let { Path.of(it.toUri()).toString() })
                        put("line", diag.lineNumber)
                        put("col", diag.columnNumber)
                        put("kind", kind)
                        put("message", diag.getMessage(Locale.ROOT))
                    }
                }
                val classes = if (ok) written.mapValues { Files.readAllBytes(it.value) } else emptyMap()
                Result(ok, list, classes, null)
            }
        } catch (e: Exception) {
            Result(false, Json.array(), emptyMap(), e.toString())
        }
    }
}

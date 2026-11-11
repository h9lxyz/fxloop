package org.lab59.fxloop.server.app

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.lab59.fxloop.launch.LaunchDescriptor
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.server.mcp.ToolException
import org.objectweb.asm.ClassReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Plugin-free Gradle prepare (PRD 11 4.2): the server carries the Gradle plugin as a resource and builds its runtime jar
 * from its own classes, writes both once to `~/.fxloop/cache/<version>/gradle/` and applies the plugin through a
 * generated init script, so nothing is written into the user's build. `fxloopPrepare` then runs exactly as with the
 * plugin applied.
 */
object GradlePrepare {
    const val RESOURCE_DIR = "/org/lab59/fxloop/server/gradle/"
    const val INIT_SCRIPT = "fxloop.init.gradle.kts"

    /** Jars in classpath order: the plugin, then what it needs at runtime. */
    val JARS = listOf("fxloop-gradle-plugin", "fxloop-gradle-runtime")

    private const val RUNTIME_JAR = "fxloop-gradle-runtime.jar"

    /** The plugin's runtime dependencies. The server has them too, so they are not bundled a second time as jars. */
    private val RUNTIME_PACKAGES = listOf(
        "org/lab59/fxloop/protocol/", "org/lab59/fxloop/launch/", "org/objectweb/asm/", "kotlin/",
        "com/fasterxml/jackson/annotation/", "com/fasterxml/jackson/core/", "com/fasterxml/jackson/databind/",
    )

    /** One class per dependency, to find where it was loaded from: the shaded jar, or separate jars and class folders. */
    private val RUNTIME_ROOTS = listOf(
        Json::class.java, LaunchDescriptor::class.java, ClassReader::class.java, Unit::class.java,
        JsonProperty::class.java, JsonParser::class.java, ObjectMapper::class.java,
    )

    /** `id("org.lab59.fxloop")`, `apply plugin: 'org.lab59.fxloop'`, `fxloop-gradle-plugin` (classpath), but not `group = "org.lab59.fxloop"`. */
    private val PLUGIN_REFERENCE = Regex("""\b(id|plugin|apply|alias)\b[^\n]*["']org\.lab59\.fxloop["']|fxloop-gradle-plugin|org\.lab59\.fxloop\.gradle""")

    private val BUILD_FILES = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

    /** Cheap check: does a build file of the project mention the FXLoop plugin? (Falls back to a retry if wrong.) */
    fun pluginApplied(projectDir: Path): Boolean = BUILD_FILES.any { name ->
        val f = projectDir.resolve(name)
        Files.isRegularFile(f) && runCatching { PLUGIN_REFERENCE.containsMatchIn(Files.readString(f)) }.getOrDefault(false)
    }

    /** Task output meaning the plugin is not applied (so the init-script route is needed). */
    fun taskMissing(output: String): Boolean =
        output.contains("Task 'fxloopPrepare' not found") || output.contains("Cannot locate tasks that match 'fxloopPrepare'")

    fun duplicateTask(output: String): Boolean =
        output.contains("fxloopPrepare") && (output.contains("already exists") || output.contains("Cannot add task"))

    fun defaultCacheDir(version: String): Path =
        Path.of(System.getProperty("user.home"), ".fxloop", "cache", version, "gradle")

    /**
     * Writes the jars into [dir] (skipping files already identical) and returns their paths.
     * @throws ToolException when this server build was made without the Gradle plugin jar.
     */
    fun extract(dir: Path, open: (String) -> InputStream? = ::bundled): List<Path> {
        val out = mutableListOf<Path>()
        try {
            Files.createDirectories(dir)
            for (name in JARS) {
                val target = dir.resolve("$name.jar")
                val bytes = (open("$name.jar") ?: throw missing(name)).use { it.readAllBytes() }
                if (!Files.isRegularFile(target) || !sameContent(target, bytes)) {
                    val tmp = Files.createTempFile(dir, name, ".tmp")
                    Files.write(tmp, bytes)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                out.add(target)
            }
        } catch (e: IOException) {
            throw ToolException("CONFIG", "cannot extract the bundled Gradle plugin to $dir: ${e.message}",
                "make ~/.fxloop writable, or apply id(\"org.lab59.fxloop\") in the build")
        }
        return out
    }

    private fun bundled(name: String): InputStream? =
        if (name == RUNTIME_JAR) ByteArrayInputStream(runtimeJar()) else GradlePrepare::class.java.getResourceAsStream(RESOURCE_DIR + name)

    /** [RUNTIME_PACKAGES] as one jar. Sorted with fixed timestamps: the same server yields the same bytes, so [extract] leaves the file alone. */
    internal fun runtimeJar(): ByteArray {
        val entries = TreeMap<String, ByteArray>()
        fun collect(base: Path) = Files.walk(base).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { f ->
                val name = base.relativize(f).toString().replace('\\', '/')
                if (RUNTIME_PACKAGES.any(name::startsWith)) entries[name] = Files.readAllBytes(f)
            }
        }
        RUNTIME_ROOTS.map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().forEach { root ->
            if (Files.isDirectory(root)) collect(root) else FileSystems.newFileSystem(root).use { collect(it.getPath("/")) }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { timeLocal = LocalDateTime.of(1980, 2, 1, 0, 0) })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun missing(name: String) = ToolException("CONFIG",
        "this server build lacks the bundled Gradle plugin ($name.jar) needed to prepare a Gradle project that does not apply org.lab59.fxloop",
        "apply id(\"org.lab59.fxloop\") in the build, use a release server jar, or rebuild the server after `gradlew -p fxloop-gradle-plugin publishToMavenLocal`")

    private fun sameContent(file: Path, bytes: ByteArray): Boolean {
        if (Files.size(file) != bytes.size.toLong()) return false
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(Files.readAllBytes(file)).contentEquals(md.digest(bytes))
    }

    /**
     * Init script (Kotlin DSL) putting [jars] on the initscript classpath and applying the plugin to every project that
     * applies `application` or the OpenJFX plugin (and to a single-project build that applies `java`). Projects that
     * already apply `org.lab59.fxloop` are left alone.
     */
    fun initScript(jars: List<Path>): String {
        val files = jars.joinToString(",\n") { "\"${kts(it.toAbsolutePath().toString())}\"" }
        return """
            // Generated by the FXLoop server (PRD 11 4.2); not part of your build. Applies the FXLoop Gradle plugin
            // without touching build files. Delete freely: it is rewritten on the next prepare.
            initscript {
                dependencies {
                    classpath(files(
                        @@FILES@@
                    ))
                }
            }

            allprojects {
                val fxloopTarget = this
                fun fxloop() {
                    if (!fxloopTarget.pluginManager.hasPlugin("org.lab59.fxloop")) {
                        fxloopTarget.pluginManager.apply(org.lab59.fxloop.gradle.FxloopPlugin::class.java)
                    }
                }
                pluginManager.withPlugin("application") { fxloop() }
                pluginManager.withPlugin("org.openjfx.javafxplugin") { fxloop() }
                pluginManager.withPlugin("java") { if (fxloopTarget == fxloopTarget.rootProject && fxloopTarget.subprojects.isEmpty()) fxloop() }
            }
        """.trimIndent().replace("@@FILES@@", files.replace("\n", "\n            ")) + "\n"
    }

    /** Writes [initScript] to [file] only when it changed (a stable file keeps Gradle's configuration cache valid). */
    fun writeInitScript(file: Path, jars: List<Path>): Path {
        val text = initScript(jars)
        Files.createDirectories(file.toAbsolutePath().parent)
        if (!Files.isRegularFile(file) || Files.readString(file) != text) Files.writeString(file, text)
        return file
    }

    internal fun kts(s: String): String = s.replace('\\', '/').replace("\"", "\\\"").replace("$", "\\$")
}

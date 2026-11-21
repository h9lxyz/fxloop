package org.lab59.fxloop.server.attach

import org.lab59.fxloop.launch.LaunchDescriptor
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/** A failure the `attach` command explains to the developer in one message (no stack trace). */
class AttachException(message: String, val exitCode: Int = 1) : RuntimeException(message)

/**
 * What the Attach API tells about a running JVM without loading anything into it: its system properties and the agent
 * properties (`sun.jvm.args`). Everything `attach` decides about the app (is it JavaFX, which project, which descriptor)
 * is derived from this, so it can be unit-tested without a live JVM.
 */
data class JvmFacts(val pid: Long, val props: Map<String, String>, val agentProps: Map<String, String> = emptyMap()) {
    /** JavaFX sets both once its toolkit runs; either one is enough. */
    val javafx: Boolean get() = props.containsKey("javafx.runtime.version") || props.containsKey("javafx.version")

    val userDir: Path get() = Path.of(props["user.dir"] ?: ".").toAbsolutePath().normalize()

    /** Class path entries, absolute (relative entries are resolved against the target's `user.dir`, not ours). */
    val classPath: List<Path> get() = entries(props["java.class.path"])

    val modulePath: List<Path> get() = entries(props["jdk.module.path"])

    val mainModule: String? get() = props["jdk.module.main"]?.takeIf { it.isNotBlank() }

    /**
     * `jdk.module.main.class`, else the first token of `sun.java.command` (`mod/cls` for `-m`, a jar path for `-jar`).
     * Null when the JVM does not say (an embedded launcher).
     */
    val mainClass: String?
        get() = props["jdk.module.main.class"]?.takeIf { it.isNotBlank() } ?: mainClassOf(props["sun.java.command"])

    /** The name the developer knows the app by: the main class's simple name, or the jar's file name. */
    val displayName: String
        get() {
            val m = mainClass ?: return "pid $pid"
            return if (m.endsWith(".jar", ignoreCase = true)) m else m.substringAfterLast('.')
        }

    /** Feature release of the target JVM (`java.specification.version`; `1.8` reads as 8). */
    val javaRelease: Int get() = (props["java.specification.version"] ?: "0").removePrefix("1.").substringBefore('.').toIntOrNull() ?: 0

    val jvmArgs: String get() = agentProps["sun.jvm.args"].orEmpty()

    /**
     * Modules the app added (`--add-modules`, which the launcher hands to the VM as `-Djdk.module.addmods[.n]=`), e.g.
     * `javafx.controls,javafx.fxml` for JavaFX from an SDK's `lib` folder on the module path.
     */
    val addModules: List<String>
        get() {
            val args = jvmArgs.split(Regex("\\s+"))
            val values = args.mapIndexedNotNull { i, a ->
                when {
                    a.startsWith("-Djdk.module.addmods") && '=' in a -> a.substringAfter('=')
                    a.startsWith("--add-modules=") -> a.substringAfter('=')
                    a == "--add-modules" -> args.getOrNull(i + 1)
                    else -> null
                }
            }
            return values.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

    private fun entries(value: String?): List<Path> = value.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        .map { userDir.resolve(it.trim()).toAbsolutePath().normalize() }

    companion object {
        /**
         * Main class from a `sun.java.command` (or the Attach API's display name): its first token, without the module
         * of `mod/cls`; for `-jar` the jar's file name.
         */
        fun mainClassOf(command: String?): String? {
            val first = command?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return if (first.endsWith(".jar", ignoreCase = true)) first.substringAfterLast('/').substringAfterLast('\\')
            else first.substringAfter('/')
        }
    }
}

/**
 * The project a running app belongs to, inferred from its class path: [projectDir] is where FXLoop keeps its files
 * (`target/fxloop` or `build/fxloop`), [moduleDir] holds the sources, [outputDirs] are the app's own runtime directories.
 */
data class AttachProject(val projectDir: Path, val moduleDir: Path, val outputDirs: List<Path>, val buildTool: String) {
    /** `maven` → "Maven", `gradle` → "Gradle", for the printed summary. */
    val buildToolName: String get() = buildTool.replaceFirstChar(Char::uppercase)
}

/** Project inference and the descriptor for an attached app (no build tool runs: the app is built and running). */
object AttachInference {
    val BUILD_FILES = listOf("pom.xml", "build.gradle", "build.gradle.kts")

    /** The outcome: a project, or why there is none (the app then runs inspect-only). */
    data class Result(val project: AttachProject?, val whyNone: String? = null)

    /**
     * Known output layouts, as path suffixes; `*` matches any one segment. Maven (also IntelliJ with Maven and Eclipse
     * m2e), Gradle, IntelliJ building a Gradle project itself, Eclipse Buildship / VS Code Java.
     */
    private val OUTPUT_SUFFIXES = listOf(
        listOf("target", "classes"),
        listOf("build", "classes", "*", "main"),
        listOf("build", "resources", "main"),
        listOf("out", "production", "classes"),
        listOf("out", "production", "resources"),
        listOf("bin", "main"),
    )

    fun hasBuildFile(dir: Path): Boolean = BUILD_FILES.any { Files.isRegularFile(dir.resolve(it)) }

    /** The nearest directory at or above [dir] with a build file, or null. */
    fun nearestBuildDir(dir: Path): Path? = generateSequence(dir) { it.parent }.firstOrNull(::hasBuildFile)

    /**
     * Directory entries of the class path, then of the module path, that hold the app's own classes or resources. Jars
     * are ignored, and so are a folder of jars (a JavaFX SDK's `lib` on the module path) and a folder with a build file
     * (`-cp .` from the project root: that is the project, not a build output).
     */
    fun runtimeDirs(facts: JvmFacts): List<Path> = (facts.classPath + facts.modulePath)
        .filter { Files.isDirectory(it) && !isJarFolder(it) && !hasBuildFile(it) }.distinct()

    /** A folder of jars (and natives) rather than of classes: it has jar files and no subfolders or class files. */
    fun isJarFolder(dir: Path): Boolean = try {
        Files.list(dir).use { s ->
            val children = s.toList()
            children.any { it.fileName.toString().endsWith(".jar", true) && Files.isRegularFile(it) } &&
                children.none { Files.isDirectory(it) || it.fileName.toString().endsWith(".class") }
        }
    } catch (e: IOException) {
        false
    }

    /** Path entries with each folder of jars replaced by its jars: javac and the descriptor's readers take jars, not such folders. */
    fun expandJarFolders(entries: List<Path>): List<Path> = entries.flatMap { e ->
        if (Files.isDirectory(e) && isJarFolder(e)) {
            Files.list(e).use { s -> s.filter { it.fileName.toString().endsWith(".jar", true) }.sorted().toList() }
        } else {
            listOf(e)
        }
    }.distinct()

    /** The module a runtime directory was built from: strip a known output suffix, else the nearest dir with a build file. */
    fun moduleDirOf(runtimeDir: Path): Path? {
        val names = (0 until runtimeDir.nameCount).map { runtimeDir.getName(it).toString() }
        for (suffix in OUTPUT_SUFFIXES) {
            if (names.size <= suffix.size) continue
            val tail = names.takeLast(suffix.size)
            if (suffix.indices.all { suffix[it] == "*" || suffix[it] == tail[it] }) {
                var d = runtimeDir
                repeat(suffix.size) { d = d.parent }
                return d
            }
        }
        return nearestBuildDir(runtimeDir)
    }

    /**
     * @param explicitProjectDir `--project-dir` / `FXLOOP_PROJECT_DIR`: replaces the inference, but must hold a build
     * file and at least one of the app's runtime directories
     * @throws AttachException when [explicitProjectDir] does not fit the app
     */
    fun infer(facts: JvmFacts, explicitProjectDir: Path? = null): Result {
        val dirs = runtimeDirs(facts)
        if (explicitProjectDir != null) {
            val p = explicitProjectDir.toAbsolutePath().normalize()
            if (!hasBuildFile(p)) throw AttachException("--project-dir $p has no build file (${BUILD_FILES.joinToString(", ")})")
            val inside = dirs.filter { it.startsWith(p) }
            if (inside.isEmpty()) throw AttachException("the app does not run classes from --project-dir $p" +
                (if (dirs.isEmpty()) " (it runs from jars only)" else "; its class directories are ${dirs.joinToString()}"))
            val module = moduleDirOf(inside.first())?.takeIf { it.startsWith(p) } ?: p
            return Result(AttachProject(p, module, inside.filter { moduleDirOf(it) == module }.ifEmpty { inside }, buildToolOf(p)))
        }
        if (dirs.isEmpty()) return Result(null, "it runs from jars only, not from a project's build output (target/classes, build/classes/…)")
        val module = moduleDirOf(dirs.first()) ?: return Result(null, "no build file (${BUILD_FILES.joinToString(", ")}) above ${dirs.first()}")
        val project = nearestBuildDir(module) ?: return Result(null, "no build file (${BUILD_FILES.joinToString(", ")}) above ${dirs.first()}")
        return Result(AttachProject(project, module, dirs.filter { moduleDirOf(it) == module }, buildToolOf(project)))
    }

    fun buildToolOf(dir: Path): String = if (Files.isRegularFile(dir.resolve("pom.xml"))) "maven" else "gradle"

    /**
     * `launch.json` for an attached app from the JVM's facts plus conventions. [LaunchDescriptor.classpath] keeps the
     * JVM's real entries (the other output dirs included): resource saves are copied into every class path directory that
     * holds the file, which is how a stylesheet reaches an IDE's or Gradle's separate resources folder.
     */
    fun descriptor(facts: JvmFacts, p: AttachProject): LaunchDescriptor {
        val out = p.outputDirs.firstOrNull(::hasClassFile) ?: p.outputDirs.first()
        fun existing(vararg rel: String) = rel.map { p.moduleDir.resolve(it) }.filter { Files.isDirectory(it) }
        val sources = existing("src/main/java", "src/main/kotlin")
        // stylesheets or FXML kept next to the sources (Maven <resource><directory>src/main/java</directory>) are resources too
        val resources = existing("src/main/resources") + sources.filter(::holdsUiResources)
        return LaunchDescriptor(
            buildTool = p.buildTool,
            mainClass = facts.mainClass ?: "",
            mainModule = facts.mainModule,
            classpath = expandJarFolders(facts.classPath).filter { it != out }.map { it.toString() },
            modulePath = expandJarFolders(facts.modulePath).filter { it != out }.map { it.toString() },
            addModules = facts.addModules,
            workingDir = facts.userDir.toString(),
            sourceRoots = sources.map { it.toString() },
            resourceRoots = resources.map { it.toString() },
            outputDir = out.toString(),
            release = classRelease(out)?.toString() ?: facts.props["java.specification.version"],
            annotationProcessors = annotationProcessorHint(p.moduleDir),
        )
    }

    private fun hasClassFile(dir: Path): Boolean = firstClassFile(dir) != null

    private fun holdsUiResources(dir: Path): Boolean = try {
        Files.walk(dir).use { s -> s.asSequence().any { f -> f.fileName.toString().lowercase().let { it.endsWith(".css") || it.endsWith(".fxml") } } }
    } catch (e: IOException) {
        false
    }

    private fun firstClassFile(dir: Path): Path? = try {
        Files.walk(dir).use { s -> s.asSequence().firstOrNull { it.fileName.toString().endsWith(".class") && Files.isRegularFile(it) } }
    } catch (e: IOException) {
        null
    }

    /** Java release a class in [dir] was compiled for: class-file major version minus 44 (61 → 17). */
    fun classRelease(dir: Path): Int? {
        val f = firstClassFile(dir) ?: return null
        return try {
            DataInputStream(Files.newInputStream(f)).use { input ->
                if (input.readInt() != 0xCAFEBABE.toInt()) return null
                input.readUnsignedShort() // minor
                (input.readUnsignedShort() - 44).takeIf { it >= 1 }
            }
        } catch (e: IOException) {
            null
        }
    }

    private val PROCESSOR_HINTS = listOf("lombok", "annotationprocessor", "kapt", "ksp", "mapstruct")

    /** The module's build file mentions an annotation processor: the reloader then compiles through the build tool. */
    fun annotationProcessorHint(moduleDir: Path): Boolean = BUILD_FILES.map(moduleDir::resolve).filter { Files.isRegularFile(it) }.any { f ->
        val text = runCatching { Files.readString(f).lowercase() }.getOrDefault("")
        PROCESSOR_HINTS.any { it in text }
    }

    /** One candidate JVM and the project it belongs to, for choosing among several. */
    data class Candidate(val facts: JvmFacts, val project: AttachProject?)

    /**
     * Which candidate `attach` without a pid takes: the only one; else the only one belonging to [hereProject] (the
     * project of the current directory or `--project-dir`; its submodules count); else null (ask, or list and exit 2).
     */
    fun choose(candidates: List<Candidate>, hereProject: Path?): Candidate? {
        if (candidates.size == 1) return candidates.single()
        if (hereProject == null) return null
        val here = hereProject.toAbsolutePath().normalize()
        return candidates.filter { it.project?.projectDir?.startsWith(here) == true }.singleOrNull()
    }

    /**
     * The same app again after a restart: same main class and the same inferred project dir (inspect-only: the same
     * `user.dir`).
     */
    fun sameApp(before: Candidate, now: Candidate): Boolean {
        if (before.facts.mainClass != now.facts.mainClass) return false
        val p = before.project
        return if (p != null) now.project?.projectDir == p.projectDir else now.project == null && now.facts.userDir == before.facts.userDir
    }
}

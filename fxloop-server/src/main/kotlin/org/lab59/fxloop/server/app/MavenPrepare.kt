package org.lab59.fxloop.server.app

import org.lab59.fxloop.launch.JavaFxModules
import org.lab59.fxloop.launch.LaunchMode
import org.lab59.fxloop.launch.MainClassException
import org.lab59.fxloop.launch.MainClassResolver
import org.lab59.fxloop.launch.MainClassStep
import org.lab59.fxloop.protocol.LaunchDescriptor
import org.lab59.fxloop.server.mcp.ToolException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Plugin-free Maven prepare (PRD 11 4.2): produces the same [LaunchDescriptor] as `fxloop-maven-plugin:prepare`
 * from one Maven invocation (`compile`, `help:effective-pom` when stale, `dependency:build-classpath`) plus the shared
 * detection in `fxloop-launch`. Pure functions where possible so the mapping is unit-testable from fixture XML.
 */
object MavenPrepare {
    const val EFFECTIVE_POM = "effective-pom.xml"
    const val CLASSPATH_FILE = "cp.txt"
    private val WHITESPACE = Regex("\\s+")
    private const val PROCESSOR_SERVICE = "META-INF/services/javax.annotation.processing.Processor"

    /** Goals after the lifecycle `compile`; the effective POM is regenerated only when [pom] (or a parent) is newer. */
    fun goals(fxloopDir: Path, pom: Path): List<String> = buildList {
        add("-DskipTests")
        add("compile")
        if (!effectivePomFresh(fxloopDir, pom)) {
            add("help:effective-pom")
            add("-Doutput=${fxloopDir.resolve(EFFECTIVE_POM)}")
        }
        add("dependency:build-classpath")
        add("-Dmdep.outputFile=${fxloopDir.resolve(CLASSPATH_FILE)}")
        add("-Dmdep.pathSeparator=${File.pathSeparator}")
        add("-DincludeScope=runtime")
    }

    fun effectivePomFresh(fxloopDir: Path, pom: Path): Boolean {
        val f = fxloopDir.resolve(EFFECTIVE_POM)
        return Files.isRegularFile(f) && Files.getLastModifiedTime(f).toMillis() >= EffectivePom.newestPomMtime(pom)
    }

    /** The effective POM written by the build, or the raw `pom.xml` when `help:effective-pom` could not run. */
    fun loadPom(fxloopDir: Path, pom: Path): EffectivePom {
        val f = fxloopDir.resolve(EFFECTIVE_POM)
        return if (Files.isRegularFile(f)) EffectivePom.parse(f) else EffectivePom.parse(pom)
    }

    fun readClasspath(fxloopDir: Path): List<String> {
        val text = Files.readString(fxloopDir.resolve(CLASSPATH_FILE)).trim()
        return if (text.isEmpty()) emptyList() else text.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    /** `…/org/openjfx/javafx-controls/21/javafx-controls-21-linux.jar` -> `javafx-controls` (local-repository layout). */
    fun javaFxArtifactId(path: String): String? {
        val parts = path.replace('\\', '/').split('/')
        val i = parts.indexOf("openjfx")
        if (i < 1 || parts[i - 1] != "org" || parts.size < i + 4) return null
        return parts[i + 1]
    }

    fun describe(
        projectDir: Path,
        pom: EffectivePom,
        classpath: List<String>,
        explicitMain: String?,
        jvmArgs: List<String> = emptyList(),
        appArgs: List<String> = emptyList(),
        launchMode: String? = null,
        mavenRepo: Path? = null,
    ): LaunchDescriptor {
        val dir = projectDir.toAbsolutePath()
        val outputDir = Path.of(pom.outputDirectory ?: dir.resolve("target/classes").toString()).toAbsolutePath()
        val javafx = pom.plugin("org.openjfx", "javafx-maven-plugin")
        val exec = pom.plugin("org.codehaus.mojo", "exec-maven-plugin")
        val jar = pom.plugin("org.apache.maven.plugins", "maven-jar-plugin")
        val compiler = pom.plugin("org.apache.maven.plugins", "maven-compiler-plugin")

        val main = try {
            MainClassResolver.resolve(
                listOf(
                    MainClassStep("fxloop.properties main-class", explicitMain),
                    MainClassStep("javafx-maven-plugin", javafx?.text("mainClass")),
                    MainClassStep("exec-maven-plugin", exec?.text("mainClass")),
                    MainClassStep("maven-jar-plugin manifest", jar?.text("archive", "manifest", "mainClass")),
                ),
                listOf(outputDir),
            )
        } catch (e: MainClassException) {
            throw mainClassError(e)
        }

        val sourceRoots = existing(
            listOfNotNull(pom.sourceDirectory ?: dir.resolve("src/main/java").toString(), dir.resolve("src/main/kotlin").toString()) +
                pom.extraSourceDirectories +
                // maven-compiler-plugin registers its generated-sources dir as a compile source root once it has run
                listOf(Path.of(pom.directory ?: dir.resolve("target").toString(), "generated-sources", "annotations").toString()),
        )
        val resourceRoots = existing(pom.resourceDirectories.ifEmpty { listOf(dir.resolve("src/main/resources").toString()) })

        val moduleInfo = sourceRoots.map { Path.of(it, "module-info.java") }.firstOrNull { Files.isRegularFile(it) }
        val mode = try {
            LaunchMode.resolve(launchMode, moduleInfo != null)
        } catch (e: IllegalArgumentException) {
            throw ToolException("CONFIG", e.message ?: "invalid launch mode", "use auto, classpath or modulepath")
        }

        val runtime = classpath.map { Path.of(it).toAbsolutePath() }.filter { it != outputDir }.map { it.toString() }
        val cp = mutableListOf<String>()
        val mp = mutableListOf<String>()
        val addModules = mutableListOf<String>()
        var mainModule = main.mainModule
        if (mode == LaunchMode.MODULEPATH) {
            if (moduleInfo != null) {
                if (mainModule == null) mainModule = LaunchMode.parseModuleName(Files.readString(moduleInfo))
                mp += outputDir.toString()
                mp += runtime
            } else {
                mainModule = null
                val fxIds = ArrayList<String>()
                for (p in runtime) {
                    val id = javaFxArtifactId(p)
                    if (id != null) { mp += p; fxIds += id } else cp += p
                }
                addModules += JavaFxModules.moduleNames(fxIds)
            }
        } else {
            mainModule = null
            cp += runtime
        }

        val d = LaunchDescriptor.builder()
            .buildTool("maven")
            .mainClass(main.mainClass)
            .mainModule(mainModule)
            .classpath(cp)
            .modulePath(mp)
            .addModules(addModules)
            .jvmArgs(jvmArgs + javafx?.childTexts("options").orEmpty())
            .appArgs(appArgs + javafx?.text("commandlineArgs").orEmpty().split(WHITESPACE).filter { it.isNotEmpty() })
            .workingDir(dir.toString())
            .sourceRoots(sourceRoots)
            .resourceRoots(resourceRoots)
            .outputDir(outputDir.toString())
            .release(release(pom, compiler))
            .annotationProcessors(detectProcessors(pom, compiler, classpath, mavenRepo))
            .build()
        return d
    }

    internal fun mainClassError(e: MainClassException): ToolException =
        if (e.noneFound) {
            ToolException("CONFIG", "no main class found in ${e.outputDirs.joinToString()} after compiling",
                "add `main-class=<fully.qualified.Name>` to fxloop.properties (or set FXLOOP_MAIN_CLASS)")
        } else {
            ToolException("CONFIG",
                "cannot determine the main class unambiguously. Candidates:\n  - " + e.candidates.joinToString("\n  - ") { it.describe() },
                "pin one by adding a line to fxloop.properties, e.g.\n" + e.candidates.joinToString("\n") { "main-class=${it.name}" })
        }

    private fun isValue(v: String?): Boolean = v != null && v.isNotBlank() && !v.contains("\${")

    internal fun release(pom: EffectivePom, compiler: EffectivePom.PluginNode?): String {
        val r = compiler?.text("release")
        if (isValue(r)) return r!!
        for (key in listOf("maven.compiler.release", "maven.compiler.target")) {
            val v = pom.property(key)
            if (isValue(v)) return v!!.trim()
        }
        return Runtime.version().feature().toString()
    }

    /**
     * Same signal as the Maven plugin: `annotationProcessorPaths`, or a processor service file on the compile path.
     * `build-classpath` is limited to runtime scope, so `provided` dependencies (Lombok and friends) are located
     * in the local repository by their coordinates.
     */
    internal fun detectProcessors(pom: EffectivePom, compiler: EffectivePom.PluginNode?, classpath: List<String>, repo: Path?): Boolean {
        if (compiler?.has("annotationProcessorPaths") == true) return true
        val jars = classpath.map(::File) + pom.dependencies.filter { it.scope == "provided" }.mapNotNull { dep ->
            val v = dep.version?.takeIf { it.isNotBlank() && !it.contains("\${") } ?: return@mapNotNull null
            val base = repo ?: localRepo(classpath) ?: return@mapNotNull null
            val suffix = dep.classifier?.takeIf { it.isNotEmpty() }?.let { "-$it" }.orEmpty()
            base.resolve(dep.groupId.replace('.', '/')).resolve(dep.artifactId).resolve(v).resolve("${dep.artifactId}-$v$suffix.jar").toFile()
        }
        for (f in jars) {
            try {
                if (f.isFile) ZipFile(f).use { if (it.getEntry(PROCESSOR_SERVICE) != null) return true }
                else if (f.isDirectory && File(f, PROCESSOR_SERVICE).isFile) return true
            } catch (_: IOException) {
                // unreadable jar: skip
            }
        }
        return false
    }

    /** The local repository root, inferred from classpath entries (`<root>/<group path>/<artifact>/<version>/file.jar`). */
    internal fun localRepo(classpath: List<String>): Path? {
        for (e in classpath) {
            val p = e.replace('\\', '/')
            val i = p.indexOf("/org/openjfx/").takeIf { it > 0 } ?: p.indexOf("/org/jetbrains/").takeIf { it > 0 } ?: continue
            return Path.of(e.substring(0, i))
        }
        val home = System.getProperty("user.home") ?: return null
        return Path.of(home, ".m2", "repository").takeIf { Files.isDirectory(it) }
    }

    private fun existing(dirs: List<String>): List<String> =
        dirs.map { Path.of(it) }.filter { Files.isDirectory(it) }.map { it.toAbsolutePath().toString() }.distinct()

}

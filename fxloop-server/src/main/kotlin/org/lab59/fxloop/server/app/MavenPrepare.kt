package org.lab59.fxloop.server.app

import org.lab59.fxloop.launch.JavaFxArtifacts
import org.lab59.fxloop.launch.LaunchAssembler
import org.lab59.fxloop.launch.LaunchAssembly
import org.lab59.fxloop.launch.MainClassException
import org.lab59.fxloop.launch.MainClassStep
import org.lab59.fxloop.protocol.LaunchDescriptor
import org.lab59.fxloop.server.mcp.ToolException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plugin-free Maven prepare (PRD 11 4.2): produces the same [LaunchDescriptor] as `fxloop-maven-plugin:prepare`
 * from one Maven invocation (`compile`, `help:effective-pom` when stale, `dependency:build-classpath`) plus the shared
 * detection in `fxloop-launch`. Pure functions where possible so the mapping is unit-testable from fixture XML.
 */
object MavenPrepare {
    const val EFFECTIVE_POM = "effective-pom.xml"
    const val CLASSPATH_FILE = "cp.txt"

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
        val runtime by lazy { LaunchAssembler.withoutOutputDir(classpath, outputDir) }

        val assembly = LaunchAssembler.assemble(
            buildTool = "maven",
            mainClassSteps = listOf(
                MainClassStep("fxloop.properties main-class", explicitMain),
                MainClassStep("javafx-maven-plugin", javafx?.text("mainClass")),
                MainClassStep("exec-maven-plugin", exec?.text("mainClass")),
                MainClassStep("maven-jar-plugin manifest", jar?.text("archive", "manifest", "mainClass")),
            ),
            outputDir = outputDir,
            launchMode = launchMode,
            sourceRoots = listOfNotNull(pom.sourceDirectory ?: dir.resolve("src/main/java").toString(), dir.resolve("src/main/kotlin").toString()) +
                pom.extraSourceDirectories +
                // maven-compiler-plugin registers its generated-sources dir as a compile source root once it has run
                listOf(Path.of(pom.directory ?: dir.resolve("target").toString(), "generated-sources", "annotations").toString()),
            resourceRoots = pom.resourceDirectories.ifEmpty { listOf(dir.resolve("src/main/resources").toString()) },
            distinctRoots = true,
            runtimeClasspath = { runtime },
            // no artifact metadata here: JavaFX jars are recognised by their local-repository path
            javafx = { JavaFxArtifacts(runtime.filter { javaFxArtifactId(it) != null }.toSet(), runtime.mapNotNull(::javaFxArtifactId)) },
            jvmArgs = jvmArgs + javafx?.childTexts("options").orEmpty(),
            appArgs = appArgs + LaunchAssembler.splitArgs(javafx?.text("commandlineArgs")),
            workingDir = dir.toString(),
            release = release(pom, compiler),
            annotationProcessors = { detectProcessors(pom, compiler, classpath, mavenRepo) },
        )
        return when (assembly) {
            is LaunchAssembly.Assembled -> assembly.descriptor
            is LaunchAssembly.MainClassUnresolved -> throw mainClassError(assembly.error)
            is LaunchAssembly.InvalidLaunchMode ->
                throw ToolException("CONFIG", assembly.error.message ?: "invalid launch mode", "use auto, classpath or modulepath")
        }
    }

    internal fun mainClassError(e: MainClassException): ToolException =
        if (e.noneFound) {
            ToolException("CONFIG", "no main class found in ${e.outputDirs.joinToString()} after compiling",
                "add `main-class=<fully.qualified.Name>` to fxloop.properties (or set FXLOOP_MAIN_CLASS)")
        } else {
            ToolException("CONFIG",
                "cannot determine the main class unambiguously. Candidates:" + e.candidateLines,
                "pin one by adding a line to fxloop.properties, e.g.\n" + e.candidates.joinToString("\n") { "main-class=${it.name}" })
        }

    internal fun release(pom: EffectivePom, compiler: EffectivePom.PluginNode?): String =
        LaunchAssembler.mavenRelease(compiler?.text("release"), pom::property)

    /**
     * Same signal as the Maven plugin: `annotationProcessorPaths`, or a processor service file on the compile path.
     * `build-classpath` is limited to runtime scope, so `provided` dependencies (Lombok and friends) are located
     * in the local repository by their coordinates.
     */
    internal fun detectProcessors(pom: EffectivePom, compiler: EffectivePom.PluginNode?, classpath: List<String>, repo: Path?): Boolean =
        LaunchAssembler.detectProcessors(compiler?.has("annotationProcessorPaths") == true) {
            classpath + pom.dependencies.filter { it.scope == "provided" }.mapNotNull { dep ->
                val v = dep.version?.takeIf { it.isNotBlank() && !it.contains("\${") } ?: return@mapNotNull null
                val base = repo ?: localRepo(classpath) ?: return@mapNotNull null
                val suffix = dep.classifier?.takeIf { it.isNotEmpty() }?.let { "-$it" }.orEmpty()
                base.resolve(dep.groupId.replace('.', '/')).resolve(dep.artifactId).resolve(v).resolve("${dep.artifactId}-$v$suffix.jar").toString()
            }
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
}

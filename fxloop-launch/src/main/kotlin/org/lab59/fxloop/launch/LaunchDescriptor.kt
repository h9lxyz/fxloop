package org.lab59.fxloop.launch

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.lab59.fxloop.protocol.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * `launch.json` emitted by `fxloop:prepare` (Maven) and `fxloopPrepare` (Gradle). Single source of truth for both
 * build plugins; JSON schema published as `org/lab59/fxloop/launch/launch.schema.json`.
 *
 * @property buildTool `maven` or `gradle`
 * @property mainModule main module name when the app itself is modular, else `null`
 * @property classpath absolute classpath entries (excluding [outputDir])
 * @property modulePath absolute module-path entries
 * @property addModules modules to pass to `--add-modules`
 * @property release Java release level for in-process compilation (e.g. "17")
 * @property annotationProcessors true when annotation processors were detected on the compile path
 * @property fcp reserved for FCP overrides (port, timeouts)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LaunchDescriptor(
    val schemaVersion: Int = SCHEMA_VERSION,
    val buildTool: String,
    val mainClass: String,
    val mainModule: String? = null,
    val classpath: List<String> = emptyList(),
    val modulePath: List<String> = emptyList(),
    val addModules: List<String> = emptyList(),
    val jvmArgs: List<String> = emptyList(),
    val appArgs: List<String> = emptyList(),
    val workingDir: String,
    val sourceRoots: List<String> = emptyList(),
    val resourceRoots: List<String> = emptyList(),
    val outputDir: String,
    val release: String? = null,
    val annotationProcessors: Boolean = false,
    val fcp: Map<String, Any> = emptyMap(),
) {
    fun writeTo(file: Path) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(file, Json.pretty(this))
    }

    /**
     * The command line that runs the app with [javaAgent] (`<jar>=<args>`), as `fxloop:run` / `fxloopRun` start it:
     * descriptor JVM args, the agent, classpath or module path, main class, descriptor app args, then [extraAppArgs].
     */
    fun runCommand(java: String, javaAgent: String, extraAppArgs: List<String> = emptyList()): List<String> = buildList {
        add(java)
        addAll(jvmArgs)
        add("-javaagent:$javaAgent")
        val cp = buildList {
            if (mainModule == null) add(outputDir)
            addAll(classpath)
        }
        if (modulePath.isNotEmpty()) addAll(listOf("--module-path", modulePath.joinToString(File.pathSeparator)))
        if (addModules.isNotEmpty()) addAll(listOf("--add-modules", addModules.joinToString(",")))
        if (cp.isNotEmpty()) addAll(listOf("-cp", cp.joinToString(File.pathSeparator)))
        if (mainModule != null) addAll(listOf("-m", "$mainModule/$mainClass")) else add(mainClass)
        addAll(appArgs)
        addAll(extraAppArgs)
    }

    /**
     * The inspector helper started next to a developer-run app: the FXLoop server in sidecar mode (no MCP) for
     * [projectDir], serving the viewer and CSS inspector while no agent session is connected. [inspectorEdit] turns on
     * the inspector's "Try it live" scratch CSS (`FXLOOP_INSPECTOR_EDIT`), on by default for a developer-run app.
     */
    fun inspectorHelperCommand(java: String, serverJar: Path, projectDir: Path, bar: String?, inspectorEdit: Boolean = true): List<String> =
        listOf(java, "-jar", serverJar.toString(), "--sidecar=true", "--project-dir=$projectDir", "--bar=${bar ?: "on"}",
            "--inspector-edit=$inspectorEdit")

    /**
     * Agent arguments for a developer-run app: self-generated token in [handshake], optional standalone dev bar, and
     * [inspectorEdit] recorded in the handshake so an agent session that connects keeps "Try it live" on. [ownerPid] is
     * the build process running the app: when it dies without its shutdown hook (SIGKILL, IDE hard stop) the app exits
     * on its own instead of lingering as an orphan.
     */
    fun runAgentArgs(handshake: Path, bar: String?, inspectorEdit: Boolean = false, ownerPid: Long? = null): String {
        val b = bar?.trim()?.lowercase()?.takeIf { it == "on" || it == "pill" }
        return "handshake=$handshake,state=${handshake.resolveSibling("state.json")},logs=true" + (b?.let { ",bar=$it" } ?: "") +
            (if (inspectorEdit) ",inspectorEdit=true" else "") + (ownerPid?.let { ",ownerPid=$it" } ?: "")
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "launch.json"

        @JvmStatic
        fun readFrom(file: Path): LaunchDescriptor = Json.MAPPER.readValue(Files.readString(file), LaunchDescriptor::class.java)

        /** Null-tolerant creator so missing JSON arrays become empty lists. */
        @JvmStatic
        @JsonCreator
        fun fromJson(
            @JsonProperty("schemaVersion") schemaVersion: Int?,
            @JsonProperty("buildTool") buildTool: String,
            @JsonProperty("mainClass") mainClass: String,
            @JsonProperty("mainModule") mainModule: String?,
            @JsonProperty("classpath") classpath: List<String>?,
            @JsonProperty("modulePath") modulePath: List<String>?,
            @JsonProperty("addModules") addModules: List<String>?,
            @JsonProperty("jvmArgs") jvmArgs: List<String>?,
            @JsonProperty("appArgs") appArgs: List<String>?,
            @JsonProperty("workingDir") workingDir: String,
            @JsonProperty("sourceRoots") sourceRoots: List<String>?,
            @JsonProperty("resourceRoots") resourceRoots: List<String>?,
            @JsonProperty("outputDir") outputDir: String,
            @JsonProperty("release") release: String?,
            @JsonProperty("annotationProcessors") annotationProcessors: Boolean?,
            @JsonProperty("fcp") fcp: Map<String, Any>?,
        ) = LaunchDescriptor(
            schemaVersion ?: SCHEMA_VERSION, buildTool, mainClass, mainModule, classpath.orEmpty(), modulePath.orEmpty(),
            addModules.orEmpty(), jvmArgs.orEmpty(), appArgs.orEmpty(), workingDir, sourceRoots.orEmpty(),
            resourceRoots.orEmpty(), outputDir, release, annotationProcessors ?: false, fcp.orEmpty(),
        )

        /** Fluent builder kept for Java callers and incremental construction in the build plugins. */
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder {
        private var buildTool: String? = null
        private var mainClass: String? = null
        private var mainModule: String? = null
        private var classpath: List<String> = emptyList()
        private var modulePath: List<String> = emptyList()
        private var addModules: List<String> = emptyList()
        private var jvmArgs: List<String> = emptyList()
        private var appArgs: List<String> = emptyList()
        private var workingDir: String? = null
        private var sourceRoots: List<String> = emptyList()
        private var resourceRoots: List<String> = emptyList()
        private var outputDir: String? = null
        private var release: String? = null
        private var annotationProcessors = false

        fun buildTool(v: String?) = apply { buildTool = v }
        fun mainClass(v: String?) = apply { mainClass = v }
        fun mainModule(v: String?) = apply { mainModule = v }
        fun classpath(v: List<String>?) = apply { classpath = v.orEmpty() }
        fun modulePath(v: List<String>?) = apply { modulePath = v.orEmpty() }
        fun addModules(v: List<String>?) = apply { addModules = v.orEmpty() }
        fun jvmArgs(v: List<String>?) = apply { jvmArgs = v.orEmpty() }
        fun appArgs(v: List<String>?) = apply { appArgs = v.orEmpty() }
        fun workingDir(v: String?) = apply { workingDir = v }
        fun sourceRoots(v: List<String>?) = apply { sourceRoots = v.orEmpty() }
        fun resourceRoots(v: List<String>?) = apply { resourceRoots = v.orEmpty() }
        fun outputDir(v: String?) = apply { outputDir = v }
        fun release(v: String?) = apply { release = v }
        fun annotationProcessors(v: Boolean) = apply { annotationProcessors = v }

        fun build() = LaunchDescriptor(
            SCHEMA_VERSION, requireNotNull(buildTool) { "buildTool" }, requireNotNull(mainClass) { "mainClass" },
            mainModule, classpath, modulePath, addModules, jvmArgs, appArgs, requireNotNull(workingDir) { "workingDir" },
            sourceRoots, resourceRoots, requireNotNull(outputDir) { "outputDir" }, release, annotationProcessors,
        )
    }
}

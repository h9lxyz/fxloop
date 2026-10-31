package dev.fxloop.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService

/** Registers the `fxloop` extension and the `fxloopPrepare` and `fxloopRun` tasks. */
class FxloopPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(JavaPlugin::class.java)

        val ext = project.extensions.create(EXTENSION_NAME, FxloopExtension::class.java)
        ext.launchMode.convention("auto")

        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        val runtimeClasspath = project.configurations.getByName(main.runtimeClasspathConfigurationName)

        val javafxArtifacts = runtimeClasspath.incoming.artifacts.resolvedArtifacts.map { set ->
            set.filter { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.group == JavaFxModules.GROUP_ID }
        }

        val task = project.tasks.register(TASK_NAME, FxloopPrepareTask::class.java) { t ->
            t.group = "fxloop"
            t.description = "Writes build/fxloop/launch.json describing how to launch this JavaFX application."
            t.dependsOn(JavaPlugin.CLASSES_TASK_NAME)

            t.launchMode.convention(ext.launchMode)
            t.explicitMainClass.convention(ext.mainClass)
            t.jvmArgs.convention(ext.jvmArgs)
            t.appArgs.convention(ext.appArgs)

            t.runtimeClasspath.from(runtimeClasspath)
            t.classesDir.convention(main.java.classesDirectory)
            // all compiled outputs (Java plus Kotlin/Groovy/Scala when those plugins add theirs)
            t.classesDirs.from(main.output.classesDirs)
            // every language's source dirs (allSource includes Kotlin's src/main/kotlin), minus the resource dirs
            t.sourceRoots.convention(
                main.allSource.sourceDirectories.elements.zip(main.resources.sourceDirectories.elements) { all, res ->
                    val resources = res.map { it.asFile.absolutePath }.toSet()
                    all.map { it.asFile.absolutePath }.filter { it !in resources }.distinct()
                },
            )
            t.resourceRoots.convention(main.resources.sourceDirectories.elements.map { s -> s.map { it.asFile.absolutePath } })
            t.workingDir.convention(project.layout.projectDirectory.asFile.absolutePath)
            t.javafxJars.convention(javafxArtifacts.map { l -> l.map { it.file.absolutePath } })
            t.javafxArtifactIds.convention(
                javafxArtifacts.map { l -> l.map { (it.id.componentIdentifier as ModuleComponentIdentifier).module } },
            )
            t.outputFile.convention(project.layout.buildDirectory.file("fxloop/launch.json"))
        }

        val agent = project.configurations.create(AGENT_CONFIGURATION) { c ->
            c.isCanBeConsumed = false
            c.isTransitive = false
            c.description = "The FXLoop agent jar used by fxloopRun"
        }
        project.dependencies.add(AGENT_CONFIGURATION, "dev.fxloop:fxloop-agent:$VERSION")
        val server = project.configurations.create(SERVER_CONFIGURATION) { c ->
            c.isCanBeConsumed = false
            c.isTransitive = false
            c.description = "The FXLoop server jar run by fxloopRun as the inspector helper"
        }
        project.dependencies.add(SERVER_CONFIGURATION, "dev.fxloop:fxloop-server:$VERSION:all")
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
        project.tasks.register(RUN_TASK_NAME, FxloopRunTask::class.java) { t ->
            t.group = "fxloop"
            t.description = "Runs the app with the FXLoop agent and dev bar; any agent session can connect to it later."
            t.launchFile.convention(task.flatMap { it.outputFile })
            t.agentJar.from(agent)
            t.javaLauncher.convention(toolchains.launcherFor(javaExt.toolchain))
            t.bar.convention(project.providers.gradleProperty("fxloop.bar").orElse("on"))
            t.inspector.convention(project.providers.gradleProperty("fxloop.inspector").map { it != "false" }.orElse(true))
            t.inspectorEdit.convention(project.providers.gradleProperty("fxloop.inspectorEdit").map { it != "false" }.orElse(true))
            t.serverJar.from(server)
            t.projectDir.convention(project.layout.projectDirectory)
        }

        // Values that need the finished build model; resolved to plain strings so nothing captures the Project
        // at execution time (configuration-cache friendly).
        project.afterEvaluate { p ->
            task.configure { t ->
                t.javafxMainClass.set(javafxPluginMainClass(p))
                main.output.resourcesDir?.let { t.resourcesDir.set(it) }
                p.extensions.findByType(JavaApplication::class.java)?.let { app ->
                    val mc = app.mainClass.orNull
                    val mm = app.mainModule.orNull
                    if (mc != null) t.applicationMainClass.set(if (mm != null) "$mm/$mc" else mc)
                    t.applicationJvmArgs.set(app.applicationDefaultJvmArgs.toList())
                }
                (p.tasks.findByName(JavaPlugin.JAR_TASK_NAME) as? Jar)?.let { jar ->
                    val v = jar.manifest.attributes["Main-Class"].let { if (it is Provider<*>) it.orNull else it }
                    if (v != null) t.manifestMainClass.set(v.toString())
                }
                t.release.set(release(p))
                t.annotationProcessors.set(
                    p.configurations.getByName(main.annotationProcessorConfigurationName).allDependencies.isNotEmpty(),
                )
            }
        }
    }

    /** `javafx { mainClass }` from org.openjfx.javafxplugin when present (accessed reflectively). */
    private fun javafxPluginMainClass(p: Project): String? {
        val ext = p.extensions.findByName("javafx") ?: return null
        return try {
            val v = ext.javaClass.getMethod("getMainClass").invoke(ext)
            (if (v is Provider<*>) v.orNull else v)?.toString()
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun release(p: Project): String {
        val compile = p.tasks.findByName(JavaPlugin.COMPILE_JAVA_TASK_NAME) as? JavaCompile
        compile?.options?.release?.orNull?.let { return it.toString() }
        val java = p.extensions.getByType(JavaPluginExtension::class.java)
        java.toolchain.languageVersion.orNull?.let { return it.asInt().toString() }
        return java.targetCompatibility.majorVersion
    }

    companion object {
        const val EXTENSION_NAME = "fxloop"
        const val TASK_NAME = "fxloopPrepare"
        const val RUN_TASK_NAME = "fxloopRun"
        const val AGENT_CONFIGURATION = "fxloopAgent"
        const val SERVER_CONFIGURATION = "fxloopServer"

        /** This plugin's version (from the generated `fxloop.properties`); the agent jar is pinned to it. */
        val VERSION: String by lazy {
            java.util.Properties().apply {
                FxloopPlugin::class.java.getResourceAsStream("fxloop.properties")?.use { load(it) }
            }.getProperty("version") ?: error("fxloop.properties missing from the FXLoop Gradle plugin")
        }
    }
}

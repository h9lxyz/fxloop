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

/** Registers the `fxloop` extension and the `fxloopPrepare` task. */
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
            // all class output dirs (build/classes/java/main, build/classes/kotlin/main, ...)
            t.classesDirs.from(main.output.classesDirs)
            // every source language of the set (java, kotlin, groovy...), minus resources
            t.sourceRoots.convention(
                main.allSource.sourceDirectories.elements.zip(main.resources.sourceDirectories.elements) { all, res ->
                    val r = res.map { it.asFile.absoluteFile }.toSet()
                    all.map { it.asFile.absoluteFile }.filter { it !in r }.map { it.absolutePath }
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
    }
}

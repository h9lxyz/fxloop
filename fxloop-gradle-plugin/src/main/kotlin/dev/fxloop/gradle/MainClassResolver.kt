package dev.fxloop.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Resolves the application main class: fxloop extension, openjfx plugin, `application`, jar manifest,
 * then an ASM scan of compiled classes (mirrors the Maven plugin's order).
 */
internal object MainClassResolver {
    private const val APPLICATION = "javafx/application/Application"

    /** Plain inputs; any string may be null or blank. */
    data class Inputs(
        val explicit: String?,
        val javafxPlugin: String?,
        val application: String?,
        val jarManifest: String?,
        val outputDir: Path?,
    )

    /** [mainModule] is non-null only when a `module/class` form was given. */
    data class Result(val mainModule: String?, val mainClass: String, val source: String)

    fun resolve(input: Inputs): Result {
        val candidates = listOf(
            "fxloop.mainClass" to input.explicit,
            "javafx plugin" to input.javafxPlugin,
            "application.mainClass" to input.application,
            "jar manifest Main-Class" to input.jarManifest,
        )
        candidates.firstOrNull { !it.second.isNullOrBlank() }?.let { (name, v) -> return parse(v!!.trim(), name) }
        return scan(input.outputDir)
    }

    fun parse(value: String, source: String): Result {
        val slash = value.indexOf('/')
        return if (slash > 0) Result(value.substring(0, slash), value.substring(slash + 1), source)
        else Result(null, value, source)
    }

    private class ClassInfo(val name: String, val superName: String?) {
        var hasMain = false
        var referencesApplication = false
        val referenced = HashSet<String>()
    }

    private fun scan(outputDir: Path?): Result {
        val classes = HashMap<String, ClassInfo>()
        if (outputDir != null && outputDir.isDirectory()) {
            Files.walk(outputDir).use { s ->
                s.filter { it.extension == "class" && it.name != "module-info.class" }.forEach { p ->
                    Files.newInputStream(p).use { ins -> read(ClassReader(ins)).let { classes[it.name] = it } }
                }
            }
        }
        val appNames = classes.values.filter { isApplication(it, classes, 0) }.map { it.name }.toSet()
        val apps = appNames.sorted()
        val mainClasses = classes.values.filter { it.hasMain }
        val mains = mainClasses.map { it.name }.sorted()
        val launchers = mainClasses
            .filter { it.name !in appNames && (it.referencesApplication || appNames.any(it.referenced::contains)) }
            .map { it.name }.sorted()

        // Launcher pattern: a non-Application main class that launches the Application subclass.
        when {
            launchers.size == 1 -> return Result(null, launchers[0], "class scan (launcher)")
            launchers.isEmpty() && apps.size == 1 -> return Result(null, apps[0], "class scan (Application subclass)")
            launchers.isEmpty() && apps.isEmpty() && mains.size == 1 -> return Result(null, mains[0], "class scan (main method)")
        }
        val all = apps.map { "$it (extends Application)" } +
            mains.map { "$it (declares main)" + if (it in launchers) " [launcher]" else "" }
        throw GradleException(
            if (all.isEmpty()) "FXLoop: no main class found in $outputDir. Compile first, or set fxloop { mainClass = \"<fqcn>\" }."
            else "FXLoop: cannot determine the main class unambiguously. Candidates:\n  - " +
                all.joinToString("\n  - ") +
                "\nSet fxloop { mainClass = \"<fqcn>\" } (or application { mainClass = ... }).",
        )
    }

    private tailrec fun isApplication(ci: ClassInfo, all: Map<String, ClassInfo>, depth: Int): Boolean {
        val sup = ci.superName
        if (sup == null || depth > 32) return false
        if (sup == APPLICATION) return true
        val parent = all[sup.replace('/', '.')] ?: return false
        return isApplication(parent, all, depth + 1)
    }

    private fun read(cr: ClassReader): ClassInfo {
        lateinit var ci: ClassInfo
        cr.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, sig: String?, superName: String?, itf: Array<out String>?) {
                ci = ClassInfo(name.replace('/', '.'), superName)
            }

            override fun visitMethod(access: Int, name: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor {
                if (name == "main" && desc == "([Ljava/lang/String;)V" &&
                    access and Opcodes.ACC_PUBLIC != 0 && access and Opcodes.ACC_STATIC != 0
                ) ci.hasMain = true
                fun note(internal: String) {
                    if (internal == APPLICATION) ci.referencesApplication = true
                    ci.referenced.add(internal.replace('/', '.'))
                }
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, owner: String, n: String, d: String, itf: Boolean) = note(owner)
                    override fun visitLdcInsn(value: Any?) {
                        if (value is Type && value.sort == Type.OBJECT) note(value.internalName)
                    }
                    override fun visitTypeInsn(op: Int, type: String) = note(type)
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return ci
    }
}

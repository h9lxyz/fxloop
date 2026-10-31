package dev.fxloop.maven

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import org.apache.maven.plugin.MojoFailureException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Resolves the application main class from plain inputs: explicit parameter, javafx-maven-plugin,
 * exec-maven-plugin, jar-plugin manifest, then an ASM scan of compiled classes.
 */
object MainClassResolver {

    private const val APPLICATION = "javafx/application/Application"

    /** Plain inputs; any string may be `null` or blank. */
    data class Inputs(
        val explicit: String?,
        val javafxPlugin: String?,
        val execPlugin: String?,
        val jarManifest: String?,
        val outputDir: Path?,
    )

    /** Result; [mainModule] is non-null only when a `module/class` form was given. */
    data class Result(val mainModule: String?, val mainClass: String, val source: String)

    @JvmStatic
    @Throws(MojoFailureException::class)
    fun resolve(input: Inputs): Result {
        val steps = listOf(
            input.explicit to "fxloop.mainClass",
            input.javafxPlugin to "javafx-maven-plugin",
            input.execPlugin to "exec-maven-plugin",
            input.jarManifest to "maven-jar-plugin manifest",
        )
        for ((value, name) in steps) {
            if (!value.isNullOrBlank()) return parse(value.trim(), name)
        }
        return scan(input.outputDir)
    }

    internal fun parse(value: String, source: String): Result {
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
        if (outputDir != null && Files.isDirectory(outputDir)) {
            try {
                Files.walk(outputDir).use { s ->
                    s.filter { it.toString().endsWith(".class") }.forEach { p ->
                        if (p.fileName.toString() == "module-info.class") return@forEach
                        Files.newInputStream(p).use { ins ->
                            val ci = read(ClassReader(ins))
                            classes[ci.name] = ci
                        }
                    }
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
        val appNames = classes.values.filter { isApplication(it, classes, 0) }.map { it.name }.toSet()
        val apps = appNames.sorted()
        val withMain = classes.values.filter { it.hasMain }
        val mains = withMain.map { it.name }.sorted()
        val launchers = withMain
            .filter { it.name !in appNames && (it.referencesApplication || appNames.any(it.referenced::contains)) }
            .map { it.name }
            .sorted()

        if (launchers.size == 1) return Result(null, launchers[0], "class scan (launcher)")
        if (launchers.isEmpty() && apps.size == 1) return Result(null, apps[0], "class scan (Application subclass)")
        if (launchers.isEmpty() && apps.isEmpty() && mains.size == 1) {
            return Result(null, mains[0], "class scan (main method)")
        }
        val all = apps.map { "$it (extends Application)" } +
            mains.map { "$it (declares main)" + if (it in launchers) " [launcher]" else "" }
        val msg = if (all.isEmpty()) {
            "No main class found in $outputDir. Compile first, or set -Dfxloop.mainClass=<fqcn>."
        } else {
            "Cannot determine the main class unambiguously. Candidates:\n  - " + all.joinToString("\n  - ") +
                "\nSet -Dfxloop.mainClass=<fqcn> (or <mainClass> in the fxloop plugin configuration)."
        }
        throw MojoFailureException(msg)
    }

    private fun isApplication(ci: ClassInfo, all: Map<String, ClassInfo>, depth: Int): Boolean {
        val sup = ci.superName
        if (sup == null || depth > 32) return false
        if (sup == APPLICATION) return true
        val parent = all[sup.replace('/', '.')]
        return parent != null && isApplication(parent, all, depth + 1)
    }

    private fun read(cr: ClassReader): ClassInfo {
        lateinit var ci: ClassInfo
        cr.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int, access: Int, name: String, signature: String?, superName: String?,
                interfaces: Array<out String>?,
            ) {
                ci = ClassInfo(name.replace('/', '.'), superName)
            }

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor {
                if (name == "main" && descriptor == "([Ljava/lang/String;)V" &&
                    access and Opcodes.ACC_PUBLIC != 0 && access and Opcodes.ACC_STATIC != 0
                ) {
                    ci.hasMain = true
                }
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean,
                    ) = note(owner)

                    override fun visitLdcInsn(value: Any?) {
                        if (value is Type && value.sort == Type.OBJECT) note(value.internalName)
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) = note(type)

                    private fun note(internal: String) {
                        if (internal == APPLICATION) ci.referencesApplication = true
                        ci.referenced.add(internal.replace('/', '.'))
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return ci
    }
}

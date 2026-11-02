package dev.fxloop.launch

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** One explicit source of a main class (parameter, plugin config, manifest ...); blank [value] is skipped. */
data class MainClassStep(val source: String, val value: String?)

/** A class the scan found but could not choose between. */
data class MainClassCandidate(val name: String, val extendsApplication: Boolean, val declaresMain: Boolean, val launcher: Boolean) {
    /** Human-readable label, e.g. `p.App (extends Application)` or `p.Main (declares main) [launcher]`. */
    fun describe(): String =
        if (extendsApplication) "$name (extends Application)"
        else "$name (declares main)" + if (launcher) " [launcher]" else ""
}

/**
 * Main class could not be determined. [candidates] is empty when nothing was found ([outputDirs] holds the
 * scanned dirs); otherwise it lists the ambiguous classes (Application subclasses first, then main classes).
 * The default [message] is generic; build-tool callers compose their own with their own hint text.
 */
class MainClassException(val outputDirs: List<Path>, val candidates: List<MainClassCandidate>) :
    RuntimeException(
        if (candidates.isEmpty()) "No main class found in ${outputDirs.joinToString()}"
        else "Cannot determine the main class unambiguously. Candidates:\n  - " +
            candidates.joinToString("\n  - ") { it.describe() },
    ) {
    val noneFound: Boolean get() = candidates.isEmpty()
}

/**
 * Resolves the application main class from plain inputs: ordered explicit [MainClassStep]s first, then an ASM
 * scan of compiled classes in the output dirs.
 */
object MainClassResolver {

    private const val APPLICATION = "javafx/application/Application"

    /** Result; [mainModule] is non-null only when a `module/class` form was given. */
    data class Result(val mainModule: String?, val mainClass: String, val source: String)

    /** First non-blank step wins (optionally `module/class`); otherwise scan [outputDirs]. */
    @JvmStatic
    @Throws(MainClassException::class)
    fun resolve(steps: List<MainClassStep>, outputDirs: List<Path>): Result {
        for (step in steps) {
            if (!step.value.isNullOrBlank()) return parse(step.value.trim(), step.source)
        }
        return scan(outputDirs)
    }

    /** Scan only, no explicit steps. */
    @JvmStatic
    @Throws(MainClassException::class)
    fun scan(outputDirs: List<Path>): Result = scanDirs(outputDirs)

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

    private fun scanDirs(outputDirs: List<Path>): Result {
        val classes = HashMap<String, ClassInfo>()
        for (outputDir in outputDirs.filter { Files.isDirectory(it) }) {
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
        val candidates = apps.map { MainClassCandidate(it, extendsApplication = true, declaresMain = it in mains, launcher = false) } +
            mains.map { MainClassCandidate(it, extendsApplication = false, declaresMain = true, launcher = it in launchers) }
        throw MainClassException(outputDirs, candidates)
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
